(ns eca.llm-providers.aws-bedrock
  "AWS Bedrock provider implementation using Converse/ConverseStream APIs.

   AUTHENTICATION:
   This implementation uses Bearer token authentication, which requires
   an external proxy/gateway that handles AWS SigV4 signing.

   Set BEDROCK_API_KEY environment variable or configure :key in config.clj
   with a token provided by your authentication proxy.

   ENDPOINTS:
  - Standard: https://your-proxy.com/model/{modelId}/converse
  - Streaming: https://your-proxy.com/model/{modelId}/converse-stream

   Configure the :url in your provider config to point to your proxy endpoint."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [eca.logger :as logger]
   [hato.client :as http])
  (:import (java.io DataInputStream BufferedInputStream)))

;; --- Helper Functions ---

(defn resolve-model-id
  "Resolve model ID from configuration."
  [model-alias config]
  (let [keyword-alias (keyword model-alias)
        model-config (get-in config [:models keyword-alias])]
    (or (:modelName model-config)
        (name model-alias))))

(defn format-tool-spec
  "Convert ECA tool format to AWS Bedrock toolSpec format."
  [tool]
  (let [f (:function tool)]
    {:toolSpec {:name (:name f)
                :description (:description f)
                :inputSchema {:json (:parameters f)}}}))

(defn format-tool-config
  "Format tools into AWS Bedrock toolConfig structure."
  [tools]
  (let [tools-seq (if (sequential? tools) tools [tools])]
    (when (seq tools-seq)
      {:tools (mapv format-tool-spec tools-seq)})))

(defn parse-tool-result
  "Parse tool execution result into AWS Bedrock toolResult format.

   Handles both JSON objects and plain text responses.
   AWS Bedrock accepts content as either {:json ...} or {:text ...}."
  [content tool-call-id is-error?]
  (let [inner-content (try
                        (if is-error?
                          [{:text (str content)}]
                          ;; Try to parse as JSON for structured results
                          (let [parsed (if (string? content)
                                         (json/parse-string content true)
                                         content)]
                            (if (or (map? parsed) (vector? parsed))
                              [{:json parsed}]
                              [{:text (str content)}])))
                        (catch Exception e
                          (logger/debug "Failed to parse tool result as JSON, using text" e)
                          [{:text (str content)}]))]
    {:toolResult {:toolUseId tool-call-id
                  :content inner-content
                  :status (if is-error? "error" "success")}}))

(defn message->bedrock
  "Convert ECA message format to AWS Bedrock Converse API format.

   Message role mappings:
  - system: Handled separately in system blocks
  - user: Maps to user role with text content
  - assistant: Maps to assistant role with text or toolUse content
  - tool_call: Maps to user role with toolResult content (AWS requirement)"
  [msg]
  (case (:role msg)
    ;; AWS Bedrock requires tool results in a user message with toolResult block
    ;; ECA uses 'tool_call' role following OpenAI convention
    "tool_call"
    {:role "user"
     :content [(parse-tool-result (:content msg)
                                  (:tool_call_id msg)
                                  (:error msg))]}

    "assistant"
    {:role "assistant"
     :content (if (:tool_calls msg)
                ;; Assistant requesting tool calls
                (mapv (fn [tc]
                        {:toolUse {:toolUseId (:id tc)
                                   :name (get-in tc [:function :name])
                                   :input (json/parse-string
                                           (get-in tc [:function :arguments]) keyword)}})
                      (:tool_calls msg))
                ;; Standard assistant text response
                [{:text (:content msg)}])}

    ;; Default: user role with text content
    {:role "user"
     :content [{:text (:content msg)}]}))

