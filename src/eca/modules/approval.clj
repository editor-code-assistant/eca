(ns eca.modules.approval
  "The `:eca/approval` module: each tool call's approval from the
   `toolCall.approval` rules, the chat's remembered approvals and trust mode.
   Its `:tool/before-call` handler runs first, so other modules see the
   approval and may change it; a second one runs after them and keeps a deny
   from the rules, for the model's arguments and the ones the modules left.
   The rules are evaluated by `eca.features.tools/approval`."
  (:require
   [clojure.string :as string]
   [eca.db :as db]
   [eca.features.tools :as f.tools]
   [eca.shared :refer [multi-str]]))

(set! *warn-on-reflection* true)

(def ^:private config-deny-reason
  {:code :user-config-deny :text "Tool call rejected by user config"})

(defn ^:private approval-from-config
  "`:tool/before-call` handler of `:eca/approval`: the approval of the call by
   the `toolCall.approval` rules, the chat's remembered approvals and trust,
   with the `:reason` for it."
  [{:keys [all-tools db config agent trust chat-id]} {:keys [tool arguments]}]
  (let [decision (f.tools/approval all-tools (f.tools/resolve-tool tool all-tools) arguments db config agent
                                   {:trust trust :chat-id chat-id})
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
  [{:keys [all-tools db config agent trust chat-id requested-arguments]} {:keys [tool arguments approval reason]}]
  (when (not= :deny approval)
    (let [rules (fn [args]
                  (f.tools/approval all-tools (f.tools/resolve-tool tool all-tools) args db config agent
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
                                       {:priority -900 :handler #'keep-config-deny}]}
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
