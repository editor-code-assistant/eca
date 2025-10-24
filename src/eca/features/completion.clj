(ns eca.features.completion
  (:require
   [clojure.string :as string]
   [eca.features.prompt :as f.prompt]
   [eca.llm-api :as llm-api]
   [eca.messenger :as messenger]))

(def ^:private completion-tag "<|ECA_TAG|>")

(defn ^:private insert-compleiton-tag [doc-text position]
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

(defn complete [{:keys [doc-text doc-version position]} db* config messenger]
  (let [full-model (get-in config [:completion :model])
        [provider model] (string/split full-model #"/" 2)
        db @db*
        model-capabilities (get-in db [:models full-model])
        provider-auth (get-in db [:auth provider])
        {:keys [line character]} position
        input-code (insert-compleiton-tag doc-text position)
        instructions (f.prompt/inline-completion-prompt)
        {:keys [error-message result]} (llm-api/complete!
                                        {:provider provider
                                         :model model
                                         :config config
                                         :input-code input-code
                                         :instructions instructions
                                         :provider-auth provider-auth
                                         :model-capabilities model-capabilities})]
    (cond
      error-message
      (messenger/showMessage messenger {:type :warning :message error-message})

      (not result)
      (messenger/showMessage messenger {:type :info :message "No suggestions found"})

      :else
      {:items [{:id "123"
                :text (normalize-code-result result)

                :doc-version doc-version
                :range {:start {:line line :character character}
                        :end {:line line :character character}}}]})))
