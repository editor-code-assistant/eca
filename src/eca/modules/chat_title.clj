(ns eca.modules.chat-title
  "The `:eca/chat-title` module: the chat model titles each chat at its first
   request, and again at its third."
  (:require
   [clojure.string :as string]
   [eca.features.prompt :as f.prompt]
   [eca.llm-api :as llm-api]
   [eca.message-sanitize :as message-sanitize]))

(set! *warn-on-reflection* true)

(defn ^:private message-text-content
  "Extract plain text from a chat message's :content, ignoring non-text parts
   and the contexts modules added."
  [{:keys [content]}]
  (let [parts (cond
                (string? content)
                [content]

                (sequential? content)
                (into [] (keep (fn [part]
                                 (when (and (map? part)
                                            (#{:text "text"} (:type part))
                                            (not (message-sanitize/module-context? part)))
                                   (:text part))))
                      content)

                :else
                [])
        joined (string/join "\n" (remove string/blank? parts))]
    (when-not (string/blank? joined)
      joined)))

(defn ^:private conversation->title-transcript
  "Render user/assistant messages as a plain-text transcript for title generation.
   Each line is prefixed by role; per-message text is truncated to avoid blowing up
   the title call for very long chats.

   Flattening the history into a single user message (instead of replaying it as
   role-structured past-messages) prevents the title model from mirroring the
   prior assistant's conversational style (e.g. planning-mode '## Understand'
   headers), which was producing garbage titles on Opus."
  [messages]
  (let [max-chars 2000]
    (->> messages
         (keep (fn [{:keys [role] :as msg}]
                 (when-let [text (message-text-content msg)]
                   (let [truncated (if (> (count text) max-chars)
                                     (str (subs text 0 max-chars) " …")
                                     text)]
                     (str role ": " truncated)))))
         (string/join "\n\n"))))

(defn ^:private model-title
  "A title from the chat model, when `chat.title` is on: at the chat's first
   request without a title, and at its third request, from the conversation
   so far."
  [{:keys [db* config provider model full-model agent]} {:keys [messages prompt-messages prompt-count title]}]
  (let [retitle? (= prompt-count 3)]
    (when (and (get-in config [:chat :title])
               (or (and (not title) (not retitle?))
                   retitle?))
      ;; On retitle, flatten the conversation into a single user message
      ;; instead of replaying it as role-structured past-messages. This
      ;; prevents the title model (notably Opus) from mimicking the prior
      ;; assistant's style and emitting section-header titles like "Understand".
      (let [title-user-messages (if retitle?
                                  [{:role "user"
                                    :content [{:type :text
                                               :text (str "Summarize the following conversation as a thread title.\n"
                                                          "Follow the rules from the system prompt. Output only the title.\n\n"
                                                          "Conversation:\n"
                                                          (conversation->title-transcript messages))}]}]
                                  prompt-messages)
            db @db*]
        (when-let [output-text (:output-text (llm-api/sync-prompt!
                                              {:provider provider
                                               :model model
                                               :model-capabilities
                                               (assoc (get-in db [:models full-model]) :reason? false :tools false :web-search false)
                                               :instructions (f.prompt/get-config-prompt :chatTitle agent config)
                                               :past-messages nil
                                               :user-messages title-user-messages
                                               :config config
                                               :provider-auth (get-in db [:auth provider])
                                               :subagent? true}))]
          {:title output-text})))))

(def module
  {:id :eca/chat-title
   :description "The chat model titles each chat, and titles it again at its third request."
   :config-spec {:chat {:title {:type :boolean :default true}}
                 :prompts {:chatTitle {:type :string :default "${classpath:prompts/title.md}"}}}
   :event-handlers {:chat/title {:handler #'model-title}}})
