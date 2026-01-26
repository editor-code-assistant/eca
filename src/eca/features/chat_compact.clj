(ns eca.features.chat-compact
  (:require
   [eca.messenger :as messenger]
   [eca.shared :as shared]))

(defn compact-side-effect! [{:keys [chat-id full-model db* messenger]} auto-compact?]
  ;; Replace chat history with summary
  (swap! db* (fn [db]
               (assoc-in db [:chats chat-id :messages]
                         [{:role "user"
                           :content [{:type :text
                                      :text (str "The conversation was compacted/summarized, consider this summary:\n"
                                                 (get-in db [:chats chat-id :last-summary]))}]}])))

  ;; Zero chat usage
  (swap! db* assoc-in [:chats chat-id :last-input-tokens] nil)
  (swap! db* assoc-in [:chats chat-id :last-output-tokens] nil)
  (swap! db* assoc-in [:chats chat-id :last-input-cache-creation-tokens] nil)
  (swap! db* assoc-in [:chats chat-id :last-input-cache-read-tokens] nil)
  (swap! db* assoc-in [:chats chat-id :total-input-tokens] nil)
  (swap! db* assoc-in [:chats chat-id :total-output-tokens] nil)
  (swap! db* assoc-in [:chats chat-id :total-input-cache-creation-tokens] nil)
  (swap! db* assoc-in [:chats chat-id :total-input-cache-read-tokens] nil)
  (messenger/chat-content-received
   messenger
   {:chat-id chat-id
    :role :system
    :content {:type :text
              :text (if auto-compact?
                      "Auto-compacted chat"
                      "Compacted chat")}})
  (when-let [usage (shared/usage-sumary chat-id full-model @db*)]
    (messenger/chat-content-received
     messenger
     {:chat-id chat-id
      :role :system
      :content (merge {:type :usage}
                      usage)})))
