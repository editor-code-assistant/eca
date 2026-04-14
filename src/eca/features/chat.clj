(ns eca.features.chat
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.background-tasks :as bg]
   [eca.features.chat.lifecycle :as lifecycle]
   [eca.features.chat.tool-calls :as tc]
   [eca.features.commands :as f.commands]
   [eca.features.context :as f.context]
   [eca.features.hooks :as f.hooks]
   [eca.features.index :as f.index]
   [eca.features.prompt :as f.prompt]
   [eca.features.rules :as f.rules]
   [eca.features.skills :as f.skills]
   [eca.features.tools :as f.tools]
   [eca.features.tools.mcp :as f.mcp]
   [eca.llm-api :as llm-api]
   [eca.llm-providers.errors :as llm-providers.errors]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.metrics :as metrics]
   [eca.shared :as shared :refer [assoc-some future*]]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[CHAT]")

(defn ^:private estimate-tokens
  "Rough token estimate: ~4 chars per token."
  ^long [^String s]
  (if s
    (quot (count s) 4)
    0))

(defn ^:private tool-output-text [msg]
  (let [contents (get-in msg [:content :output :contents])]
    (reduce (fn [^String acc {:keys [text]}]
              (if text (str acc text) acc))
            ""
            contents)))

(defn ^:private server-tool-result-text [msg]
  (let [raw-content (get-in msg [:content :raw-content])]
    (reduce (fn [^String acc item]
              (if-let [text (:text item)]
                (str acc text)
                acc))
            ""
            raw-content)))

(def ^:private cleared-tool-output
  {:error false
   :contents [{:type :text :text "[content cleared to reduce context size]"}]})

(def ^:private cleared-raw-content
  [{:type "text" :text "[content cleared to reduce context size]"}])

(defn ^:private prune-tool-results!
  "Prunes old tool result content from chat history to reduce context size.
   Walks messages backwards, protecting the most recent tool outputs up to
   `protect-budget` estimated tokens. Clears older tool outputs with a placeholder.
   Returns the estimated number of tokens freed."
  [db* chat-id {:keys [protect-budget]
                :or {protect-budget 40000}}]
  (let [messages (get-in @db* [:chats chat-id :messages] [])
        n (count messages)
        {:keys [pruned-messages freed-tokens]}
        (loop [i (dec n)
               protected-tokens 0
               freed-tokens 0
               result messages]
          (if (neg? i)
            {:pruned-messages result
             :freed-tokens freed-tokens}
            (let [msg (nth messages i)
                  role (:role msg)]
              (cond
                (= "compact_marker" role)
                {:pruned-messages result
                 :freed-tokens freed-tokens}

                (= "tool_call_output" role)
                (let [text (tool-output-text msg)
                      tokens (estimate-tokens text)]
                  (if (< protected-tokens protect-budget)
                    (recur (dec i) (+ protected-tokens tokens) freed-tokens result)
                    (recur (dec i) protected-tokens (+ freed-tokens tokens)
                           (assoc result i (assoc-in msg [:content :output] cleared-tool-output)))))

                (= "server_tool_result" role)
                (let [text (server-tool-result-text msg)
                      tokens (estimate-tokens text)]
                  (if (< protected-tokens protect-budget)
                    (recur (dec i) (+ protected-tokens tokens) freed-tokens result)
                    (recur (dec i) protected-tokens (+ freed-tokens tokens)
                           (assoc result i (assoc-in msg [:content :raw-content] cleared-raw-content)))))

                :else
                (recur (dec i) protected-tokens freed-tokens result)))))]
    (when (pos? freed-tokens)
      (swap! db* assoc-in [:chats chat-id :messages] pruned-messages))
    freed-tokens))

(defn ^:private message-content->chat-content [role message-content content-id]
  (case role
    ("user"
     "system"
     "assistant") [{:role role
                    :content (reduce
                              (fn [m content]
                                (case (:type content)
                                  :text (assoc m
                                               :type :text
                                               :text (str (:text m) "\n" (:text content)))
                                  m))
                              (assoc-some {} :content-id content-id)
                              message-content)}]
    "tool_call" [{:role :assistant
                  :content {:type :toolCallPrepare
                            :origin (:origin message-content)
                            :name (:name message-content)
                            :server (:server message-content)
                            :summary (:summary message-content)
                            :details (:details message-content)
                            :arguments-text ""
                            :id (:id message-content)}}]
    "tool_call_output" [{:role :assistant
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
                                   :outputs (:contents (:output message-content))}}]
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
                                 :text (if (:auto? message-content)
                                         "── Chat auto-compacted ──"
                                         "── Chat compacted ──")}}]
    "flag" [{:role :system
             :content {:type :flag
                       :text (:text message-content)
                       :contentId content-id}}]))

(defn ^:private send-chat-contents! [messages chat-ctx]
  (doseq [message messages]
    (let [chat-contents (message-content->chat-content (:role message) (:content message) (:content-id message))
          subagent-chat-id (when (= "tool_call_output" (:role message))
                             (get-in message [:content :details :subagent-chat-id]))]
      (if-let [subagent-messages (when subagent-chat-id
                                   (get-in @(:db* chat-ctx) [:chats subagent-chat-id :messages]))]
        ;; For subagent tool calls: send toolCallRun + toolCallRunning, then
        ;; subagent messages, then toolCalled — matching live execution order.
        (let [before-called (butlast chat-contents)
              called (last chat-contents)]
          (doseq [{:keys [role content]} before-called]
            (lifecycle/send-content! chat-ctx role content))
          (send-chat-contents! subagent-messages
                               (assoc chat-ctx
                                      :chat-id subagent-chat-id
                                      :parent-chat-id (:chat-id chat-ctx)))
          (lifecycle/send-content! chat-ctx (:role called) (:content called)))
        (doseq [{:keys [role content]} chat-contents]
          (lifecycle/send-content! chat-ctx role content))))))

(defn default-model [db config]
  (llm-api/default-model db config))

(defn ^:private update-pre-request-state
  "Pure function to compute new state from hook result."
  [{:keys [final-prompt additional-contexts stop?]} {:keys [parsed raw-output exit]} action-name]
  (let [replaced-prompt (:replacedPrompt parsed)
        additional-context (if parsed
                             (:additionalContext parsed)
                             raw-output)
        success? (= 0 exit)]
    {:final-prompt (if (and replaced-prompt success?)
                     replaced-prompt
                     final-prompt)
     :additional-contexts (if (and additional-context success?)
                            (conj additional-contexts
                                  {:hook-name action-name :content additional-context})
                            additional-contexts)
     :stop? (or stop?
                (false? (get parsed :continue true)))}))

