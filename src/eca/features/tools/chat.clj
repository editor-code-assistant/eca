(ns eca.features.tools.chat
  (:require
   [eca.features.tools.util :as tools.util]))

(set! *warn-on-reflection* true)

(defn ^:private compact-chat [arguments {:keys [db* chat-id]}]
  (let [chat (get-in @db* [:chats chat-id])]
    (if (or (:compacting? chat) (:auto-compacting? chat))
      (do
        (swap! db* update-in [:chats chat-id]
               assoc
               :compacting? false
               :last-summary (get arguments "summary")
               :compact-done? true)
        (tools.util/single-text-content "Compacted successfully!"))
      (tools.util/single-text-content
       "Chat compaction is not active for this request. This tool is available only while chat compaction is in progress. To compact manually, the user must use the `/compact` command; compaction may also start automatically when context usage reaches the configured threshold."
       :error))))

(def definitions
  {"compact_chat"
   {:description "During chat compaction, submit a summary that will become the active conversation context"
    :parameters {:type "object"
                 :properties {"summary" {:type "string"
                                         :description "The summary/compacted text"}}
                 :required ["summary"]}
    :handler #'compact-chat
    :summary-fn (constantly "Compacting...")}})
