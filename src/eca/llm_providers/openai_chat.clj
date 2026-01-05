(ns eca.llm-providers.openai-chat
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.client-http :as client]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.shared :refer [assoc-some deep-merge]]
   [hato.client :as http]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[OPENAI-CHAT]")

(def ^:private chat-completions-path "/chat/completions")

(defn ^:private parse-usage [usage]
  (let [input-cache-read-tokens (-> usage :prompt_tokens_details :cached_tokens)]
    {:input-tokens (if input-cache-read-tokens
                     (- (:prompt_tokens usage) input-cache-read-tokens)
                     (:prompt_tokens usage))
     :output-tokens (:completion_tokens usage)
     :input-cache-read-tokens input-cache-read-tokens}))

(defn ^:private extract-content
  "Extract text content from various message content formats.
   Handles: strings (legacy eca), nested arrays from chat.clj, and fallback."
  [content supports-image?]
  (cond
    ;; Legacy/fallback: handles system messages, error strings, or unexpected simple text content
    (string? content)
    [{:type "text"
      :text (string/trim content)}]

    (and (sequential? content)
         (every? #(= "text" (name (:type %))) content))
    (->> content (map :text) (remove nil?) (string/join "\n"))

    (sequential? content)
    (vec
     (keep
      #(case (name (:type %))

         "text"
         {:type "text"
          :text (:text %)}

         "image"
         (when supports-image?
           {:type "image_url"
            :image_url {:url (format "data:%s;base64,%s"
                                     (:media-type %)
                                     (:base64 %))}})

         %)
      content))

    :else
    [{:type "text"
      :text (str content)}]))

(defn ^:private ->tools [tools]
  (mapv (fn [tool]
          {:type "function"
           :function (-> (select-keys tool [:description :parameters])
                         (assoc :name (:full-name tool)))})
        tools))

(defn ^:private response-body->result [{:keys [choices usage]} on-tools-called-wrapper]
  (when (> (count choices) 1)
    (throw (ex-info "Multiple choices in response!" {})))
  (let [message (-> choices first :message)
        tools-to-call (->> (:tool_calls message)
                           (map (fn [tool-call]
                                  (cond-> {:id (:id tool-call)
                                           :full-name (:name (:function tool-call))
                                           :arguments (json/parse-string (:arguments (:function tool-call)))}
                                     ;; Preserve Google Gemini thought signatures
                                    (get-in tool-call [:extra_content :google :thought_signature])
                                    (assoc :external-id
                                           (get-in tool-call [:extra_content :google :thought_signature]))))))
        ;; DeepSeek returns reasoning_content, OpenAI o1 returns reasoning
        reasoning-content (:reasoning_content message)]
    {:usage (parse-usage usage)
     :reason-id (str (random-uuid))
     :tools-to-call tools-to-call
     :call-tools-fn (fn [on-tools-called]
                      (on-tools-called-wrapper tools-to-call on-tools-called nil))
     :reason-text (or (:reasoning message)
                      (:reasoning_text message)
                      reasoning-content)
     :reasoning-content reasoning-content
     :output-text (:content message)}))

(defn ^:private base-chat-request!
  [{:keys [rid extra-headers body url-relative-path api-url api-key on-error on-stream
           on-tools-called-wrapper http-client]}]
  (let [url (str api-url (or url-relative-path chat-completions-path))
        on-error (if on-stream
                   on-error
                   (fn [error-data]
                     (llm-util/log-response logger-tag rid "response-error" error-data)
                     {:error error-data}))
        headers (merge {"Authorization" (str "Bearer " api-key)
                        "Content-Type" "application/json"}
                       extra-headers)]
    (llm-util/log-request logger-tag rid url body headers)
    @(http/post
      url
      {:headers headers
       :body (json/generate-string body)
       :throw-exceptions? false
       :async? true
       :http-client (client/merge-with-global-http-client http-client)
       :as (if on-stream :stream :json)}
      (fn [{:keys [status body]}]
        (try
          (if (not= 200 status)
            (let [body-str (if on-stream (slurp body) body)]
              (logger/warn logger-tag rid "Unexpected response status: %s body: %s" status body-str)
              (on-error {:message (format "LLM response status: %s body: %s" status body-str)}))
            (if on-stream
              (with-open [rdr (io/reader body)]
                (doseq [[event data] (llm-util/event-data-seq rdr)]
                  (llm-util/log-response logger-tag rid event data)
                  (on-stream event data))
                (on-stream "stream-end" {}))
              (do
                (llm-util/log-response logger-tag rid "full-response" body)
                (response-body->result body on-tools-called-wrapper))))
          (catch Exception e
            (on-error {:exception e}))))
      (fn [e]
        (on-error {:exception e})))))

(defn ^:private transform-message
  "Transform a single ECA message to OpenAI format. Returns nil for unsupported roles.

   For 'reason' messages:
   - If :reasoning-content exists, emit as assistant message with reasoning_content field (DeepSeek-style)
   - Otherwise, wrap the text in thinking tags (text-based reasoning fallback)"
  [{:keys [role content] :as _msg} supports-image? think-tag-start think-tag-end]
  (case role
    "tool_call" {:role "assistant"
                 :tool_calls [(cond-> {:id       (:id content)
                                       :type     "function"
                                       :function {:name      (:full-name content)
                                                  :arguments (json/generate-string (:arguments content))}}
                                ;; Preserve Google Gemini thought signatures if present
                                (:external-id content)
                                (assoc-in [:extra_content :google :thought_signature]
                                          (:external-id content)))]}
    "tool_call_output" {:role "tool"
                        :tool_call_id (:id content)
                        :content (llm-util/stringfy-tool-result content)}
    "user" {:role "user"
            :content (extract-content content supports-image?)}
    "reason" (if (:delta-reasoning? content)
               ;; DeepSeek-style: reasoning_content must be passed back to API
               {:role "assistant"
                :content ""
                :reasoning_content (:text content)}
               ;; Fallback: wrap in thinking tags for models that use text-based reasoning
               {:role "assistant"
                :content [{:type "text"
                           :text (str think-tag-start (:text content) think-tag-end)}]})
    "assistant" {:role "assistant"
                 :content (extract-content content supports-image?)}
    "system" {:role "system"
              :content (extract-content content supports-image?)}
    nil))

(defn ^:private merge-assistant-messages
  "Merge two assistant messages into one.
   Concatenates contents and tool_calls, and preserves reasoning_content."
  [prev msg]
  (let [prev-content (:content prev)
        msg-content (:content msg)
        blank-string? (fn [s] (and (string? s) (string/blank? s)))
        combined-content (cond
                           (nil? prev-content)
                           msg-content

                           (nil? msg-content)
                           prev-content

                           (blank-string? prev-content)
                           msg-content

                           (blank-string? msg-content)
                           prev-content

                           (and (string? prev-content) (string? msg-content))
                           (if (or (string/ends-with? prev-content "\n")
                                   (string/starts-with? msg-content "\n"))
                             (str prev-content msg-content)
                             (str prev-content "\n" msg-content))

                           (and (sequential? prev-content) (sequential? msg-content))
                           (vec (concat prev-content msg-content))

                           :else
                           (let [as-seq (fn [c] (if (sequential? c) c [{:type "text" :text (str c)}]))]
                             (vec (concat (as-seq prev-content) (as-seq msg-content)))))]
    (cond-> {:role "assistant"
             :content (or combined-content "")}
      (or (:reasoning_content prev) (:reasoning_content msg))
      (assoc :reasoning_content (str (:reasoning_content prev) (:reasoning_content msg)))

      (or (:tool_calls prev) (:tool_calls msg))
      (assoc :tool_calls (vec (concat (:tool_calls prev)
                                      (:tool_calls msg)))))))

(defn ^:private merge-adjacent-assistants
  "Merge all adjacent assistant messages.
   This is required by many OpenAI-compatible APIs (including DeepSeek)
   which do not allow multiple consecutive assistant messages."
  [messages]
  (reduce
   (fn [acc msg]
     (let [prev (peek acc)]
       (if (and (= "assistant" (:role prev))
                (= "assistant" (:role msg)))
         (conj (pop acc) (merge-assistant-messages prev msg))
         (conj acc msg))))
   []
   messages))

(defn ^:private valid-message?
  "Check if a message should be included in the final output."
  [{:keys [role content tool_calls reasoning_content] :as _msg}]
  (or (= role "tool") ; Never remove tool messages
      (seq tool_calls) ; Keep messages with tool calls
      (seq reasoning_content) ; Keep messages with reasoning_content (DeepSeek)
      (and (string? content)
           (not (string/blank? content)))
      (sequential? content)))

(defn ^:private normalize-messages
  "Converts ECA message format to OpenAI API format (also used by compatible providers).

   Key transformations:
   - Transforms each ECA message to API format (tool_call -> assistant with tool_calls, etc.)
   - Merges adjacent assistant messages (required by OpenAI/DeepSeek APIs)
   - Filters out invalid/empty messages

   The pipeline: transform -> merge adjacent assistants -> filter valid"
  [messages supports-image? think-tag-start think-tag-end]
  (->> messages
       (map #(transform-message % supports-image? think-tag-start think-tag-end))
       (remove nil?)
       merge-adjacent-assistants
       (filter valid-message?)))

(defn ^:private execute-accumulated-tools!
  [tool-calls* on-tools-called-wrapper on-tools-called handle-response]
  (let [all-accumulated (vals @tool-calls*)
        completed-tools (->> all-accumulated
                             (filter #(every? % [:id :full-name :arguments-text]))
                             (map (fn [{:keys [arguments-text full-name] :as tool-call}]
                                    (try
                                      (assoc tool-call :arguments (json/parse-string arguments-text))
                                      (catch Exception e
                                        (let [error-msg (format "Failed to parse JSON arguments for tool '%s': %s"
                                                                full-name (ex-message e))]
                                          (logger/warn logger-tag error-msg)
                                          (assoc tool-call :arguments {} :parse-error error-msg)))))))
        ;; Filter out tool calls with parse errors to prevent execution with invalid data
        valid-tools (remove :parse-error completed-tools)]
    (if (seq valid-tools)
      ;; We have valid tools to execute, continue the conversation
      (on-tools-called-wrapper valid-tools on-tools-called handle-response)
      ;; No valid tools (all had parse errors or none accumulated) - don't loop
      nil)))

(defn ^:private process-text-think-aware
  "Incremental parser that splits streamed content into user text and thinking blocks.
   - Maintains a rolling buffer across chunks to handle tags that may be split across chunks
   - Outside thinking: emit user text up to <think> and keep a small tail to detect split tags
   - Inside thinking: emit reasoning up to </think> and keep a small tail to detect split tags
   - When a tag boundary is found, open/close the reasoning block accordingly

   Uses a combined reasoning-state* atom with keys: :id, :type, :content, :buffer"
  [text reasoning-state* think-tag-start think-tag-end on-message-received on-reason]
  (let [start-len (count think-tag-start)
        end-len (count think-tag-end)
        ;; Keep a small tail to detect tags split across chunk boundaries.
        ;; Invariant: buffer length is bounded by max(start-tail, end-tail) so it cannot grow unbounded.
        start-tail (max 0 (dec start-len))
        end-tail (max 0 (dec end-len))
        emit-text! (fn [^String s]
                     (when (pos? (count s))
                       (on-message-received {:type :text :text s})))
        emit-think! (fn [^String s id]
                      (when (and (pos? (count s)) id)
                        (on-reason {:status :thinking :id id :text s})))]
    (when (seq text)
      ;; Add new text to buffer
      (swap! reasoning-state* update :buffer str text)
      (loop []
        (let [state        @reasoning-state*
              ^String buf  (:buffer state)
              current-type (:type state)
              current-id   (:id state)]
          (if (= current-type :tag)
            ;; Inside a thinking block; look for end tag
            (let [idx (.indexOf buf ^String think-tag-end)]
              (if (>= idx 0)
                (let [before (.substring buf 0 idx)
                      after  (.substring buf (+ idx end-len))]
                  (when current-id (emit-think! before current-id))
                  (swap! reasoning-state* assoc :buffer after)
                  ;; Finish thinking block
                  (when current-id
                    (on-reason {:status :finished :id current-id}))
                  (swap! reasoning-state* assoc :type nil :id nil)
                  (recur))
                (let [emit-len (max 0 (- (count buf) end-tail))]
                  (when (pos? emit-len)
                    (when current-id (emit-think! (.substring buf 0 emit-len) current-id))
                    (swap! reasoning-state* assoc :buffer (.substring buf emit-len))))))
            ;; Outside a thinking block; look for start tag
            (let [idx (.indexOf buf ^String think-tag-start)]
              (if (>= idx 0)
                (let [before (.substring buf 0 idx)
                      after  (.substring buf (+ idx start-len))]
                  (emit-text! before)
                  ;; Start thinking block
                  (let [new-id (str (random-uuid))]
                    (swap! reasoning-state* assoc
                           :id new-id
                           :type :tag
                           :buffer after)
                    (on-reason {:status :started :id new-id})
                    (recur)))
                (let [emit-len (max 0 (- (count buf) start-tail))]
                  (when (pos? emit-len)
                    (emit-text! (.substring buf 0 emit-len))
                    (swap! reasoning-state* assoc :buffer (.substring buf emit-len))))))))))))

(defn ^:private finish-reasoning!
  "Finish the current reasoning block if present and reset the state.
   Call this after flushing buffered chunks when streaming."
  [reasoning-state* on-reason]
  (let [state @reasoning-state*]
    (when (and (:type state) (:id state))
      (on-reason {:status :finished
                  :id (:id state)
                  :delta-reasoning? (= (:type state) :delta)}))
    (reset! reasoning-state* {:id nil :type nil :content "" :buffer ""})))

(defn ^:private prune-history
  "Ensure DeepSeek-style reasoning_content is discarded from history but kept for the active turn.
   Only drops 'reason' messages WITH :delta-reasoning? before the last user message.
   Think-tag based reasoning (without :delta-reasoning?) is preserved and transformed to assistant messages."
  [messages]
  (if-let [last-user-idx (llm-util/find-last-user-msg-idx messages)]
    (->> messages
         (keep-indexed (fn [i m]
                         (when-not (and (= "reason" (:role m))
                                        (get-in m [:content :delta-reasoning?])
                                        (< i last-user-idx))
                           m)))
         vec)
    messages))

(defn chat-completion!
  "Primary entry point for OpenAI chat completions with streaming support.

   Handles the full conversation flow including tool calls, streaming responses,
   and message normalization. Supports both single and parallel tool execution.
   Compatible with OpenRouter and other OpenAI-compatible providers."
  [{:keys [model user-messages instructions temperature api-key api-url url-relative-path
           past-messages tools extra-payload extra-headers supports-image?
           think-tag-start think-tag-end http-client]}
   {:keys [on-message-received on-error on-prepare-tool-call on-tools-called on-reason on-usage-updated] :as callbacks}]
  (let [think-tag-start (or think-tag-start "<think>")
        think-tag-end (or think-tag-end "</think>")
        stream? (boolean callbacks)
        system-messages (when instructions [{:role "system" :content instructions}])
        ;; Pipeline: prune history -> normalize -> merge adjacent assistants -> filter
        all-messages (prune-history (vec (concat past-messages user-messages)))
        messages (vec (concat
                       system-messages
                       (normalize-messages all-messages supports-image? think-tag-start think-tag-end)))

        body (deep-merge
              (assoc-some
               {:model model
                :messages messages
                :stream stream?
                :max_completion_tokens 32000}
               :temperature temperature
               :tools (when (seq tools) (->tools tools)))
              extra-payload)

        ;; Atom to accumulate tool call data from streaming chunks.
        ;; OpenAI streams tool call arguments across multiple chunks, so we need to
        ;; accumulate partial JSON strings before parsing them. Keys are tool call
        ;; indices (fallback: IDs) to keep chunks grouped for the active response.
        tool-calls* (atom {})

        ;; Reasoning state machine:
        ;; - :type nil   = not in reasoning block
        ;; - :type :tag  = inside <think>...</think> tags (text-based reasoning)
        ;; - :type :delta = receiving reasoning_content deltas (DeepSeek/OpenAI o1 style)
        ;; :content accumulates reasoning_content for echo back; :buffer holds partial text for tag detection
        reasoning-state* (atom {:id nil
                                :type nil
                                :content ""
                                :buffer ""})
        flush-content-buffer (fn []
                               (let [state @reasoning-state*
                                     buf (:buffer state)]
                                 (when (pos? (count buf))
                                   (if (= (:type state) :tag)
                                     (when (:id state)
                                       (on-reason {:status :thinking :id (:id state) :text buf}))
                                     (on-message-received {:type :text :text buf}))
                                   (swap! reasoning-state* assoc :buffer ""))))
        wrapped-on-error (fn [error-data]
                           (flush-content-buffer)
                           (finish-reasoning! reasoning-state* on-reason)
                           (on-error error-data))
        start-delta-reasoning (fn []
                                (let [new-reason-id (str (random-uuid))]
                                  (swap! reasoning-state* assoc
                                         :id new-reason-id
                                         :type :delta
                                         :content ""
                                         :buffer "")
                                  (on-reason {:status :started :id new-reason-id})))
        find-existing-tool-key (fn [tool-calls index id]
                                 (some (fn [[k v]] (when (or (some-> id (= (:id v)))
                                                             (and (nil? (:id v))
                                                                  (some-> index (= (:index v)))))
                                                     k))
                                       tool-calls))
        on-tools-called-wrapper (fn on-tools-called-wrapper [tools-to-call on-tools-called handle-response]
                                  (when-let [{:keys [new-messages]} (on-tools-called tools-to-call)]
                                    (let [pruned-messages (prune-history new-messages)
                                          new-messages-list (vec (concat
                                                                  system-messages
                                                                  (normalize-messages pruned-messages supports-image? think-tag-start think-tag-end)))
                                          new-rid (llm-util/gen-rid)]
                                      (reset! tool-calls* {})
                                      (base-chat-request!
                                       {:rid new-rid
                                        :body (assoc body :messages new-messages-list)
                                        :on-tools-called-wrapper on-tools-called-wrapper
                                        :extra-headers extra-headers
                                        :http-client http-client
                                        :api-url api-url
                                        :api-key api-key
                                        :url-relative-path url-relative-path
                                        :on-error wrapped-on-error
                                        :on-stream (when stream? (fn [event data] (handle-response event data tool-calls*)))}))))

        handle-response (fn handle-response [event data tool-calls*]
                          (if (= event "stream-end")
                            (do
                              ;; Flush any leftover buffered content and finish reasoning if needed
                              (flush-content-buffer)
                              (finish-reasoning! reasoning-state* on-reason)
                              (execute-accumulated-tools! tool-calls* on-tools-called-wrapper on-tools-called handle-response))
                            (when (seq (:choices data))
                              (doseq [choice (:choices data)]
                                (let [delta (:delta choice)
                                      finish-reason (:finish_reason choice)]
                                  ;; Process content if present (with thinking blocks support)
                                  (when-let [ct (:content delta)]
                                    (process-text-think-aware ct reasoning-state* think-tag-start think-tag-end on-message-received on-reason))

                                  ;; Process reasoning if present (o1 models and compatible providers)
                                  (when-let [reasoning-text (or (:reasoning delta)
                                                                (:reasoning_content delta)
                                                                (:reasoning_text delta))]
                                    (when-not (= (:type @reasoning-state*) :delta)
                                      (start-delta-reasoning))
                                    (on-reason {:status :thinking
                                                :id (:id @reasoning-state*)
                                                :text reasoning-text}))

                                  ;; Check if reasoning just stopped (delta-based)
                                  (let [state @reasoning-state*]
                                    (when (and (= (:type state) :delta)
                                               (:id state)  ;; defensive check
                                               (nil? (:reasoning delta))
                                               (nil? (:reasoning_content delta))
                                               (nil? (:reasoning_text delta)))
                                      ;; Flush any buffered content before finishing reasoning
                                      (flush-content-buffer)
                                      (finish-reasoning! reasoning-state* on-reason)))

                                  ;; Process tool calls if present
                                  (when (:tool_calls delta)
                                    ;; Flush any leftover buffered content before finishing
                                    (flush-content-buffer)
                                    (doseq [tool-call (:tool_calls delta)]
                                      (let [{:keys [index id function extra_content]} tool-call
                                            {name :name args :arguments} function
                                            ;; Extract Google Gemini thought signature if present
                                            thought-signature (get-in extra_content [:google :thought_signature])
                                            existing-key (find-existing-tool-key @tool-calls* index id)
                                            existing (when existing-key (get @tool-calls* existing-key))
                                            tool-key (or existing-key index id)]
                                        (if (nil? tool-key)
                                          (logger/warn logger-tag "Received tool_call delta without index/id; ignoring"
                                                       {:tool-call tool-call})
                                          (do
                                            (swap! tool-calls* update tool-key
                                                   (fn [existing]
                                                     (cond-> (or existing {:index index})
                                                       (some? index) (assoc :index index)
                                                       (and id (nil? (:id existing))) (assoc :id id)
                                                       (and name (nil? (:full-name existing))) (assoc :full-name name)
                                                       args (update :arguments-text (fnil str "") args)
                                                       ;; Store thought signature for Google Gemini
                                                       thought-signature (assoc :external-id thought-signature))))
                                            (when-let [updated-tool-call (get @tool-calls* tool-key)]
                                              ;; Streaming tool_calls may split metadata (id/name) and arguments across deltas.
                                              ;; Emit prepare once we can correlate the call (id + full-name), on first id or args deltas,
                                              ;; so :tool-prepare always precedes :tool-run in the tool-call state machine.
                                              (when (and (:id updated-tool-call)
                                                         (:full-name updated-tool-call)
                                                         (or (nil? (:id existing)) args))
                                                (on-prepare-tool-call
                                                 (assoc updated-tool-call
                                                        :arguments-text (or args ""))))))))))
                                  ;; Process finish reason if present (but not tool_calls which is handled above)
                                  (when finish-reason
                                    ;; Flush any leftover buffered content before finishing
                                    (flush-content-buffer)
                                    ;; Handle reasoning completion
                                    (finish-reasoning! reasoning-state* on-reason)
                                    ;; Handle regular finish
                                    (when (not= finish-reason "tool_calls")
                                      (on-message-received {:type :finish :finish-reason finish-reason})))))))
                          (when-let [usage (:usage data)]
                            (on-usage-updated (parse-usage usage))))
        rid (llm-util/gen-rid)]
    (base-chat-request!
     {:rid rid
      :body body
      :extra-headers extra-headers
      :api-url api-url
      :api-key api-key
      :http-client http-client
      :url-relative-path url-relative-path
      :tool-calls* tool-calls*
      :on-tools-called-wrapper on-tools-called-wrapper
      :on-error wrapped-on-error
      :on-stream (when stream?
                   (fn [event data] (handle-response event data tool-calls*)))})))
