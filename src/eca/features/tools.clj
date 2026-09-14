(ns eca.features.tools
  "This ns centralizes all available tools for LLMs including
   eca native tools and MCP servers."
  (:require
   [clojure.string :as string]
   [clojure.walk :as walk]
   [eca.features.tools.agent :as f.tools.agent]
   [eca.features.tools.background :as f.tools.background]
   [eca.features.tools.chat :as f.tools.chat]
   [eca.features.tools.custom :as f.tools.custom]
   [eca.features.tools.editor :as f.tools.editor]
   [eca.features.tools.fetch-rule :as f.tools.fetch-rule]
   [eca.features.tools.filesystem :as f.tools.filesystem]
   [eca.features.tools.git :as f.tools.git]
   [eca.features.tools.mcp :as f.mcp]
   [eca.features.tools.mcp.clojure-mcp]
   [eca.features.tools.shell :as f.tools.shell]
   [eca.features.tools.skill :as f.tools.skill]
   [eca.features.tools.task :as f.tools.task]
   [eca.features.tools.tool-search :as f.tools.tool-search]
   [eca.features.tools.ask-user :as f.tools.ask-user]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.metrics :as metrics]
   [eca.shared :refer [assoc-some] :as shared])
  (:import
   [java.util Map]
   [java.util.regex PatternSyntaxException]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[TOOLS]")

(defn legacy-manual-approval? [config tool-name]
  (let [manual-approval? (get-in config [:toolCall :manualApproval] nil)]
    (if (coll? manual-approval?)
      (some #(= tool-name (str %)) manual-approval?)
      manual-approval?)))

(defn ^:private approval-match
  "When the approval rule entry matches this tool call, returns what matched:
   {:selector <config selector> :args-matcher <regex str>} (:args-matcher only
   when the rule has argsMatchers). Returns nil when the rule doesn't match."
  [[server-or-full-tool-name config] tool-call-server tool-call-name args native-tools]
  (when (tools.util/tool-selector-matches? server-or-full-tool-name tool-call-server tool-call-name native-tools)
    (let [args-matchers (:argsMatchers config)]
      (if (map? args-matchers)
        (when-let [matcher (some (fn [[arg-name matchers]]
                                   (when-let [arg (get args arg-name)]
                                     (some #(when (re-matches (re-pattern (str %)) (str arg))
                                              (str %))
                                           matchers)))
                                 args-matchers)]
          {:selector server-or-full-tool-name
           :args-matcher matcher})
        {:selector server-or-full-tool-name}))))

(defn approval-decision
  "Full approval decision for the specific tool call:
   {:decision (:ask, :allow, :deny or :trust/allow)
    :rule     <map with the :source of the decision and, for config rules,
               the matched :selector and :args-matcher>}.
   Sources are checked in precedence order:
   1. :config-deny             - config `deny` rules
   2. :session-remember        - session 'approve and remember' approvals
   3. :tool-built-in-check     - the tool's own check (e.g. path outside workspace)
   4. :config-ask              - config `ask` rules
   5. :config-allow            - config `allow` rules
   6. :legacy-manual-approval  - deprecated `toolCall.manualApproval`
   7. :by-default              - config `byDefault`
   8. :fallback                - probably a config error, default to ask
   Agent-name parameter is required - pass nil for global-only approval rules.
   Optional opts map supports :trust - when true, promotes :ask to :trust/allow
   (never overrides :deny). Callers should normalize :trust/allow to :allow."
  ([all-tools tool args db config agent-name]
   (approval-decision all-tools tool args db config agent-name nil))
  ([all-tools tool args db config agent-name {:keys [trust]}]
   (let [{:keys [server name require-approval-fn approval-keys-fn]} tool
         remembered? (if approval-keys-fn
                       ;; Granular remember: every derived key (e.g. "git checkout")
                       ;; must have been remembered, and the command must be fully
                       ;; understood (:complete?), otherwise still ask.
                       (let [{keys' :keys complete? :complete?} (approval-keys-fn args)
                             remembered-keys (get-in db [:tool-calls name :remembered-command-keys] #{})]
                         (boolean (and complete?
                                       (seq keys')
                                       (every? remembered-keys keys'))))
                       (boolean (get-in db [:tool-calls name :remember-to-approve?])))
         native-tools (filter #(= :native (:origin %)) all-tools)
         {:keys [allow ask deny byDefault]}   (merge (get-in config [:toolCall :approval])
                                                     (get-in config [:agent agent-name :toolCall :approval]))
         match-rule (fn [rules source]
                      (when-let [match (some #(approval-match % (:name server) name args native-tools) rules)]
                        (assoc match :source source)))
         {:keys [decision rule]}
         (or (when-let [rule (match-rule deny :config-deny)]
               {:decision :deny :rule rule})

             (when remembered?
               {:decision :allow :rule {:source :session-remember}})

             (when (and require-approval-fn (require-approval-fn args {:db db}))
               {:decision :ask :rule {:source :tool-built-in-check}})

             (when-let [rule (match-rule ask :config-ask)]
               {:decision :ask :rule rule})

             (when-let [rule (match-rule allow :config-allow)]
               {:decision :allow :rule rule})

             (when (legacy-manual-approval? config name)
               {:decision :ask :rule {:source :legacy-manual-approval}})

             (case byDefault
               "ask" {:decision :ask :rule {:source :by-default}}
               "allow" {:decision :allow :rule {:source :by-default}}
               "deny" {:decision :deny :rule {:source :by-default}}
               nil)

             ;; Probably a config error, default to ask
             {:decision :ask :rule {:source :fallback}})]
     (if (and trust (= decision :ask))
       {:decision :trust/allow :rule rule}
       {:decision decision :rule rule}))))

(defn approval
  "Return the approval keyword for the specific tool call: :ask, :allow or :deny.
   See `approval-decision` for the full decision including which rule matched,
   the precedence order and the supported opts."
  ([all-tools tool args db config agent-name]
   (approval all-tools tool args db config agent-name nil))
  ([all-tools tool args db config agent-name opts]
   (:decision (approval-decision all-tools tool args db config agent-name opts))))

(defn ^:private get-tool-patterns
  "Returns a set of tool patterns at `config-path`, merging global and agent-specific."
  [config agent-name config-path]
  (set (concat (get-in config config-path [])
               (if agent-name
                 (get-in config (into [:agent agent-name] config-path) [])
                 []))))

(defn ^:private get-disabled-tools
  "Returns a set of disabled tools, merging global and agent-specific."
  [config agent-name]
  (get-tool-patterns config agent-name [:disabledTools]))

(def ^:private compile-tool-pattern
  "Anchored regex for a tool pattern entry, or nil when it is not a valid regex
   (the entry is then matched literally). Memoized: every entry is tested against
   every tool on every turn, and it keeps the warning to once per bad entry."
  (memoize
   (fn [entry]
     (try (re-pattern entry)
          (catch PatternSyntaxException _
            (logger/warn logger-tag
                         (format "Tool pattern '%s' is not a valid regex, matching it literally instead. Use '.*' for a wildcard."
                                 entry))
            nil)))))

(defn tool-entry-matches?
  "Matches a tool pattern entry (`disabledTools`, `mcpToolSearch`) against a
   tool, checking in order:
   1. Entry as anchored regex against a eca builtin tool name (no `eca__` prefix needed).
   2. Entry as exact server name, matching all tools of that server.
   3. Entry as anchored regex against the tool full name `server__tool`.
   Invalid regexes fall back to literal equality."
  [entry tool]
  (let [entry (str entry)
        server-name (:name (:server tool))
        tool-name (:name tool)
        full-name (str server-name "__" tool-name)
        pattern (compile-tool-pattern entry)]
    (boolean
     (or (and (= "eca" server-name)
              (if pattern
                (re-matches pattern tool-name)
                (= entry tool-name)))
         (= entry server-name)
         (if pattern
           (re-matches pattern full-name)
           (= entry full-name))))))

(defn ^:private tool-matches-any? [tool entries]
  (boolean (some #(tool-entry-matches? % tool) entries)))

(defn ^:private tool-disabled? [tool disabled-tools]
  (tool-matches-any? tool disabled-tools))

(defn make-tool-status-fn
  "Returns a function that marks tools as disabled based on config and agent.
   If agent-name is nil, only uses global disabledTools."
  [config agent-name]
  (let [disabled-tools (get-disabled-tools config agent-name)]
    (fn [tool]
      (assoc-some tool :disabled (tool-disabled? tool disabled-tools)))))

(defn ^:private replace-string-values-with-vars
  "walk through config parsing dynamic string contents if value is a string."
  [m vars]
  (walk/postwalk
   (fn [x]
     (if (string? x)
       (shared/safe-selmer-render x vars "tool-config")
       x))
   m))

(defn ^:private static-native-definitions
  "Native tool definitions that don't depend on chat/db/config."
  []
  (merge {}
         f.tools.filesystem/definitions
         f.tools.shell/definitions
         f.tools.git/definitions
         f.tools.editor/definitions
         f.tools.chat/definitions
         f.tools.skill/definitions
         f.tools.task/definitions
         f.tools.background/definitions
         f.tools.tool-search/definitions
         f.tools.ask-user/definitions))

(defn tool-approval-keys
  "Returns {:keys #{...} :complete? bool} when the native tool `tool-name`
   defines granular approval keys for these arguments, else nil."
  [tool-name arguments]
  (when-let [approval-keys-fn (get-in (static-native-definitions) [tool-name :approval-keys-fn])]
    (approval-keys-fn arguments)))

(defn ^:private native-definitions
  [chat-id agent-name db config]
  (into
   {}
   (map (fn [[name tool]]
          [name (-> tool
                    (assoc :name name)
                    (replace-string-values-with-vars
                     {:workspaceRoots   (tools.util/workspace-roots-strs db)
                      :readFileMaxLines (get-in config [:toolCall :readFile :maxLines])
                      :fullModel        (get-in db [:chats chat-id :model])
                      :variant          (get-in db [:chats chat-id :variant])}))]))
   (merge (static-native-definitions)
          (f.tools.agent/definitions config db agent-name)
          (f.tools.custom/definitions config)
          (f.tools.fetch-rule/definitions config db chat-id agent-name))))

(defn native-tools
  ([db config]
   (native-tools nil nil db config))
  ([chat-id agent-name db config]
   (mapv #(assoc % :server {:name "eca"})
         (vals (native-definitions chat-id agent-name db config)))))

(defn ^:private filter-subagent-tools
  "Filter tools for subagent execution.

   - Excludes spawn_agent to prevent nesting.
   - Excludes task because task list state is currently chat-local; it should be managed by the parent agent.
   - Excludes git because subagents don't perform git operations.
   - Excludes ask_user because subagents run non-interactively and cannot prompt the user."
  [tools]
  (filterv #(not (contains? #{"spawn_agent" "task" "git" "ask_user"} (:name %))) tools))

(defn ^:private get-defer-all-percent
  "Percentage of the model context window the MCP tool definitions may take
   before all of them are deferred without any pattern. The agent value overrides
   the global one; nil (the default) leaves them unlimited."
  [config agent-name]
  (let [agent-config (get-in config [:agent agent-name :mcpToolSearch])]
    (if (contains? agent-config :deferAllWhenTotalTokensExceedPercentOfContext)
      (:deferAllWhenTotalTokensExceedPercentOfContext agent-config)
      (get-in config [:mcpToolSearch :deferAllWhenTotalTokensExceedPercentOfContext]))))

(defn ^:private tools-tokens
  "Rough token cost of the tool definitions sent on every request, measured over
   the same wire fields `shared/context-breakdown` reports as Tool definitions."
  [tools]
  (shared/estimate-tokens
   (pr-str (mapv #(select-keys % [:name :description :parameters]) tools))))

(defn ^:private defer-all-by-total-tokens?
  "True when the MCP tool definitions as a whole outgrow
   `deferAllWhenTotalTokensExceedPercentOfContext` of the model context window.
   False without a percent or a known context window, so an unknown model never
   silently hides its tools."
  [mcp-tools config agent-name db full-model]
  (let [percent (get-defer-all-percent config agent-name)
        context-limit (get-in db [:models full-model :limit :context])]
    (boolean (and (number? percent)
                  (not (neg? percent))
                  (number? context-limit)
                  (pos? context-limit)
                  (seq mcp-tools)
                  (> (tools-tokens mcp-tools)
                     (* context-limit (/ (double percent) 100.0)))))))

(defn ^:private mark-deferred-tools
  "Marks MCP tools kept out of the LLM context with `:deferrable`, and with
   `:deferred` while the chat has not loaded them yet via `eca__search_tools`.
   A tool is deferred when the MCP tool definitions as a whole outgrow
   `mcpToolSearch.deferAllWhenTotalTokensExceedPercentOfContext` of the context
   window, or when it matches `includePattern`, and in both cases only if it does
   not match `excludePattern`.
   Deferred tools stay resolvable and callable; they are simply kept out of the
   tool schemas sent to the LLM.
   Native ECA tools are never deferred: they are the agent's baseline capabilities,
   and a catch-all pattern taking them away would break the agent. They are also
   left out of the token count, so the limit tracks what MCP actually adds."
  [tools {:keys [config agent-name activated db full-model]}]
  (let [include-patterns (get-tool-patterns config agent-name [:mcpToolSearch :includePattern])
        exclude-patterns (get-tool-patterns config agent-name [:mcpToolSearch :excludePattern])
        mcp-tools (filterv #(not= :native (:origin %)) tools)
        defer-all? (defer-all-by-total-tokens? mcp-tools config agent-name db full-model)]
    (if-not (or defer-all? (seq include-patterns))
      tools
      (mapv (fn [tool]
              (if (and (not= :native (:origin tool))
                       (or defer-all? (tool-matches-any? tool include-patterns))
                       (not (tool-matches-any? tool exclude-patterns)))
                (assoc tool
                       :deferrable true
                       :deferred (not (contains? activated (:full-name tool))))
                tool))
            tools))))

(defn deferrable-tools
  "Tools kept out of the LLM context until loaded with `eca__search_tools`."
  [all-tools]
  (filterv :deferrable all-tools))

(defn tools-for-llm
  "Tools whose schemas should be sent to the LLM on this turn."
  [all-tools]
  (remove :deferred all-tools))

(defn resolve-tool
  [tool-name all-tools]
  (or (some #(when (= tool-name (:full-name %)) %) all-tools)
      (when-not (string/includes? tool-name "__")
        (when-let [resolved-tool (some #(when (and (= :native (:origin %))
                                                   (= tool-name (:name %)))
                                          %) all-tools)]
          (logger/info logger-tag "Auto-resolved bare native tool name"
                       {:requested-name tool-name
                        :resolved-name (:full-name resolved-tool)})
          resolved-tool))))

(defn all-tools
  "Returns all available tools, including both native ECA tools
   (like filesystem and shell tools) and tools provided by MCP servers.
   Removes denied tools.
   When chat is a subagent (has :subagent), filters tools based on agent definition.
   `full-model` is the model the prompt runs with; it defaults to the chat's stored
   model, which is not set yet when the first prompt of a chat computes its tools,
   so prompt code passes the resolved model to keep the tool set stable across turns."
  ([chat-id agent-name db config]
   (all-tools chat-id agent-name db config nil))
  ([chat-id agent-name db config {:keys [full-model]}]
   (let [disabled-tools (get-disabled-tools config agent-name)
         subagent (get-in db [:chats chat-id :subagent])
         full-model (or full-model (get-in db [:chats chat-id :model]))
         all-tools (->> (concat
                         (mapv #(assoc % :origin :native) (native-tools chat-id agent-name db config))
                         (mapv #(assoc % :origin :mcp) (f.mcp/all-tools db)))
                        (mapv #(update % :parameters tools.util/reorder-schema-required-first))
                        (mapv #(assoc % :full-name (str (-> % :server :name) "__" (:name %))))
                        (mapv (fn [tool]
                                (update tool :description
                                        (fn [desc]
                                          (or (get-in config [:agent agent-name :prompts :tools (:full-name tool)])
                                              (get-in config [:prompts :tools (:full-name tool)])
                                              desc)))))
                        (filterv (fn [tool]
                                   (and (not (tool-disabled? tool disabled-tools))
                                        ;; check for enabled-fn if present
                                        ((or (:enabled-fn tool) (constantly true))
                                         {:agent agent-name
                                          :db db
                                          :chat-id chat-id
                                          :config config
                                          :full-model full-model})))))
         ;; Apply subagent tool filtering if applicable
         all-tools (if subagent
                     (filter-subagent-tools all-tools)
                     all-tools)
         all-tools (remove (fn [tool]
                             (= :deny (approval all-tools tool {} db config agent-name)))
                           all-tools)
         all-tools (mark-deferred-tools all-tools
                                        {:config config
                                         :agent-name agent-name
                                         :activated (tools.util/activated-deferred-tools db chat-id)
                                         :db db
                                         :full-model full-model})]
     ;; search_tools only earns its context slot when something is actually deferred.
     (if (some :deferrable all-tools)
       all-tools
       (remove #(= f.tools.tool-search/tool-full-name (:full-name %)) all-tools)))))

(defn call-tool! [^String full-name ^Map arguments chat-id tool-call-id agent-name db* config messenger metrics
                  call-state-fn         ; thunk
                  state-transition-fn   ; params: event & event-data
                  {:keys [trust]}]
  (logger/info logger-tag (format "Calling tool '%s' with args '%s'" full-name arguments))
  (let [arguments (update-keys arguments clojure.core/name)
        db @db*
        all-tools (all-tools chat-id agent-name db config)
        tool-meta (resolve-tool full-name all-tools)
        resolved-full-name (:full-name tool-meta full-name)
        server-name (get-in tool-meta [:server :name])
        tool-name (:name tool-meta)
        arguments (if-let [parameters (:parameters tool-meta)]
                    (tools.util/omit-optional-empty-string-args parameters arguments)
                    arguments)
        required-args-error (when-let [parameters (:parameters tool-meta)]
                              (tools.util/required-params-error parameters arguments))]
    (try
      (when-not tool-meta
        (throw (ex-info (format "Tool '%s' not found" full-name) {:full-name full-name
                                                                  :arguments arguments
                                                                  :all-tools (mapv :full-name all-tools)})))
      (let [result (-> (if required-args-error
                         required-args-error
                         (if-let [native-tool-handler (and (= "eca" server-name)
                                                           (get-in (native-definitions chat-id agent-name db config) [tool-name :handler]))]
                           (native-tool-handler arguments {:db db
                                                           :db* db*
                                                           :config config
                                                           :messenger messenger
                                                           :agent agent-name
                                                           :metrics metrics
                                                           :chat-id chat-id
                                                           :all-tools all-tools
                                                           :tool-call-id tool-call-id
                                                           :call-state-fn call-state-fn
                                                           :state-transition-fn state-transition-fn
                                                           :trust trust})
                           (f.mcp/call-tool! server-name tool-name arguments {:db db
                                                                              :db* db*
                                                                              :config config
                                                                              :metrics metrics})))
                       (tools.util/maybe-truncate-output config tool-call-id))]
        (logger/debug logger-tag "Tool call result: " result)
        (metrics/count-up! "tool-called" {:name resolved-full-name :error (:error result)} metrics)
        (if-let [r (:rollback-changes result)]
          (do
            (swap! db* assoc-in [:chats chat-id :tool-calls tool-call-id :rollback-changes] r)
            (dissoc result :rollback-changes))
          result))
      (catch Exception e
        (let [error-msg (or (.getMessage e) (.getName (class e)))]
          (logger/warn logger-tag (format "Error calling tool %s: %s\n%s" full-name error-msg (with-out-str (.printStackTrace e))))
          (metrics/count-up! "tool-called" {:name full-name :error true} metrics)
          {:error true
           :contents [{:type :text
                       :text (str "Error calling tool: " error-msg)}]})))))

(defn ^:private notify-server-updated [metrics messenger tool-status-fn server]
  (metrics/count-up! "mcp-server-status" {:name (:name server)
                                          :status (:status server)} metrics)
  (messenger/tool-server-updated messenger (-> server
                                               (assoc :type :mcp)
                                               (update :tools
                                                       #(mapv (comp tool-status-fn
                                                                    (fn [t] (assoc t :server {:name (:name server)})))
                                                              %)))))

(defn ^:private notify-server-removed [metrics messenger params]
  (metrics/count-up! "mcp-server-status" {:name (:name params)
                                          :status "removed"} metrics)
  (messenger/tool-server-removed messenger params))

(defn init-servers! [db* messenger config metrics]
  (let [default-agent (get config :defaultAgent)
        tool-status-fn (make-tool-status-fn config default-agent)]
    (messenger/tool-server-updated messenger {:type :native
                                              :name "ECA"
                                              :status "running"
                                              :tools (->> (native-tools @db* config)
                                                          (remove #(= "compact_chat" (:name %)))
                                                          (mapv tool-status-fn)
                                                          (mapv #(select-keys % [:name :description :parameters :disabled])))})
    (f.mcp/initialize-servers-async!
     {:on-server-updated (partial notify-server-updated metrics messenger tool-status-fn)}
     db*
     config
     metrics)))

(defn reconcile-servers!
  "Diff MCP servers between prev-config and new-config and apply add/remove/restart
   accordingly, pushing `tool/serverUpdated` and `tool/serverRemoved` notifications.
   Called by the config-polling loop when the resolved config changes."
  [prev-config new-config db* messenger metrics]
  (let [default-agent (get new-config :defaultAgent)
        tool-status-fn (make-tool-status-fn new-config default-agent)]
    (f.mcp/reconcile-servers!
     prev-config new-config db* metrics
     {:on-server-updated (partial notify-server-updated metrics messenger tool-status-fn)
      :on-server-removed (partial notify-server-removed metrics messenger)})))

(defn stop-server! [name db* messenger config metrics]
  (let [tool-status-fn (make-tool-status-fn config nil)]
    (f.mcp/stop-server!
     name
     db*
     config
     {:on-server-updated (partial notify-server-updated metrics messenger tool-status-fn)})))

(defn start-server! [name db* messenger config metrics]
  (let [tool-status-fn (make-tool-status-fn config nil)]
    (f.mcp/start-server!
     name
     db*
     config
     metrics
     {:on-server-updated (partial notify-server-updated metrics messenger tool-status-fn)})))

(defn connect-server! [name db* messenger config metrics]
  (let [tool-status-fn (make-tool-status-fn config nil)]
    (f.mcp/connect-server!
     name
     db*
     config
     metrics
     {:on-server-updated (partial notify-server-updated metrics messenger tool-status-fn)})))

(defn logout-server! [name db* messenger config metrics]
  (let [tool-status-fn (make-tool-status-fn config nil)]
    (f.mcp/logout-server!
     name
     db*
     config
     metrics
     {:on-server-updated (partial notify-server-updated metrics messenger tool-status-fn)})))

(defn update-server! [name server-fields db* messenger config metrics]
  (let [tool-status-fn (make-tool-status-fn config nil)]
    (f.mcp/update-server!
     name
     server-fields
     db*
     config
     metrics
     {:on-server-updated (partial notify-server-updated metrics messenger tool-status-fn)})))

(defn disable-server! [name db* messenger config metrics]
  (let [tool-status-fn (make-tool-status-fn config nil)]
    (f.mcp/disable-server!
     name
     db*
     config
     {:on-server-updated (partial notify-server-updated metrics messenger tool-status-fn)})))

(defn enable-server! [name db* messenger config metrics]
  (let [tool-status-fn (make-tool-status-fn config nil)]
    (f.mcp/enable-server!
     name
     db*
     metrics
     {:on-server-updated (partial notify-server-updated metrics messenger tool-status-fn)})))

(defn add-server! [name server-config opts db* messenger config metrics]
  (let [tool-status-fn (make-tool-status-fn config nil)]
    (f.mcp/add-server!
     name
     server-config
     opts
     db*
     config
     metrics
     {:on-server-updated (partial notify-server-updated metrics messenger tool-status-fn)})))

(defn remove-server! [name db* messenger config metrics]
  (let [tool-status-fn (make-tool-status-fn config nil)]
    (f.mcp/remove-server!
     name
     db*
     config
     {:on-server-updated (partial notify-server-updated metrics messenger tool-status-fn)
      :on-server-removed (partial notify-server-removed metrics messenger)})))

(defn tool-call-summary [all-tools full-name args config db]
  (when-let [summary-fn (:summary-fn (resolve-tool full-name all-tools))]
    (try
      (summary-fn {:args args
                   :config config
                   :db db})
      (catch Exception e
        (logger/error (format "Error in tool call summary fn %s: %s" name (.getMessage e)))
        nil))))

(defn tool-call-details-before-invocation
  "Return the tool call details before invoking the tool."
  [name arguments server db config chat-id ask-approval? tool-call-id]
  (try
    (tools.util/tool-call-details-before-invocation name arguments server {:db db
                                                                           :config config
                                                                           :chat-id chat-id
                                                                           :ask-approval? ask-approval?
                                                                           :tool-call-id tool-call-id})
    (catch Exception e
      ;; Avoid failling tool call because of error on getting details.
      (logger/error logger-tag (format "Error getting details for %s with args %s: %s" name arguments e))
      nil)))

(defn tool-call-details-after-invocation
  "Return the tool call details after invoking the tool."
  [name arguments details result ctx]
  (tools.util/tool-call-details-after-invocation name arguments details result ctx))

(defn tool-call-destroy-resource!
  "Destroy the resource in the tool call named `name`."
  [full-name resource-kwd resource]
  (tools.util/tool-call-destroy-resource! full-name resource-kwd resource))

(defn refresh-tool-servers!
  "Updates all tool servers (native and MCP) with new agent status."
  [tool-status-fn db* messenger config]
  (messenger/tool-server-updated messenger {:type :native
                                            :name "ECA"
                                            :status "running"
                                            :tools (->> (native-tools @db* config)
                                                        (mapv tool-status-fn)
                                                        (mapv #(select-keys % [:name :description :parameters :disabled])))})
  (doseq [[server-name {:keys [tools status]}] (:mcp-clients @db*)]
    (messenger/tool-server-updated messenger {:type :mcp
                                              :name server-name
                                              :status (name (or status :unknown))
                                              :tools (mapv tool-status-fn (or tools []))}))
  (doseq [[server-name server-config] (:mcpServers config)]
    (when (and (get server-config :disabled false)
               (not (contains? (:mcp-clients @db*) server-name)))
      (messenger/tool-server-updated messenger {:type :mcp
                                                :name server-name
                                                :status "disabled"
                                                :tools []}))))
