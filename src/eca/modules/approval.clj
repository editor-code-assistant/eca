(ns eca.modules.approval
  "The `:eca/approval` module: each tool call's approval from the
   `toolCall.approval` rules, the chat's remembered approvals and trust mode.
   Its `:tool/before-call` handler runs first, so other modules see the
   approval and may change it; a second one runs after them and keeps a deny
   from the rules, for the model's arguments and the ones the modules left.
   It answers `:tool/approval` with the rules too: ECA hides the tools and
   skills they deny."
  (:require
   [clojure.string :as string]
   [eca.db :as db]
   [eca.features.tools :as f.tools]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.shared :refer [multi-str]]))

(set! *warn-on-reflection* true)

;; The rules

(defn ^:private legacy-manual-approval? [config tool-name]
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

(defn ^:private approval-decision
  "Full approval decision for the specific tool call:
   {:decision (:ask, :allow, :deny or :trust/allow)
    :rule     <map with the :source of the decision and, for config rules,
               the matched :selector and :args-matcher>}.
   Sources are checked in precedence order:
   1. :config-deny             - config `deny` rules
   2. :session-remember        - the chat session 'approve and remember' approvals
   3. :tool-built-in-check     - the tool's own check (e.g. path outside workspace)
   4. :config-ask              - config `ask` rules
   5. :config-allow            - config `allow` rules
   6. :legacy-manual-approval  - deprecated `toolCall.manualApproval`
   7. :by-default              - config `byDefault`
   8. :fallback                - probably a config error, default to ask
   Agent-name parameter is required - pass nil for global-only approval rules.
   Optional opts map supports :trust - when true, promotes :ask to :trust/allow
   (never overrides :deny) - and :chat-id, whose chat session remembered
   approvals are considered (none without it). Callers should normalize
   :trust/allow to :allow."
  ([all-tools tool args db config agent-name]
   (approval-decision all-tools tool args db config agent-name nil))
  ([all-tools tool args db config agent-name {:keys [trust chat-id]}]
   (let [{:keys [server name require-approval-fn approval-keys-fn]} tool
         remembered (get (db/remembered-approvals db chat-id) (str (:name server) "__" name))
         remembered? (if approval-keys-fn
                       ;; Granular remember: every derived key (e.g. "git checkout")
                       ;; must have been remembered, and the command must be fully
                       ;; understood (:complete?), otherwise still ask.
                       (let [{keys' :keys complete? :complete?} (approval-keys-fn args)
                             remembered-keys (:remembered-command-keys remembered #{})]
                         (boolean (and complete?
                                       (seq keys')
                                       (every? remembered-keys keys'))))
                       (boolean (:remember-to-approve? remembered)))
         native-tools (filter #(= :native (:origin %)) all-tools)
         {:keys [allow ask deny byDefault]}   (merge (get-in config [:toolCall :approval])
                                                     (get-in config [:agent agent-name :toolCall :approval]))
         ;; unlike the rules, an agent's folders add to the global ones
         inside-workspace (tools.util/inside-workspace-folders
                           (concat (get-in config [:toolCall :approval :insideWorkspace])
                                   (get-in config [:agent agent-name :toolCall :approval :insideWorkspace])))
         match-rule (fn [rules source]
                      (when-let [match (some #(approval-match % (:name server) name args native-tools) rules)]
                        (assoc match :source source)))
         {:keys [decision rule]}
         (or (when-let [rule (match-rule deny :config-deny)]
               {:decision :deny :rule rule})

             (when remembered?
               {:decision :allow :rule {:source :session-remember}})

             (when (and require-approval-fn
                        (require-approval-fn args {:db db :inside-workspace inside-workspace}))
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

(defn ^:private approval
  "Return the approval keyword for the specific tool call: :ask, :allow or :deny.
   See `approval-decision` for the full decision including which rule matched,
   the precedence order and the supported opts."
  ([all-tools tool args db config agent-name]
   (approval all-tools tool args db config agent-name nil))
  ([all-tools tool args db config agent-name opts]
   (:decision (approval-decision all-tools tool args db config agent-name opts))))

;; Tool calls

(def ^:private config-deny-reason
  {:code :user-config-deny :text "Tool call rejected by user config"})

(defn ^:private approval-from-config
  "`:tool/before-call` handler of `:eca/approval`: the approval of the call by
   the `toolCall.approval` rules, the chat's remembered approvals and trust,
   with the `:reason` for it."
  [{:keys [all-tools db config agent trust chat-id]} {:keys [tool arguments]}]
  (let [tool-def (f.tools/resolve-tool tool all-tools)
        opts {:trust trust :chat-id chat-id}
        ;; Which rule caused the config's decision, to help debugging approval configs, as
        ;; docs/config/tools.md shows it. #536
        _ (logger/debug "[CHAT]" "Tool call approval decision"
                        (assoc (approval-decision all-tools tool-def arguments db config agent opts)
                               :tool tool :agent agent))
        decision (approval all-tools tool-def arguments db config agent opts)
        reason (case decision
                 :trust/allow {:code :trust-allow :text "Tool call allowed by trust mode"}
                 :allow {:code :user-config-allow :text "Tool call allowed by user config"}
                 :deny config-deny-reason
                 nil)]
    ;; no :reason key for :ask: a nil one reads as a handler's value of the wrong type
    (cond-> {:approval (if (= :trust/allow decision) :allow decision)}
      reason (assoc :reason reason))))

(defn ^:private keep-config-deny
  "`:tool/before-call` handler of `:eca/approval` after the other modules': a
   call the rules deny stays denied whatever a module answered, also for the
   arguments a module changed them to. The rules decide again only when a
   module may have changed the outcome: it changed the arguments, or decided
   with its own reason. The shell hooks run after it."
  [{:keys [all-tools db config agent trust chat-id requested-arguments]}
   {:keys [tool arguments reason] current-approval :approval}]
  (when (not= :deny current-approval)
    (let [rules (fn [args]
                  (approval all-tools (f.tools/resolve-tool tool all-tools) args db config agent
                            {:trust trust :chat-id chat-id}))
          code (:code reason)
          arguments-changed? (not= arguments requested-arguments)]
      (when (or
             ;; the rules denied the call, and a module answered without its own reason
             (= :user-config-deny code)
             ;; a module changed the arguments, before or after the first handler, or
             ;; decided with its own reason: what do the rules say of the model's?
             (and (or arguments-changed? (not (contains? #{nil :user-config-allow :trust-allow} code)))
                  (= :deny (rules requested-arguments)))
             ;; and of the ones the modules left
             (and arguments-changed?
                  (= :deny (rules arguments))))
        {:approval :deny :reason config-deny-reason}))))

(defn ^:private tool-approval
  "`:tool/approval` handler: the rules' approval of a call outside of a chat,
   without its remembered approvals or trust mode: `:allow`, `:ask` or
   `:deny`. A tool not in the ctx's tools is taken by its names."
  [{:keys [all-tools db config agent]} {:keys [tool server arguments]}]
  (let [tool-def (or (f.tools/resolve-tool tool all-tools)
                     {:name (string/replace-first tool (str server "__") "") :server {:name server}})]
    {:approval (approval all-tools tool-def arguments db config agent)}))

(def ^:private no-remembered-approvals-msg
  "No tool call approvals remembered in this chat.")

(defn ^:private remembered-approvals-lines
  "One markdown line per remembered tool, listing its remembered command keys
   for granular tools (e.g. shell commands)."
  [approvals]
  (for [[full-name {:keys [remembered-command-keys]}] (sort-by key approvals)]
    (str "- `" full-name "`: "
         (if (seq remembered-command-keys)
           (string/join ", " (map #(str "`" % "`") (sort remembered-command-keys)))
           "all calls"))))

(defn ^:private approvals-command [{:keys [db* chat-id]} _args]
  (let [approvals (db/remembered-approvals @db* chat-id)]
    (if (empty? approvals)
      no-remembered-approvals-msg
      (multi-str "Tool call approvals remembered in this chat:"
                 ""
                 (remembered-approvals-lines approvals)
                 ""
                 "Run `/forget-approvals` to forget them."))))

(defn ^:private forget-approvals-command [{:keys [db* chat-id]} _args]
  (let [[old-db] (swap-vals! db* db/update-remembered-approvals chat-id (constantly nil))
        approvals (db/remembered-approvals old-db chat-id)]
    (if (empty? approvals)
      no-remembered-approvals-msg
      (multi-str "Forgot the tool call approvals remembered in this chat:"
                 ""
                 (remembered-approvals-lines approvals)))))

(def module
  {:id :eca/approval
   :description "Tool approval from the toolCall.approval rules."
   ;; first, so other modules see and may change the approval, then again after
   ;; them and before the shell hooks (-1000), so a deny from the rules stays (4.17)
   :event-handlers {:tool/before-call [{:priority 1000 :handler #'approval-from-config}
                                       {:priority -900 :handler #'keep-config-deny}]
                    :tool/approval #'tool-approval}
   ;; the tool modules add their allow entries
   :config-spec {:toolCall {:approval {:type :map
                                       :default {:byDefault "ask" :allow {} :ask {} :deny {}}}}}
   :commands {"approvals" {:description "List tool call approvals remembered in the current chat via approve & remember."
                           :arguments []
                           :handler #'approvals-command}
              "forget-approvals" {:description (str "Forget tool call approvals remembered in the current chat, "
                                                    "so those calls ask for approval again.")
                                  :arguments []
                                  :handler #'forget-approvals-command}}})
