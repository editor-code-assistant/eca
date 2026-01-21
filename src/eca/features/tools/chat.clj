(ns eca.features.tools.chat
  (:require
   [eca.features.tools.util :as tools.util]))

(set! *warn-on-reflection* true)

(defn ^:private compact-chat [arguments {:keys [db* chat-id]}]
  (let [summary (get arguments "summary")]
    ;; Mark chat as not compacting anymore
    (swap! db* assoc-in [:chats chat-id :compacting?] false)

    ;; Save summary to replace chat history later
    (swap! db* assoc-in [:chats chat-id :last-summary] summary)

    (tools.util/single-text-content "Compacted successfully!")))

(def definitions
  {"compact_chat"
   {:description "Compact / summarize a chat, cleaning chat history, emptying usage and presenting the summary to user"
    :parameters {:type "object"
                 :properties {"summary" {:type "string"
                                         :description "The summary/compacted text"}}
                 :required ["summary"]}
    :handler #'compact-chat
    :enabled-fn (fn [{:keys [db chat-id]}]
                  (or (get-in db [:chats chat-id :compacting?] false)
                      (get-in db [:chats chat-id :auto-compacting?] false)))
    :summary-fn (constantly "Compacting...")}})