(defn build-payload
  "Build AWS Bedrock Converse API request payload from messages and options.

   CRITICAL: For tool-enabled conversations, the caller (ECA core) MUST include
   tool definitions in options for every request after tools are first used.
   AWS Bedrock requires consistent toolConfig throughout the conversation."
  [messages options]
  (let [system-prompts (filter #(= (:role %) "system") messages)
        conversation (->> messages
                          (remove #(= (:role %) "system"))
                          (mapv message->bedrock))
        system-blocks (mapv (fn [m] {:text (:content m)}) system-prompts)

        ;; Base inference config
        base-config {:maxTokens (or (:max_tokens options) (:maxTokens options) 1024)
                     :temperature (or (:temperature options) 0.7)
                     :topP (or (:top_p options) (:topP options) 1.0)}

        ;; Additional model-specific fields (e.g., top_k for Claude)
        additional-fields (select-keys options [:top_k :topK])]

    (cond-> {:messages conversation
             :inferenceConfig (merge base-config
                                     (select-keys options [:stopSequences]))}
      ;; Add system prompts if present
      (seq system-blocks)
      (assoc :system system-blocks)

      ;; CRITICAL FIX: Only send toolConfig if tools are explicitly provided.
      ;; AWS Bedrock requires the full tool definitions if tools are active.
      ;; Sending an empty list {:tools []} causes a 400 error.
      ;; The caller (ECA core) is responsible for managing tool state.
      (:tools options)
      (assoc :toolConfig (format-tool-config (:tools options)))

      ;; Add model-specific fields if present
      (seq additional-fields)
      (assoc :additionalModelRequestFields
             (into {} (map (fn [[k v]] [(name k) v]) additional-fields))))))

(defn parse-bedrock-response
  "Parse AWS Bedrock Converse API response.

   Returns either:
  - {:role 'assistant' :content text} for standard responses
  - {:role 'assistant' :content nil :tool_calls [...]} for tool requests"
  [body]
  (let [response (json/parse-string body true)
        output-msg (get-in response [:output :message])
        stop-reason (:stopReason response)
        content (:content output-msg)
        usage (:usage response)]

    ;; Log token usage if present
    (when usage
      (logger/debug "Token usage" {:input (:inputTokens usage)
                                   :output (:outputTokens usage)
                                   :total (:totalTokens usage)}))

    (if (= stop-reason "tool_use")
      ;; Model is requesting tool execution
      (let [tool-blocks (filter :toolUse content)
            tool-calls (mapv (fn [b]
                               (let [t (:toolUse b)]
                                 {:id (:toolUseId t)
                                  :type "function"
                                  :function {:name (:name t)
                                             :arguments (json/generate-string (:input t))}}))
                             tool-blocks)]
        {:role "assistant" :content nil :tool_calls tool-calls})

      ;; Standard text response
      (let [text (-> (filter :text content) first :text)]
        {:role "assistant" :content text}))))

;; --- Binary Stream Parser ---

(defn- convert-keyword-values
  "Convert keyword values to strings while preserving nested structures."
  [x]
  (cond
    (map? x) (into {} (map (fn [[k v]] [k (convert-keyword-values v)]) x))
    (vector? x) (vec (map convert-keyword-values x))
    (and (keyword? x) (not (namespace x))) (name x)
    :else x))

(defn parse-event-stream
  "Parses AWS Event Stream (Binary format) from a raw InputStream.

   AWS Event Stream Protocol (per AWS documentation):
  - Prelude: Total Length (4 bytes) + Headers Length (4 bytes) [Big Endian]
  - Headers: Variable length key-value pairs
  - Headers CRC: 4 bytes (CRC32 checksum)
  - Payload: Variable length (typically JSON)
  - Message CRC: 4 bytes (CRC32 checksum)

   This implementation reads and validates the structure, extracting JSON payloads
   for processing. Empty payloads (heartbeats) are handled gracefully."
  [^java.io.InputStream input-stream]
  (let [dis (DataInputStream. (BufferedInputStream. input-stream))]
    (lazy-seq
     (try
       ;; 1. Read Prelude (8 bytes, Big Endian)
       (let [total-len (.readInt dis)
             headers-len (.readInt dis)]

         ;; 2. Read and skip headers
         (when (> headers-len 0)
           (let [header-bytes (byte-array headers-len)]
             (.readFully dis header-bytes)))

         ;; 3. Read headers CRC (4 bytes)
         ;; FIXED: Use readFully instead of skipBytes for reliability
         (let [headers-crc (byte-array 4)]
           (.readFully dis headers-crc))

         ;; 4. Calculate and read payload
         ;; Formula: total-len = prelude(8) + headers + headers-crc(4) + payload + message-crc(4)
         (let [payload-len (- total-len 8 headers-len 4 4)
               payload-bytes (byte-array (max 0 payload-len))]

           (when (> payload-len 0)
             (.readFully dis payload-bytes))

           ;; 5. Read message CRC (4 bytes)
           (let [message-crc (byte-array 4)]
             (.readFully dis message-crc))

           ;; 6. Parse JSON payload if present
           (if (> payload-len 0)
             (let [payload-str (String. payload-bytes "UTF-8")
                   event (json/parse-string payload-str true)
                   ;; Convert keyword values back to strings
                   event (convert-keyword-values event)]
               (cons event (parse-event-stream dis)))
             ;; Empty payload (heartbeat), continue to next event
             (parse-event-stream dis))))

       (catch java.io.EOFException _
         ;; End of stream reached normally
         nil)
       (catch Exception e
         (logger/debug "Stream parsing error" {:error (.getMessage e)})
         nil)))))

(defn extract-text-deltas
  "Extract text content from AWS Event Stream events.

   Filters contentBlockDelta events and extracts text deltas.
   Handles empty events (heartbeats) gracefully."
  [events]
  (vec (keep (fn [event]
               (when-let [delta (get-in event [:contentBlockDelta :delta])]
                 (:text delta)))
             events)))

(defn extract-tool-calls-from-stream
  "Extract tool calls from AWS Event Stream events.

   Handles contentBlockDelta events with toolUse information.
   Accumulates tool use data across multiple delta events."
  [events]
  (let [tool-calls (atom {})]
    (doseq [event events]
      (when-let [delta (get-in event [:contentBlockDelta :delta])]
        (when-let [tool-use (get-in delta [:toolUse])]
          (let [tool-id (:toolUseId tool-use)
                existing (get @tool-calls tool-id {})]
            (swap! tool-calls assoc tool-id
                   (merge existing tool-use))))))
    (vec (vals @tool-calls))))

;; --- Endpoint Construction ---

(defn build-endpoint
  "Constructs the API endpoint URL with model ID interpolation.

   Supports two modes:
   1. Custom proxy URL (base URL without placeholder)
   2. Standard AWS Bedrock URL (requires region)"
  [config model-id stream?]
  (let [raw-url (:url config)
        region (or (:region config) "us-east-1")
        suffix (if stream? "converse-stream" "converse")
        full-model-id (str region "." model-id)]
    (if raw-url
      ;; Custom proxy URL: append region.modelId/suffix
      (str raw-url full-model-id "/" suffix)
      
      ;; Standard AWS Bedrock URL
      (format "https://bedrock-runtime.%s.amazonaws.com/model/%s/%s"
              region full-model-id suffix))))

;; --- Public API Functions ---

(defn chat!
  "Execute synchronous chat completion via AWS Bedrock Converse API.

   Required config keys:
  - :key or BEDROCK_API_KEY env var: Bearer token for authentication
  - :model: Model alias or ID
  - :user-messages: Conversation history
  - :extra-payload: Additional options (tools, temperature, etc.)

   Returns map with either:
  - {:output-text string} for text responses
  - {:tools-to-call [...]} for tool call requests"
  [config callbacks]
  (let [token (or (:key config) (System/getenv "BEDROCK_API_KEY"))
        model-id (resolve-model-id (:model config) config)
        endpoint (build-endpoint config model-id false)
        timeout (or (:timeout config) 30000)
        headers {"Authorization" (str "Bearer " token)
                 "Content-Type" "application/json"}
        payload (build-payload (:user-messages config) (:extra-payload config))

        _ (logger/debug "Bedrock request" {:endpoint endpoint
                                           :model-id model-id
                                           :message-count (count (:messages payload))})

        {:keys [status body error]} (http/post endpoint
                                               {:headers headers
                                                :body (json/generate-string payload)
                                                :timeout timeout})]
    (if (and (not error) (= 200 status))
      (let [response (parse-bedrock-response body)
            {:keys [on-message-received on-prepare-tool-call]} callbacks]
        (if-let [tool-calls (:tool_calls response)]
          ;; Model requesting tool execution
          (do
            (on-prepare-tool-call tool-calls)
            {:tools-to-call tool-calls})
          ;; Standard text response
          (do
            (on-message-received {:type :text :text (:content response)})
            {:output-text (:content response)})))
      (do
        (logger/error "Bedrock API error" {:status status :error error :body body})
        (throw (ex-info "Bedrock API error" {:status status :body body}))))))

(defn stream-chat!
  "Execute streaming chat completion via AWS Bedrock ConverseStream API.

   Required config keys:
  - :key or BEDROCK_API_KEY env var: Bearer token for authentication
  - :model: Model alias or ID
  - :user-messages: Conversation history
  - :extra-payload: Additional options (tools, temperature, etc.)

   Streams text deltas via on-message-received callback.
   Returns map with {:output-text string} containing complete response."
  [config callbacks]
  (let [token (or (:key config) (System/getenv "BEDROCK_API_KEY"))
        model-id (resolve-model-id (:model config) config)
        endpoint (build-endpoint config model-id true)
        timeout (or (:timeout config) 30000)
        headers {"Authorization" (str "Bearer " token)
                 "Content-Type" "application/json"}
        payload (build-payload (:user-messages config) (:extra-payload config))

        _ (logger/debug "Bedrock stream request" {:endpoint endpoint
                                                  :model-id model-id
                                                  :message-count (count (:messages payload))})

        {:keys [status body error]} (http/post endpoint
                                               {:headers headers
                                                :body (json/generate-string payload)
                                                :timeout timeout
                                                ;; CRITICAL: Request raw InputStream for binary parsing
                                                :as :stream})]
    (try
      (if (and (not error) (= 200 status))
        (let [{:keys [on-message-received on-prepare-tool-call]} callbacks
              events (or (parse-event-stream body) [])
              texts (extract-text-deltas events)
              tool-calls (extract-tool-calls-from-stream events)]
          
          ;; Stream each text delta to callback
          (doseq [text texts]
            (on-message-received {:type :text :text text}))
          
          ;; Handle tool calls if present
          (if (seq tool-calls)
            (let [formatted-tool-calls (mapv (fn [tc]
                                               {:id (:toolUseId tc)
                                                :type "function"
                                                :function {:name (:name tc)
                                                           :arguments (json/generate-string (:input tc))}})
                                             tool-calls)]
              (on-prepare-tool-call formatted-tool-calls)
              {:tools-to-call formatted-tool-calls})
            
            ;; Return complete text response
            {:output-text (str/join "" texts)}))
        (do
          (logger/error "Bedrock Stream API error" {:status status :error error})
          (throw (ex-info "Bedrock Stream API error" {:status status}))))
      (finally
        ;; CRITICAL: Ensure stream is closed to prevent resource leaks
        (when (instance? java.io.Closeable body)
          (.close ^java.io.Closeable body))))))
