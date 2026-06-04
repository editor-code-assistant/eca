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

(defn ^:private prompt-cache-key
  "Builds a provider-agnostic prompt cache key.
   OpenAI's Responses API sends it as `prompt_cache_key`; other providers
   currently ignore it. Scoping by agent prevents cache hits across
   agent switches within the same user session."
  [agent]
  (str (System/getProperty "user.name") "@ECA"
       (when (not-empty agent) (str "/" agent))))

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
     "assistant") (let [text-content (reduce
                                      (fn [m content]
                                        (case (:type content)
                                          :text (assoc m
                                                       :type :text
                                                       :text (str (:text m) "\n" (:text content)))
                                          m))
                                      (assoc-some {} :content-id content-id)
                                      message-content)
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
    "tool_call_output" (let [contents (:contents (:output message-content))
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

(defn ^:private resolve-full-model
  "Resolve the full model id for a prompt response and LLM request."
  [requested-model db chat-id agent-config config]
  (or requested-model
      (let [stored-model (get-in db [:chats chat-id :model])
            agent-default-model (:defaultModel agent-config)]
        (cond
          (and stored-model
               (contains? (:models db) stored-model))
          stored-model

          (and agent-default-model
               (contains? (:models db) agent-default-model))
          agent-default-model

          :else
          (default-model db config)))))

(defn ^:private update-pre-request-state
  "Pure function to compute new state from hook result."
  [{:keys [final-prompt additional-contexts stop-turn? blocked? blocked-reasons stop-reason stop-hook-name]}
   {:keys [parsed exit]}
   action-name]
  (let [replaced-prompt (shared/not-blank (get parsed "replacedPrompt"))
        additional-context (shared/not-blank (get parsed "additionalContext"))
        success? (= 0 exit)
        stop-turn-result? (and success? (false? (get parsed "continue" true)))]
    {:final-prompt (if (and replaced-prompt success?)
                     replaced-prompt
                     final-prompt)
     :additional-contexts (if (and additional-context success?)
                            (conj additional-contexts
                                  {:hook-name action-name :content additional-context})
                            additional-contexts)
     :stop-turn? (or stop-turn? stop-turn-result?)
     :blocked? blocked?
     :blocked-reasons blocked-reasons
     :stop-reason (if stop-turn-result?
                    (or stop-reason (shared/not-blank (get parsed "stopReason")))
                    stop-reason)
     :stop-hook-name (if stop-turn-result?
                       (or stop-hook-name action-name)
                       stop-hook-name)}))

(defn ^:private pre-request-block-reason
  "Stderr text of a preRequest exit-2 block, or nil when empty. The user-facing
   wrapper (lifecycle/request-blocked-by-hook-message) supplies the fallback."
  [{:keys [raw-error]}]
  (some-> raw-error str string/trim not-empty))

(defn ^:private finish-blocked-or-stopped-pre-request!
  [chat-ctx {:keys [blocked? stop-turn? stop-reason stop-hook-name blocked-reasons blocked-hook-name]}]
  (when (or stop-turn? blocked?)
    (cond
      stop-turn? (lifecycle/send-turn-stopped-by-hook! chat-ctx stop-hook-name stop-reason)
      blocked?   (if (seq blocked-reasons)
                   (doseq [reason blocked-reasons]
                     (lifecycle/send-content! chat-ctx :system {:type :text :text reason}))
                   ;; Visible hooks already showed their stderr in their block; just name the hook.
                   (lifecycle/send-content! chat-ctx :system
                                            {:type :text
                                             :text (lifecycle/request-blocked-by-hook-message blocked-hook-name nil)})))
    (lifecycle/finish-chat-prompt-stopped! :idle chat-ctx)))

(defn ^:private run-pre-request-action!
  "Run a single preRequest hook action, updating the accumulator state.

  State is a map:
  - :final-prompt
  - :additional-contexts
  - :stop-turn? (true when any hook requests current-turn stop via continue:false)
  - :stop-reason
  - :blocked? (true when any hook blocks the request via exit 2)
  - :blocked-reasons"
  [db chat-ctx hook hook-name idx action state]
  (if (:stop-turn? state)
    state
    (let [id (str (random-uuid))
          action-type (:type action)
          action-name (if (> (count (:actions hook)) 1)
                        (str hook-name "-" (inc idx))
                        hook-name)
          visible? (get hook :visible true)]
      (lifecycle/notify-before-hook-action! chat-ctx {:id id
                                                      :visible? visible?
                                                      :name action-name
                                                      :type action-type})
      ;; Run the hook action
      (if-let [result (f.hooks/run-hook-action! action
                                                action-name
                                                :preRequest
                                                (merge (f.hooks/chat-hook-data db chat-ctx)
                                                       {:prompt (:final-prompt state)})
                                                db)]
        (let [{:keys [raw-error exit]} result
              exit-2? (= f.hooks/hook-rejection-exit-code exit)]
          ;; Notify after action
          (lifecycle/notify-after-hook-action! chat-ctx (merge result
                                                               {:id id
                                                                :name action-name
                                                                :type action-type
                                                                :visible? visible?
                                                                :status exit
                                                                :hook-type :preRequest
                                                                :error raw-error}))
          ;; Exit 2 blocks the prompt but does not stop remaining preRequest
          ;; hooks; only successful continue:false short-circuits processing.
          ;; The actual prompt finish happens once after reduction completes.
          ;; Only collect stderr from INVISIBLE hooks; visible hooks already
          ;; surface stderr through hookActionFinished :error, so duplicating
          ;; it as a separate chat message would be noisy.
          (if exit-2?
            (cond-> (assoc state :blocked? true)
              ;; Remember the first blocking hook so the user-facing message can
              ;; name it even for visible hooks (consistent with turn-stopped).
              (not (:blocked-hook-name state)) (assoc :blocked-hook-name action-name)
              (not visible?) (update :blocked-reasons conj
                                     (lifecycle/request-blocked-by-hook-message
                                      action-name
                                      (pre-request-block-reason result))))
            (update-pre-request-state state
                                      result
                                      action-name)))
        ;; No result from action
        (do
          (lifecycle/notify-after-hook-action! chat-ctx {:id id
                                                         :name action-name
                                                         :visible? visible?
                                                         :type action-type
                                                         :hook-type :preRequest
                                                         :exit 1
                                                         :status 1})
          state)))))

(defn ^:private run-pre-request-hook!
  "Run all actions for a single preRequest hook, threading state."
  [db chat-ctx state [hook-name hook]]
  (reduce
   (fn [s [idx action]]
     (if (:stop-turn? s)
       (reduced s)
       (run-pre-request-action! db chat-ctx hook (name hook-name) idx action s)))
   state
   (map-indexed vector (:actions hook))))

(defn ^:private run-pre-request-hooks!
  "Run preRequest hooks with chaining support.

  Returns a map with:
  - :final-prompt
  - :additional-contexts (vector of {:hook-name name :content context})
  - :stop-turn? (true when any hook requests current-turn stop via continue:false)
  - :stop-reason
  - :blocked? (true when any hook blocks the request via exit 2)
  - :blocked-reasons"
  [{:keys [db* config message] :as chat-ctx}]
  (let [db @db*]
    (reduce
     (fn [state hook-entry]
       (if (:stop-turn? state)
         (reduced state)
         (run-pre-request-hook! db chat-ctx state hook-entry)))
     {:final-prompt message
      :additional-contexts []
      :stop-turn? false
      :blocked? false
      :blocked-reasons []
      :blocked-hook-name nil
      :stop-reason nil
      :stop-hook-name nil}
     (->> (:hooks config)
          ;; This function owns preRequest chaining, so it must select legacy
          ;; prePrompt hooks itself; hook-matches? only normalizes legacy types
          ;; for the generic trigger-if-matches! path.
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
  (let [;; Build a name->command map. `into {}` keeps the last entry on key
        ;; collisions, and `all-commands` places plugin/custom entries after
        ;; MCP prompts, so a plugin command wins over an MCP prompt with the
        ;; same prefixed name.
        cmds-by-name (into {} (map (juxt :name identity)) (f.commands/all-commands db config))
        slash? (string/starts-with? message "/")
        possible-command (when slash? (subs message 1))
        [command-name & args] (when possible-command
                                (let [toks (tokenize-args possible-command)] (if (seq toks) toks [""])))
        args (vec args)
        matched (get cmds-by-name command-name)]
    (cond
      (= :mcpPrompt (:type matched))
      (let [[server prompt] (string/split command-name #":" 2)]
        {:type :mcp-prompt
         :server server
         :prompt prompt
         :args args})

      matched
      {:type :eca-command
       :command command-name
       :args args}

      :else
      {:type :prompt-message
       :message message})))

(defn ^:private truncated-response?
  "Returns true when the response text shows signs of being truncated mid-stream.
   Checks for unclosed code fences (odd number of ``` markers at line start)."
  [^String text]
  (when-not (string/blank? text)
    (odd? (count (re-seq #"(?m)^```" text)))))

(defn ^:private auto-compact-finished-side-effect!
  "on-finished-side-effect for auto-compaction: clear the auto-compacting flag,
   apply the compact side effects and run postCompact hooks. When a postCompact
   hook stops the turn (continue:false), surface the reason and finish here
   (postRequest hooks were already skipped while auto-compacting), returning
   {:stop-after-finish? true} so the resume continuation does not fire."
  [{:keys [db* chat-id] :as chat-ctx}]
  (swap! db* update-in [:chats chat-id] dissoc :auto-compacting?)
  (let [{:keys [stop-turn? stop-reason stop-hook-name]} (lifecycle/complete-compact! chat-ctx "auto")]
    (when stop-turn?
      (lifecycle/send-turn-stopped-by-hook! chat-ctx stop-hook-name stop-reason)
      (lifecycle/finish-chat-prompt-stopped! :idle chat-ctx))
    {:stop-after-finish? stop-turn?}))

(defn ^:private resume-after-auto-compact!
  "on-after-finish! for auto-compaction: resume the original user task."
  [chat-ctx user-messages]
  (prompt-messages!
   (concat [{:role "user"
             :content [{:type :text
                        :text "Continue with the task. The previous user request was:"}]}]
           user-messages)
   :auto-compact
   (assoc chat-ctx :auto-compacted? true)))

(defn ^:private trigger-auto-compact!
  "Trigger auto-compact: send compact prompt, then resume the original task."
  [{:keys [db* config chat-id agent] :as chat-ctx}
   all-tools
   user-messages]
  (let [{:keys [blocked? reason hook-name stop-turn?]} (lifecycle/run-pre-compact-hooks! chat-ctx "auto" "")]
    (if blocked?
      (do
        (logger/info logger-tag "Auto-compaction blocked by hook" {:chat-id chat-id
                                                                   :stop-turn? stop-turn?})
        ;; continue:false stops the turn (reason from stopReason, hook-name for
        ;; provenance); exit 2 blocks compaction only, with no user-facing reason.
        (if stop-turn?
          (do (lifecycle/send-turn-stopped-by-hook! chat-ctx hook-name reason)
              (lifecycle/finish-chat-prompt-stopped! :idle chat-ctx))
          (do (lifecycle/send-content! chat-ctx :system {:type :text :text (lifecycle/compaction-blocked-by-hook-message hook-name)})
              (prompt-messages! user-messages
                                :auto-compact-blocked
                                (assoc chat-ctx :auto-compacted? true))))
        nil)
      (let [db             @db*
            compact-prompt (f.prompt/compact-prompt nil all-tools agent config db)]
        (logger/info logger-tag "Auto-compacting chat" {:chat-id chat-id})
        (swap! db* assoc-in [:chats chat-id :auto-compacting?] true)
        (prompt-messages!
         [{:role "user" :content "Compact the chat following the template:"}
          {:role "user" :content compact-prompt}]
         :auto-compact
         (assoc chat-ctx
                :on-finished-side-effect #(auto-compact-finished-side-effect! chat-ctx)
                :on-after-finish! #(resume-after-auto-compact! chat-ctx user-messages)))
        nil))))

(defn ^:private log-api-switch!
  "Log when the user swaps to a model whose api differs from the chat's
   `:last-api`. Cross-api history reconciliation is handled by
   `eca.llm-api/sanitize-past-messages-for-api`, which drops entries whose
   opaque ids the new provider would reject and triggers a chat-visible
   notice via `:on-history-sanitized`. This used to throw outright (#209)."
  [db chat-id provider model config]
  (let [current-api (:api (llm-api/provider->api-handler provider model config))
        last-api (get-in db [:chats chat-id :last-api])]
    (when (and last-api current-api (not= last-api current-api))
      (logger/info logger-tag
                   (format "Model swap detected: last-api=%s current-api=%s"
                           (name last-api) (name current-api))
                   {:chat-id chat-id
                    :last-api last-api
                    :current-api current-api
                    :provider provider
                    :model model}))))

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
   For each notification, adds a user message to history so the LLM is aware.
   Marks each job as :notified true and evicts them from the registry."
  [chat-id db* add-to-history!]
  (let [notifications* (volatile! nil)]
    (swap! db* (fn [db]
                 (if-let [notifs (seq (get-in db [:chats chat-id :pending-job-notifications]))]
                   (do (vreset! notifications* (vec notifs))
                       (assoc-in db [:chats chat-id :pending-job-notifications] []))
                   db)))
    (when-let [notifications @notifications*]
      (doseq [{:keys [job-id status exit-code label output-tail]} notifications]
        (let [text (str "Background job " job-id " (`" label "`) "
                        (name status) " with exit code " exit-code "."
                        (when (seq output-tail)
                          (str "\nLast 20 lines of output:\n"
                               (string/join "\n" output-tail))))
              user-message {:role "user"
                            :content [{:type :text :text text}]
                            :content-id (str (random-uuid))}]
          (add-to-history! user-message)))
      (doseq [{:keys [job-id]} notifications]
        (swap! bg/registry* assoc-in [:jobs job-id :notified] true))
      (bg/evict-notified-jobs!))))

(defn ^:private message-text-content
  "Extract plain text from a chat message's :content, ignoring non-text parts."
  [{:keys [content]}]
  (let [parts (cond
                (string? content)
                [content]

                (sequential? content)
                (into [] (keep (fn [part]
                                 (when (and (map? part) (#{:text "text"} (:type part)))
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

(defn ^:private sanitize-title
  "Clean up a chat title: take first meaningful line, strip control chars,
   markdown header prefixes, collapse whitespace, and truncate to 40 chars.

   If the first non-blank line is a bare markdown header with nothing else
   (e.g. '## Understand' — a planning-mode section the title model sometimes
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
                        (let [{:keys [final-prompt additional-contexts stop-turn? blocked?] :as pre-request-state}
                              (run-pre-request-hooks! (assoc chat-ctx :message original-text))]
                          (cond
                            (or stop-turn? blocked?) (do (finish-blocked-or-stopped-pre-request! chat-ctx pre-request-state) nil)
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
                                                        (reduce (fn [msgs {:keys [content]}]
                                                                  (update-in msgs [context-idx :content]
                                                                             #(conj (if (string? %)
                                                                                      [{:type :text :text %}]
                                                                                      (vec %))
                                                                                    {:type :text
                                                                                     :text (lifecycle/wrap-additional-context content)})))
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
      ;; Persist the variant the chat is currently using so subsequent
      ;; chat/selectedModelChanged, chat/selectedAgentChanged and resume
      ;; flows can read it back. `chat-ctx :variant` is `(or request-variant
      ;; (:variant agent-config))`, so it tracks whatever variant the LLM
      ;; call is actually using on this prompt, and overwriting on every
      ;; prompt keeps the record in sync with the user's current pick.
      (swap! db* assoc-in [:chats chat-id :variant] (:variant chat-ctx))
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
            ;; Origin api of every reason/tool_call/server_tool_* entry written
            ;; during this prompt. Used by the cross-api history sanitizer in
            ;; eca.llm-api so a later swap to a different model can drop entries
            ;; whose opaque ids (Anthropic signatures, OpenAI rs_*/encrypted_content,
            ;; toolu_*/call_* tool ids) the new provider would reject. #209
            current-api (:api (llm-api/provider->api-handler provider model config))
            add-to-history! (fn [{:keys [role content] :as msg}]
                              (let [with-ts (update msg :created-at #(or % (System/currentTimeMillis)))
                                    tagged (if (and current-api
                                                    (map? content)
                                                    (#{"reason" "tool_call" "tool_call_output"
                                                       "server_tool_use" "server_tool_result"} role))
                                             (assoc with-ts :content (assoc content :api current-api))
                                             with-ts)]
                                (swap! db* update-in [:chats chat-id :messages] (fnil conj []) tagged)
                                ;; Persist after meaningful history mutations so a
                                ;; long-running chat is recoverable mid-loop even if
                                ;; ECA dies (crash, kill, host reboot) before the
                                ;; end-of-prompt save runs. tool_call and
                                ;; server_tool_use are skipped because they are
                                ;; always paired with the corresponding *_output /
                                ;; *_result entry appended right after, which
                                ;; triggers the save in their place.
                                (when-not (#{"tool_call" "server_tool_use"} role)
                                  (db/update-workspaces-cache! @db* metrics))))
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
        (log-api-switch! db chat-id provider model config)
        (when generate-title?
          ;; On retitle (3rd prompt), flatten the conversation into a single
          ;; user message instead of replaying it as role-structured past-messages.
          ;; This prevents the title model (notably Opus) from mimicking the prior
          ;; assistant's style and emitting section-header titles like "Understand".
          (let [title-user-messages
                (if retitle?
                  (let [history (->> (get-in db [:chats chat-id :messages] [])
                                     shared/messages-after-last-compact-marker
                                     (filterv #(contains? #{"user" "assistant"} (:role %))))
                        transcript (conversation->title-transcript
                                    (into (vec history) user-messages))]
                    [{:role "user"
                      :content [{:type :text
                                 :text (str "Summarize the following conversation as a thread title.\n"
                                            "Follow the rules from the system prompt. Output only the title.\n\n"
                                            "Conversation:\n"
                                            transcript)}]}])
                  user-messages)]
            (future* config
              (when-let [{:keys [output-text]} (llm-api/sync-prompt!
                                                {:provider provider
                                                 :model model
                                                 :model-capabilities
                                                 (assoc model-capabilities :reason? false :tools false :web-search false)
                                                 :instructions (f.prompt/chat-title-prompt agent config)
                                                 :past-messages nil
                                                 :user-messages title-user-messages
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
                :prompt-cache-key (prompt-cache-key agent)
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
                :on-history-sanitized (fn [{:keys [dropped-count dropped-apis target-api]}]
                                        (let [from-label (->> dropped-apis
                                                              (remove nil?)
                                                              (map name)
                                                              sort
                                                              (string/join ", "))]
                                          (logger/info logger-tag
                                                       (format "Dropped %d cross-provider history entries on switch to %s"
                                                               dropped-count (some-> target-api name))
                                                       {:chat-id chat-id
                                                        :dropped-count dropped-count
                                                        :dropped-apis dropped-apis})
                                          (lifecycle/send-content!
                                           chat-ctx :system
                                           {:type :text
                                            :text (format "Note: dropped %d history %s from a previous provider (%s) because the current model (%s) cannot reuse its opaque ids. Reasoning context is lost across providers; tool calls were also removed if they originated elsewhere."
                                                          dropped-count
                                                          (if (= 1 dropped-count) "entry" "entries")
                                                          (if (seq from-label) from-label "unknown")
                                                          (some-> target-api name))})))
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
                                         :image (let [client-content {:type :image
                                                                      :media-type (:media-type msg)
                                                                      :base64 (:base64 msg)}
                                                      history-content (assoc-some
                                                                       {:media-type (:media-type msg)
                                                                        :base64 (:base64 msg)}
                                                                       :id (:id msg))]
                                                  ;; Provider normalize-messages converts this role back to a user-role image for replay.
                                                  (add-to-history! {:role "image_generation_call"
                                                                    :content history-content})
                                                  (lifecycle/send-content! chat-ctx :assistant client-content))
                                         :limit-reached (do (lifecycle/send-content!
                                                             chat-ctx
                                                             :system
                                                             {:type :text
                                                              :text (str "API limit reached. Tokens: "
                                                                         (json/generate-string (:tokens msg)))})
                                                            (swap! db* update-in [:chats chat-id] dissoc :auto-compacting? :compacting?)
                                                            (lifecycle/finish-chat-prompt-stopped! :idle chat-ctx))
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
                                                            (not (or (:on-finished-side-effect chat-ctx)
                                                                     (:on-after-finish! chat-ctx))))
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
                                                        (assoc chat-ctx
                                                               :on-finished-side-effect
                                                               (fn []
                                                                 (swap! db* update-in [:chats chat-id] dissoc :auto-compacting?))
                                                               :on-after-finish!
                                                               (fn []
                                                                 (prompt-messages!
                                                                  [{:role "user"
                                                                    :content [{:type :text
                                                                               :text "Your previous response was interrupted mid-stream. Continue from where you left off, do not redo completed steps."}]}]
                                                                  :auto-continue
                                                                  (assoc chat-ctx :auto-continued? true))))))
                                                     (lifecycle/finish-chat-prompt!
                                                      :idle
                                                      (assoc-some chat-ctx :response (some-> response-text string/trim not-empty)))))))
                :on-prepare-tool-call (fn [{:keys [id full-name arguments-text]}]
                                        (lifecycle/assert-chat-not-stopped! chat-ctx)
                                        (let [tool (f.tools/resolve-tool full-name all-tools)
                                              resolved-full-name (or (:full-name tool) full-name)]
                                          (when-not tool
                                            (logger/warn logger-tag "Tool not found for prepare"
                                                         {:full-name full-name
                                                          :available-tools (mapv :full-name all-tools)}))
                                          (tc/transition-tool-call! db* chat-ctx id :tool-prepare
                                                                    {:name (or (:name tool) full-name)
                                                                     :server (:name (:server tool))
                                                                     :full-name resolved-full-name
                                                                     :origin (or (:origin tool) :unknown)
                                                                     :arguments-text arguments-text
                                                                     :summary (f.tools/tool-call-summary all-tools resolved-full-name nil config @db*)})))
                :on-tools-called (tc/on-tools-called!
                                  (assoc chat-ctx :continue-fn
                                         (fn [tc-all-tools tc-user-messages]
                                           (if (get-in @db* [:chats chat-id :compact-done?])
                                             ;; Manual /compact maintenance prompt: skip postRequest hooks
                                             ;; (postCompact provides compact_summary; postRequest would
                                             ;; just see the trailing "Compacted successfully!" tool result).
                                             (do (swap! db* update-in [:chats chat-id] dissoc :compact-done?)
                                                 (lifecycle/finish-chat-prompt! :idle
                                                                                (assoc chat-ctx :skip-post-request-hooks? true))
                                                 nil)
                                             (if (and (lifecycle/auto-compact? chat-id agent full-model config @db*)
                                                      (not (:auto-compacted? chat-ctx)))
                                               (trigger-auto-compact! chat-ctx tc-all-tools tc-user-messages)
                                               (do
                                                 (consume-steer-message! chat-id db* chat-ctx add-to-history!)
                                                 (consume-pending-job-notifications! chat-id db* add-to-history!)
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
                :on-server-image-generation (fn [{:keys [status id name]}]
                                              (lifecycle/assert-chat-not-stopped! chat-ctx)
                                              (let [summary "Generating image"]
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
                                                                                        :arguments {}
                                                                                        :manual-approval false
                                                                                        :summary summary})
                                                             (tc/transition-tool-call! db* chat-ctx id :approval-allow
                                                                                       {:reason :server-tool})
                                                             (tc/transition-tool-call! db* chat-ctx id :execution-start
                                                                                       {:delayed-future (delay nil)
                                                                                        :origin :server
                                                                                        :name name
                                                                                        :server :llm
                                                                                        :arguments {}
                                                                                        :start-time (System/currentTimeMillis)
                                                                                        :summary summary
                                                                                        :progress-text "Generating image"}))
                                                  :finished (let [start-time (get @server-tool-times* id)
                                                                  total-time-ms (if start-time
                                                                                  (- (System/currentTimeMillis) start-time)
                                                                                  0)
                                                                  resolved-name (get-in (tc/get-tool-call-state @db* chat-id id) [:name] "image_generation")]
                                                              (tc/transition-tool-call! db* chat-ctx id :execution-end
                                                                                        {:origin :server
                                                                                         :name resolved-name
                                                                                         :server :llm
                                                                                         :arguments {}
                                                                                         :error false
                                                                                         :outputs [{:type :text :text "Generated image (png)"}]
                                                                                         :total-time-ms total-time-ms
                                                                                         :progress-text "Generating"
                                                                                         :summary summary})
                                                              (tc/transition-tool-call! db* chat-ctx id :cleanup-finished
                                                                                        {:name resolved-name}))
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
                                                              (not (or (:on-finished-side-effect chat-ctx)
                                                                       (:on-after-finish! chat-ctx)))
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
                                                                     (assoc chat-ctx
                                                                            :on-finished-side-effect
                                                                            (fn []
                                                                              (swap! db* update-in [:chats chat-id] dissoc :auto-compacting?))
                                                                            :on-after-finish!
                                                                            (fn []
                                                                              (prompt-messages!
                                                                               [{:role "user"
                                                                                 :content [{:type :text
                                                                                            :text "Your previous response was interrupted mid-stream. Continue from where you left off, do not redo completed steps."}]}]
                                                                               :auto-continue
                                                                               (assoc chat-ctx :auto-continued? true))))))
                                    (do
                                      (when-not stopping?
                                        (lifecycle/send-content! chat-ctx :system {:type :text :text (str "\n\n" (or message (str "Error: " (or (ex-message exception) (.getName (class exception))))))}))
                                      ;; Defensive save: finish-chat-prompt! can short-circuit when
                                      ;; :prompt-finished? was already set or the prompt-id rotated,
                                      ;; which would leave a chat that hit an error without a save.
                                      ;; Persist explicitly so users can always /resume an errored chat.
                                      (db/update-workspaces-cache! @db* metrics)
                                      (lifecycle/finish-chat-prompt! :idle (lifecycle/strip-hook-callbacks chat-ctx))))))))})
              (catch Exception e
                (when-not (:silent? (ex-data e))
                  (logger/error e)
                  (swap! db* update-in [:chats chat-id] dissoc :auto-compacting? :compacting?)
                  (when-not (string/blank? @received-msgs*)
                    (add-to-history! {:role "assistant"
                                      :content [{:type :text :text @received-msgs*}]}))
                  (lifecycle/send-content! chat-ctx :system {:type :text :text (str "\n\n" "Error: " (or (ex-message e) (.getName (class e))))})
                  ;; Belt-and-suspenders: persist before finish-chat-prompt!,
                  ;; which may short-circuit. See note above in :on-error.
                  (db/update-workspaces-cache! @db* metrics)
                  (lifecycle/finish-chat-prompt! :idle (lifecycle/strip-hook-callbacks chat-ctx))))
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
    (let [{:keys [type on-finished-side-effect on-after-finish!] :as result} (f.commands/handle-command! command args chat-ctx)]
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
                         (lifecycle/finish-chat-prompt! :idle (assoc chat-ctx :skip-post-request-hooks? true)))
        :new-chat-status (lifecycle/finish-chat-prompt! (:status result) (assoc chat-ctx :skip-post-request-hooks? true))
        :send-prompt (let [prompt-contents (:prompt result)]
                       ;; Keep original slash command in :message for hooks (already in parent chat-ctx)
                       (prompt-messages! [{:role "user" :content prompt-contents}]
                                         :eca-command
                                         (assoc-some chat-ctx
                                                     :on-finished-side-effect on-finished-side-effect
                                                     :on-after-finish! on-after-finish!)))
        nil))
    (catch Exception e
      (logger/error e)
      (lifecycle/send-content! chat-ctx :system {:type :text
                                                 :text (str "Error: " (ex-message e) "\n\nCheck ECA stderr for more details.")})
      (lifecycle/finish-chat-prompt-stopped! :idle chat-ctx))))

(defn ^:private run-start-hooks!
  "Runs chatStart (or subagentStart for subagent chats) hooks for this chat's
   first prompt. Collects additionalContext from successful hooks into
   :startup-context so build-chat-instructions and /prompt-show pick it up, and
   returns {:stop-turn? boolean :stop-reason string-or-nil} when a hook returns
   {\"continue\":false} on exit 0. db-before-hooks is the stable snapshot used
   for hook reads; db* is mutated only for hook side effects."
  [{:keys [db* chat-id agent messenger variant] :as _chat-ctx} db-before-hooks resumed? full-model]
  (config/await-plugins-resolved!)
  (let [config (config/all db-before-hooks)
        hook-results* (atom [])
        subagent? (some? (get-in db-before-hooks [:chats chat-id :subagent]))
        hook-ctx (cond-> {:messenger messenger :chat-id chat-id}
                   subagent? (assoc :parent-chat-id (db/parent-chat-id db-before-hooks chat-id)))
        hook-type (if subagent? :subagentStart :chatStart)
        hook-data (cond-> (f.hooks/chat-hook-data db-before-hooks {:chat-id chat-id
                                                                   :agent agent
                                                                   :full-model full-model
                                                                   :variant variant})
                    subagent? (assoc :parent-chat-id (db/parent-chat-id db-before-hooks chat-id))
                    (not subagent?) (assoc :resumed resumed?))]
    (f.hooks/trigger-if-matches! hook-type
                                 hook-data
                                 {:on-before-action (partial lifecycle/notify-before-hook-action! hook-ctx)
                                  :on-after-action (fn [result]
                                                     (lifecycle/notify-after-hook-action! hook-ctx result)
                                                     (swap! hook-results* conj result))}
                                 db-before-hooks
                                 config)
    (when-let [additional-contexts (seq (keep (fn [{:keys [parsed exit]}]
                                                (when (zero? exit)
                                                  (shared/not-blank (get parsed "additionalContext"))))
                                              @hook-results*))]
      (swap! db* assoc-in [:chats chat-id :startup-context]
             (string/join "\n\n" additional-contexts)))
    ;; Note: :chat-start-fired is set by prompt* (via swap-vals!) before this
    ;; runs, since prompt* is what decides whether start hooks fire at all.
    (let [stop-result (some (fn [{:keys [parsed name] :as result}]
                              (when (f.hooks/successful-continue-false? result)
                                {:stop-turn? true
                                 :stop-reason (shared/not-blank (get parsed "stopReason"))
                                 :stop-hook-name name}))
                            @hook-results*)]
      (or stop-result {:stop-turn? false}))))

(defn ^:private prompt*
  [{:keys [model]}
   {:keys [chat-id contexts message agent agent-config db* messenger config metrics] :as base-chat-ctx}]
  (let [provided-chat-id chat-id
        ;; Snapshot DB to detect new/resumed chat BEFORE hooks mutate it
        [db-before-hooks _] (swap-vals! db* assoc-in [:chat-start-fired chat-id] true)
        existing-chat-before-prompt (get-in db-before-hooks [:chats chat-id])
        chat-start-fired? (get-in db-before-hooks [:chat-start-fired chat-id])
        has-messages? (seq (:messages existing-chat-before-prompt))
        resumed? (boolean (and (not chat-start-fired?)
                               provided-chat-id
                               has-messages?))
        ;; await-plugins-resolved! must run even though db-before-hooks may not
        ;; have :model yet (model sync can lag behind plugin resolution).
        _ (when-not chat-start-fired? (config/await-plugins-resolved!))
        ;; Respect explicit model; otherwise prefer the chat's stored model
        ;; (so resumed chats keep the provider/model they started with, #417);
        ;; then fall back to the agent default if it resolves to an available
        ;; model; finally, deterministic default-model resolution.
        full-model (resolve-full-model model @db* chat-id agent-config config)
        start-hook-result (when-not chat-start-fired?
                            (run-start-hooks! base-chat-ctx db-before-hooks resumed? full-model))]
    (if (:stop-turn? start-hook-result)
      (do
        (lifecycle/send-turn-stopped-by-hook! base-chat-ctx
                                              (:stop-hook-name start-hook-result)
                                              (:stop-reason start-hook-result))
        (lifecycle/finish-chat-prompt-stopped! :idle base-chat-ctx)
        {:chat-id chat-id
         :model full-model
         :status :prompting})
      (let [;; Re-read DB after potential chatStart modifications
            db @db*
            _ (when (seq contexts)
                (lifecycle/send-content! {:messenger messenger :chat-id chat-id} :system {:type :progress
                                                                                          :state :running
                                                                                          :text "Parsing given context"}))
            refined-contexts (concat
                              (f.context/agents-file-contexts db config)
                              (f.context/raw-contexts->refined contexts db))
            {static-rules :static path-scoped-rules :path-scoped} (f.rules/all-rules config (:workspace-folders db) agent full-model)
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
            repo-map* (delay (f.index/repo-map db config {:as-string? true}))
            prompt-cache (get-in db [:chats chat-id :prompt-cache])
            instructions (if (and prompt-cache
                                  (= (:agent prompt-cache) agent)
                                  (= (:model prompt-cache) full-model))
                           {:static (:static prompt-cache)
                            :dynamic (f.prompt/build-dynamic-instructions refined-contexts db)}
                           (let [result (f.prompt/build-chat-instructions
                                         refined-contexts static-rules path-scoped-rules skills repo-map*
                                         agent config chat-id all-tools db)]
                             (swap! db* assoc-in [:chats chat-id :prompt-cache]
                                    {:static (:static result)
                                     :agent agent
                                     :model full-model})
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
        (swap! db* update-in [:chats chat-id] dissoc :prompt-finished? :follow-up-active?)
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
         :status :prompting}))))

(def ^:private max-client-chat-id-length 256)

(defn ^:private server-managed-subagent-chat-id?
  [db chat-id]
  (and (string? chat-id)
       (string/starts-with? chat-id "subagent-")
       (some? (get-in db [:chats chat-id :subagent]))))

(defn validate-client-chat-id
  "Validates a client-supplied chat id. Returns nil when valid, otherwise an
   error message string.

   Public so non-prompt selection handlers (`chat/selectedModelChanged`,
   `chat/selectedAgentChanged`) can apply the same rules to their `chat-id`
   field.

   Rejected: blank, the reserved `subagent-` prefix (used for deterministic
   subagent chat ids in `eca.features.tools.agent`), embedded whitespace or
   control characters (which would mangle log lines / tab-line titles), and
   ids longer than `max-client-chat-id-length` characters."
  [chat-id]
  (cond
    (string/blank? chat-id)
    "chatId must be a non-blank string"

    (string/starts-with? chat-id "subagent-")
    "chatId prefix 'subagent-' is reserved for server-managed subagent chats"

    (re-find #"[\s\p{Cntrl}]" chat-id)
    "chatId must not contain whitespace or control characters"

    (> (count chat-id) max-client-chat-id-length)
    (str "chatId must be " max-client-chat-id-length " characters or fewer")))

(defn prompt
  [{:keys [message agent behavior chat-id contexts variant trust] :as params} db* messenger config metrics]
  (let [provided-chat-id chat-id
        invalid-id-reason (when (and (some? provided-chat-id)
                                     (not (server-managed-subagent-chat-id? @db* provided-chat-id)))
                            (validate-client-chat-id provided-chat-id))]
    (if invalid-id-reason
      (do (logger/warn logger-tag "Rejected chat/prompt with invalid chat-id"
                       {:chat-id provided-chat-id :reason invalid-id-reason})
          {:chat-id provided-chat-id
           :model "error"
           :status :error})
      (let [raw-agent (or agent
                          behavior ;; backward compat: accept old 'behavior' param
                          (-> config :chat :defaultAgent) ;; legacy
                          (-> config :defaultAgent))
            chat-id (or provided-chat-id (str (random-uuid)))
            ;; Atomically seed the chat record if absent and remember whether
            ;; we were the ones to create it. swap-vals! returns [old new] so
            ;; chat-just-created? is true iff the chat was missing pre-swap.
            [old-db _] (swap-vals! db* update :chats
                                   (fn [chats]
                                     (if (contains? chats chat-id)
                                       chats
                                       (assoc chats chat-id {:id chat-id}))))
            chat-just-created? (not (contains? (:chats old-db) chat-id))
            ;; A freshly-created chat with no client-provided trust inherits
            ;; the server default (chat.defaultTrust), so every editor gets
            ;; trust-by-default without having to send anything.
            seeded-default-trust? (and chat-just-created?
                                       (nil? trust)
                                       (boolean (-> config :chat :defaultTrust)))
            effective-trust (if seeded-default-trust? true trust)
            ;; Notify observers (other clients, remote SSE viewers) about a
            ;; new client-initiated chat. Skipped on the legacy null-id path
            ;; because the prompting client learns its id from the response.
            _ (when (and provided-chat-id chat-just-created?)
                (messenger/chat-opened messenger {:chat-id chat-id}))
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
                                       :trust effective-trust
                                       :variant (or variant (:variant agent-config))
                                       :on-follow-up (fn [follow-up-text chat-ctx]
                                                       (prompt-messages!
                                                        [{:role "user"
                                                          :content [{:type :text
                                                                     :text follow-up-text}]}]
                                                        :follow-up
                                                        chat-ctx))}
                                      :parent-chat-id (db/parent-chat-id @db* chat-id))
            _ (when (some? effective-trust)
                (swap! db* assoc-in [:chats chat-id :trust] effective-trust))
            ;; When we seeded the default (the client didn't ask), align the
            ;; client's per-chat trust indicator with the auto-approval the
            ;; server is about to apply.
            _ (when (and seeded-default-trust? provided-chat-id)
                (config/notify-fields-changed-only! {:chat {:select-trust true}} messenger db* chat-id))]
        (logger/with-chat-context chat-id (:parent-chat-id base-chat-ctx)
          (try
            (prompt* params base-chat-ctx)
            (catch Exception e
              (logger/error e)
              (lifecycle/send-content! base-chat-ctx :system {:type :text
                                                              :text (str "Error: " (ex-message e) "\n\nCheck ECA stderr for more details.")})
              (lifecycle/finish-chat-prompt! :idle (lifecycle/strip-hook-callbacks base-chat-ctx))
              {:chat-id chat-id
               :model "error"
               :status :error})))))))

(defn tool-call-approve [{:keys [chat-id tool-call-id save]} db* messenger metrics]
  (logger/with-chat-context chat-id (db/parent-chat-id @db* chat-id)
    (if-not (get-in @db* [:chats chat-id :tool-calls tool-call-id])
      (logger/warn logger-tag "tool-call-approve ignored: unknown chat or tool-call"
                   {:chat-id chat-id :tool-call-id tool-call-id})
      (let [chat-ctx {:chat-id chat-id
                      :db* db*
                      :metrics metrics
                      :messenger messenger}]
        (tc/transition-tool-call! db* chat-ctx tool-call-id :user-approve
                                  {:reason {:code :user-choice-allow
                                            :text "Tool call allowed by user choice"}})
        (when (= "session" save)
          (let [tool-call-name (get-in @db* [:chats chat-id :tool-calls tool-call-id :name])]
            (swap! db* assoc-in [:tool-calls tool-call-name :remember-to-approve?] true)))))))

(defn tool-call-reject [{:keys [chat-id tool-call-id]} db* messenger metrics]
  (logger/with-chat-context chat-id (db/parent-chat-id @db* chat-id)
    (if-not (get-in @db* [:chats chat-id :tool-calls tool-call-id])
      (logger/warn logger-tag "tool-call-reject ignored: unknown chat or tool-call"
                   {:chat-id chat-id :tool-call-id tool-call-id})
      (let [chat-ctx {:chat-id chat-id
                      :db* db*
                      :metrics metrics
                      :messenger messenger}]
        (tc/transition-tool-call! db* chat-ctx tool-call-id :user-reject
                                  {:reason {:code :user-choice-deny
                                            :text "Tool call rejected by user choice"}})))))

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
  (logger/with-chat-context chat-id (db/parent-chat-id @db* chat-id)
    (when (and (string? message)
               (not (string/blank? message))
               (identical? :running (get-in @db* [:chats chat-id :status])))
      (logger/info logger-tag "Steer message received" {:chat-id chat-id})
      (swap! db* update-in [:chats chat-id :steer-message]
             (fn [existing] (if existing (str existing "\n" message) message))))))

(defn prompt-steer-remove
  "Drop any pending steer message for the chat.
   No-op if no steer message is pending or the chat is not present.
   Idempotent: cancelling an already-consumed steer is silent."
  [{:keys [chat-id]} db*]
  (logger/with-chat-context chat-id (db/parent-chat-id @db* chat-id)
    (let [removed?* (volatile! false)]
      (swap! db* (fn [db]
                   (if (get-in db [:chats chat-id :steer-message])
                     (do (vreset! removed?* true)
                         (update-in db [:chats chat-id] dissoc :steer-message))
                     db)))
      (when @removed?*
        (logger/info logger-tag "Steer message removed" {:chat-id chat-id})))))

(defn prompt-stop
  ([params db* messenger metrics]
   (prompt-stop params db* messenger metrics {}))
  ([{:keys [chat-id]} db* messenger metrics {:keys [silent?]}]
   (logger/with-chat-context chat-id (db/parent-chat-id @db* chat-id)
     (when (identical? :running (get-in @db* [:chats chat-id :status]))
       ;; Set :stopping immediately to prevent race with stream callbacks
       ;; that check status via assert-chat-not-stopped! or cancelled?
       (swap! db* assoc-in [:chats chat-id :status] :stopping)
       (let [chat-ctx {:chat-id chat-id
                       :db* db*
                       :metrics metrics
                       :messenger messenger
                       :parent-chat-id (db/parent-chat-id @db* chat-id)}]
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
         (lifecycle/finish-chat-prompt! :stopping (lifecycle/strip-hook-callbacks chat-ctx)))))))

(defn delete-chat
  [{:keys [chat-id]} db* messenger config metrics]
  (let [db @db*]
    (logger/with-chat-context chat-id (db/parent-chat-id db chat-id)
      (when-let [chat (get-in db [:chats chat-id])]
        ;; Trigger chatEnd hook BEFORE deleting (chat still exists in cache)
        (let [{:keys [agent model variant]} chat
              hook-data (f.hooks/chat-hook-data db {:chat-id    chat-id
                                                    :agent      agent
                                                    :full-model model
                                                    :variant    variant})]
          (f.hooks/trigger-if-matches! :chatEnd
                                       hook-data
                                       {}
                                       db
                                       config)))
      ;; Delete chat from memory
      (swap! db* update :chats dissoc chat-id)
      (messenger/chat-deleted messenger {:chat-id chat-id})
      ;; Save updated cache (without this chat)
      (db/update-workspaces-cache! @db* metrics))))

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
  [{:keys [chat-id content-id include]} db* messenger metrics]
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
      ;; Rollback is the user's recovery tool for a chat that got into a bad
      ;; state. Persist immediately so the cleaned-up history survives a
      ;; restart instead of relying on the next unrelated save.
      (db/update-workspaces-cache! @db* metrics)
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
            flag-msg {:role "flag" :content {:text text} :content-id flag-id :created-at (System/currentTimeMillis)}
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

(defn list-chats
  "Pure projection over `(:chats db)`: returns a summary list intended for the
   client sidebar. Subagent chats are excluded. Supports optional
   `:limit` (positive int) and `:sort-by` (`:updated-at` or `:created-at`;
   default `:updated-at`). Results are sorted descending by the chosen
   timestamp, falling back to the other when the primary is nil.
   The map key is used as the authoritative `:id` because legacy DB rows
   may have been persisted without an `:id` inside the value, and clients
   need a non-nil id to call `chat/open`."
  [db {:keys [limit] sort-key :sort-by}]
  (let [primary (or sort-key :updated-at)
        secondary (if (= primary :updated-at) :created-at :updated-at)
        chats (->> (:chats db)
                   (remove (fn [[_ v]] (:subagent v)))
                   (sort-by (fn [[_ v]] (or (get v primary) (get v secondary) 0)) >)
                   (mapv (fn [[k {:keys [title status created-at updated-at model messages]}]]
                           (assoc-some
                            {:id k
                             :title title
                             :status (or status :idle)
                             :message-count (count messages)}
                            :created-at created-at
                            :updated-at updated-at
                            :model model))))]
    {:chats (if (and limit (pos? (long limit)))
              (vec (take (long limit) chats))
              chats)}))

(defn open-chat!
  "Replay a persisted chat over the wire so a freshly-started client can render
   it. Emits `chat/cleared` (messages) followed by `chat/opened` and streams each
   persisted message via `send-chat-contents!`. Also re-aligns the client's
   selected model to the resumed chat's stored `:model` via a `config/updated`
   notification, so an Opus-started chat keeps using Opus on the next prompt
   (#417). Performs no DB mutation otherwise.
   Returns `{:found? false}` when the chat does not exist or is a subagent,
   otherwise `{:found? true :chat-id ... :title ...}`."
  [{:keys [chat-id]} db* messenger config]
  (let [chat (get-in @db* [:chats chat-id])]
    (if (or (nil? chat) (:subagent chat))
      {:found? false}
      (let [title (:title chat)
            messages (:messages chat)
            chat-ctx {:chat-id chat-id :db* db* :messenger messenger}]
        (messenger/chat-cleared messenger {:chat-id chat-id :messages true})
        (messenger/chat-opened messenger (assoc-some {:chat-id chat-id} :title title))
        (send-chat-contents! messages chat-ctx)
        (lifecycle/send-content! chat-ctx :system (assoc-some {:type :metadata} :title title))
        (config/notify-selected-model-changed! (:model chat) db* messenger config (:variant chat))
        (config/notify-selected-trust-changed! (:trust chat) db* messenger)
        {:found? true :chat-id chat-id :title title}))))
