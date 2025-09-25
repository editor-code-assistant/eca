(ns eca.llm-providers.openai-chat
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [hato.client :as http]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[OPENAI-CHAT]")

(def ^:private chat-completions-path "/chat/completions")

(defn ^:private extract-content
  "Extract text content from various message content formats.
   Handles: strings (legacy eca), nested arrays from chat.clj, and fallback."
  [content supports-image?]
  (cond
    ;; Legacy/fallback: handles system messages, error strings, or unexpected simple text content
    (string? content)
    [{:type "text"
      :text (string/trim content)}]

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
           :function (select-keys tool [:name :description :parameters])})
        tools))

(defn ^:private base-request! [{:keys [rid extra-headers body api-url api-key on-error on-response]}]
  (let [url (str api-url chat-completions-path)]
    (llm-util/log-request logger-tag rid url body)
    (http/post
     url
     {:headers (merge {"Authorization" (str "Bearer " api-key)
                       "Content-Type" "application/json"}
                      extra-headers)
      :body (json/generate-string body)
      :throw-exceptions? false
      :async? true
      :as :stream}
     (fn [{:keys [status body]}]
       (try
         (if (not= 200 status)
           (let [body-str (slurp body)]
             (logger/warn logger-tag "Unexpected response status: %s body: %s" status body-str)
             (on-error {:message (format "LLM response status: %s body: %s" status body-str)}))
           (with-open [rdr (io/reader body)]
             (doseq [[event data] (llm-util/event-data-seq rdr)]
               (llm-util/log-response logger-tag rid event data)
               (on-response event data))
             (on-response "stream-end" {})))
         (catch Exception e
           (on-error {:exception e}))))
     (fn [e]
       (on-error {:exception e})))))

(defn ^:private transform-message
  "Transform a single ECA message to OpenAI format. Returns nil for unsupported roles."
  [{:keys [role content] :as _msg} supports-image?]
  (case role
    "tool_call"        {:type :tool-call  ; Special marker for accumulation
                        :data {:id (:id content)
                               :type "function"
                               :function {:name (:name content)
                                          :arguments (json/generate-string (:arguments content))}}}
    "tool_call_output" {:role "tool"
                        :tool_call_id (:id content)
                        :content (llm-util/stringfy-tool-result content)}
    "user"             {:role "user"
                        :content (extract-content content supports-image?)}
    "assistant"        {:role "assistant"
                        :content (extract-content content supports-image?)}
    "system"           {:role "system"
                        :content (extract-content content supports-image?)}
    nil))

(defn ^:private accumulate-tool-calls
  "Handle tool call accumulation according to OpenAI API requirements.
   Tool calls must be grouped into assistant messages."
  [transformed-messages]
  (let [{:keys [messages tool-calls]}
        (reduce (fn [acc msg]
                  (if (= :tool-call (:type msg))
                    (update acc :tool-calls conj (:data msg))
                    (let [acc-with-flushed
                          (if (seq (:tool-calls acc))
                            (-> acc
                                (update :messages conj {:role "assistant"
                                                        :tool_calls (:tool-calls acc)})
                                (assoc :tool-calls []))
                            acc)]
                      (update acc-with-flushed :messages conj msg))))
                {:messages [] :tool-calls []}
                transformed-messages)]
    ;; Flush any remaining tool calls
    (if (seq tool-calls)
      (conj messages {:role "assistant" :tool_calls tool-calls})
      messages)))

(defn ^:private valid-message?
  "Check if a message should be included in the final output."
  [{:keys [role content tool_calls] :as msg}]
  (and msg
       (or (= role "tool")           ; Never remove tool messages
           (seq tool_calls)          ; Keep messages with tool calls
           (and (string? content)
                (not (string/blank? content)))
           (sequential? content))))

