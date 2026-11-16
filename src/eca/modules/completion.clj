(ns eca.modules.completion
  "The `:eca/completion` module: inline code completion, `completion/inline`."
  (:require
   [clojure.string :as string]
   [eca.features.login :as f.login]
   [eca.features.prompt :as f.prompt]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]
   [eca.metrics :as metrics]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[COMPLETION]")

(def ^:private completion-tag "<ECA_TAG>")

(defn ^:private insert-completion-tag [doc-text position]
  (let [{:keys [line character]} position
        ;; Ensure we have at least one line to work with
        lines (let [ls (if (seq doc-text)
                         (string/split-lines doc-text)
                         [""])]
                (if (seq ls) ls [""]))
        line-idx (dec line)
        line-str (nth lines line-idx "")
        char-idx (dec character)
        prefix (subs line-str 0 char-idx)
        suffix (subs line-str char-idx)
        updated-line (str prefix completion-tag suffix)]
    (->> (assoc lines line-idx updated-line)
         (string/join "\n"))))

(defn inline-completion-prompt [config]
  (let [legacy-config-file-prompt (get-in config [:completion :systemPromptFile])
        legacy-config-prompt (get-in config [:completion :systemPrompt])
        config-prompt (f.prompt/get-config-prompt :completion nil config)]
    (cond
      legacy-config-prompt
      legacy-config-prompt

      config-prompt
      config-prompt

      :else
      (f.prompt/prompt-file legacy-config-file-prompt))))

(defn complete [{:keys [doc-text doc-version position]} db* config messenger metrics]
  (let [full-model (get-in config [:completion :model])
        [provider model] (shared/full-model->provider+model full-model)
        _ (f.login/maybe-renew-auth-token!
           {:provider provider
            :on-renewing (fn [] nil)
            :on-error (fn [error-msg] (logger/error logger-tag (format "Auth token renew failed: %s" error-msg)))}
           {:db* db*
            :messenger messenger
            :config config
            :metrics metrics})
        db @db*
        model-capabilities (get-in db [:models full-model])
        provider-auth (get-in db [:auth provider])
        {:keys [line character]} position
        input-code (insert-completion-tag doc-text position)
        instructions (inline-completion-prompt config)
        {:keys [error output-text]} (deref (future
                                             (try
                                               (llm-api/sync-prompt!
                                                {:provider provider
                                                 :model model
                                                 :config config
                                                 :prompt input-code
                                                 :instructions instructions
                                                 :provider-auth provider-auth
                                                 :model-capabilities (assoc model-capabilities
                                                                            :reason? false
                                                                            :tools false
                                                                            :web-search false)})
                                               (catch Exception e
                                                 {:error {:exception e}})))
                                           30000 ;; TODO move to config
                                           {:error {:message "Timeout waiting for completion"}})]
    (cond
      (:message error)
      {:error {:type :warning
               :message (:message error)}}

      (:exception error)
      (do
        (logger/error logger-tag "Error when requesting completion: %s" (:exception error))
        {:error {:type :warning
                 :message (.getMessage ^Exception (:exception error))}})

      (not output-text)
      {:error {:type :info
               :message "No suggestions found"}}

      :else
      {:items [{:text (shared/normalize-code-result output-text)
                :doc-version doc-version
                :range {:start {:line line :character character}
                        :end {:line line :character character}}}]})))

(defn ^:private completion-inline [{:keys [db* config metrics messenger]} params]
  (metrics/task metrics :eca/completion-inline
    (complete params db* config messenger metrics)))

(def module
  {:id :eca/completion
   :description "Inline code completion."
   ;; its config defaults (completion.model, prompts.completion) are core's for now
   :rpc {"completion/inline" {:kind :request :handler #'completion-inline}}})
