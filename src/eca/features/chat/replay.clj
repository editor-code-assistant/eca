(ns eca.features.chat.replay
  "A chat's history as the client shows it: the contents of its messages,
   sent again when a chat is opened, forked or rolled back."
  (:require
   [clojure.string :as string]
   [eca.features.chat.history :as f.chat.history]
   [eca.message-sanitize :as message-sanitize]
   [eca.messenger :as messenger]
   [eca.shared :as shared :refer [assoc-some]]))

(set! *warn-on-reflection* true)

(defn ^:private message-content->chat-content
  "What clients get for a message of the history, when a chat is opened
   again. The contexts modules added aren't shown, as they weren't live."
  [role message-content content-id]
  (case role
    ("user"
     "system"
     "assistant") (let [text-content (reduce
                                      (fn [m content]
                                        (case (:type content)
                                          :text (assoc m
                                                       :type :text
                                                       :text (str (:text m) "\n" (:text content)))
                                          m))
                                      (assoc-some {} :content-id content-id)
                                      (remove message-sanitize/module-context? message-content))
                        image-entries (keep
                                       (fn [content]
                                         (when (= :image (:type content))
                                           {:role role
                                            :content {:type :image
                                                      :media-type (:media-type content)
                                                      :base64 (:base64 content)}}))
                                       message-content)
                       ;; Drop the text entry when there's no actual text and no image-only content
                       ;; would have produced an empty `{}` content map.
                        text-entries (if (:type text-content)
                                       [{:role role :content text-content}]
                                       [])]
                    (vec (concat text-entries image-entries)))
    "tool_call" [{:role :assistant
                  :content {:type :toolCallPrepare
                            :origin (:origin message-content)
                            :name (:name message-content)
                            :server (:server message-content)
                            :summary (:summary message-content)
                            :details (:details message-content)
                            :arguments-text ""
                            :id (:id message-content)}}]
    ;; Mirror the live path in tool-calls.clj :send-toolCalled: split image
    ;; outputs out of the toolCalled :outputs (which is text-only per
    ;; protocol) and re-emit them as standalone ChatImageContent entries so
    ;; reopened/resumed chats render MCP-produced images at the same point
    ;; they appeared live.
    "tool_call_output" (let [contents (let [contents (:contents (:output message-content))]
                                        (if (sequential? contents)
                                          (into [] (remove message-sanitize/module-context?) contents)
                                          contents))
                             image? #(and (map? %) (= :image (:type %)))
                             ;; Only partition when contents is a sequence of content
                             ;; maps that includes images; otherwise pass through.
                             image-outputs (when (sequential? contents) (filter image? contents))
                             text-outputs (if (seq image-outputs)
                                            (vec (remove image? contents))
                                            contents)]
                         (into [{:role :assistant
                                 :content (assoc-some
                                           {:type :toolCallRun
                                            :id (:id message-content)
                                            :name (:name message-content)
                                            :server (:server message-content)
                                            :origin (:origin message-content)
                                            :arguments (:arguments message-content)}
                                           :details (:details message-content)
                                           :summary (:summary message-content))}
                                {:role :assistant
                                 :content (assoc-some
                                           {:type :toolCallRunning
                                            :id (:id message-content)
                                            :name (:name message-content)
                                            :server (:server message-content)
                                            :origin (:origin message-content)
                                            :arguments (:arguments message-content)}
                                           :details (:details message-content)
                                           :summary (:summary message-content))}
                                {:role :assistant
                                 :content {:type :toolCalled
                                           :origin (:origin message-content)
                                           :name (:name message-content)
                                           :server (:server message-content)
                                           :arguments (:arguments message-content)
                                           :total-time-ms (:total-time-ms message-content)
                                           :summary (:summary message-content)
                                           :details (:details message-content)
                                           :error (:error message-content)
                                           :id (:id message-content)
                                           :outputs text-outputs}}]
                               (map (fn [img]
                                      {:role :assistant
                                       :content {:type :image
                                                 :media-type (:media-type img)
                                                 :base64 (:base64 img)}}))
                               image-outputs))
    "image_generation_call" [{:role :assistant
                              :content {:type :image
                                        :media-type (:media-type message-content)
                                        :base64 (:base64 message-content)}}]
    "server_tool_use" [{:role :assistant
                        :content {:type :toolCallPrepare
                                  :origin :server
                                  :name (:name message-content)
                                  :server :llm
                                  :arguments-text ""
                                  :id (:id message-content)}}]
    "server_tool_result" (let [id (:tool-use-id message-content)]
                           [{:role :assistant
                             :content {:type :toolCallRun
                                       :id id
                                       :origin :server
                                       :server :llm}}
                            {:role :assistant
                             :content {:type :toolCallRunning
                                       :id id
                                       :origin :server
                                       :server :llm}}
                            {:role :assistant
                             :content {:type :toolCalled
                                       :id id
                                       :origin :server
                                       :server :llm
                                       :name "web_search"
                                       :arguments {}
                                       :error false}}])
    "reason" (cond-> [{:role :assistant
                       :content {:type :reasonStarted
                                 :id (:id message-content)}}]
               (:text message-content)
               (conj {:role :assistant
                      :content {:type :reasonText
                                :id (:id message-content)
                                :text (:text message-content)}})
               true
               (conj {:role :assistant
                      :content {:type :reasonFinished
                                :id (:id message-content)
                                :total-time-ms (:total-time-ms message-content)}}))
    "compact_marker" [{:role :system
                       :content {:type :text
                                 :text (cond
                                         ;; a module's checkpoint (eca.api/checkpoint!)
                                         (shared/not-blank (:label message-content))
                                         (str "── " (:label message-content) " ──")

                                         (:auto? message-content) "── Chat auto-compacted ──"
                                         :else "── Chat compacted ──")}}]
    "flag" [{:role :system
             :content {:type :flag
                       :text (:text message-content)
                       :contentId content-id}}]))

