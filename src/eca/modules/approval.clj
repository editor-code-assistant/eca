(ns eca.modules.approval
  "The `:eca/approval` module: each tool call's approval from the
   `toolCall.approval` rules, the chat's remembered approvals and trust mode.
   Its `:tool/before-call` handler runs first, so other modules see the
   approval and may change it. The rules are evaluated by
   `eca.features.tools/approval`, which core also uses to keep a config deny."
  (:require
   [eca.features.built-in-command :as built-in-command]
   [eca.features.tools :as f.tools]))

(set! *warn-on-reflection* true)

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
                 :deny {:code :user-config-deny :text "Tool call rejected by user config"}
                 nil)]
    ;; no :reason key for :ask: a nil one reads as a handler's value of the wrong type
    (cond-> {:approval (if (= :trust/allow decision) :allow decision)}
      reason (assoc :reason reason))))

(def module
  {:id :eca/approval
   :description "Tool approval from the toolCall.approval rules."
   ;; first, so other modules see and may change the approval (4.17)
   :event-handlers {:tool/before-call {:priority 1000 :handler #'approval-from-config}}
   ;; the tool modules add their allow entries
   :config-spec {:toolCall {:approval {:type :map
                                       :default {:byDefault "ask" :allow {} :ask {} :deny {}}}}}
   :commands (built-in-command/commands
              [["approvals" "List tool call approvals remembered in the current chat via approve & remember." []]
               ["forget-approvals" (str "Forget tool call approvals remembered in the current chat, so those calls "
                                        "ask for approval again.")
                []]])})