(defn ^:private run-pre-request-action!
  "Run a single preRequest hook action, updating the accumulator state.

  State is a map:
  - :final-prompt
  - :all-messages
  - :additional-contexts
  - :stop? (true when any hook requests stop)"
  [db chat-ctx chat-id hook hook-name idx action state]
  (if (:stop? state)
    state
    (let [id (str (random-uuid))
          action-type (:type action)
          action-name (if (> (count (:actions hook)) 1)
                        (str hook-name "-" (inc idx))
                        hook-name)
          visible? (get hook :visible true)]
      (lifecycle/notify-before-hook-action! chat-ctx {:id id
                                                      :visible? visible?
                                                      :name action-name})
      ;; Run the hook action
      (if-let [result (f.hooks/run-hook-action! action
                                                action-name
                                                :preRequest
                                                (merge (f.hooks/chat-hook-data db chat-id (:agent chat-ctx))
                                                       {:prompt (:final-prompt state)
                                                        :all-messages (:all-messages state)})
                                                db)]
        (let [{:keys [parsed raw-output raw-error exit]} result
              should-continue? (get parsed :continue true)]
          ;; Notify after action
          (lifecycle/notify-after-hook-action! chat-ctx (merge result
                                                               {:id id
                                                                :name action-name
                                                                :type action-type
                                                                :visible? visible?
                                                                :status exit
                                                                :output raw-output
                                                                :error raw-error}))
          ;; Check if hook wants to stop
          (when (false? should-continue?)
            (when-let [stop-reason (:stopReason parsed)]
              (lifecycle/send-content! chat-ctx :system {:type :text :text stop-reason}))
            (lifecycle/finish-chat-prompt! :idle chat-ctx))
          ;; Update accumulator
          (update-pre-request-state state
                                    result
                                    action-name))
        ;; No result from action
        (do
          (lifecycle/notify-after-hook-action! chat-ctx {:id id
                                                         :name action-name
                                                         :visible? visible?
                                                         :type action-type
                                                         :exit 1
                                                         :status 1})
          state)))))

(defn ^:private run-pre-request-hook!
  "Run all actions for a single preRequest hook, threading state."
  [db chat-ctx chat-id state [hook-name hook]]
  (reduce
   (fn [s [idx action]]
     (if (:stop? s)
       (reduced s)
       (run-pre-request-action! db chat-ctx chat-id hook (name hook-name) idx action s)))
   state
   (map-indexed vector (:actions hook))))

