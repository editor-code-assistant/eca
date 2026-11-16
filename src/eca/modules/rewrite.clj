(ns eca.modules.rewrite
  "The `:eca/rewrite` module: rewrite a selection with the model,
   `rewrite/prompt`, streaming the text back in `rewrite/contentReceived`."
  (:require
   [clojure.string :as string]
   [eca.features.login :as f.login]
   [eca.features.prompt :as f.prompt]
   [eca.features.tools :as f.tools]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.metrics :as metrics]
   [eca.shared :as shared :refer [future* multi-str]]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[REWRITE]")

(defn ^:private send-content! [ctx content]
  (messenger/notify!
   (:messenger ctx)
   "rewrite/contentReceived"
   {:rewrite-id (:rewrite-id ctx)
    :content content}))

(defn ^:private rewrite-file-context
  "Returns the file content to inline in the rewrite prompt.
  When the file has more than `max-lines` lines, returns a window of
  `max-lines` lines centered on the selection's start line, prefixed with a
  one-line truncation marker. Otherwise returns the full content."
  [path range max-lines]
  (when path
    (when-let [content (llm-api/refine-file-context path nil)]
      (let [lines (string/split-lines content)
            total (count lines)]
        (if (or (not (pos-int? max-lines)) (<= total max-lines))
          content
          (let [sel-line (max 1 (or (some-> range :start :line) 1))
                half (quot max-lines 2)
                raw-start (- sel-line half)
                start (max 1 (min (inc (- total max-lines)) raw-start))
                end (min total (dec (+ start max-lines)))
                windowed (string/join "\n" (subvec lines (dec start) end))]
            (str (format ";; [File truncated: showing lines %d-%d of %d]\n" start end total)
                 windowed)))))))

(defn build-rewrite-instructions [text path full-text range all-tools config db]
  (let [legacy-prompt-file (-> config :rewrite :systemPromptFile)
        legacy-config-prompt (-> config :rewrite :systemPrompt)
        config-prompt (f.prompt/get-config-prompt :rewrite nil config)
        prompt-str (cond
                     legacy-config-prompt
                     legacy-config-prompt

                     config-prompt
                     config-prompt

                     :else
                     (f.prompt/prompt-file legacy-prompt-file))]
    (shared/safe-selmer-render prompt-str
                               (merge
                                (f.prompt/->base-selmer-ctx all-tools db)
                                {:text text
                                 :path (when path
                                         (str "- File path: " path))
                                 :rangeText (multi-str
                                             (str "- Start line: " (-> range :start :line))
                                             (str "- Start character: " (-> range :start :character))
                                             (str "- End line: " (-> range :end :line))
                                             (str "- End character: " (-> range :end :character)))
                                 :fullText (when full-text
                                             (multi-str
                                              "- Full file content"
                                              "```"
                                              full-text
                                              "```"))})
                               "rewrite-prompt")))

(defn prompt
  [{:keys [id prompt text path range]} db* config messenger metrics]
  (let [db @db*
        full-model (or (get-in config [:rewrite :model])
                       (llm-api/default-model db config))
        _ (when-not full-model
            (throw (ex-info llm-api/no-available-model-error-msg {})))
        [provider model] (shared/full-model->provider+model full-model)
        model-capabilities (get-in db [:models full-model])
        max-file-lines (get-in config [:rewrite :fullFileMaxLines])
        full-text (rewrite-file-context path range max-file-lines)
        start-time (System/currentTimeMillis)
        all-tools (f.tools/all-tools nil nil @db* config)
        instructions (build-rewrite-instructions text path full-text range all-tools config db)
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
    (let [accumulated-text (atom "")]
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
                                   :text (do (swap! accumulated-text str (:text msg))
                                             (send-content! ctx {:type :text
                                                                 :text (:text msg)}))
                                   :finish (let [raw @accumulated-text
                                                 normalized (shared/normalize-code-result raw)]
                                             (when (not= raw normalized)
                                               (send-content! ctx {:type :replace
                                                                   :text normalized}))
                                             (send-content! ctx {:type :finished
                                                                 :total-time-ms (- (System/currentTimeMillis) start-time)}))
                                   nil))
          :on-error (fn [{:keys [message exception]}]
                      (send-content! ctx {:type :error
                                          :message (or message (str "Error: " (ex-message exception)))}))})))
    {:status "prompting"
     :model full-model}))

(defn ^:private rewrite-prompt
  "`rewrite/prompt`: an error the client should show (`:error-response`, e.g.
   a failed login) is the answer's `:error`."
  [{:keys [db* config metrics messenger]} params]
  (metrics/task metrics :eca/rewrite-prompt
    (try
      (prompt params db* config messenger metrics)
      (catch clojure.lang.ExceptionInfo e
        (if-let [error-response (:error-response (ex-data e))]
          {:error error-response}
          (throw e))))))

(def module
  {:id :eca/rewrite
   :description "Rewrite the selected code with the model."
   ;; its config defaults (rewrite.fullFileMaxLines, prompts.rewrite) are core's for now
   :rpc {"rewrite/prompt" {:kind :request :handler #'rewrite-prompt}}})
