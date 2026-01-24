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
                      "Auto-compacted chat to:\n\n"
                      "Compacted chat to:\n\n")}})
  (messenger/chat-content-received
   messenger
   {:chat-id chat-id
    :role :assistant
    :content {:type :text
              :text (get-in @db* [:chats chat-id :last-summary])}})
  (when-let [usage (shared/usage-msg->usage {:input-tokens 0 :output-tokens 0} full-model {:chat-id chat-id :db* db*})]
    (messenger/chat-content-received
     messenger
     {:chat-id chat-id
      :role :system
      :content (merge {:type :usage}
                      usage)})))
