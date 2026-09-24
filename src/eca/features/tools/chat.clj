(ns eca.features.tools.chat
  (:require
   [clojure.string :as string]
   [eca.features.chat.title :as chat.title]
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

(defn ^:private sanitized-title-arg [args]
  (let [title (get args "title")]
    (when (string? title)
      (chat.title/sanitize-title title))))

(defn ^:private rename-chat-title [arguments {:keys [db* chat-id messenger metrics]}]
  (let [title (sanitized-title-arg arguments)]
    (cond
      (string/blank? title)
      (tools.util/single-text-content
       "INVALID_ARGS: title is required and must not be blank."
       :error)

      (not (get-in @db* [:chats chat-id]))
      (tools.util/single-text-content "Chat not found." :error)

      :else
      (if-let [title (chat.title/update-chat-title! db* chat-id title messenger metrics)]
        (tools.util/single-text-content
         (format "Chat title renamed to: %s" title))
        (tools.util/single-text-content "Chat not found." :error)))))

(def definitions
  {"compact_chat"
   {:description "During chat compaction, submit a summary that will become the active conversation context"
    :parameters {:type "object"
                 :properties {"summary" {:type "string"
                                          :description "The summary/compacted text"}}
                 :required ["summary"]}
    :handler #'compact-chat
    :summary-fn (constantly "Compacting...")}

   "rename_chat_title"
   {:description "Rename the current chat title. Use this only after the user explicitly asks to rename the chat title."
    :parameters {:type "object"
                 :properties {"title" {:type "string"
                                        :description "The new title for the current chat"}}
                 :required ["title"]}
    :handler #'rename-chat-title
    :summary-fn (fn [{:keys [args]}]
                  (let [title (sanitized-title-arg args)]
                    (if (string/blank? title)
                      "Renaming chat"
                      (format "Renaming chat: %s" title))))}})
