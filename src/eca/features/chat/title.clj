(ns eca.features.chat.title
  (:require
   [clojure.string :as string]
   [eca.features.chat.persistence :as chat.persistence]
   [eca.messenger :as messenger]
   [eca.shared :refer [assoc-some]]))

(set! *warn-on-reflection* true)

(defn sanitize-title
  "Clean up a chat title: take first meaningful line, strip control chars,
   markdown header prefixes, collapse whitespace, and truncate to 40 chars.

   If the first non-blank line is a bare markdown header with nothing else
   (e.g. '## Understand' - a planning-mode section the title model sometimes
   mimics), fall through to the next non-blank line when one exists."
  [^String s]
  (when s
    (let [lines (->> (string/split s #"\n")
                     (map string/trim)
                     (remove string/blank?))
          bare-header? (fn [^String line]
                         (boolean (re-matches #"#+\s+\S.*" line)))
          picked (or (when-let [first-line (first lines)]
                       (if (and (bare-header? first-line)
                                (seq (rest lines)))
                         (first (rest lines))
                         first-line))
                     "")]
      (-> picked
          (string/replace #"[\x00-\x1f\x7f]" " ")
          (string/replace #"^#+\s*" "")
          (string/replace #"\s+" " ")
          (string/trim)
          (as-> t (subs t 0 (min (count t) 40)))))))

(defn- commit-title-update!
  [db* chat-id title update-chat]
  (loop []
    (let [db @db*
          chat (get-in db [:chats chat-id])]
      (if-not chat
        nil
        (let [title (sanitize-title title)
              updated-chat (update-chat chat title)]
          (if-not updated-chat
            nil
            (let [new-db (assoc-in db [:chats chat-id] updated-chat)]
              (if (compare-and-set! db* db new-db)
                {:db new-db
                 :chat updated-chat
                 :title title}
                (recur)))))))))

(defn- notify-title! [messenger chat-id parent-chat-id role title]
  (messenger/chat-content-received messenger
                                   (assoc-some {:chat-id chat-id
                                                :role role
                                                :content {:type :metadata :title title}}
                                               :parent-chat-id parent-chat-id)))

(defn- update-title-with-side-effects!
  [db* chat-id title update-chat {:keys [messenger metrics parent-chat-id role save?]
                                  :or {role "system"
                                       save? (constantly true)}}]
  (chat.persistence/with-save-lock!
    (fn []
      (when-let [{:keys [title] :as result}
                 (commit-title-update! db* chat-id title update-chat)]
        (let [db @db*
              chat (get-in db [:chats chat-id])]
          (when (and chat (= title (:title chat)))
            (when messenger
              (notify-title! messenger chat-id parent-chat-id role title))
            (when (and metrics (save? chat))
              (chat.persistence/save-chat-current! db* chat-id metrics))
            (assoc result :db @db* :chat (get-in @db* [:chats chat-id]))))))))

(defn update-chat-title!
  "Set CHAT-ID's title to TITLE, mark it custom, notify clients, and save it."
  [db* chat-id title messenger metrics]
  (when-let [{:keys [title]}
             (update-title-with-side-effects!
              db* chat-id title
              (fn [chat title]
                (assoc chat
                       :title title
                       :title-custom? true
                       :updated-at (System/currentTimeMillis)))
              {:messenger messenger
               :metrics metrics})]
    title))

(defn- expected-chat-state?
  [chat opts]
  (and (or (not (contains? opts :expected-prompt-id))
           (= (:expected-prompt-id opts) (:prompt-id chat)))
       (or (not (contains? opts :expected-user-prompt-count))
           (= (:expected-user-prompt-count opts) (:user-prompt-count chat)))))

(defn update-generated-chat-title!
  "Set CHAT-ID's generated title when no custom title won the race."
  ([db* chat-id title]
   (update-generated-chat-title! db* chat-id title nil))
  ([db* chat-id title opts]
   (let [opts (or opts {})]
     (update-title-with-side-effects!
      db* chat-id title
      (fn [chat title]
        (when (and (not (:title-custom? chat))
                   (expected-chat-state? chat opts))
          (assoc chat :title title)))
      (assoc opts :save? #(= :idle (:status %)))))))