(defn ^:private normalize-messages
  "Converts ECA message format to OpenAI API format (also used by compatible providers).

   Key transformations:
   - Flushes accumulated tool_calls into a single assistant message (OpenAI API requirement)
   - Converts tool_call role to tool_calls array in assistant message
   - Converts tool_call_output role to tool role with tool_call_id
   - Extracts content from various message content formats

   The OpenAI Chat Completions API requires that tool_calls must be present in an
   'assistant' role message, not as separate messages. This function ensures compliance
   with that requirement by accumulating tool calls and flushing them into assistant
   messages when a non-tool_call message is encountered."
  [messages supports-image?]
  (->> messages
       (map #(transform-message % supports-image?))
       (remove nil?)
       accumulate-tool-calls
       (filter valid-message?)))

(defn ^:private execute-accumulated-tools!
  [{:keys [tool-calls-atom instructions extra-headers body api-url api-key
           on-tools-called on-error handle-response supports-image?]}]
  (let [all-accumulated (vals @tool-calls-atom)
        completed-tools (->> all-accumulated
                             (filter #(every? % [:id :name :arguments-text]))
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
      (when-let [{:keys [new-messages]} (on-tools-called valid-tools)]
        (let [new-messages-list (vec (concat
                                      (when instructions [{:role "system" :content instructions}])
                                      (normalize-messages new-messages supports-image?)))]
          (reset! tool-calls-atom {})
          (let [new-rid (llm-util/gen-rid)]
            (base-request!
             {:rid new-rid
              :body (assoc body :messages new-messages-list)
              :extra-headers extra-headers
              :api-url api-url
              :api-key api-key
              :on-error on-error
              :on-response (fn [event data] (handle-response event data tool-calls-atom new-rid))}))))
      ;; No completed tools at all - let the streaming response provide the actual finish_reason
      nil)))

(defn chat!
  "Primary entry point for OpenAI chat completions with streaming support.

   Handles the full conversation flow including tool calls, streaming responses,
   and message normalization. Supports both single and parallel tool execution.
   Compatible with OpenRouter and other OpenAI-compatible providers."
  [{:keys [model user-messages instructions temperature api-key api-url max-output-tokens
           past-messages tools extra-payload extra-headers supports-image?]
    :or {temperature 1.0}}
   {:keys [on-message-received on-error on-prepare-tool-call on-tools-called on-reason]}]
  (let [messages (vec (concat
                       (when instructions [{:role "system" :content instructions}])
                       (normalize-messages past-messages supports-image?)
                       (normalize-messages user-messages supports-image?)))

        body (merge {:model               model
                     :messages            messages
                     :temperature         temperature
                     :stream              true
                     :parallel_tool_calls true}
                    (when max-output-tokens {:max_tokens max-output-tokens})
                    (when (seq tools) {:tools (->tools tools)})
                    extra-payload)

        ;; Atom to accumulate tool call data from streaming chunks.
        ;; OpenAI streams tool call arguments across multiple chunks, so we need to
        ;; accumulate the partial JSON strings before parsing them. Keys are either
        ;; index numbers for simple cases, or "index-id" composite keys for parallel
        ;; tool calls that share the same index but have different IDs.
        tool-calls* (atom {})

        ;; Reasoning state tracking - generate new ID for each thinking block
        current-reason-id* (atom nil)
        reasoning-started* (atom false)

        handle-response (fn handle-response [event data tool-calls-atom rid]
                          (if (= event "stream-end")
                            (execute-accumulated-tools!
                             {:tool-calls-atom tool-calls-atom
                              :instructions    instructions
                              :supports-image? supports-image?
                              :extra-headers   extra-headers
                              :body            body
                              :api-url         api-url
                              :api-key         api-key
                              :on-tools-called on-tools-called
                              :on-error        on-error
                              :handle-response handle-response})
                            (when (seq (:choices data))
                              (doseq [choice (:choices data)]
                                (let [delta         (:delta choice)
                                      finish-reason (:finish_reason choice)]
                                  ;; Process content if present
                                  (when (:content delta)
                                    (on-message-received {:type :text :text (:content delta)}))

                                  ;; Process reasoning if present (o1 models and compatible providers)
                                  (when-let [reasoning-text (or (:reasoning delta)
                                                                (:reasoning_content delta))]
                                    (when on-reason
                                      (when-not @reasoning-started*
                                        ;; Generate new reason-id for each thinking block
                                        (let [new-reason-id (str (random-uuid))]
                                          (reset! current-reason-id* new-reason-id)
                                          (reset! reasoning-started* true)
                                          (on-reason {:status :started :id new-reason-id})))
                                      (on-reason {:status :thinking
                                                  :id     @current-reason-id*
                                                  :text   reasoning-text})))

                                  ;; Check if reasoning just stopped (was active, now nil, and we have content)
                                  (when (and @reasoning-started*
                                             (nil? (:reasoning delta))
                                             (nil? (:reasoning_content delta))
                                             (:content delta)
                                             on-reason)
                                    (on-reason {:status :finished :id @current-reason-id*})
                                    (reset! reasoning-started* false))

                                  ;; Process tool calls if present
                                  (when (:tool_calls delta)
                                    (doseq [tool-call (:tool_calls delta)]
                                      (let [{:keys [index id function]}  tool-call
                                            {name :name args :arguments} function
                                            ;; Use RID as key to avoid collisions between API requests
                                            tool-key                     (str rid "-" index)
                                            ;; Create globally unique tool call ID for client
                                            unique-id                    (when id (str rid "-" id))]
                                        (when (and name unique-id)
                                          (on-prepare-tool-call {:id unique-id :name name :arguments-text ""}))
                                        (swap! tool-calls-atom update tool-key
                                               (fn [existing]
                                                 (cond-> (or existing {:index index})
                                                   unique-id (assoc :id unique-id)
                                                   name      (assoc :name name)
                                                   args      (update :arguments-text (fnil str "") args))))
                                        (when-let [updated-tool-call (get @tool-calls-atom tool-key)]
                                          (when (and (:id updated-tool-call) (:name updated-tool-call)
                                                     (not (string/blank? (:arguments-text updated-tool-call))))
                                            (on-prepare-tool-call updated-tool-call))))))
                                  ;; Process finish reason if present (but not tool_calls which is handled above)
                                  (when finish-reason
                                    ;; Handle reasoning completion
                                    (when (and @reasoning-started* on-reason)
                                      (on-reason {:status :finished :id @current-reason-id*})
                                      (reset! reasoning-started* false))
                                    ;; Handle regular finish
                                    (when (not= finish-reason "tool_calls")
                                      (on-message-received {:type :finish :finish-reason finish-reason}))))))))
        rid (llm-util/gen-rid)]
    (base-request!
     {:rid         rid
      :body        body
      :extra-headers extra-headers
      :api-url     api-url
      :api-key     api-key
      :tool-calls* tool-calls*
      :on-error    on-error
      :on-response (fn [event data] (handle-response event data tool-calls* rid))})))
