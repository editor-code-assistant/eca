(ns eca.features.hooks
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [eca.features.tools.shell :as f.tools.shell]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(def ^:private logger-tag "[HOOK]")

(def ^:const hook-rejection-exit-code 2)

(def ^:const default-hook-timeout-ms 30000)

(defn base-hook-data
  "Returns common fields for ALL hooks (session and chat hooks).
   These fields are present in every hook type."
  [db]
  {:workspaces (shared/get-workspaces db)
   :db-cache-path (shared/db-cache-path db)})

(defn chat-hook-data
  "Returns common fields for CHAT-RELATED hooks.
   Includes base fields plus chat-specific fields (chat-id, agent).
   Use this for: preRequest, postRequest, subagentPostRequest, preToolCall, postToolCall, chatStart, chatEnd."
  [db chat-id agent-name]
  (merge (base-hook-data db)
         {:chat-id chat-id
          :agent agent-name
          :behavior agent-name}))

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
                   {:kind :selector
                    :selector (tools.util/selector->string tool-selector)
                    :args-matchers (:argsMatchers config)}
                   (logger/warn logger-tag "Ignoring non-map matcher entry for selector" {:selector tool-selector}))))
         vec)

    :else
    []))

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
              (let [value (arg-value tool-input arg-name)]
                (and (some? value)
                     (sequential? matchers)
                     (some #(regex-matches? % value) matchers))))
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
   - :preRequest, :postRequest, :subagentPostRequest (prompt lifecycle)
   - :preToolCall, :postToolCall (tool lifecycle)

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
                (logger/error logger-tag (format "Hook '%s' has both 'shell' and 'file' - must have exactly one" name))

                (and (not shell) (not file))
                (logger/error logger-tag (format "Hook '%s' missing both 'shell' and 'file' - must have one" name))

                (nil? cwd)
                (logger/error logger-tag (format "Hook '%s' cannot run: no workspace folders configured" name))

                shell
                (do (logger/debug logger-tag (format "Running hook '%s' inline shell '%s' with input '%s'" name shell input))
                    (run-and-parse-output! {:cwd cwd :input input :script shell :timeout (:timeout action)}))

                file
                (do (logger/debug logger-tag (format "Running hook '%s' file '%s' with input '%s'" name file input))
                    (run-and-parse-output! {:cwd cwd :input input :file (str (fs/expand-home file)) :timeout (:timeout action)}))))

    (logger/warn logger-tag (format "Unknown hook action type '%s' for %s" (:type action) name))))

(defn trigger-if-matches!
  "Run hook of specified type if matches any config for that type"
  [hook-type
   data
   {:keys [on-before-action on-after-action native-tools]
    :or {on-before-action identity
         on-after-action identity}}
   db
   config]
  ;; Sort hooks by name to ensure deterministic execution order.
  (doseq [[name hook] (sort-by key (:hooks config))]
    (when (hook-matches? hook-type data hook native-tools)
      (vec
       (map-indexed (fn [i action]
                      (let [id (str (random-uuid))
                            action-type (:type action)
                            name (if (> (count (:actions hook)) 1)
                                   (str name "-" (inc i))
                                   name)
                            visible? (get hook :visible true)]
                        (on-before-action {:id id
                                           :visible? visible?
                                           :name name})
                        (if-let [result (run-hook-action! action name hook-type data db)]
                          (on-after-action (merge result
                                                  {:id id
                                                   :name name
                                                   :type action-type
                                                   :visible? visible?}))
                          (on-after-action {:id id
                                            :name name
                                            :visible? visible?
                                            :type action-type
                                            :exit 1}))))
                    (:actions hook))))))
