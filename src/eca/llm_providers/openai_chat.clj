(ns eca.llm-providers.openai-chat
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
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

(defn ^:private response-body->result [body on-tools-called-wrapper]
  (let [tools-to-call (->> (:choices body)
                           (mapcat (comp :tool_calls :message))
                           (map (fn [tool-call]
                                  {:id (:id tool-call)
                                   :full-name (:name (:function tool-call))
                                   :arguments (json/parse-string (:arguments (:function tool-call)))})))]
    {:usage (parse-usage (:usage body))
     :reason-id (str (random-uuid))
     :tools-to-call tools-to-call
     :call-tools-fn (fn [on-tools-called]
                      (on-tools-called-wrapper tools-to-call on-tools-called nil))
     ;; Support both :reasoning (OpenAI) and :reasoning_content (DeepSeek)
     :reason-text (->> (:choices body)
                       (map (comp #(or (:reasoning %) (:reasoning_content %)) :message))
                       (string/join "\n")
                       not-empty)
     :output-text (->> (:choices body)
                       (map (comp :content :message))
                       (string/join "\n")
                       not-empty)}))

(defn ^:private base-chat-request!
  [{:keys [rid extra-headers body url-relative-path api-url api-key on-error on-stream on-tools-called-wrapper]}]
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
  "Transform a single ECA message to OpenAI format. Returns nil for unsupported roles."
  [{:keys [role content] :as _msg} supports-image? think-tag-start think-tag-end]
  (case role
    "tool_call" {:type :tool-call ; Special marker for accumulation
                 :data {:id (:id content)
                        :type "function"
                        :function {:name (:full-name content)
                                   :arguments (json/generate-string (:arguments content))}}}
    "tool_call_output" {:role "tool"
                        :tool_call_id (:id content)
                        :content (llm-util/stringfy-tool-result content)}
    "user" {:role "user"
            :content (extract-content content supports-image?)}
    "reason" (if-let [reasoning-content (:external-id content)]
               ;; DeepSeek-style: reasoning_content must be passed back to API
               ;; Emit as assistant message with reasoning_content field
               {:role "assistant"
                :content ""
                :reasoning_content reasoning-content}
               ;; Fallback: wrap in thinking tags for models that use text-based reasoning
               {:role "assistant"
                :content [{:type "text"
                           :text (str think-tag-start (:text content) think-tag-end)}]})
    "assistant" {:role "assistant"
                 :content (extract-content content supports-image?)}
    "system" {:role "system"
              :content (extract-content content supports-image?)}
    nil))

(defn ^:private accumulate-assistant-parts
  "Combines tool_calls and reasoning_content into single assistant messages.

   Tool calls must be grouped into assistant messages. For DeepSeek-style APIs,
   reasoning_content must be on the SAME assistant message as tool_calls or content.

   This handles the pattern where ECA stores:
   1. {:role \"reason\" ...} -> {:role \"assistant\" :reasoning_content \"...\"}
   2. {:role \"tool_call\" ...} -> accumulated into tool_calls
   3. {:role \"assistant\" ...} -> standard text response

   And combines them into:
   {:role \"assistant\" :reasoning_content \"...\" :tool_calls [...]}
   OR
   {:role \"assistant\" :reasoning_content \"...\" :content \"...\"}"
  [transformed-messages]
  (let [flush-pending (fn [{:keys [tool-calls pending-reasoning] :as acc}]
                        (cond
                          (seq tool-calls)
                          (-> acc
                              (update :messages conj
                                      (cond-> {:role "assistant" :tool_calls tool-calls}
                                        pending-reasoning (assoc :reasoning_content pending-reasoning)))
                              (assoc :tool-calls [])
                              (assoc :pending-reasoning nil))

                          pending-reasoning
                          (-> acc
                              (update :messages conj {:role "assistant" :content "" :reasoning_content pending-reasoning})
                              (assoc :pending-reasoning nil))

                          :else acc))

        {:keys [messages tool-calls pending-reasoning]}
        (reduce (fn [acc msg]
                  (cond
                    (= :tool-call (:type msg))
                    (update acc :tool-calls conj (:data msg))

                    (:reasoning_content msg)
                    (assoc acc :pending-reasoning (:reasoning_content msg))

                    ;; If it's an assistant text message, we might want to merge reasoning
                    (= "assistant" (:role msg))
                    (if (seq (:tool-calls acc))
                      ;; We have tools waiting. Flush tools + reasoning first.
                      (-> acc
                          flush-pending
                          (update :messages conj msg))
                      ;; No tools, just potential reasoning. Merge it!
                      (-> acc
                          (update :messages conj (cond-> msg
                                                   (:pending-reasoning acc)
                                                   (assoc :reasoning_content (:pending-reasoning acc))))
                          (assoc :pending-reasoning nil)))

                    :else
                    (-> acc
                        flush-pending
                        (update :messages conj msg))))
                {:messages [] :tool-calls [] :pending-reasoning nil}
                transformed-messages)]
    ;; Final flush
    (:messages (flush-pending {:messages messages :tool-calls tool-calls :pending-reasoning pending-reasoning}))))

(defn ^:private valid-message?
  "Check if a message should be included in the final output."
  [{:keys [role content tool_calls reasoning_content] :as msg}]
  (and msg
       (or (= role "tool") ; Never remove tool messages
           (seq tool_calls) ; Keep messages with tool calls
           (seq reasoning_content) ; Keep messages with reasoning_content (DeepSeek)
           (and (string? content)
                (not (string/blank? content)))
           (sequential? content))))

(defn ^:private normalize-messages
  "Converts ECA message format to OpenAI API format (also used by compatible providers).

   Key transformations:
   - Flushes accumulated tool_calls into a single assistant message (OpenAI API requirement)
   - Converts tool_call role to tool_calls array in assistant message
   - Converts tool_call_output role to tool role with tool_call_id
   - Converts reason role to assistant message with reasoning_content (DeepSeek API requirement)
   - Extracts content from various message content formats

   The OpenAI Chat Completions API requires that tool_calls must be present in an
   'assistant' role message, not as separate messages. This function ensures compliance
   with that requirement by accumulating tool calls and flushing them into assistant
   messages when a non-tool_call message is encountered."
  [messages supports-image? think-tag-start think-tag-end]
  (->> messages
       (map #(transform-message % supports-image? think-tag-start think-tag-end))
       (remove nil?)
       accumulate-assistant-parts
       (filter valid-message?)))

(defn ^:private execute-accumulated-tools!
  [tool-calls* on-tools-called-wrapper on-tools-called handle-response]
  (let [all-accumulated (vals @tool-calls*)
        completed-tools (->> all-accumulated
                             (filter #(every? % [:id :full-name :arguments-text]))
                             (map (fn [{:keys [arguments-text name] :as tool-call}]
                                    (try
                                      (assoc tool-call :arguments (json/parse-string arguments-text))
                                      (catch Exception e
                                        (let [error-msg (format "Failed to parse JSON arguments for tool '%s': %s"
                                                                name (ex-message e))]
                                          (logger/warn logger-tag error-msg)
                                          (assoc tool-call :arguments {} :parse-error error-msg)))))))
        ;; Filter out tool calls with parse errors to prevent execution with invalid data
        valid-tools (remove :parse-error completed-tools)]
    (if (seq completed-tools)
      ;; We have some completed tools (valid or with errors), so continue the conversation
      (on-tools-called-wrapper valid-tools on-tools-called handle-response)
      ;; No completed tools at all - let the streaming response provide the actual finish_reason
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
        start-tail (max 0 (dec start-len))
        end-tail (max 0 (dec end-len))
        emit-text! (fn [^String s]
                     (when (pos? (count s))
                       (on-message-received {:type :text :text s})))
        emit-think! (fn [^String s id]
                      (when (pos? (count s))
                        (on-reason {:status :thinking :id id :text s})))]
    (when (seq text)
      ;; Add new text to buffer
      (swap! reasoning-state* update :buffer str text)
      (loop []
        (let [state @reasoning-state*
              ^String buf (:buffer state)
              current-type (:type state)
              current-id (:id state)]
          (if (= current-type :tag)
            ;; Inside a thinking block; look for end tag
            (let [idx (.indexOf buf ^String think-tag-end)]
              (if (>= idx 0)
                (let [before (.substring buf 0 idx)
                      after (.substring buf (+ idx end-len))]
                  (emit-think! before current-id)
                  (swap! reasoning-state* assoc :buffer after)
                  ;; Finish thinking block
                  (when on-reason
                    (on-reason {:status :finished :id current-id}))
                  (swap! reasoning-state* assoc :type nil :id nil)
                  (recur))
                (let [emit-len (max 0 (- (count buf) end-tail))]
                  (when (pos? emit-len)
                    (emit-think! (.substring buf 0 emit-len) current-id)
                    (swap! reasoning-state* assoc :buffer (.substring buf emit-len))))))
            ;; Outside a thinking block; look for start tag
            (let [idx (.indexOf buf ^String think-tag-start)]
              (if (>= idx 0)
                (let [before (.substring buf 0 idx)
                      after (.substring buf (+ idx start-len))]
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

(defn chat-completion!
  "Primary entry point for OpenAI chat completions with streaming support.

   Handles the full conversation flow including tool calls, streaming responses,
   and message normalization. Supports both single and parallel tool execution.
   Compatible with OpenRouter and other OpenAI-compatible providers."
  [{:keys [model user-messages instructions temperature api-key api-url url-relative-path
           past-messages tools extra-payload extra-headers supports-image?
           think-tag-start think-tag-end]}
   {:keys [on-message-received on-error on-prepare-tool-call on-tools-called on-reason on-usage-updated] :as callbacks}]
  (let [think-tag-start (or think-tag-start "<think>")
        think-tag-end (or think-tag-end "</think>")
        stream? (boolean callbacks)
        messages (vec (concat
                       (when instructions [{:role "system" :content instructions}])
                       (normalize-messages past-messages supports-image? think-tag-start think-tag-end)
                       (normalize-messages user-messages supports-image? think-tag-start think-tag-end)))

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
        ;; accumulate the partial JSON strings before parsing them. Keys are either
        ;; index numbers for simple cases, or "index-id" composite keys for parallel
        ;; tool calls that share the same index but have different IDs.
        tool-calls* (atom {})

        ;; Combined reasoning state tracking
        ;; Tracks: id, type (:tag, :delta, or nil), content (accumulated reasoning), buffer (text buffer)
        reasoning-state* (atom {:id nil
                                :type nil
                                :content ""
                                :buffer ""})
        flush-content-buffer (fn []
                               (let [state @reasoning-state*
                                     buf (:buffer state)]
                                 (when (pos? (count buf))
                                   (if (= (:type state) :tag)
                                     (on-reason {:status :thinking :id (:id state) :text buf})
                                     (on-message-received {:type :text :text buf}))
                                   (swap! reasoning-state* assoc :buffer ""))))
        finish-reasoning (fn []
                           (let [state @reasoning-state*]
                             (when (:type state)
                               (let [accumulated-reasoning (when (= (:type state) :delta)
                                                             (not-empty (:content state)))]
                                 (on-reason (cond-> {:status :finished :id (:id state)}
                                              accumulated-reasoning (assoc :external-id accumulated-reasoning))))
                               (reset! reasoning-state* {:id nil :type nil :content "" :buffer ""}))))
        wrapped-on-error (fn [error-data]
                           (finish-reasoning)
                           (when on-error (on-error error-data)))
        start-delta-reasoning (fn []
                                (let [new-reason-id (str (random-uuid))]
                                  (swap! reasoning-state* assoc
                                         :id new-reason-id
                                         :type :delta
                                         :content ""
                                         :buffer "")
                                  (on-reason {:status :started :id new-reason-id})))
        on-tools-called-wrapper (fn on-tools-called-wrapper [tools-to-call on-tools-called handle-response]
                                  (when-let [{:keys [new-messages]} (on-tools-called tools-to-call)]
                                    (let [new-messages-list (vec (concat
                                                                  (when instructions [{:role "system" :content instructions}])
                                                                  (normalize-messages new-messages supports-image? think-tag-start think-tag-end)))
                                          new-rid (llm-util/gen-rid)]
                                      (reset! tool-calls* {})
                                      (base-chat-request!
                                       {:rid new-rid
                                        :body (assoc body :messages new-messages-list)
                                        :on-tools-called-wrapper on-tools-called-wrapper
                                        :extra-headers extra-headers
                                        :api-url api-url
                                        :api-key api-key
                                        :url-relative-path url-relative-path
                                        :on-error wrapped-on-error
                                        :on-stream (when stream? (fn [event data] (handle-response event data tool-calls* new-rid)))}))))

        handle-response (fn handle-response [event data tool-calls* rid]
                          (if (= event "stream-end")
                            (do
                              ;; Flush any leftover buffered content and finish reasoning if needed
                              (flush-content-buffer)
                              (finish-reasoning)
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
                                                                (:reasoning_content delta))]
                                    (let [state @reasoning-state*]
                                      (when-not (= (:type state) :delta)
                                        (start-delta-reasoning))
                                      ;; Accumulate reasoning content for APIs that require it back (e.g., DeepSeek)
                                      (swap! reasoning-state* update :content str reasoning-text)
                                      (on-reason {:status :thinking
                                                  :id (:id state)
                                                  :text reasoning-text})))

                                  ;; Check if reasoning just stopped (delta-based)
                                  (let [state @reasoning-state*]
                                    (when (and (= (:type state) :delta)
                                               (nil? (:reasoning delta))
                                               (nil? (:reasoning_content delta))
                                               on-reason)
                                      ;; Pass accumulated reasoning_content as external-id for DeepSeek compatibility
                                      (let [accumulated-reasoning (not-empty (:content state))]
                                        (on-reason (cond-> {:status :finished :id (:id state)}
                                                     accumulated-reasoning (assoc :external-id accumulated-reasoning))))
                                      (reset! reasoning-state* {:id nil :type nil :content "" :buffer ""})))

                                  ;; Process tool calls if present
                                  (when (:tool_calls delta)
                                    ;; Flush any leftover buffered content before finishing
                                    (flush-content-buffer)
                                    (doseq [tool-call (:tool_calls delta)]
                                      (let [{:keys [index id function]} tool-call
                                            {name :name args :arguments} function
                                            ;; Use RID as key to avoid collisions between API requests
                                            tool-key (str rid "-" index)
                                            ;; Create globally unique tool call ID for client
                                            unique-id (when id (str rid "-" id))]
                                        (when (and name unique-id)
                                          (on-prepare-tool-call {:id unique-id
                                                                 :full-name name
                                                                 :arguments-text ""}))
                                        (swap! tool-calls* update tool-key
                                               (fn [existing]
                                                 (cond-> (or existing {:index index})
                                                   unique-id (assoc :id unique-id)
                                                   name (assoc :full-name name)
                                                   args (update :arguments-text (fnil str "") args))))
                                        (when-let [updated-tool-call (get @tool-calls* tool-key)]
                                          (when (and (:id updated-tool-call)
                                                     (:full-name updated-tool-call)
                                                     args)
                                            (on-prepare-tool-call (assoc updated-tool-call :arguments-text args)))))))
                                  ;; Process finish reason if present (but not tool_calls which is handled above)
                                  (when finish-reason
                                    ;; Flush any leftover buffered content before finishing
                                    (flush-content-buffer)
                                    ;; Handle reasoning completion
                                    (finish-reasoning)
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
      :url-relative-path url-relative-path
      :tool-calls* tool-calls*
      :on-tools-called-wrapper on-tools-called-wrapper
      :on-error wrapped-on-error
      :on-stream (when stream?
                   (fn [event data] (handle-response event data tool-calls* rid)))})))
