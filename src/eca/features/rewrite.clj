(ns eca.features.rewrite
  (:require
   [eca.features.login :as f.login]
   [eca.features.prompt :as f.prompt]
   [eca.features.tools :as f.tools]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.shared :as shared :refer [future*]]))

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
        _ (when-not full-model
            (throw (ex-info llm-api/no-available-model-error-msg {})))
        [provider model] (shared/full-model->provider+model full-model)
        model-capabilities (get-in db [:models full-model])
        full-text (when path (llm-api/refine-file-context path nil))
        start-time (System/currentTimeMillis)
        all-tools (f.tools/all-tools nil nil @db* config)
        instructions (f.prompt/build-rewrite-instructions text path full-text range all-tools config db)
        ctx {:db* db*
             :config config
             :messenger messenger
             :metrics metrics
             :rewrite-id id}
        _ (f.login/maybe-renew-auth-token!
           {:provider provider
            :on-error (fn [error-msg]
                        (logger/error logger-tag (format "Auth token renew failed: %s" error-msg))
                        (throw (ex-info error-msg {:error-response {:message error-msg}})))}
           ctx)
        ;; get refreshed auth in case of token renew
        provider-auth (get-in @db* [:auth provider])]
    (future* config
      (llm-api/sync-or-async-prompt!
       {:provider provider
        :model model
        :instructions instructions
        :model-capabilities model-capabilities
        :config config
        :user-messages [{:role "user" :content [{:type :text :text prompt}]}]
        :past-messages []
        :provider-auth provider-auth
        :on-first-response-received (fn [& _]
                                      (send-content! ctx {:type :started}))
        :on-reason (fn [{:keys [status]}]
                     (when (= :started status)
                       (send-content! ctx {:type :reasoning})))
        :on-message-received (fn [{:keys [type] :as msg}]
                               (case type
                                 :text (send-content! ctx {:type :text
                                                           :text (:text msg)})
                                 :finish (send-content! ctx {:type :finished
                                                             :total-time-ms (- (System/currentTimeMillis) start-time)})
                                 nil))
        :on-error (fn [{:keys [message exception]}]
                    (send-content! ctx {:type :error
                                        :message (or message (str "Error: " (ex-message exception)))}))}))
    {:status "prompting"
     :model full-model}))
