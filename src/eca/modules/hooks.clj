(ns eca.modules.hooks
  "The `:eca/hooks` module: the user's shell hooks, from the `hooks` config,
   and `/hooks`. The hooks run as this module's event handlers, after the
   other modules' ones (priority -1000), so a module's block or stop skips
   them; disabling the module turns them all off."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.chat.lifecycle :as f.chat.lifecycle]
   [eca.features.tools :as f.tools]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.modules :as modules]
   [eca.shared :as shared :refer [assoc-some]]
   [eca.shell :as shell]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[HOOK]")

(def ^:const hook-rejection-exit-code 2)

(def ^:const default-hook-timeout-ms 30000)

(defn ^:private session-id-from-db-cache-path
  "Session key derived from `db-cache-path`: the workspace cache dir name.
   The path is that dir itself since the per-chat layout (#557); a legacy
   `db.transit.json` file path yields its parent dir name."
  [db-cache-path]
  (some-> (when db-cache-path
            (if (string/ends-with? db-cache-path ".json")
              (fs/parent (fs/file db-cache-path))
              (fs/file db-cache-path)))
          fs/file-name
          str
          not-empty))

(defn base-hook-data
  "Returns common fields for ALL hooks: :workspaces, :cwd, :db-cache-path,
   :session-id, :eca-executable, and :eca-version."
  [db]
  (let [workspaces (shared/get-workspaces db)
        db-cache-path (shared/db-cache-path db)]
    {:workspaces workspaces
     :cwd (first workspaces)
     :db-cache-path db-cache-path
     :session-id (session-id-from-db-cache-path db-cache-path)
     :eca-executable @shared/eca-executable*
     :eca-version (config/eca-version)}))

(defn chat-hook-data
  "Returns common fields for CHAT-RELATED hooks.
   Merges base-hook-data, optional model fields (:full-model, :variant),
   and chat-specific fields (:chat-id, :agent, :behavior)."
  [db {:keys [chat-id agent full-model variant]}]
  (merge (base-hook-data db)
         (assoc-some {} :full-model full-model :variant variant)
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
          proc (shell/start-shell-process! opts)
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
   - :chatStart, :chatEnd, :chatStatusChanged (chat lifecycle)
   - :subagentStart, :subagentPostRequest (subagent lifecycle)
   - :preRequest, :postRequest (prompt lifecycle)
   - :preToolCall, :postToolCall (tool lifecycle)
   - :preCompact, :postCompact (compact lifecycle)

   With `ctx`, which carries `:config`, the `:shell/before-exec` event runs
   first, so modules can change the command.

   Returns map with :exit, :raw-output, :raw-error, :parsed"
  [action name hook-type data db & [ctx]]
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
                                               (merge {:hook-name name :hook-type hook-type} data)))
                  ;; modules may change the command first (:shell/before-exec)
                  run-command! (fn [opts]
                                 (run-and-parse-output!
                                  (shell/before-exec
                                   ctx opts {:source :hook
                                             :hook name
                                             :hook-type hook-type
                                             :timeout-ms (or (:timeout action) default-hook-timeout-ms)})))]
              (cond
                (and shell file)
                (logger/warn logger-tag (format "Hook '%s' has both 'shell' and 'file' - must have exactly one" name))

                (and (not shell) (not file))
                (logger/warn logger-tag (format "Hook '%s' missing both 'shell' and 'file' - must have one" name))

                (nil? cwd)
                (logger/warn logger-tag (format "Hook '%s' cannot run: no workspace folders configured" name))

                shell
                (do (logger/debug logger-tag (format "Running hook '%s' inline shell '%s' with input '%s'" name shell input))
                    (run-command! {:cwd cwd :input input :script shell :timeout (:timeout action)}))

                file
                (let [expanded (str (fs/expand-home file))
                      resolved (if (fs/absolute? expanded)
                                 (fs/file expanded)
                                 (fs/file cwd expanded))]
                  (if (fs/exists? resolved)
                    (do (logger/debug logger-tag (format "Running hook '%s' file '%s' with input '%s'" name file input))
                        (run-command! {:cwd cwd :input input :file expanded :timeout (:timeout action)}))
                    (let [msg (format "Hook file not found: '%s' (relative paths resolve against the first workspace root)" (str resolved))]
                      (logger/warn logger-tag (format "Hook '%s': %s" name msg))
                      {:exit 1 :raw-output nil :raw-error msg :parsed nil})))))

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
  [hook-type data {:keys [on-before-action on-after-action ctx]} db hook-name hook i action]
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
    (let [result (merge (or (run-hook-action! action action-name hook-type data db ctx)
                            {:exit 1})
                        base)]
      (on-after-action result)
      result)))

