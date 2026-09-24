(ns eca.features.chat.persistence
  (:require
   [eca.db :as db]))

(set! *warn-on-reflection* true)

(defonce ^:private chat-save-lock (Object.))

(defn with-save-lock!
  "Run F while holding the chat save lock."
  [f]
  (locking chat-save-lock
    (f)))

(defn save-chat-current!
  "Persist CHAT-ID from the current DB atom snapshot."
  [db* chat-id metrics]
  (with-save-lock!
    #(db/save-chat! @db* chat-id metrics)))
