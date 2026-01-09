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
  (:import (java.io DataInputStream BufferedInputStream ByteArrayInputStream)))

;; --- Helper Functions ---

(defn resolve-model-id
  "Resolve model ID from configuration."
  [model-alias config]
  (let [keyword-alias (keyword model-alias)
        model-config (get-in config [:models keyword-alias])]
    (or (:modelName model-config)
        (name model-alias))))

(defn format-tool-spec [tool]
  (let [f (:function tool)]
    {:toolSpec {:name (:name f)
                :description (:description f)
                ;; AWS requires inputSchema wrapped in "json" key
                :inputSchema {:json (:parameters f)}}}))

(defn format-tool-config [tools]
  (let [tools-seq (if (sequential? tools) tools [tools])]
    (when (seq tools-seq)
      {:tools (mapv format-tool-spec tools-seq)})))

(defn parse-tool-result [content tool-call-id is-error?]
  (let [inner-content (try
                        (if is-error?
                          [{:text (str content)}]
                          [{:json (json/parse-string content true)}])
                        (catch Exception _
                          [{:text (str content)}]))]
    {:toolUseId tool-call-id
     :content inner-content
     :status (if is-error? "error" "success")}))

(defn message->bedrock [msg]
  (case (:role msg)
    "tool"
    {:role "user"
     :content [(parse-tool-result (:content msg)
                                  (:tool_call_id msg)
                                  (:error msg))]}
    
    "assistant"
    {:role "assistant"
     :content (if (:tool_calls msg)
                (mapv (fn [tc]
                        {:toolUse {:toolUseId (:id tc)
                                   :name (get-in tc [:function :name])
                                   :input (json/parse-string 
                                          (get-in tc [:function :arguments]) keyword)}})
                      (:tool_calls msg))
                [{:text (:content msg)}])}
    
    ;; Default/User
    {:role "user"
     :content [{:text (:content msg)}]}))

(defn build-payload [messages options]
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
      (seq system-blocks) 
      (assoc :system system-blocks)
      
      (:tools options) 
      (assoc :toolConfig (format-tool-config (:tools options)))
      
      ;; Add additionalModelRequestFields if present
      (seq additional-fields)
      (assoc :additionalModelRequestFields 
             (into {} (map (fn [[k v]] [(name k) v]) additional-fields))))))

(defn parse-bedrock-response [body]
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
      (let [tool-blocks (filter :toolUse content)
            tool-calls (mapv (fn [b]
                               (let [t (:toolUse b)]
                                 {:id (:toolUseId t)
                                  :type "function"
                                  :function {:name (:name t)
                                             :arguments (json/generate-string (:input t))}}))
                             tool-blocks)]
        {:role "assistant" :content nil :tool_calls tool-calls})
      
      (let [text (-> (filter :text content) first :text)]
        {:role "assistant" :content text}))))

;; --- Binary Stream Parser ---

(defn parse-event-stream
  "Parses AWS Event Stream (Binary format) from a raw InputStream.
  
   AWS Event Stream Protocol:
   - Prelude: Total Length (4) + Headers Length (4)
   - Headers: Variable length
   - Headers CRC: 4 bytes
   - Payload: Variable length  
   - Message CRC: 4 bytes"
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
        
         ;; 3. Skip headers CRC (4 bytes)
         (.skipBytes dis 4)
        
         ;; 4. Calculate and read payload
         ;; total-len = prelude(8) + headers + headers-crc(4) + payload + message-crc(4)
         (let [payload-len (- total-len 8 headers-len 4 4)
               payload-bytes (byte-array payload-len)]
        
           (when (> payload-len 0)
             (.readFully dis payload-bytes))
        
           ;; 5. Skip message CRC (4 bytes)
           (.skipBytes dis 4)
        
           ;; 6. Parse JSON payload if present
           (if (> payload-len 0)
             (let [payload-str (String. payload-bytes "UTF-8")
                   event (json/parse-string payload-str true)]
               (cons event (parse-event-stream dis)))
             ;; Empty payload (heartbeat), continue to next event
             (parse-event-stream dis))))
      
       (catch java.io.EOFException _ nil)
       (catch Exception e
         (logger/debug "Stream parsing error" e)
         nil)))))

(defn extract-text-deltas
  "Takes the sequence of parsed JSON events and extracts text content.
   Handles empty events (heartbeats) gracefully."
  [events]
  (vec (keep (fn [event]
               (when-let [delta (get-in event [:contentBlockDelta :delta])]
                 (:text delta)))
             events)))

;; --- Endpoint Construction ---

(defn- build-endpoint
  "Constructs the API endpoint URL with model ID interpolation."
  [config model-id stream?]
  (let [raw-url (:url config)
        region (or (:region config) "us-east-1")
        suffix (if stream? "converse-stream" "converse")]
    (if raw-url
      ;; Interpolate {modelId} in custom proxy URLs
      (str/replace raw-url "{modelId}" model-id)
      ;; Construct standard AWS URL
      (format "https://bedrock-runtime.%s.amazonaws.com/model/%s/%s" 
              region model-id suffix))))

;; --- Public API Functions ---

(defn chat! [config callbacks]
  (let [token (or (:key config) (System/getenv "BEDROCK_API_KEY"))
        model-id (resolve-model-id (:model config) config)
        endpoint (build-endpoint config model-id false)
        timeout (or (:timeout config) 30000)
        headers {"Authorization" (str "Bearer " token)
                 "Content-Type" "application/json"}
        payload (build-payload (:user-messages config) (:extra-payload config))
        
        {:keys [status body error]} (http/post endpoint 
                                         {:headers headers
                                          :body (json/generate-string payload)
                                          :timeout timeout})]
    (if (and (not error) (= 200 status))
      (let [response (parse-bedrock-response body)
            {:keys [on-message-received on-error on-prepare-tool-call on-tools-called on-usage-updated]} callbacks]
        (if-let [tool-calls (:tool_calls response)]
          (do
            (on-prepare-tool-call tool-calls)
            {:tools-to-call tool-calls})
          (do
            (on-message-received {:type :text :text (:content response)})
            {:output-text (:content response)})))
      (do
        (logger/error "Bedrock API error" {:status status :error error :body body})
        (throw (ex-info "Bedrock API error" {:status status :body body}))))))

(defn stream-chat! [config callbacks]
  (let [token (or (:key config) (System/getenv "BEDROCK_API_KEY"))
        model-id (resolve-model-id (:model config) config)
        endpoint (build-endpoint config model-id true)
        timeout (or (:timeout config) 30000)
        headers {"Authorization" (str "Bearer " token)
                 "Content-Type" "application/json"}
        payload (build-payload (:user-messages config) (:extra-payload config))
        
        {:keys [status body error]} (http/post endpoint
                                         {:headers headers
                                          :body (json/generate-string payload)
                                          :timeout timeout})]
    (if (and (not error) (= 200 status))
      (let [{:keys [on-message-received on-error]} callbacks
            events (parse-event-stream body)
            texts (extract-text-deltas events)]
        (doseq [text texts]
          (on-message-received {:type :text :text text}))
        {:output-text (str/join "" texts)})
      (do
        (logger/error "Bedrock Stream API error" {:status status :error error})
        (throw (ex-info "Bedrock Stream API error" {:status status}))))))