(defn ^:private merge-adjacent-reasons
  "Display-only: collapses each run of consecutive reason messages into one,
   mirroring the live grouping in `lifecycle/send-content!`. Keeps the first
   block id, joins non-blank texts with a blank line and sums the times."
  [messages]
  (into []
        (mapcat (fn [[first-message :as run]]
                  (if (and (= "reason" (:role first-message)) (next run))
                    [(assoc first-message
                            :content {:id (get-in first-message [:content :id])
                                      :text (some->> run
                                                     (keep #(shared/not-blank (get-in % [:content :text])))
                                                     seq
                                                     (string/join "\n\n"))
                                      :total-time-ms (some->> run
                                                              (keep #(get-in % [:content :total-time-ms]))
                                                              seq
                                                              (reduce +))})]
                    run)))
        (partition-by #(= "reason" (:role %)) messages)))

(defn messages->contents
  "Pure transform of persisted `messages` into the flat sequence of chat-content
   notification payloads ({:chat-id :role :content :parent-chat-id?}) that the
   streaming replay sends, including nested subagent expansion. `db` is a plain
   db snapshot used only to resolve subagent message histories. Consecutive
   reason messages render as a single reasoning block, like live streaming.

   Shared by the streaming replay (`send-chat-contents!`) and the request/response
   `chat/history` method so both render identical content."
  [messages {:keys [chat-id parent-chat-id db]}]
  (let [->payload (fn [{:keys [role content]}]
                    (assoc-some {:chat-id chat-id :role role :content content}
                                :parent-chat-id parent-chat-id))]
    (into []
          (mapcat
           (fn [message]
             (let [chat-contents (message-content->chat-content (:role message) (:content message) (:content-id message))
                   subagent-chat-id (when (= "tool_call_output" (:role message))
                                      (get-in message [:content :details :subagent-chat-id]))
                   subagent-messages (when subagent-chat-id
                                       (f.chat.history/replayed-messages (get-in db [:chats subagent-chat-id :messages])
                                                                               (:content message)))]
               (if (some? subagent-messages)
                 ;; For subagent tool calls: toolCallRun + toolCallRunning, then
                 ;; subagent messages, then toolCalled — matching live execution order.
                 (concat (map ->payload (butlast chat-contents))
                         (messages->contents subagent-messages
                                             {:chat-id subagent-chat-id
                                              :parent-chat-id chat-id
                                              :db db})
                         [(->payload (last chat-contents))])
                 (map ->payload chat-contents))))
           (merge-adjacent-reasons messages)))))

(defn send-chat-contents! [messages chat-ctx]
  (doseq [payload (messages->contents messages {:chat-id (:chat-id chat-ctx)
                                                :parent-chat-id (:parent-chat-id chat-ctx)
                                                :db @(:db* chat-ctx)})]
    (messenger/chat-content-received (:messenger chat-ctx) payload)))
