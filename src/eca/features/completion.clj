(ns eca.features.completion
  (:require
   [clojure.string :as string]
   [eca.features.login :as f.login]
   [eca.features.prompt :as f.prompt]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]))

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

(defn ^:private normalize-code-result
  "Normalize code removing any markdown wrapper"
  [code]
  (or
   ;; Triple backticks with optional language label
   (when-let [[_ inner] (re-matches #"(?s)^\s*```[^\r\n]*\r?\n(.*?)\r?\n?```\s*$" code)]
     (string/trim inner))
   ;; Single backticks wrapping the whole content
   (when-let [[_ inner] (re-matches #"(?s)^\s*`(.*?)`\s*$" code)]
     (string/trim inner))
   ;; Fallback: extract first fenced block anywhere in the string
   (when-let [[_ inner] (re-find #"(?s)```[^\r\n]*\r?\n(.*?)\r?\n?```" code)]
     (string/trim inner))
   code))

(defn ^:private maybe-renew-auth-token! [provider db* messenger config metrics]
  (when-let [expires-at (get-in @db* [:auth provider :expires-at])]
    (when (<= (long expires-at) (quot (System/currentTimeMillis) 1000))
      (f.login/renew-auth! provider
                           {:db* db*
                            :messenger messenger
                            :config config
                            :metrics metrics}
                           {:on-error (fn [error-msg]
                                        (logger/error logger-tag (format "Auth token renew failed: %s" error-msg)))}))))

(defn complete [{:keys [doc-text doc-version position]} db* config messenger metrics]
  (let [full-model (get-in config [:completion :model])
        [provider model] (string/split full-model #"/" 2)
        _ (maybe-renew-auth-token! provider db* messenger config metrics)
        db @db*
        model-capabilities (get-in db [:models full-model])
        provider-auth (get-in db [:auth provider])
        {:keys [line character]} position
        input-code (insert-completion-tag doc-text position)
        instructions (f.prompt/inline-completion-prompt config)
        {:keys [error result]} (deref (future
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
                                                                     :web-search false)}))
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
                 :message (:message (.getMessage ^Exception (:exception error)))}})

      (not result)
      {:error {:type :info
               :message "No suggestions found"}}

      :else
      {:items [{:text (normalize-code-result result)
                :doc-version doc-version
                :range {:start {:line line :character character}
                        :end {:line line :character character}}}]})))