(defn active-hooks
  "The configured hooks, while `:eca/hooks` is active."
  [config]
  (when (modules/active? config :eca/hooks)
    (:hooks config)))

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
              :on-after-action on-after-action
              ;; for the :shell/before-exec event of each hook command
              :ctx (assoc-some {:config config} :chat-id (:chat-id data))}
        matched-actions (for [[hook-key hook] (sort-by key (active-hooks config))
                              :when (hook-matches? hook-type data hook native-tools)
                              [i action] (map-indexed vector (:actions hook))]
                          [(name hook-key) hook i action])]
    (reduce (fn [_ [name hook i action]]
              (let [result (run-matched-action! hook-type data opts db name hook i action)]
                (when (stop-processing? result)
                  (reduced nil))))
            nil
            matched-actions)))

(defn ^:private hook-label
  "Who acted, as the messages core shows name it: \"hook 'lint'\"."
  [hook-name]
  (str "hook '" hook-name "'"))

(defn ^:private hooks-of-type?
  "Whether a hook of `hook-type` (a keyword) is configured."
  [config hook-type]
  (some #(= hook-type (keyword (:type %))) (vals (active-hooks config))))

;;; What a hook shows in the chat

(defn ^:private hook-system-text
  "User-facing standalone message attributed to a hook by name.
   Ends with a blank line so the message renders as its own paragraph and never
   visually glues to a following assistant stream (e.g. a preRequest systemMessage
   emitted right before the model's reply)."
  [hook-name text]
  (str "Hook '" hook-name "': " text "\n\n"))

(defn ^:private format-hook-output
  "Format the hook execution block's text: 'Hook executed' (or raw plain-text
   stdout when the hook emitted no JSON) plus structural effect lines.
   systemMessage is intentionally NOT included here - it is surfaced as a
   standalone user-facing message by notify-after-hook-action!, never as the
   block's text. Tool hooks correlate with their tool widget via the structured
   :tool-call-id sent on hookActionFinished, not via the block text."
  [hook-type {:strs [replacedPrompt additionalContext followUp replacedOutput updatedInput] :as parsed} raw-output]
  (let [replaced-prompt (shared/not-blank replacedPrompt)
        additional-context (shared/not-blank additionalContext)
        follow-up (shared/not-blank followUp)]
    (if parsed
      (cond-> "Hook executed"
        (and (= :preRequest hook-type) replaced-prompt) (str "\nReplacedPrompt: " (pr-str replaced-prompt))
        (and (= :preToolCall hook-type) (some? updatedInput)) (str "\nUpdatedInput: " (pr-str updatedInput))
        (and (#{:postRequest :subagentPostRequest} hook-type) follow-up) (str "\nFollowUp: " follow-up)
        (and (= :postToolCall hook-type) (string? replacedOutput)) (str "\nReplacedOutput: " (pr-str replacedOutput))
        additional-context (str "\nAdditionalContext: " additional-context))
      (not-empty raw-output))))

(defn ^:private notify-before-hook-action! [chat-ctx {:keys [id name type visible?]}]
  (when visible?
    (f.chat.lifecycle/send-content! chat-ctx :system
                                    {:type :hookActionStarted
                                     :action-type type
                                     :name name
                                     :id id})))

(defn ^:private notify-after-hook-action! [chat-ctx {:keys [id name parsed raw-output raw-error exit type hook-type visible? tool-call-id]}]
  (let [advisory-chat-hook? (contains? #{:chatEnd :chatStatusChanged} hook-type)
        ;; JSON output fields are only honored on exit 0 (and never for advisory chat hooks).
        parsed-effects (when (and (not advisory-chat-hook?) (zero? exit)) parsed)
        suppress? (boolean (get parsed-effects "suppressOutput"))]
    ;; Two independent channels:
    ;; 1. Execution block, gated by `visible?`. If a started event was sent we must
    ;;    send a matching finished (same id) so the client closes the spinner.
    ;;    `suppressOutput` drops only the body, not the block. Never carries systemMessage.
    (when visible?
      (f.chat.lifecycle/send-content! chat-ctx :system
                                      (cond-> (assoc-some {:type :hookActionFinished
                                                           :action-type type
                                                           :id id
                                                           :name name
                                                           :status exit}
                                                          :tool-call-id tool-call-id)
                                        (not suppress?) (assoc :output (format-hook-output hook-type parsed-effects raw-output)
                                                               :error raw-error))))

    ;; 2. systemMessage: standalone message, independent of visible/suppressOutput.
    (when-let [system-message (shared/not-blank (get parsed-effects "systemMessage"))]
      (f.chat.lifecycle/send-content! chat-ctx :system {:type :text :text (hook-system-text name system-message)}))))

(defn ^:private ui-options
  "`trigger-if-matches!`'s callbacks showing each action in the chat, then
   calling `after-fn` with its result."
  [chat-ctx after-fn]
  {:on-before-action (partial notify-before-hook-action! chat-ctx)
   :on-after-action (fn [result]
                      (notify-after-hook-action! chat-ctx result)
                      (after-fn result))})

;;; Session and chat lifecycle

(defn ^:private session-start
  "`:session/started` handler: the sessionStart hooks."
  [{:keys [db* config]} _event]
  (trigger-if-matches! :sessionStart (base-hook-data @db*) {} @db* config)
  nil)

(defn ^:private session-end
  "`:session/ended` handler: the sessionEnd hooks."
  [{:keys [db* config]} _event]
  (trigger-if-matches! :sessionEnd (base-hook-data @db*) {} @db* config)
  nil)

(defn ^:private chat-start
  "`:chat/started` handler: chatStart hooks, or subagentStart for a subagent,
   at the chat's first prompt. Their additionalContext, from successful hooks,
   goes in the chat's system prompt, after the modules' one; a continue:false
   on exit 0 stops the turn."
  [{:keys [db* chat-id agent messenger variant config]} {:keys [model resumed?]}]
  (let [db @db*
        hook-results* (atom [])
        subagent? (some? (get-in db [:chats chat-id :subagent]))
        hook-ctx (cond-> {:messenger messenger :chat-id chat-id}
                   subagent? (assoc :parent-chat-id (db/parent-chat-id db chat-id)))
        hook-type (if subagent? :subagentStart :chatStart)
        hook-data (cond-> (chat-hook-data db {:chat-id chat-id
                                              :agent agent
                                              :full-model model
                                              :variant variant})
                    subagent? (assoc :parent-chat-id (db/parent-chat-id db chat-id))
                    (not subagent?) (assoc :resumed resumed?))]
    (trigger-if-matches! hook-type hook-data (ui-options hook-ctx #(swap! hook-results* conj %)) db config)
    (let [contexts (keep (fn [{:keys [parsed exit]}]
                           (when (zero? exit)
                             (shared/not-blank (get parsed "additionalContext"))))
                         @hook-results*)
          stop (some (fn [{:keys [parsed name] :as result}]
                       (when (successful-continue-false? result)
                         {:reason (shared/not-blank (get parsed "stopReason")) :name name}))
                     @hook-results*)]
      (cond-> {}
        (seq contexts) (assoc :additional-context (string/join "\n\n" contexts))
        stop (assoc :stop (or (:reason stop) "") :by (hook-label (:name stop)))))))

(defn ^:private chat-end
  "`:chat/ended` handler: the chatEnd hooks, before the chat goes."
  [{:keys [db* config]} {:keys [chat-id agent model variant]}]
  (let [db @db*]
    (trigger-if-matches! :chatEnd
                         (chat-hook-data db {:chat-id chat-id :agent agent :full-model model :variant variant})
                         {}
                         db
                         config)
    nil))

(defn ^:private chat-status-changed
  "`:chat/status-changed` handler: the advisory chatStatusChanged hooks, with
   the chat's aggregate status. Nothing shows in the chat."
  [{:keys [db* config] :as chat-ctx}
   {:keys [status pending-approval-ids pending-question-ids running-tool-ids waiting-reason parent-chat-id]}]
  (when (hooks-of-type? config :chatStatusChanged)
    (let [db @db*]
      (trigger-if-matches! :chatStatusChanged
                           (merge (chat-hook-data db chat-ctx)
                                  (when parent-chat-id
                                    {:parent-chat-id parent-chat-id})
                                  (assoc-some {:status status
                                               :awaiting-user-input (boolean (or (seq pending-approval-ids)
                                                                                 (seq pending-question-ids)))
                                               :pending-approval-tool-call-ids pending-approval-ids
                                               :pending-question-tool-call-ids pending-question-ids
                                               :running-tool-call-ids running-tool-ids}
                                              :waiting-reason waiting-reason))
                           {}
                           db
                           config)))
  nil)

;;; preRequest

(defn ^:private update-pre-request-state
  "Pure function to compute new state from hook result."
  [{:keys [final-prompt additional-contexts stop-turn? blocked? blocked-reasons stop-reason stop-hook-name]}
   {:keys [parsed exit]}
   action-name]
  (let [replaced-prompt (shared/not-blank (get parsed "replacedPrompt"))
        additional-context (shared/not-blank (get parsed "additionalContext"))
        success? (= 0 exit)
        stop-turn-result? (and success? (false? (get parsed "continue" true)))]
    {:final-prompt (if (and replaced-prompt success?)
                     replaced-prompt
                     final-prompt)
     :additional-contexts (if (and additional-context success?)
                            (conj additional-contexts
                                  {:hook-name action-name :content additional-context})
                            additional-contexts)
     :stop-turn? (or stop-turn? stop-turn-result?)
     :blocked? blocked?
     :blocked-reasons blocked-reasons
     :stop-reason (if stop-turn-result?
                    (or stop-reason (shared/not-blank (get parsed "stopReason")))
                    stop-reason)
     :stop-hook-name (if stop-turn-result?
                       (or stop-hook-name action-name)
                       stop-hook-name)}))

(defn ^:private pre-request-block-reason
  "Stderr text of a preRequest exit-2 block, or nil when empty."
  [{:keys [raw-error]}]
  (some-> raw-error str string/trim not-empty))

(defn ^:private run-pre-request-action!
  "Run a single preRequest hook action, updating the accumulator state.

  State is a map:
  - :final-prompt
  - :additional-contexts
  - :stop-turn? (true when any hook requests current-turn stop via continue:false)
  - :stop-reason
  - :blocked? (true when any hook blocks the request via exit 2)
  - :blocked-reasons, the invisible blocking hooks' `{:name :reason}`"
  [db chat-ctx hook hook-name idx action state]
  (if (:stop-turn? state)
    state
    (let [id (str (random-uuid))
          action-type (:type action)
          action-name (if (> (count (:actions hook)) 1)
                        (str hook-name "-" (inc idx))
                        hook-name)
          visible? (get hook :visible true)]
      (notify-before-hook-action! chat-ctx {:id id
                                            :visible? visible?
                                            :name action-name
                                            :type action-type})
      ;; Run the hook action
      (if-let [result (run-hook-action! action
                                        action-name
                                        :preRequest
                                        (merge (chat-hook-data db chat-ctx)
                                               {:prompt (:final-prompt state)})
                                        db
                                        chat-ctx)]
        (let [{:keys [raw-error exit]} result
              exit-2? (= hook-rejection-exit-code exit)]
          ;; Notify after action
          (notify-after-hook-action! chat-ctx (merge result
                                                     {:id id
                                                      :name action-name
                                                      :type action-type
                                                      :visible? visible?
                                                      :status exit
                                                      :hook-type :preRequest
                                                      :error raw-error}))
          ;; Exit 2 blocks the prompt but does not stop remaining preRequest
          ;; hooks; only successful continue:false short-circuits processing.
          ;; Only collect stderr from INVISIBLE hooks; visible hooks already
          ;; surface stderr through hookActionFinished :error, so duplicating
          ;; it as a separate chat message would be noisy.
          (if exit-2?
            (cond-> (assoc state :blocked? true)
              ;; Remember the first blocking hook so the user-facing message can
              ;; name it even for visible hooks (consistent with turn-stopped).
              (not (:blocked-hook-name state)) (assoc :blocked-hook-name action-name)
              (not visible?) (update :blocked-reasons conj {:name action-name
                                                            :reason (pre-request-block-reason result)}))
            (update-pre-request-state state
                                      result
                                      action-name)))
        ;; No result from action
        (do
          (notify-after-hook-action! chat-ctx {:id id
                                               :name action-name
                                               :visible? visible?
                                               :type action-type
                                               :hook-type :preRequest
                                               :exit 1
                                               :status 1})
          state)))))

(defn ^:private run-pre-request-hook!
  "Run all actions for a single preRequest hook, threading state."
  [db chat-ctx state [hook-name hook]]
  (reduce
   (fn [s [idx action]]
     (if (:stop-turn? s)
       (reduced s)
       (run-pre-request-action! db chat-ctx hook (name hook-name) idx action s)))
   state
   (map-indexed vector (:actions hook))))

(defn ^:private run-pre-request-hooks!
  "Run preRequest hooks with chaining support.

  Returns a map with:
  - :final-prompt
  - :additional-contexts (vector of {:hook-name name :content context})
  - :stop-turn? (true when any hook requests current-turn stop via continue:false)
  - :stop-reason
  - :blocked? (true when any hook blocks the request via exit 2)
  - :blocked-reasons"
  [{:keys [db* config message] :as chat-ctx}]
  (let [db @db*]
    (reduce
     (fn [state hook-entry]
       (if (:stop-turn? state)
         (reduced state)
         (run-pre-request-hook! db chat-ctx state hook-entry)))
     {:final-prompt message
      :additional-contexts []
      :stop-turn? false
      :blocked? false
      :blocked-reasons []
      :blocked-hook-name nil
      :stop-reason nil
      :stop-hook-name nil}
     (->> (active-hooks config)
          ;; This function owns preRequest chaining, so it must select legacy
          ;; prePrompt hooks itself; hook-matches? only normalizes legacy types
          ;; for the generic trigger-if-matches! path.
          (filter #({"preRequest" "prePrompt"} (:type (val %))))
          (sort-by key)))))

(defn ^:private pre-request
  "`:chat/before-request` handler, also for a compaction's request: the
   preRequest hooks, chained, each seeing the prompt as the previous one left
   it. A successful continue:false stops the turn and the next hooks; an exit
   2 blocks the request, the next hooks still running. Their additionalContexts
   follow the prompt as plain blocks, after the modules' ones."
  [ctx {:keys [prompt]}]
  (let [{:keys [final-prompt additional-contexts stop-turn? stop-reason stop-hook-name
                blocked? blocked-reasons blocked-hook-name]}
        (run-pre-request-hooks! (assoc ctx :message prompt))]
    (cond
      stop-turn?
      {:stop (or stop-reason "") :by (hook-label stop-hook-name)}

      blocked?
      ;; an invisible hook's message has its stderr; visible ones showed theirs in
      ;; their block, so the message names the first one. Core shows the last.
      (let [{:keys [name reason]} (last blocked-reasons)]
        (doseq [{:keys [name reason]} (butlast blocked-reasons)]
          (f.chat.lifecycle/send-ended! ctx "Request blocked" {:by (hook-label name)} reason))
        {:block (or reason true)
         :by (hook-label (if (seq blocked-reasons) name blocked-hook-name))})

      :else
      (cond-> {}
        (not= prompt final-prompt) (assoc :prompt final-prompt)
        (seq additional-contexts) (assoc :additional-context
                                         (mapv (fn [{:keys [content]}] {:text content :label? false})
                                               additional-contexts))))))

;;; postRequest

(defn ^:private run-post-request-shell-hooks!
  "Run postRequest (for primary chats) and subagentPostRequest (for subagents) hooks.
   Returns {:follow-up-text string-or-nil :stop-turn? boolean
            :stop-reason string-or-nil :stop-hook-name string-or-nil}.

   postRequest/subagentPostRequest exit 2 with stderr is treated as followUp:
   stderr becomes the followUp text, analogous to how preToolCall/postToolCall
   exit 2 makes stderr LLM-visible payload. This is because these hooks run
   after the prompt finished, so exit 2 cannot 'block' the request; instead it
   contributes a continuation instruction."
  [chat-ctx db* config chat-id response]
  (let [db @db*
        results* (atom [])
        subagent? (some? (get-in db [:chats chat-id :subagent]))
        follow-up-active? (get-in db [:chats chat-id :follow-up-active?])
        ;; follow-up-active is always sent as a boolean (documented contract),
        ;; so hook authors can rely on the key being present as true or false.
        base-hook-data (assoc-some (chat-hook-data db chat-ctx)
                                   :response response
                                   :follow-up-active (boolean follow-up-active?))
        cb (ui-options chat-ctx #(swap! results* conj %))
        ;; postRequest is primary-only. Subagent chats use subagentPostRequest.
        _ (if subagent?
            (trigger-if-matches! :subagentPostRequest
                                 (assoc base-hook-data :parent-chat-id (db/parent-chat-id db chat-id))
                                 cb
                                 db
                                 config)
            (trigger-if-matches! :postRequest base-hook-data cb db config))
        hook-results @results*
        follow-ups (->> hook-results
                        (keep (fn [{:keys [parsed exit raw-error]}]
                                (cond
                                  (zero? exit)
                                  (shared/not-blank (get parsed "followUp"))

                                  (= hook-rejection-exit-code exit)
                                  (shared/not-blank raw-error))))
                        seq)
        follow-up-text (some->> follow-ups (string/join "\n\n"))
        stop-result (some (fn [{:keys [parsed name] :as result}]
                            (when (successful-continue-false? result)
                              {:stop-reason (shared/not-blank (get parsed "stopReason"))
                               :stop-hook-name name}))
                          hook-results)]
    {:follow-up-text follow-up-text
     :stop-turn? (boolean stop-result)
     :stop-reason (:stop-reason stop-result)
     :stop-hook-name (:stop-hook-name stop-result)}))

(defn ^:private post-request
  "`:chat/after-request` handler: the postRequest hooks, or subagentPostRequest
   for a subagent. Their followUps come after the modules' one; a successful
   continue:false ends the turn."
  [{:keys [db* config chat-id] :as chat-ctx} {:keys [response follow-up]}]
  (let [{:keys [follow-up-text stop-turn? stop-reason stop-hook-name]}
        (run-post-request-shell-hooks! chat-ctx db* config chat-id response)]
    (cond-> {}
      follow-up-text (assoc :follow-up (string/join "\n\n" (remove nil? [(shared/not-blank follow-up) follow-up-text])))
      stop-turn? (assoc :stop (or stop-reason "") :by (hook-label stop-hook-name)))))

;;; preToolCall

(def ^:private hook-approval-rank
  {"allow" 1
   "ask" 2
   "deny" 3})

(defn ^:private stronger-hook-approval
  [current candidate]
  (if (> (get hook-approval-rank candidate 0)
         (get hook-approval-rank current 0))
    candidate
    current))

(defn ^:private process-pre-tool-call-hook-result
  "Pure function: fold a single hook result into accumulated state.

   acc is {:hook-results [], :approval-override nil,
           :tool-call-rejected-by-hook? false, :tool-call-rejection-reason nil,
           :stop-turn? false, :stop-reason nil, :stop-hook-name nil}"
  [acc result]
  (let [parsed (:parsed result)
        success? (zero? (:exit result))
        exit-code-2? (= hook-rejection-exit-code (:exit result))
        stop-turn-result? (and success? (false? (get parsed "continue" true)))
        ;; success? (exit 0) and exit-code-2? are mutually exclusive, so the
        ;; approval field is only read on exit 0.
        hook-approval (when success?
                        (get parsed "approval"))]
    (cond-> (update acc :hook-results conj result)
      ;; Handle current-turn stop for successful hooks, even without rejection.
      stop-turn-result?
      (merge {:stop-turn? true
              :stop-reason (shared/not-blank (get parsed "stopReason"))
              :stop-hook-name (:name result)})

      ;; Handle rejection (exit code 2 or successful explicit deny)
      (or exit-code-2? (= "deny" hook-approval))
      (merge {:tool-call-rejected-by-hook? true
              :tool-call-rejection-reason (if exit-code-2?
                                            (or (shared/not-blank (:raw-error result))
                                                "Tool call rejected by hook")
                                            (or (shared/not-blank (get parsed "additionalContext"))
                                                "Tool call rejected by hook"))})

      ;; Handle approval override with hook precedence: deny > ask > allow.
      (contains? hook-approval-rank hook-approval)
      (update :approval-override stronger-hook-approval hook-approval))))

(defn ^:private pre-tool-call
  "`:tool/before-call` handler: the preToolCall hooks, with the call as the
   modules left it. Their updatedInputs merge into the arguments, in order; a
   hook's ask makes an allowed call ask, but its allow never loosens an ask or
   a deny; an exit 2 or a deny rejects the call, with the stderr or the
   additionalContext as the reason for the model; a successful continue:false
   stops the turn. ask_user, which always waits for the user, shows as ask."
  [{:keys [config db all-tools] :as chat-ctx}
   {:keys [tool server arguments approval tool-call-id chat-id agent model variant]}]
  (let [{:keys [name]} (f.tools/resolve-tool tool all-tools)
        hook-approval (if (and (= "eca" server)
                               (= "ask_user" name)
                               (= :allow approval))
                        :ask
                        approval)
        state* (atom {:hook-results []
                      :approval-override nil
                      :tool-call-rejected-by-hook? false
                      :tool-call-rejection-reason nil
                      :stop-turn? false
                      :stop-reason nil
                      :stop-hook-name nil})
        _ (trigger-if-matches!
           :preToolCall
           (merge (chat-hook-data db {:chat-id chat-id
                                      :agent agent
                                      :full-model model
                                      :variant variant})
                  {:tool-name name
                   :server server
                   :tool-input arguments
                   :approval hook-approval
                   :tool-call-id tool-call-id})
           {:on-before-action (partial notify-before-hook-action! chat-ctx)
            :on-after-action (fn [result]
                               (notify-after-hook-action! chat-ctx (assoc result :tool-call-id tool-call-id))
                               (swap! state* process-pre-tool-call-hook-result result))
            :native-tools (filter #(= :native (:origin %)) all-tools)}
           db
           config)
        {:keys [hook-results approval-override tool-call-rejected-by-hook?
                tool-call-rejection-reason stop-turn? stop-reason stop-hook-name]} @state*
        updated-inputs (keep (fn [{:keys [parsed exit]}]
                               (let [updated (get parsed "updatedInput")]
                                 (when (and (zero? exit) (map? updated))
                                   updated)))
                             hook-results)]
    (cond-> {}
      (seq updated-inputs) (assoc :arguments (reduce merge arguments updated-inputs))
      (and (= "ask" approval-override) (= :allow approval)) (assoc :approval :ask)
      tool-call-rejected-by-hook? (assoc :block tool-call-rejection-reason)
      stop-turn? (assoc :stop (or stop-reason "") :by (hook-label stop-hook-name)))))

;;; postToolCall

(defn ^:private fold-post-tool-call-result
  "Folds a postToolCall hook's result into what the model gets of the tool's:
   `{:replacement :contexts :stop}`. An exit 2 hides the result (its stderr, or
   a placeholder, replaces it); a successful hook's replacedOutput replaces it
   and its additionalContext follows. A replacement drops the contexts before
   it."
  [acc {:keys [parsed raw-error name exit]}]
  (cond
    (= hook-rejection-exit-code exit)
    (assoc acc :replacement (or raw-error "[Tool output hidden by hook]") :contexts [])

    (zero? exit)
    (let [replaced-output (get parsed "replacedOutput")
          context (shared/not-blank (get parsed "additionalContext"))]
      (when (and (some? replaced-output) (not (string? replaced-output)))
        (logger/warn logger-tag "Ignoring non-string replacedOutput from postToolCall hook"
                     {:hook-name name :value replaced-output}))
      (cond-> acc
        (and (false? (get parsed "continue" true)) (not (:stop acc)))
        (assoc :stop {:name name :reason (shared/not-blank (get parsed "stopReason"))})

        (string? replaced-output) (assoc :replacement replaced-output :contexts [])
        context (update :contexts conj context)))

    :else acc))

(defn ^:private post-tool-call
  "`:tool/called` handler: the postToolCall hooks, once the client got the
   result, only on success unless runOnError. What they change is only for the
   model (see `fold-post-tool-call-result`); a successful continue:false ends
   the turn after this batch of tool calls."
  [{:keys [db* config chat-id agent] :as chat-ctx} {:keys [tool server arguments result tool-call-id]}]
  (let [db @db*
        all-tools (f.tools/all-tools chat-id agent db config)
        {:keys [name]} (f.tools/resolve-tool tool all-tools)
        state* (atom {:replacement nil :contexts [] :stop nil})]
    (trigger-if-matches!
     :postToolCall
     (merge (chat-hook-data db chat-ctx)
            {:tool-name name
             :server server
             :tool-input arguments
             :tool-response (tools.util/contents->text (:contents result))
             :error (:error result)
             :tool-call-id tool-call-id})
     {:on-before-action (partial notify-before-hook-action! chat-ctx)
      :on-after-action (fn [hook-result]
                         ;; tagged with the tool-call-id for the client to correlate
                         (notify-after-hook-action! chat-ctx (assoc hook-result :tool-call-id tool-call-id))
                         (swap! state* fold-post-tool-call-result hook-result))
      :native-tools (filter #(= :native (:origin %)) all-tools)}
     db
     config)
    (let [{:keys [replacement contexts stop]} @state*]
      (cond-> {}
        replacement (assoc :result replacement)
        (seq contexts) (assoc :additional-context (mapv (fn [text] {:text text :label? false}) contexts))
        stop (assoc :stop (or (:reason stop) "") :by (hook-label (:name stop)))))))

;;; preCompact and postCompact

(defn ^:private compact-hook-trigger
  "A compaction event's trigger as the hooks get it: \"manual\" or \"auto\"."
  [trigger]
  (if (= :manual trigger) "manual" "auto"))

(defn ^:private compact-hook-data
  [db chat-ctx trigger extra]
  (merge (chat-hook-data db chat-ctx)
         {:triggered trigger}
         extra))

(defn ^:private compact-block-reason
  "Extract the user-facing stopReason from a continue:false hook result, or nil.
   Exit 2 never produces a user-facing reason here."
  [{:keys [parsed exit]}]
  (when (zero? exit)
    (shared/not-blank (get parsed "stopReason"))))

(defn ^:private process-pre-compact-hook-result
  "Pure function: fold a single hook result into accumulated state.

   acc is {:blocked? false, :reason nil, :hook-name nil, :stop-turn? false}"
  [acc result]
  (let [parsed (:parsed result)
        success? (zero? (:exit result))
        exit-code-2? (= hook-rejection-exit-code (:exit result))
        stop-turn-result? (and success? (false? (get parsed "continue" true)))]
    (cond
      ;; continue:false blocks compaction and stops the turn (with optional stopReason).
      stop-turn-result?
      (merge acc {:blocked? true
                  :reason (compact-block-reason result)
                  :hook-name (:name result)
                  :stop-turn? true})

      ;; Exit 2 blocks only compaction (no user-facing reason from stderr), but we
      ;; still remember the first blocking hook's name for provenance in the
      ;; user-facing "Compaction blocked by hook '<name>'." message.
      exit-code-2?
      (cond-> (assoc acc :blocked? true)
        (nil? (:hook-name acc)) (assoc :hook-name (:name result)))

      :else acc)))

(defn ^:private run-pre-compact-hooks!
  "Run preCompact hooks. Returns {:blocked? boolean :reason string-or-nil :stop-turn? boolean}.
   Hooks match on triggered: \"manual\" or \"auto\". Compaction is blocked when
   a hook returns {\"continue\":false} or exits with code 2; only continue:false
   also requests stopping the current turn. :reason is only set for continue:false
   (from stopReason); exit 2 does not produce a user-facing reason."
  [{:keys [db* config] :as chat-ctx} trigger custom-instructions]
  (let [hook-state* (atom {:blocked? false
                           :reason nil
                           :hook-name nil
                           :stop-turn? false})
        db @db*]
    (trigger-if-matches!
     :preCompact
     (compact-hook-data db chat-ctx trigger
                        {:custom-instructions (or custom-instructions "")})
     (ui-options chat-ctx #(swap! hook-state* process-pre-compact-hook-result %))
     db
     config)
    (let [{:keys [blocked? reason hook-name stop-turn?]} @hook-state*]
      {:blocked? blocked?
       :reason reason
       :hook-name hook-name
       :stop-turn? stop-turn?})))

(defn ^:private pre-compact
  "`:chat/before-compact` handler: the preCompact hooks. A successful
   continue:false skips the compaction and stops the turn; an exit 2 only
   skips the compaction."
  [chat-ctx {:keys [trigger instructions]}]
  (let [{:keys [blocked? hook-name stop-turn? reason]}
        (run-pre-compact-hooks! chat-ctx (compact-hook-trigger trigger) instructions)]
    (cond
      stop-turn? {:stop (or reason "") :by (hook-label hook-name)}
      blocked? {:block true :by (hook-label hook-name)})))

(defn ^:private run-post-compact-hooks!
  "Run postCompact hooks after ECA has added the compact marker and compact
   summary message. Returns {:stop-turn? boolean :stop-reason :stop-hook-name}
   and, when successful hooks gave additionalContext, `:additional-contexts`,
   `{:hook-name :content}` each, for the summary."
  [{:keys [db* config] :as chat-ctx} trigger compact-summary]
  (let [db @db*
        hook-state* (atom {:stop-turn? false
                           :stop-reason nil
                           :stop-hook-name nil
                           :additional-contexts []})]
    (trigger-if-matches!
     :postCompact
     (compact-hook-data db chat-ctx trigger
                        {:compact-summary (or compact-summary "")})
     (ui-options
      chat-ctx
      (fn [{:keys [parsed exit name] :as _result}]
        (when (zero? exit)
          (when (false? (get parsed "continue" true))
            (swap! hook-state* assoc
                   :stop-turn? true
                   :stop-reason (shared/not-blank (get parsed "stopReason"))
                   :stop-hook-name name))
          (when-let [additional-context (shared/not-blank (get parsed "additionalContext"))]
            (swap! hook-state* update :additional-contexts conj
                   {:hook-name name :content additional-context})))))
     db
     config)
    (let [{:keys [stop-turn? stop-reason stop-hook-name additional-contexts]} @hook-state*]
      (cond-> {:stop-turn? stop-turn?
               :stop-reason stop-reason
               :stop-hook-name stop-hook-name}
        (seq additional-contexts) (assoc :additional-contexts additional-contexts)))))

(defn ^:private post-compact
  "`:chat/after-compact` handler: the postCompact hooks, with the summary.
   Their additionalContexts follow it as plain blocks, after the modules'
   ones; a successful continue:false stops the turn."
  [chat-ctx {:keys [trigger summary]}]
  (let [{:keys [stop-turn? stop-reason stop-hook-name additional-contexts]}
        (run-post-compact-hooks! chat-ctx (compact-hook-trigger trigger) summary)]
    (cond-> {}
      (seq additional-contexts) (assoc :additional-context
                                       (mapv (fn [{:keys [content]}] {:text content :label? false})
                                             additional-contexts))
      stop-turn? (assoc :stop (or stop-reason "") :by (hook-label stop-hook-name)))))

;;; /hooks

(def ^:private hook-type-order
  "Display order for hook types in /hooks output."
  ["sessionStart" "sessionEnd" "chatStart" "chatEnd" "chatStatusChanged" "subagentStart"
   "preRequest" "postRequest" "subagentPostRequest" "preToolCall" "postToolCall"
   "preCompact" "postCompact"])

(defn ^:private hooks-msg [config]
  (let [hooks-map (:hooks config)
        hooks-by-type (->> hooks-map
                           (keep (fn [[hook-name hook]]
                                   (when (seq (:actions hook))
                                     {:name (name hook-name)
                                      :type (:type hook)
                                      :description (:description hook)
                                      :visible (:visible hook true)
                                      :matcher (:matcher hook)})))
                           (group-by :type))
        known (set hook-type-order)
        display-order (concat hook-type-order (sort (remove known (keys hooks-by-type))))]
    (if (empty? hooks-by-type)
      "No hooks configured. Add hooks to your config under the `hooks` key."
      (reduce
       (fn [s hook-type]
         (if-let [hooks (seq (get hooks-by-type hook-type))]
           (str s "**" hook-type "**\n"
                (reduce
                 (fn [s2 {:keys [name description visible matcher]}]
                   (str s2 "- `" name "`"
                        (when description (str " — " description))
                        (when-not visible " *(hidden)*")
                        (when (some? matcher)
                          (str " matcher: `" (if (string? matcher) matcher (json/generate-string matcher)) "`"))
                        "\n"))
                 ""
                 (sort-by :name hooks))
                "\n")
           s))
       "Hooks active:\n\n"
       display-order))))

(def ^:private last-priority
  "The hooks' handlers run after the other modules' ones (4.17)."
  -1000)

(def module
  {:id :eca/hooks
   :description "The user's shell hooks."
   :config-spec {:hooks {:type :map :default {}}}
   ;; 13 hook types: the subagent ones share their chat event
   :event-handlers {:session/started {:priority last-priority :handler #'session-start}
                    :session/ended {:priority last-priority :handler #'session-end}
                    :chat/started {:priority last-priority :handler #'chat-start}
                    :chat/ended {:priority last-priority :handler #'chat-end}
                    :chat/status-changed {:priority last-priority :handler #'chat-status-changed}
                    :chat/before-request {:priority last-priority :handler #'pre-request :compaction? true}
                    :chat/after-request {:priority last-priority :handler #'post-request}
                    :tool/before-call {:priority last-priority :handler #'pre-tool-call}
                    :tool/called {:priority last-priority :handler #'post-tool-call}
                    :chat/before-compact {:priority last-priority :handler #'pre-compact}
                    :chat/after-compact {:priority last-priority :handler #'post-compact}}
   :commands {"hooks" {:description "List active hooks grouped by type."
                       :arguments []
                       :handler (fn [{:keys [config]} _args] (hooks-msg config))}}})
