(ns eca.features.rewrite
  (:require
   [clojure.string :as string]
   [eca.features.login :as f.login]
   [eca.features.prompt :as f.prompt]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]
   [eca.messenger :as messenger]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[REWRITE]")

(defn ^:private send-content! [ctx content]
  (messenger/rewrite-content-received
   (:messenger ctx)
   {:rewrite-id (:rewrite-id ctx)
    :content content}))

(defn prompt
  [{:keys [id prompt text path range]} db* config messenger metrics]
  (let [db @db*
        full-model (or (get-in config [:rewrite :model])
                       (llm-api/default-model db config))
        [provider model] (string/split full-model #"/" 2)
        model-capabilities (get-in db [:models full-model])
        full-text (when path (llm-api/refine-file-context path nil))
        instructions (f.prompt/build-rewrite-instructions text path full-text range config)
        ctx {:db* db*
             :config config
             :messenger messenger
             :metrics metrics
             :rewrite-id id}
        _ (f.login/maybe-renew-auth-token!
           {:provider provider
            :on-renewing identity
            :on-error (fn [error-msg] (logger/error logger-tag (format "Auth token renew failed: %s" error-msg)))}
           ctx)]
    (future
      (llm-api/sync-or-async-prompt!
       {:provider provider
        :model model
        :instructions instructions
        :model-capabilities model-capabilities
        :config config
        :user-messages [{:role "user" :content [{:type :text :text prompt}]}]
        :past-messages []
        :provider-auth (get-in db [:auth provider])
        :on-first-response-received (fn [& _]
                                      (send-content! ctx {:type :started}))
        :on-message-received (fn [{:keys [type] :as msg}]
                               (case type
                                 :text (send-content! ctx {:type :text
                                                           :text (:text msg)})
                                 :finish (send-content! ctx {:type :finished})
                                 nil))}))
    {:status "prompting"
     :model model}))