(defn ^:private run-pre-request-hooks!
  "Run preRequest hooks with chaining support.

  Returns a map with:
  - :final-prompt
  - :additional-contexts (vector of {:hook-name name :content context})
  - :stop? (true when any hook requests stop)"
  [{:keys [db* config chat-id message all-messages] :as chat-ctx}]
  (let [db @db*]
    (reduce
     (fn [state hook-entry]
       (if (:stop? state)
         (reduced state)
         (run-pre-request-hook! db chat-ctx chat-id state hook-entry)))
     {:final-prompt message
      :all-messages all-messages
      :additional-contexts []
      :stop? false}
     (->> (:hooks config)
          (filter #({"preRequest" "prePrompt"} (:type (val %))))
          (sort-by key)))))

(declare prompt-messages!)

(defn ^:private tokenize-args [^String s]
  (if (string/blank? s)
    []
    (->> (re-seq #"\s*\"([^\"]*)\"|\s*([^\s]+)" s)
         (map (fn [[_ quoted unquoted]] (or quoted unquoted)))
         (vec))))

(defn ^:private message->decision [message db config]
  (let [all-command-names (->> (f.commands/all-commands db config)
                               (map :name)
                               set)
        slash? (string/starts-with? message "/")
        possible-command (when slash? (subs message 1))
        [command-name & args] (when possible-command
                                (let [toks (tokenize-args possible-command)] (if (seq toks) toks [""])))
        args (vec args)
        command? (contains? all-command-names command-name)]
    (if command?
      (if (and command-name (string/includes? command-name ":"))
        (let [[server prompt] (string/split command-name #":" 2)]
          {:type :mcp-prompt
           :server server
           :prompt prompt
           :args args})
        {:type :eca-command
         :command command-name
         :args args})
      {:type :prompt-message
       :message message})))

(defn ^:private truncated-response?
  "Returns true when the response text shows signs of being truncated mid-stream.
   Checks for unclosed code fences (odd number of ``` markers at line start)."
  [^String text]
  (when-not (string/blank? text)
    (odd? (count (re-seq #"(?m)^```" text)))))

(defn ^:private trigger-auto-compact!
  "Trigger auto-compact: send compact prompt, then resume the original task."
  [{:keys [db* config chat-id agent] :as chat-ctx}
   all-tools
   user-messages]
  (let [db @db*
        compact-prompt (f.prompt/compact-prompt nil all-tools agent config db)]
    (logger/info logger-tag "Auto-compacting chat" {:chat-id chat-id})
    (swap! db* assoc-in [:chats chat-id :auto-compacting?] true)
    (prompt-messages!
     [{:role "user" :content "Compact the chat following the template:"}
      {:role "user" :content compact-prompt}]
     :auto-compact
     (assoc chat-ctx
            :on-finished-side-effect
            (fn []
              (swap! db* update-in [:chats chat-id] dissoc :auto-compacting?)
              (shared/compact-side-effect! chat-ctx true)
              ;; Resume the original task
              (prompt-messages!
               (concat [{:role "user"
                         :content [{:type :text
                                    :text "Continue with the task. The previous user request was:"}]}]
                       user-messages)
               :auto-compact
               (assoc chat-ctx :auto-compacted? true)))))
    nil))

(defn ^:private assert-compatible-apis-between-models!
  "Ensure new request is compatible with last api used.
   E.g. Anthropic is not compatible with openai and vice versa."
  [db chat-id provider model config]
  (let [current-api (:api (llm-api/provider->api-handler provider model config))
        last-api (get-in db [:chats chat-id :last-api])]
    (cond
      (not last-api) nil
      (not current-api) nil

      (or (and (= :anthropic current-api)
               (not= :anthropic last-api))
          (and (not= :anthropic current-api)
               (= :anthropic last-api)))
      (throw (ex-info "Incompatible past messages in chat.\nAnthropic models are only compatible with other Anthropic models, switch models or start a new chat." {})))))

(defn ^:private consume-steer-message!
  "Reads and clears any pending steer message for the chat in a single swap.
   If present, adds it as a user message to history and notifies the client."
  [chat-id db* chat-ctx add-to-history!]
  (let [steer-msg* (volatile! nil)]
    (swap! db* (fn [db]
                 (if-let [msg (get-in db [:chats chat-id :steer-message])]
                   (do (vreset! steer-msg* msg)
                       (update-in db [:chats chat-id] dissoc :steer-message))
                   db)))
    (when-let [steer-msg @steer-msg*]
      (let [content-id (str (random-uuid))
            user-message {:role "user"
                          :content [{:type :text :text steer-msg}]
                          :content-id content-id}]
        (add-to-history! user-message)
        (lifecycle/send-content! chat-ctx :user {:type :text
                                                 :content-id content-id
                                                 :text (str steer-msg "\n")})))))

(defn ^:private consume-pending-job-notifications!
  "Reads and clears any pending job notifications for the chat in a single swap.
   For each notification, adds a user message to history and notifies the client.
   Marks each job as :notified true and evicts them from the registry."
  [chat-id db* chat-ctx add-to-history!]
  (let [notifications* (volatile! nil)]
    (swap! db* (fn [db]
                 (if-let [notifs (seq (get-in db [:chats chat-id :pending-job-notifications]))]
                   (do (vreset! notifications* (vec notifs))
                       (assoc-in db [:chats chat-id :pending-job-notifications] []))
                   db)))
    (when-let [notifications @notifications*]
      (doseq [{:keys [job-id status exit-code label output-tail]} notifications]
        (let [content-id (str (random-uuid))
              text (str "Background job " job-id " (`" label "`) "
                        (name status) " with exit code " exit-code "."
                        (when (seq output-tail)
                          (str "\nLast 20 lines of output:\n"
                               (string/join "\n" output-tail))))
              user-message {:role "user"
                            :content [{:type :text :text text}]
                            :content-id content-id}]
          (add-to-history! user-message)
          (lifecycle/send-content! chat-ctx :system
            {:type :text
             :content-id content-id
             :text (str text "\n")})))
      (doseq [{:keys [job-id]} notifications]
        (swap! bg/registry* assoc-in [:jobs job-id :notified] true))
      (bg/evict-notified-jobs!))))

(defn ^:private sanitize-title
  "Clean up a chat title: take first meaningful line, strip control chars,
   markdown header prefixes, collapse whitespace, and truncate to 40 chars."
  [^String s]
  (when s
    (-> (string/split s #"\n")
        (->> (map string/trim)
             (remove string/blank?)
             first)
        (or "")
        (string/replace #"[\x00-\x1f\x7f]" " ")
        (string/replace #"^#+\s*" "")
        (string/replace #"\s+" " ")
        (string/trim)
        (as-> t (subs t 0 (min (count t) 40))))))

(defn ^:private prompt-messages!
  "Send user messages to LLM with hook processing.
   source-type controls hook agent.
   Run preRequest hooks before any heavy lifting.
   Only :prompt-message supports rewrite, other only allow additionalContext append."
  [user-messages source-type
   {:keys [db* config chat-id provider model full-model agent instructions metrics message messenger] :as chat-ctx}]
  (when-not full-model
    (throw (ex-info llm-api/no-available-model-error-msg {})))
  (let [original-text (or message (-> user-messages first :content first :text))
        modify-allowed? (= source-type :prompt-message)
        run-hooks? (#{:prompt-message :eca-command :mcp-prompt} source-type)
        user-messages (if run-hooks?
                        (let [past-messages (shared/messages-after-last-compact-marker
                                             (get-in @db* [:chats chat-id :messages] []))
                              {:keys [final-prompt additional-contexts stop?]}
                              (run-pre-request-hooks! (assoc chat-ctx :message original-text
                                                             :all-messages (into past-messages user-messages)))]
                          (cond
                            stop? (do (lifecycle/finish-chat-prompt! :idle chat-ctx) nil)
                            :else (let [last-user-idx (llm-util/find-last-user-msg-idx user-messages)
                                          ;; preRequest additionalContext should ideally attach to the last user message,
                                          ;; but some prompt sources may not contain a user role (e.g. prompt templates).
                                        context-idx   (or last-user-idx
                                                          (some-> user-messages seq count dec))
                                        rewritten     (if (and modify-allowed? last-user-idx final-prompt)
                                                        (assoc-in user-messages [last-user-idx :content 0 :text] final-prompt)
                                                        user-messages)
                                        with-contexts (cond
                                                        (and (seq additional-contexts) context-idx)
                                                        (reduce (fn [msgs {:keys [hook-name content]}]
                                                                  (update-in msgs [context-idx :content]
                                                                             #(conj (if (string? %)
                                                                                      [{:type :text :text %}]
                                                                                      (vec %))
                                                                                    {:type :text
                                                                                     :text (lifecycle/wrap-additional-context hook-name content)})))
                                                                rewritten
                                                                additional-contexts)

                                                        (seq additional-contexts)
                                                        (do (logger/warn logger-tag "Dropping preRequest additionalContext because no message index was found"
                                                                         {:source-type source-type
                                                                          :num-messages (count user-messages)})
                                                            rewritten)

                                                        :else
                                                        rewritten)]
                                    with-contexts)))
                        user-messages)
        prompt-id (random-uuid)]
    (when user-messages
      (when (#{:running :stopping} (get-in @db* [:chats chat-id :status]))
        (logger/info logger-tag "Superseding active prompt" {:chat-id chat-id
                                                             :status (get-in @db* [:chats chat-id :status])}))
      (swap! db* assoc-in [:chats chat-id :status] :running)
      (swap! db* update-in [:chats chat-id] dissoc :prompt-finished?)
      (swap! db* assoc-in [:chats chat-id :updated-at] (System/currentTimeMillis))
      (messenger/chat-status-changed messenger {:chat-id chat-id :status :running})
      (swap! db* assoc-in [:chats chat-id :prompt-id] prompt-id)
      (swap! db* assoc-in [:chats chat-id :model] full-model)
      (swap! db* update-in [:chats chat-id :user-prompt-count] (fnil inc 0))
      (let [chat-ctx (assoc chat-ctx :prompt-id prompt-id)
            _ (lifecycle/maybe-renew-auth-token chat-ctx) ;; ensures captured provider-auth fallback is fresh
            db @db*
            model-capabilities (get-in db [:models full-model])
            provider-auth (get-in @db* [:auth provider])
            all-tools (f.tools/all-tools chat-id agent @db* config)
            received-msgs* (atom "")
            reasonings* (atom {})
            server-tool-times* (atom {})
            pending-server-tool-uses* (atom {})
            add-to-history! (fn [msg]
                              (swap! db* update-in [:chats chat-id :messages] (fnil conj []) msg))
            on-usage-updated (fn [usage]
                               (when-let [usage (shared/usage-msg->usage usage full-model chat-ctx)]
                                 (lifecycle/send-content! chat-ctx :system (merge {:type :usage} usage))))
            prompt-count (get-in db [:chats chat-id :user-prompt-count] 0)
            retitle? (= prompt-count 3)
            generate-title? (and (get-in config [:chat :title])
                                 (not (get-in db [:chats chat-id :title-custom?]))
                                 (or (and (not (get-in db [:chats chat-id :title]))
                                          (not retitle?))
                                     retitle?))]
        (assert-compatible-apis-between-models! db chat-id provider model config)
        (when generate-title?
          (let [title-messages (if retitle?
                                 (into (get-in db [:chats chat-id :messages] [])
                                       user-messages)
                                 user-messages)]
            (future* config
              (when-let [{:keys [output-text]} (llm-api/sync-prompt!
                                                {:provider provider
                                                 :model model
                                                 :model-capabilities
                                                 (assoc model-capabilities :reason? false :tools false :web-search false)
                                                 :instructions (f.prompt/chat-title-prompt agent config)
                                                 :user-messages title-messages
                                                 :config config
                                                 :provider-auth provider-auth
                                                 :subagent? true})]
                (when output-text
                  (let [title (sanitize-title output-text)]
                    (swap! db* assoc-in [:chats chat-id :title] title)
                    (lifecycle/send-content! chat-ctx :system (assoc-some {:type :metadata} :title title))
                    (when (= :idle (get-in @db* [:chats chat-id :status]))
                      (db/update-workspaces-cache! @db* metrics))))))))
        (lifecycle/send-content! chat-ctx :system {:type :progress :state :running :text "Waiting model"})
        (if (and (lifecycle/auto-compact? chat-id agent full-model config @db*)
                 (not (:auto-compacted? chat-ctx)))
          (trigger-auto-compact! chat-ctx all-tools user-messages)
          (future* config
            (try
              (llm-api/sync-or-async-prompt!
               {:model model
                :provider provider
                :model-capabilities model-capabilities
                :user-messages user-messages
                :instructions  instructions
                :agent agent
                :past-messages (shared/messages-after-last-compact-marker
                                (get-in @db* [:chats chat-id :messages] []))
                :config  config
                :tools all-tools
                :provider-auth provider-auth
                ;; Renew before each prompt! invocation so long-running chats
                ;; (spawn_agent, retries) always get a fresh token.
                :refresh-provider-auth-fn (fn []
                                            (lifecycle/maybe-renew-auth-token chat-ctx)
                                            (get-in @db* [:auth provider]))
                :variant (:variant chat-ctx)
                :subagent? (some? (get-in @db* [:chats chat-id :subagent]))
                :cancelled? (fn []
                              (let [chat (get-in @db* [:chats chat-id])]
                                (or (identical? :stopping (:status chat))
                                    (:prompt-finished? chat)
                                    (not= prompt-id (:prompt-id chat)))))
                :on-retry (fn [{:keys [attempt max-retries delay-ms classified]}]
                            (let [{error-type :error/type error-label :error/label} classified
                                  reason (or error-label
                                             (case error-type
                                               :rate-limited "Rate limited"
                                               :overloaded "Provider overloaded"
                                               :premature-stop "Empty response"
                                               "Transient error"))]
                              (lifecycle/send-content! chat-ctx :system
                                                       {:type :progress
                                                        :state :running
                                                        :text (format "⏳ %s. Retrying in %ds (attempt %d/%d)"
                                                                      reason (quot delay-ms 1000) attempt max-retries)})))
                :on-first-response-received (fn [& _]
                                              (lifecycle/assert-chat-not-stopped! chat-ctx)
                                              (doseq [message user-messages]
                                                (add-to-history!
                                                 (assoc message :content-id (:user-content-id chat-ctx))))
                                              (swap! db* assoc-in [:chats chat-id :last-api] (:api (llm-api/provider->api-handler provider model config)))
                                              (lifecycle/send-content! chat-ctx :system {:type :progress
                                                                                         :state :running
                                                                                         :text "Generating"}))
                :on-usage-updated on-usage-updated
                :on-message-received (fn [{:keys [type] :as msg}]
                                       (lifecycle/assert-chat-not-stopped! chat-ctx)
                                       (case type
                                         :text (do (swap! received-msgs* str (:text msg))
                                                   (lifecycle/send-content! chat-ctx :assistant {:type :text :text (:text msg)}))
                                         :url (lifecycle/send-content! chat-ctx :assistant {:type :url :title (:title msg) :url (:url msg)})
                                         :limit-reached (do (lifecycle/send-content!
                                                             chat-ctx
                                                             :system
                                                             {:type :text
                                                              :text (str "API limit reached. Tokens: "
                                                                         (json/generate-string (:tokens msg)))})
                                                            (lifecycle/finish-chat-prompt! :idle (dissoc chat-ctx :on-finished-side-effect)))
                                         :finish (let [response-text @received-msgs*
                                                       stopping? (identical? :stopping (get-in @db* [:chats chat-id :status]))]
                                                   (when-not (string/blank? response-text)
                                                     (add-to-history! {:role "assistant"
                                                                       :content [{:type :text :text response-text}]}))
                                                   (if (and (not stopping?)
                                                            (not (string/blank? response-text))
                                                            (or (:premature? msg)
                                                                (truncated-response? response-text))
                                                            (not (:auto-continued? chat-ctx))
                                                            (not (:on-finished-side-effect chat-ctx)))
                                                     (do
                                                       (logger/info logger-tag "Truncated or premature response detected, auto-continuing"
                                                                    {:chat-id chat-id
                                                                     :premature? (:premature? msg)
                                                                     :truncated? (truncated-response? response-text)})
                                                       (lifecycle/send-content! chat-ctx :system
                                                                                {:type :progress :state :running :text "Response interrupted, continuing..."})
                                                       (swap! db* assoc-in [:chats chat-id :auto-compacting?] true)
                                                       (lifecycle/finish-chat-prompt!
                                                        :idle
                                                        (assoc chat-ctx :on-finished-side-effect
                                                               (fn []
                                                                 (swap! db* update-in [:chats chat-id] dissoc :auto-compacting?)
                                                                 (prompt-messages!
                                                                  [{:role "user"
                                                                    :content [{:type :text
                                                                               :text "Your previous response was interrupted mid-stream. Continue from where you left off, do not redo completed steps."}]}]
                                                                  :auto-continue
                                                                  (assoc chat-ctx :auto-continued? true))))))
                                                     (lifecycle/finish-chat-prompt! :idle chat-ctx)))))
                :on-prepare-tool-call (fn [{:keys [id full-name arguments-text]}]
                                        (lifecycle/assert-chat-not-stopped! chat-ctx)
                                        (let [all-tools (f.tools/all-tools chat-id agent @db* config)
                                              tool (tc/tool-by-full-name full-name all-tools)]
                                          (when-not tool
                                            (logger/warn logger-tag "Tool not found for prepare"
                                                         {:full-name full-name
                                                          :available-tools (mapv :full-name all-tools)}))
                                          (tc/transition-tool-call! db* chat-ctx id :tool-prepare
                                                                    {:name (or (:name tool) full-name)
                                                                     :server (:name (:server tool))
                                                                     :full-name full-name
                                                                     :origin (or (:origin tool) :unknown)
                                                                     :arguments-text arguments-text
                                                                     :summary (f.tools/tool-call-summary all-tools full-name nil config @db*)})))
                :on-tools-called (tc/on-tools-called!
                                  (assoc chat-ctx :continue-fn
                                         (fn [tc-all-tools tc-user-messages]
                                           (if (get-in @db* [:chats chat-id :compact-done?])
                                             (do (swap! db* update-in [:chats chat-id] dissoc :compact-done?)
                                                 (lifecycle/finish-chat-prompt! :idle chat-ctx)
                                                 nil)
                                             (if (and (lifecycle/auto-compact? chat-id agent full-model config @db*)
                                                      (not (:auto-compacted? chat-ctx)))
                                               (trigger-auto-compact! chat-ctx tc-all-tools tc-user-messages)
                                               (do
                                                 (consume-steer-message! chat-id db* chat-ctx add-to-history!)
                                                 (consume-pending-job-notifications! chat-id db* chat-ctx add-to-history!)
                                                 {:tools tc-all-tools
                                                  :new-messages (shared/messages-after-last-compact-marker
                                                                 (get-in @db* [:chats chat-id :messages]))})))))
                                  received-msgs* add-to-history! user-messages)
                :on-reason (fn [{:keys [status id text external-id delta-reasoning? redacted? data]}]
                             (lifecycle/assert-chat-not-stopped! chat-ctx)
                             (case status
                               :started  (do (swap! reasonings* assoc-in [id :start-time] (System/currentTimeMillis))
                                             (when redacted?
                                               (swap! reasonings* assoc-in [id :redacted?] true)
                                               (swap! reasonings* assoc-in [id :data] data))
                                             (lifecycle/send-content! chat-ctx :assistant {:type :reasonStarted :id id}))
                               :thinking (do (swap! reasonings* update-in [id :text] str text)
                                             (lifecycle/send-content! chat-ctx :assistant {:type :reasonText :id id :text text}))
                               :finished (when-let [start-time (get-in @reasonings* [id :start-time])]
                                           (let [total-time-ms (- (System/currentTimeMillis) start-time)
                                                 reasoning (get @reasonings* id)]
                                             (add-to-history! {:role "reason"
                                                               :content (cond-> {:id id
                                                                                 :external-id external-id
                                                                                 :delta-reasoning? delta-reasoning?
                                                                                 :total-time-ms total-time-ms
                                                                                 :text (:text reasoning)}
                                                                          (:redacted? reasoning)
                                                                          (assoc :redacted? true
                                                                                 :data (:data reasoning)))})
                                             (lifecycle/send-content! chat-ctx :assistant {:type :reasonFinished :total-time-ms total-time-ms :id id})))
                               nil))
                :on-server-web-search (fn [{:keys [status id name input output raw-content]}]
                                        (lifecycle/assert-chat-not-stopped! chat-ctx)
                                        (let [summary (format "Web searching%s"
                                                              (if-let [query (:query input)]
                                                                (format " '%s'" query)
                                                                ""))
                                              arguments (or input {})]
                                          (case status
                                            :started (do
                                                       (swap! server-tool-times* assoc id (System/currentTimeMillis))
                                                       (tc/transition-tool-call! db* chat-ctx id :tool-prepare
                                                                                 {:name name
                                                                                  :server :llm
                                                                                  :origin :server
                                                                                  :arguments-text ""
                                                                                  :summary summary})
                                                       (tc/transition-tool-call! db* chat-ctx id :tool-run
                                                                                 {:approved?* (promise)
                                                                                  :future-cleanup-complete?* (promise)
                                                                                  :name name
                                                                                  :server :llm
                                                                                  :origin :server
                                                                                  :arguments arguments
                                                                                  :manual-approval false
                                                                                  :summary summary})
                                                       (tc/transition-tool-call! db* chat-ctx id :approval-allow
                                                                                 {:reason :server-tool})
                                                       (tc/transition-tool-call! db* chat-ctx id :execution-start
                                                                                 {:delayed-future (delay nil)
                                                                                  :origin :server
                                                                                  :name name
                                                                                  :server :llm
                                                                                  :arguments arguments
                                                                                  :start-time (System/currentTimeMillis)
                                                                                  :summary summary
                                                                                  :progress-text "Searching the web"}))
                                            :input-ready (swap! pending-server-tool-uses* assoc id
                                                                {:role "server_tool_use"
                                                                 :content {:id id
                                                                           :name name
                                                                           :input arguments}})
                                            :finished (let [start-time (get @server-tool-times* id)
                                                            total-time-ms (if start-time
                                                                            (- (System/currentTimeMillis) start-time)
                                                                            0)
                                                            outputs (when (seq output)
                                                                      (mapv (fn [{:keys [title url]}]
                                                                              {:type :text
                                                                               :text (format "%s: %s" title url)})
                                                                            output))]
                                                        (when-let [pending-tool-use (get @pending-server-tool-uses* id)]
                                                          (add-to-history! pending-tool-use)
                                                          (swap! pending-server-tool-uses* dissoc id))
                                                        (add-to-history! {:role "server_tool_result"
                                                                          :content {:tool-use-id id
                                                                                    :raw-content raw-content}})
                                                        (tc/transition-tool-call! db* chat-ctx id :execution-end
                                                                                  {:origin :server
                                                                                   :name (get-in (tc/get-tool-call-state @db* chat-id id) [:name] "web_search")
                                                                                   :server :llm
                                                                                   :arguments {}
                                                                                   :error false
                                                                                   :outputs outputs
                                                                                   :total-time-ms total-time-ms
                                                                                   :progress-text "Generating"
                                                                                   :summary summary})
                                                        (tc/transition-tool-call! db* chat-ctx id :cleanup-finished
                                                                                  {:name (get-in (tc/get-tool-call-state @db* chat-id id) [:name] "web_search")}))
                                            nil)))
                :on-error (fn [{:keys [message exception] :as error-data}]
                            (let [{error-type :error/type} (llm-providers.errors/classify-error error-data)
                                  db @db*
                                  compacting? (or (get-in db [:chats chat-id :compacting?])
                                                  (get-in db [:chats chat-id :auto-compacting?]))]
                              (if (and (= :context-overflow error-type)
                                       (not compacting?)
                                       (not (:auto-compacted? chat-ctx)))
                                (do
                                  (logger/warn logger-tag "Context overflow detected, pruning tool results and auto-compacting"
                                               {:chat-id chat-id})
                                  (lifecycle/send-content! chat-ctx :system
                                                           {:type :text
                                                            :text "Context window exceeded. Auto-compacting conversation..."})
                                  (prune-tool-results! db* chat-id {})
                                  (trigger-auto-compact! chat-ctx all-tools user-messages))
                                (let [partial-text @received-msgs*
                                      transient-error? (contains? #{:overloaded :premature-stop} error-type)
                                      stopping? (identical? :stopping (get-in @db* [:chats chat-id :status]))
                                      can-auto-continue? (and (not stopping?)
                                                              (or transient-error?
                                                                  (string/includes? (or message "") "idle timeout"))
                                                              (not (:auto-continued? chat-ctx))
                                                              (not (:on-finished-side-effect chat-ctx))
                                                              (not compacting?))]
                                  (when compacting?
                                    (swap! db* update-in [:chats chat-id] dissoc :auto-compacting? :compacting?))
                                  (when-not (string/blank? partial-text)
                                    (add-to-history! {:role "assistant"
                                                      :content [{:type :text :text partial-text}]}))
                                  (if can-auto-continue?
                                    (do
                                      (logger/info logger-tag "Transient error during response, auto-continuing"
                                                   {:chat-id chat-id :error-type error-type})
                                      (lifecycle/send-content! chat-ctx :system
                                                               {:type :progress :state :running :text (str (or message "Connection interrupted") ", continuing...")})
                                      (swap! db* assoc-in [:chats chat-id :auto-compacting?] true)
                                      (lifecycle/finish-chat-prompt! :idle
                                                                     (assoc chat-ctx :on-finished-side-effect
                                                                            (fn []
                                                                              (swap! db* update-in [:chats chat-id] dissoc :auto-compacting?)
                                                                              (prompt-messages!
                                                                               [{:role "user"
                                                                                 :content [{:type :text
                                                                                            :text "Your previous response was interrupted mid-stream. Continue from where you left off, do not redo completed steps."}]}]
                                                                               :auto-continue
                                                                               (assoc chat-ctx :auto-continued? true))))))
                                    (do
                                      (when-not stopping?
                                        (lifecycle/send-content! chat-ctx :system {:type :text :text (str "\n\n" (or message (str "Error: " (or (ex-message exception) (.getName (class exception))))))}))
                                      (lifecycle/finish-chat-prompt! :idle (dissoc chat-ctx :on-finished-side-effect))))))))})
              (catch Exception e
                (when-not (:silent? (ex-data e))
                  (logger/error e)
                  (when-not (string/blank? @received-msgs*)
                    (add-to-history! {:role "assistant"
                                      :content [{:type :text :text @received-msgs*}]}))
                  (lifecycle/send-content! chat-ctx :system {:type :text :text (str "\n\n" "Error: " (or (ex-message e) (.getName (class e))))})
                  (lifecycle/finish-chat-prompt! :idle (dissoc chat-ctx :on-finished-side-effect))))
              (finally
                (when (and (= prompt-id (get-in @db* [:chats chat-id :prompt-id]))
                           (contains? #{:stopping :running} (get-in @db* [:chats chat-id :status])))
                  (swap! db* assoc-in [:chats chat-id :status] :idle)
                  ;; Only notify client if finish-chat-prompt! hasn't already run,
                  ;; otherwise the belated statusChanged causes duplicate finished handling.
                  (when-not (get-in @db* [:chats chat-id :prompt-finished?])
                    (messenger/chat-status-changed (:messenger chat-ctx) {:chat-id chat-id :status :idle}))
                  (db/update-workspaces-cache! @db* metrics))))))))))

(defn ^:private send-mcp-prompt!
  [{:keys [prompt args] :as _decision}
   {:keys [db*] :as chat-ctx}]
  (let [{:keys [arguments]} (first (filter #(= prompt (:name %)) (f.mcp/all-prompts @db*)))
        args-vals (zipmap (map :name arguments) args)
        {:keys [messages error-message]} (f.prompt/get-prompt! prompt args-vals @db*)]
    (cond
      error-message
      (do (lifecycle/send-content! chat-ctx
                                   :system
                                   {:type :text
                                    :text error-message})
          (lifecycle/finish-chat-prompt! :idle chat-ctx))

      (seq messages)
      (prompt-messages! messages :mcp-prompt chat-ctx)

      :else
      (do (lifecycle/send-content! chat-ctx
                                   :system
                                   {:type :text
                                    :text (format "No response from prompt '%s'." prompt)})
          (lifecycle/finish-chat-prompt! :idle chat-ctx)))))

(defn ^:private handle-command! [{:keys [command args]} chat-ctx]
  (try
    (let [{:keys [type on-finished-side-effect] :as result} (f.commands/handle-command! command args chat-ctx)]
      (case type
        :chat-messages (do
                         (when (:clear-before? result)
                           (messenger/chat-cleared (:messenger chat-ctx) {:chat-id (:chat-id chat-ctx) :messages true})
                           (messenger/chat-status-changed (:messenger chat-ctx) {:chat-id (:chat-id chat-ctx) :status :running}))
                         (doseq [[chat-id {:keys [messages title]}] (:chats result)]
                           (let [new-chat-ctx (assoc chat-ctx :chat-id chat-id)]
                             (send-chat-contents! messages new-chat-ctx)
                             (when title
                               (lifecycle/send-content! new-chat-ctx :system (assoc-some
                                                                              {:type :metadata}
                                                                              :title title)))))
                         (lifecycle/finish-chat-prompt! :idle chat-ctx))
        :new-chat-status (lifecycle/finish-chat-prompt! (:status result) chat-ctx)
        :send-prompt (let [prompt-contents (:prompt result)]
                       ;; Keep original slash command in :message for hooks (already in parent chat-ctx)
                       (prompt-messages! [{:role "user" :content prompt-contents}]
                                         :eca-command
                                         (assoc chat-ctx :on-finished-side-effect on-finished-side-effect)))
        nil))
    (catch Exception e
      (logger/error e)
      (lifecycle/send-content! chat-ctx :system {:type :text
                                                 :text (str "Error: " (ex-message e) "\n\nCheck ECA stderr for more details.")})
      (lifecycle/finish-chat-prompt! :idle (dissoc chat-ctx :on-finished-side-effect)))))

(defn ^:private prompt*
  [{:keys [model]}
   {:keys [chat-id contexts message agent agent-config db* messenger config metrics] :as base-chat-ctx}]
  (let [provided-chat-id chat-id
        ;; Snapshot DB to detect new/resumed chat BEFORE hooks mutate it
        [db0 _] (swap-vals! db* assoc-in [:chat-start-fired chat-id] true)
        existing-chat-before-prompt (get-in db0 [:chats chat-id])
        chat-start-fired? (get-in db0 [:chat-start-fired chat-id])
        has-messages? (seq (:messages existing-chat-before-prompt))
        resumed? (boolean (and (not chat-start-fired?)
                               provided-chat-id
                               has-messages?))
        ;; Trigger chatStart hook as early as possible so its additionalContext
        ;; is visible in build-chat-instructions and /prompt-show.
        _ (when-not chat-start-fired?
            ;; Wait for plugin resolution so plugin-defined hooks are available
            (config/await-plugins-resolved!)
            (let [config (config/all db0)
                  hook-results* (atom [])
                  hook-ctx {:messenger messenger :chat-id chat-id}]
              (f.hooks/trigger-if-matches! :chatStart
                                           (merge (f.hooks/base-hook-data db0)
                                                  {:chat-id chat-id
                                                   :resumed resumed?})
                                           {:on-before-action (partial lifecycle/notify-before-hook-action! hook-ctx)
                                            :on-after-action (fn [result]
                                                               (lifecycle/notify-after-hook-action! hook-ctx result)
                                                               (swap! hook-results* conj result))}
                                           db0
                                           config)
              ;; Collect additionalContext from all chatStart hooks and store
              ;; it as startup-context for this chat.
              (when-let [additional-contexts (seq (keep #(get-in % [:parsed :additionalContext]) @hook-results*))]
                (swap! db* assoc-in [:chats chat-id :startup-context]
                       (string/join "\n\n" additional-contexts)))
              ;; Mark chatStart as fired for this chat in this server run
              (swap! db* assoc-in [:chat-start-fired chat-id] true)))
        ;; Re-read DB after potential chatStart modifications
        db @db*
        ;; Respect explicit model; otherwise, if agent default is missing from
        ;; available models, fallback to deterministic default-model resolution.
        full-model (or model
                       (let [agent-default-model (:defaultModel agent-config)]
                         (if (and agent-default-model
                                  (contains? (:models db) agent-default-model))
                           agent-default-model
                           (default-model db config))))
        rules (f.rules/all config (:workspace-folders db))
        all-tools (f.tools/all-tools chat-id agent @db* config)
        skills (->> (f.skills/all config (:workspace-folders db))
                    (remove
                     (fn [skill]
                       (= :deny (f.tools/approval all-tools
                                                  {:server {:name "eca"} :name "skill"}
                                                  {"name" (:name skill)}
                                                  db
                                                  config
                                                  agent)))))
        _ (when (seq contexts)
            (lifecycle/send-content! {:messenger messenger :chat-id chat-id} :system {:type :progress
                                                                                      :state :running
                                                                                      :text "Parsing given context"}))
        refined-contexts (concat
                          (f.context/agents-file-contexts db)
                          (f.context/raw-contexts->refined contexts db))
        repo-map* (delay (f.index/repo-map db config {:as-string? true}))
        prompt-cache (get-in db [:chats chat-id :prompt-cache])
        cached-static (when (= (:agent prompt-cache) agent)
                        (:static prompt-cache))
        instructions (if cached-static
                       {:static cached-static
                        :dynamic (f.prompt/build-dynamic-instructions refined-contexts db)}
                       (let [result (f.prompt/build-chat-instructions
                                     refined-contexts rules skills repo-map*
                                     agent config chat-id all-tools db)]
                         (swap! db* update-in [:chats chat-id :prompt-cache]
                                assoc :static (:static result) :agent agent)
                         result))
        image-contents (->> refined-contexts
                            (filter #(= :image (:type %))))
        expanded-prompt-contexts (when-let [contexts-str (some-> (f.context/contexts-str-from-prompt message db)
                                                                 seq
                                                                 (f.prompt/contexts-str repo-map* nil))]
                                   [{:type :text :text contexts-str}])
        user-messages [{:role "user" :content (vec (concat [{:type :text :text message}]
                                                           expanded-prompt-contexts
                                                           image-contents))}]
        [provider model] (when full-model (shared/full-model->provider+model full-model))
        chat-ctx (merge base-chat-ctx
                        {:instructions instructions
                         :user-messages user-messages
                         :full-model full-model
                         :provider provider
                         :model model
                         :messenger messenger})
        decision (message->decision message db config)]
    ;; Show original prompt to user, but LLM receives the modified version
    (lifecycle/send-content! chat-ctx :user {:type :text
                                             :content-id (:user-content-id chat-ctx)
                                             :text (str message "\n")})
    ;; Clear prompt-finished? so finish-chat-prompt! can properly terminate
    ;; this prompt cycle. prompt-messages! already does this for regular
    ;; prompts, but commands and mcp-prompts go through different paths.
    (swap! db* update-in [:chats chat-id] dissoc :prompt-finished?)
    (case (:type decision)
      :mcp-prompt (send-mcp-prompt! decision chat-ctx)
      :eca-command (handle-command! decision chat-ctx)
      :prompt-message (prompt-messages! user-messages :prompt-message chat-ctx))
    (metrics/count-up! "prompt-received"
                       {:full-model full-model
                        :agent agent}
                       metrics)
    {:chat-id chat-id
     :model full-model
     :status :prompting}))

(defn prompt
  [{:keys [message agent behavior chat-id contexts variant trust] :as params} db* messenger config metrics]
  (let [raw-agent (or agent
                      behavior ;; backward compat: accept old 'behavior' param
                      (-> config :chat :defaultAgent) ;; legacy
                      (-> config :defaultAgent))
        chat-id (or chat-id
                    (let [new-id (str (random-uuid))]
                      (swap! db* assoc-in [:chats new-id] {:id new-id})
                      new-id))
        selected-agent (config/validate-agent-name raw-agent config)
        agent-config (get-in config [:agent selected-agent])
        base-chat-ctx (assoc-some {:metrics metrics
                                   :config config
                                   :contexts contexts
                                   :db* db*
                                   :messenger messenger
                                   :user-content-id (lifecycle/new-content-id)
                                   :message (string/trim message)
                                   :chat-id chat-id
                                   :agent selected-agent
                                   :agent-config agent-config
                                   :trust trust
                                   :variant (or variant (:variant agent-config))}
                                  :parent-chat-id (get-in @db* [:chats chat-id :parent-chat-id]))
        _ (when (some? trust)
            (swap! db* assoc-in [:chats chat-id :trust] trust))]
    (try
      (prompt* params base-chat-ctx)
      (catch Exception e
        (logger/error e)
        (lifecycle/send-content! base-chat-ctx :system {:type :text
                                                        :text (str "Error: " (ex-message e) "\n\nCheck ECA stderr for more details.")})
        (lifecycle/finish-chat-prompt! :idle (dissoc base-chat-ctx :on-finished-side-effect))
        {:chat-id chat-id
         :model "error"
         :status :error}))))

(defn tool-call-approve [{:keys [chat-id tool-call-id save]} db* messenger metrics]
  (let [chat-ctx {:chat-id chat-id
                  :db* db*
                  :metrics metrics
                  :messenger messenger}]
    (tc/transition-tool-call! db* chat-ctx tool-call-id :user-approve
                              {:reason {:code :user-choice-allow
                                        :text "Tool call allowed by user choice"}})
    (when (= "session" save)
      (let [tool-call-name (get-in @db* [:chats chat-id :tool-calls tool-call-id :name])]
        (swap! db* assoc-in [:tool-calls tool-call-name :remember-to-approve?] true)))))

(defn tool-call-reject [{:keys [chat-id tool-call-id]} db* messenger metrics]
  (let [chat-ctx {:chat-id chat-id
                  :db* db*
                  :metrics metrics
                  :messenger messenger}]
    (tc/transition-tool-call! db* chat-ctx tool-call-id :user-reject
                              {:reason {:code :user-choice-deny
                                        :text "Tool call rejected by user choice"}})))

(defn query-context
  [{:keys [query contexts chat-id]}
   db*
   config]
  {:chat-id chat-id
   :contexts (set/difference (set (f.context/all-contexts query false db* config))
                             (set contexts))})

(defn query-files
  [{:keys [query chat-id]}
   db*
   config]
  {:chat-id chat-id
   :files (set (f.context/all-contexts query true db* config))})

(defn query-commands
  [{:keys [query chat-id]}
   db*
   config]
  (let [query (string/lower-case query)
        commands (f.commands/all-commands @db* config)
        commands (if (string/blank? query)
                   commands
                   (filter #(or (some-> (:name %) string/lower-case (string/includes? query))
                                (some-> (:description %) string/lower-case (string/includes? query)))
                           commands))]
    {:chat-id chat-id
     :commands commands}))

(defn prompt-steer
  [{:keys [chat-id message]} db*]
  (when (and (string? message)
             (not (string/blank? message))
             (identical? :running (get-in @db* [:chats chat-id :status])))
    (logger/info logger-tag "Steer message received" {:chat-id chat-id})
    (swap! db* update-in [:chats chat-id :steer-message]
           (fn [existing] (if existing (str existing "\n" message) message)))))

(defn prompt-stop
  ([params db* messenger metrics]
   (prompt-stop params db* messenger metrics {}))
  ([{:keys [chat-id]} db* messenger metrics {:keys [silent?]}]
   (when (identical? :running (get-in @db* [:chats chat-id :status]))
     ;; Set :stopping immediately to prevent race with stream callbacks
     ;; that check status via assert-chat-not-stopped! or cancelled?
     (swap! db* assoc-in [:chats chat-id :status] :stopping)
     (let [chat-ctx {:chat-id chat-id
                     :db* db*
                     :metrics metrics
                     :messenger messenger
                     :parent-chat-id (get-in @db* [:chats chat-id :parent-chat-id])}]
       (when-not silent?
         (lifecycle/send-content! chat-ctx :system {:type :text
                                                    :text "\nPrompt stopped"}))

       ;; Handle each active tool call
       (doseq [[tool-call-id _] (tc/get-active-tool-calls @db* chat-id)]
         (tc/transition-tool-call! db* chat-ctx tool-call-id :stop-requested
                                   {:reason {:code :user-prompt-stop
                                             :text "Tool call rejected because of user prompt stop"}}))
       ;; Clear compacting flags so finish-chat-prompt! isn't blocked
       (swap! db* update-in [:chats chat-id] dissoc :auto-compacting? :compacting?)
       (lifecycle/finish-chat-prompt! :stopping (dissoc chat-ctx :on-finished-side-effect))))))

(defn delete-chat
  [{:keys [chat-id]} db* messenger config metrics]
  (when-let [chat (get-in @db* [:chats chat-id])]
    ;; Trigger chatEnd hook BEFORE deleting (chat still exists in cache)
    (f.hooks/trigger-if-matches! :chatEnd
                                 (merge (f.hooks/base-hook-data @db*)
                                        {:chat-id chat-id
                                         :title (:title chat)
                                         :message-count (count (:messages chat))})
                                 {}
                                 @db*
                                 config))
  ;; Delete chat from memory
  (swap! db* update :chats dissoc chat-id)
  (messenger/chat-deleted messenger {:chat-id chat-id})
  ;; Save updated cache (without this chat)
  (db/update-workspaces-cache! @db* metrics))

(defn clear-chat
  "Clear specific aspects of a chat. Currently supports clearing :messages."
  [{:keys [chat-id messages]} db* messenger metrics]
  (when (get-in @db* [:chats chat-id])
    (swap! db* update-in [:chats chat-id]
           (fn [chat]
             (cond-> chat
               messages (-> (assoc :messages [])
                            (dissoc :tool-calls :last-api :usage :task)))))
    (messenger/chat-cleared messenger {:chat-id chat-id :messages messages})
    (db/update-workspaces-cache! @db* metrics)))

(defn update-chat
  "Update chat metadata like title and trust.
   Broadcasts changes to all connected clients.
   Marks the title as custom to suppress automatic re-titling.
   Trust changes apply immediately to subsequent tool calls in the active prompt."
  [{:keys [chat-id title trust]} db* messenger metrics]
  (when (get-in @db* [:chats chat-id])
    (when (some? trust)
      (swap! db* assoc-in [:chats chat-id :trust] trust))
    (when title
      (let [title (sanitize-title title)]
        (swap! db* assoc-in [:chats chat-id :title] title)
        (swap! db* assoc-in [:chats chat-id :title-custom?] true)
        (messenger/chat-content-received messenger
                                         {:chat-id chat-id
                                          :role    "system"
                                          :content {:type :metadata :title title}})
        (db/update-workspaces-cache! @db* metrics))))
  {})

(defn rollback-chat
  "Remove messages from chat in db until content-id matches.
   Then notify to clear chat and then the kept messages."
  [{:keys [chat-id content-id include]} db* messenger]
  (let [include (if (seq include)
                  (set include)
                  ;; backwards compatibility
                  #{"messages" "tools"})
        all-messages (get-in @db* [:chats chat-id :messages])
        tool-calls (get-in @db* [:chats chat-id :tool-calls])
        new-messages (when (contains? include "messages")
                       (vec (take-while #(not= (:content-id %) content-id) all-messages)))
        removed-messages (when (contains? include "tools")
                           (vec (drop-while #(not= (:content-id %) content-id) all-messages)))
        rollback-changes (->> removed-messages
                              (filter #(= "tool_call_output" (:role %)))
                              (keep #(get-in tool-calls [(:id (:content %)) :rollback-changes]))
                              flatten
                              reverse)]
    (doseq [{:keys [path content]} rollback-changes]
      (logger/info (format "Rolling back change for '%s' to content: '%s'" path content))
      (if content
        (spit path content)
        (io/delete-file path true)))
    (when new-messages
      (swap! db* assoc-in [:chats chat-id :messages] new-messages)
      (messenger/chat-cleared
       messenger
       {:chat-id chat-id
        :messages true})
      (send-chat-contents!
       new-messages
       {:chat-id chat-id
        :db* db*
        :messenger messenger}))
    {}))


(defn ^:private find-last-message-idx
  "Find the last message index matching content-id by checking both
   :content-id (user messages) and [:content :id] (tool calls, etc)."
  [messages content-id]
  (loop [i (dec (count messages))]
    (cond
      (neg? i) nil
      (let [msg (messages i)]
        (or (= content-id (:content-id msg))
            (= content-id (get-in msg [:content :id])))) i
      :else (recur (dec i)))))

(defn add-flag
  "Add a named flag after the message identified by content-id.
   Searches both :content-id and [:content :id] to support placement
   after any message type (user, tool call, reason, etc).
   Clears and replays the chat to render the flag at the correct position."
  [{:keys [chat-id content-id text]} db* messenger metrics]
  (let [messages (vec (get-in @db* [:chats chat-id :messages]))
        insert-idx (find-last-message-idx messages content-id)]
    (when insert-idx
      (let [flag-id (str (random-uuid))
            flag-msg {:role "flag" :content {:text text} :content-id flag-id}
            insert-after (inc insert-idx)
            new-messages (into (subvec messages 0 insert-after)
                               (cons flag-msg (subvec messages insert-after)))]
        (swap! db* assoc-in [:chats chat-id :messages] new-messages)
        (db/update-workspaces-cache! @db* metrics)
        (messenger/chat-cleared messenger {:chat-id chat-id :messages true})
        (send-chat-contents! new-messages {:chat-id chat-id :db* db* :messenger messenger})))
    {}))

(defn remove-flag
  "Remove a flag message identified by content-id from the chat."
  [{:keys [chat-id content-id]} db* metrics]
  (when-let [messages (get-in @db* [:chats chat-id :messages])]
    (let [new-messages (vec (remove #(and (= "flag" (:role %))
                                          (= content-id (:content-id %)))
                                    messages))]
      (when (not= (count new-messages) (count messages))
        (swap! db* assoc-in [:chats chat-id :messages] new-messages)
        (db/update-workspaces-cache! @db* metrics))))
  {})

(defn fork-chat
  "Fork the chat creating a new chat with messages up to and including
   the message identified by content-id."
  [{:keys [chat-id content-id]} db* messenger metrics]
  (let [chat (get-in @db* [:chats chat-id])
        messages (vec (:messages chat))
        target-idx (find-last-message-idx messages content-id)]
    (when target-idx
      (let [new-id (str (random-uuid))
            now (System/currentTimeMillis)
            new-title (f.commands/fork-title (:title chat))
            kept-messages (subvec messages 0 (inc target-idx))
            new-chat {:id new-id
                      :title new-title
                      :status :idle
                      :created-at now
                      :updated-at now
                      :model (:model chat)
                      :last-api (:last-api chat)
                      :messages kept-messages
                      :prompt-finished? true}]
        (swap! db* assoc-in [:chats new-id] new-chat)
        (db/update-workspaces-cache! @db* metrics)
        (messenger/chat-opened messenger {:chat-id new-id :title new-title})
        (send-chat-contents! kept-messages {:chat-id new-id :db* db* :messenger messenger})
        (lifecycle/send-content! {:messenger messenger :chat-id new-id}
                                 :system
                                 (assoc-some {:type :metadata} :title new-title))
        (lifecycle/send-content! {:messenger messenger :chat-id chat-id}
                                 :system
                                 {:type :text :text (str "Chat forked to: " new-title)})))
    {}))