(ns eca.features.hooks
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [eca.features.tools.shell :as f.tools.shell]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[HOOK]")

(def ^:const hook-rejection-exit-code 2)

(def ^:const default-hook-timeout-ms 30000)

(defn ^:private session-id-from-db-cache-path
  [db-cache-path]
  (some-> db-cache-path fs/file fs/parent fs/file-name str not-empty))

(defn base-hook-data
  "Returns common fields for ALL hooks: :workspaces, :cwd, :db-cache-path,
   :session-id, and :eca-executable."
  [db]
  (let [workspaces (shared/get-workspaces db)
        db-cache-path (shared/db-cache-path db)]
    {:workspaces workspaces
     :cwd (first workspaces)
     :db-cache-path db-cache-path
     :session-id (session-id-from-db-cache-path db-cache-path)
     :eca-executable @shared/eca-executable*}))

(defn chat-hook-data
  "Returns common fields for CHAT-RELATED hooks.
   Merges base-hook-data, optional model fields (:full-model, :variant),
   and chat-specific fields (:chat-id, :agent, :behavior)."
  [db {:keys [chat-id agent full-model variant]}]
  (merge (base-hook-data db)
         (shared/assoc-some {} :full-model full-model :variant variant)
         {:chat-id  chat-id
          :agent    agent
          :behavior agent}))

(defn ^:private parse-hook-json
  "Attempts to parse hook output as JSON. Returns parsed map if successful, nil otherwise."
  [output]
  (when (and output (not-empty output))
    (try
      (let [parsed (json/parse-string output)]
        (if (map? parsed)
          parsed
          (logger/debug logger-tag "Hook JSON output must result in map")))
      (catch Exception e
        (logger/debug logger-tag "Hook output is not valid JSON, treating as plain text"
                      {:output output :error (.getMessage e)})
        nil))))

(defn run-shell-cmd [opts]
  (try
    (let [timeout-ms (or (:timeout opts) default-hook-timeout-ms)
          proc (f.tools.shell/start-shell-process! opts)
          result (deref proc timeout-ms ::timeout)]
      (if (= result ::timeout)
        (do
          (logger/warn logger-tag "Hook timed out" {:timeout-ms timeout-ms})
          (p/destroy-tree proc)
          {:exit 1 :out nil :err (format "Hook timed out after %d seconds" (/ timeout-ms 1000))})
        {:exit (:exit result)
         :out (:out result)
         :err (:err result)}))
    (catch Exception e
      (let [msg (or (.getMessage e) "Caught an Exception during execution of hook")]
        (logger/warn logger-tag "Got an Exception during execution" {:message msg})
        {:exit 1 :err msg}))))

(defn ^:private should-skip-on-error?
  "Check if postToolCall hook should be skipped when tool errors.
  By default, postToolCall hooks only run on success unless runOnError is true."
  [type hook data]
  (and (= type :postToolCall)
       (not (get hook :runOnError false))
       (:error data)))

(defn ^:private parse-matcher-rules
  "Returns normalized rules for missing, string, or object hook matchers.
   String matchers stay legacy regex rules; object matcher keys become tool selector rules."
  [matcher]
  (cond
    (nil? matcher)
    [{:kind :regex :pattern ".*"}]

    (string? matcher)
    [{:kind :regex :pattern matcher}]

    (map? matcher)
    (->> matcher
         (keep (fn [[tool-selector config]]
                 (if (map? config)
                   (let [has-args-matchers? (contains? config :argsMatchers)
                         am (:argsMatchers config)]
                     (if (and has-args-matchers? (not (map? am)))
                       (do
                         (logger/warn logger-tag "argsMatchers must be a map, ignoring matcher entry" {:selector tool-selector})
                         nil)
                       {:kind :selector
                        :selector (tools.util/selector->string tool-selector)
                        :args-matchers am}))
                   (logger/warn logger-tag "Ignoring non-map matcher entry for selector" {:selector tool-selector}))))
         vec)

    :else
    (do (logger/warn logger-tag "Unsupported matcher type, ignoring" {:matcher matcher})
        [])))

(defn ^:private arg-value [tool-input arg-name]
  (when (map? tool-input)
    (when-let [arg-str (tools.util/selector->string arg-name)]
      (get tool-input arg-str))))

(defn ^:private regex-matches? [pattern value]
  (try
    (boolean (re-matches (re-pattern (str pattern)) (str value)))
    (catch Exception e
      (logger/warn logger-tag "Invalid hook matcher regex" {:pattern (str pattern)
                                                            :error (.getMessage e)})
      false)))

(defn ^:private args-match?
  "Matches all configured argument rules.
   Patterns within a single argument are ORed; arguments are ANDed; missing arguments do not match."
  [args-matchers tool-input]
  (cond
    (or (nil? args-matchers)
        (and (map? args-matchers) (empty? args-matchers)))
    true

    (not (map? args-matchers))
    false

    :else
    (every? (fn [[arg-name matchers]]
              (cond
                (not (sequential? matchers))
                (do (logger/warn logger-tag "Arg matcher values must be sequential (list of regex alternatives), skipping"
                                 {:arg-name arg-name :value matchers})
                    false)
                :else
                (let [value (arg-value tool-input arg-name)]
                  (and (some? value)
                       (some #(regex-matches? % value) matchers)))))
            args-matchers)))

(defn ^:private hook-matches? [hook-type data hook native-tools]
  (let [hook-config-type (keyword (:type hook))
        hook-config-type (cond ;; legacy values
                           (= :prePrompt hook-config-type) :preRequest
                           (= :postPrompt hook-config-type) :postRequest
                           :else hook-config-type)]
    (cond
      (or (not= hook-type hook-config-type)
          (should-skip-on-error? hook-type hook data))
      false

      (contains? #{:preToolCall :postToolCall} hook-type)
      (let [rules (parse-matcher-rules (:matcher hook))
            full-name (str (:server data) "__" (:tool-name data))]
        (some (fn [{:keys [kind pattern selector args-matchers]}]
                (case kind
                  :regex (regex-matches? pattern full-name)
                  :selector (and (tools.util/tool-selector-matches? selector (:server data) (:tool-name data) native-tools)
                                 (args-match? args-matchers (:tool-input data)))
                  false))
              rules))

      (contains? #{:preCompact :postCompact} hook-type)
      (let [matcher (:matcher hook)]
        (cond
          (nil? matcher) true
          (string? matcher) (= matcher (:triggered data))
          :else (do
                  (logger/warn logger-tag "Ignoring unsupported compact hook matcher"
                               {:hook-type hook-type
                                :matcher matcher})
                  false)))

      :else
      true)))

(defn ^:private run-and-parse-output!
  "Run shell command and return parsed result map."
  [opts]
  (let [{:keys [exit out err]} (run-shell-cmd opts)
        raw-output (not-empty out)
        raw-error (not-empty err)]
    {:exit exit
     :raw-output raw-output
     :raw-error raw-error
     :parsed (parse-hook-json raw-output)}))

(defn run-hook-action!
  "Execute a single hook action. Supported hook types:
   - :sessionStart, :sessionEnd (session lifecycle)
   - :chatStart, :chatEnd (chat lifecycle)
   - :subagentStart, :subagentPostRequest (subagent lifecycle)
   - :preRequest, :postRequest (prompt lifecycle)
   - :preToolCall, :postToolCall (tool lifecycle)
   - :preCompact, :postCompact (compact lifecycle)

   Returns map with :exit, :raw-output, :raw-error, :parsed"
  [action name hook-type data db]
  (case (:type action)
    "shell" (let [cwd (some-> (:workspace-folders db)
                              first
                              :uri
                              shared/uri->filename)
                  shell (:shell action)
                  file (:file action)
                  ;; Convert to snake_case for bash/shell conventions
                  ;; Nested keys (e.g. tool_input/tool_response): kebab-case (matches LLM format)
                  input (json/generate-string (shared/map->snake-cased-map
                                               (merge {:hook-name name :hook-type hook-type} data)))]
              (cond
                (and shell file)
                (logger/warn logger-tag (format "Hook '%s' has both 'shell' and 'file' - must have exactly one" name))

                (and (not shell) (not file))
                (logger/warn logger-tag (format "Hook '%s' missing both 'shell' and 'file' - must have one" name))

                (nil? cwd)
                (logger/warn logger-tag (format "Hook '%s' cannot run: no workspace folders configured" name))

                shell
                (do (logger/debug logger-tag (format "Running hook '%s' inline shell '%s' with input '%s'" name shell input))
                    (run-and-parse-output! {:cwd cwd :input input :script shell :timeout (:timeout action)}))

                file
                (do (logger/debug logger-tag (format "Running hook '%s' file '%s' with input '%s'" name file input))
                    (run-and-parse-output! {:cwd cwd :input input :file (str (fs/expand-home file)) :timeout (:timeout action)}))))

    (logger/warn logger-tag (format "Unknown hook action type '%s' for %s" (:type action) name))))

(defn successful-continue-false?
  "True when a hook action result is a successful (exit 0) continue:false.
   This is the canonical 'turn stop' signal: JSON effects (including
   continue:false) are only honored on exit 0, so a non-zero exit never counts
   here even if its stdout contained continue:false."
  [{:keys [exit parsed]}]
  (and (zero? exit)
       (false? (get parsed "continue" true))))

(defn ^:private stop-processing?
  "Predicate over an action result: should iteration of remaining
   actions/hooks halt? True when a successful action (exit 0) returns
   continue:false."
  [result]
  (successful-continue-false? result))

(defn ^:private run-matched-action!
  "Execute a single matched action: notify before, run, notify after.
   Returns the merged action result so the caller can inspect it without
   conflating 'what happened' with 'what to do next'."
  [hook-type data {:keys [on-before-action on-after-action]} db hook-name hook i action]
  (let [id (str (random-uuid))
        action-type (:type action)
        action-name (if (> (count (:actions hook)) 1)
                      (str hook-name "-" (inc i))
                      hook-name)
        visible? (get hook :visible true)
        base {:id id
              :name action-name
              :type action-type
              :hook-type hook-type
              :visible? visible?}]
    (on-before-action (select-keys base [:id :visible? :name :type]))
    (let [result (merge (or (run-hook-action! action action-name hook-type data db)
                            {:exit 1})
                        base)]
      (on-after-action result)
      result)))

(defn trigger-if-matches!
  "Run matching hooks of `hook-type` against `data` for side effects.
   Execution order is deterministic: hooks sorted by name, then actions
   in declaration order. A successful action that returns continue:false
   halts iteration over BOTH the remaining actions of the current hook
   AND any subsequent matching hooks. Exit code 2
   carries hook-specific semantics via the per-action callbacks but does
   NOT short-circuit iteration."
  [hook-type
   data
   {:keys [on-before-action on-after-action native-tools]
    :or {on-before-action identity
         on-after-action identity}}
   db
   config]
  (let [opts {:on-before-action on-before-action
              :on-after-action on-after-action}
        matched-actions (for [[hook-key hook] (sort-by key (:hooks config))
                              :when (hook-matches? hook-type data hook native-tools)
                              [i action] (map-indexed vector (:actions hook))]
                          [(name hook-key) hook i action])]
    (reduce (fn [_ [name hook i action]]
              (let [result (run-matched-action! hook-type data opts db name hook i action)]
                (when (stop-processing? result)
                  (reduced nil))))
            nil
            matched-actions)))
