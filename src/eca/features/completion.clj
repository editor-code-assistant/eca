(ns eca.features.completion
  (:require
   [clojure.string :as string]
   [eca.features.completion-diff :as completion-diff]
   [eca.features.login :as f.login]
   [eca.features.prompt :as f.prompt]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[COMPLETION]")

(def ^:private completion-tag "<ECA_TAG>")
(def ^:private cursor-marker "<ECA_CURSOR>")
(def ^:private window-start-marker "<ECA_WINDOW_START>")
(def ^:private window-end-marker "<ECA_WINDOW_END>")

(def ^:private default-window-radius 6)
(def ^:private default-request-timeout-ms 30000)

(defn ^:private insert-completion-tag
  "Legacy prompt builder: insert the single `<ECA_TAG>` sentinel at the cursor."
  [doc-text {:keys [line character]}]
  (let [lines (let [ls (if (seq doc-text)
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

(defn ^:private region-replace-input
  "Build the model input for a region-replace completion.

  Returns `{:prompt :window :start-line :end-line}`. The prompt embeds the
  full document with the editable window wrapped in `<ECA_WINDOW_START>` /
  `<ECA_WINDOW_END>` markers and the cursor marked by `<ECA_CURSOR>` inside
  the window — the model is instructed to rewrite the window contents."
  [doc-text {:keys [line character]} window-radius]
  (let [{:keys [window start-line end-line]} (completion-diff/extract-window
                                              doc-text line window-radius)
        cursor-offset (completion-diff/cursor-position-in-window
                       window {:line line :character character} start-line)
        marked-window (if cursor-offset
                        (str (subs window 0 cursor-offset)
                             cursor-marker
                             (subs window cursor-offset))
                        window)
        all-lines (if (seq doc-text)
                    (string/split doc-text #"\n" -1)
                    [""])
        all-vec (vec all-lines)
        prefix-lines (subvec all-vec 0 (dec start-line))
        suffix-lines (subvec all-vec end-line)
        prompt (string/join
                "\n"
                (concat prefix-lines
                        [(str window-start-marker "\n" marked-window "\n" window-end-marker)]
                        suffix-lines))]
    {:prompt prompt
     :window window
     :start-line start-line
     :end-line end-line}))

(defn ^:private clean-window-output
  "Strip code fences and stray markers from the model's reply so it represents
  just the rewritten window."
  [output]
  (let [stripped (-> (or output "")
                     (string/replace cursor-marker "")
                     (string/replace window-start-marker "")
                     (string/replace window-end-marker "")
                     shared/normalize-code-result)]
    ;; Drop a single leading or trailing blank line the model often emits.
    (-> stripped
        (string/replace #"\A\n" "")
        (string/replace #"\n\z" ""))))

(defn ^:private call-llm
  [{:keys [provider model config prompt instructions provider-auth
           model-capabilities timeout-ms]}]
  (deref (future
           (try
             (llm-api/sync-prompt!
              {:provider provider
               :model model
               :config config
               :prompt prompt
               :instructions instructions
               :provider-auth provider-auth
               :model-capabilities (assoc model-capabilities
                                          :reason? false
                                          :tools false
                                          :web-search false)})
             (catch Exception e
               {:error {:exception e}})))
         timeout-ms
         {:error {:message "Timeout waiting for completion"}}))

(defn ^:private legacy-items
  "Legacy zero-width insertion at the cursor."
  [output-text doc-version line character]
  [{:text (shared/normalize-code-result output-text)
    :doc-version doc-version
    :range {:start {:line line :character character}
            :end {:line line :character character}}}])

(defn ^:private region-replace-items
  "Build the response items by diffing the model's rewritten window against
  the original window. Returns nil when there is no change."
  [output-text orig-window start-line doc-version]
  (let [new-window (clean-window-output output-text)]
    (when-let [{:keys [range text]} (completion-diff/diff-window
                                     orig-window new-window start-line)]
      [{:text text
        :doc-version doc-version
        :range range}])))

(defn complete [{:keys [doc-text doc-version position]} db* config messenger metrics]
  (let [full-model (get-in config [:completion :model])
        [provider model] (shared/full-model->provider+model full-model)
        timeout-ms (or (get-in config [:completion :requestTimeoutMs])
                       default-request-timeout-ms)
        window-radius (or (get-in config [:completion :windowRadius])
                          default-window-radius)
        _ (f.login/maybe-renew-auth-token!
           {:provider provider
            :on-renewing identity
            :on-error (fn [error-msg] (logger/error logger-tag (format "Auth token renew failed: %s" error-msg)))}
           {:db* db*
            :messenger messenger
            :config config
            :metrics metrics})
        db @db*
        region-replace? (boolean (get-in db [:client-capabilities
                                             :code-assistant
                                             :completion-capabilities
                                             :region-replace]))
        model-capabilities (get-in db [:models full-model])
        provider-auth (get-in db [:auth provider])
        {:keys [line character]} position
        instructions (if region-replace?
                       (f.prompt/inline-completion-region-replace-prompt config)
                       (f.prompt/inline-completion-prompt config))
        {:keys [prompt window start-line]} (when region-replace?
                                             (region-replace-input doc-text position window-radius))
        input-code (if region-replace?
                     prompt
                     (insert-completion-tag doc-text position))
        {:keys [error output-text]} (call-llm
                                     {:provider provider
                                      :model model
                                      :config config
                                      :prompt input-code
                                      :instructions instructions
                                      :provider-auth provider-auth
                                      :model-capabilities model-capabilities
                                      :timeout-ms timeout-ms})]
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

      region-replace?
      (if-let [items (region-replace-items output-text window start-line doc-version)]
        {:items items}
        {:error {:type :info
                 :message "No suggestions found"}})

      :else
      {:items (legacy-items output-text doc-version line character)})))
