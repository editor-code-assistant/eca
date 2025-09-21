(ns llm-mock.anthropic
  (:require
   [cheshire.core :as json]
   [clojure.string :as string]
   [integration.helper :as h]
   [llm-mock.mocks :as llm.mocks]
   [org.httpkit.server :as hk]))

(defn ^:private sse-send!
  "Send one SSE event line pair: event + data, followed by a blank line.
  Keep the connection open until the scenario finishes."
  [ch event m]
  (hk/send! ch (str "event: " event "\n") false)
  (hk/send! ch (str "data: " (json/generate-string m) "\n\n") false))

;; Simple text cases
(defn ^:private simple-text-0 [ch]
  (sse-send! ch "content_block_delta"
             {:type "content_block_delta"
              :index 0
              :delta {:type "text_delta" :text "Knock"}})
  (sse-send! ch "content_block_delta"
             {:type "content_block_delta"
              :index 0
              :delta {:type "text_delta" :text " knock!"}})
  (sse-send! ch "message_delta"
             {:type "message_delta"
              :delta {:stop_reason "end_turn"}
              :usage {:input_tokens 10
                      :output_tokens 20}})
  (hk/close ch))

(defn ^:private simple-text-1 [ch]
  (sse-send! ch "content_block_delta"
             {:type "content_block_delta"
              :index 0
              :delta {:type "text_delta" :text "Foo"}})
  (sse-send! ch "message_delta"
             {:type "message_delta"
              :delta {:stop_reason "end_turn"}
              :usage {:input_tokens 10
                      :output_tokens 5}})
  (hk/close ch))

(defn ^:private simple-text-2 [ch]
  (sse-send! ch "content_block_delta"
             {:type "content_block_delta"
              :index 0
              :delta {:type "text_delta" :text "Foo"}})
  (sse-send! ch "content_block_delta"
             {:type "content_block_delta"
              :index 0
              :delta {:type "text_delta" :text " bar!"}})
  (sse-send! ch "content_block_delta"
             {:type "content_block_delta"
              :index 0
              :delta {:type "text_delta" :text "\n\n"}})
  (sse-send! ch "content_block_delta"
             {:type "content_block_delta"
              :index 0
              :delta {:type "text_delta" :text "Ha!"}})
  (sse-send! ch "message_delta"
             {:type "message_delta"
              :delta {:stop_reason "end_turn"}
              :usage {:input_tokens 5
                      :output_tokens 15}})
  (hk/close ch))

;; Reasoning cases
(defn ^:private reasoning-0 [ch]
  ;; Start thinking block
  (sse-send! ch "content_block_start"
             {:type "content_block_start"
              :index 0
              :content_block {:type "thinking"}})
  (sse-send! ch "content_block_delta"
             {:type "content_block_delta"
              :index 0
              :delta {:type "thinking_delta" :thinking "I should say"}})
  (sse-send! ch "content_block_delta"
             {:type "content_block_delta"
              :index 0
              :delta {:type "thinking_delta" :thinking " hello"}})
  (sse-send! ch "content_block_delta"
             {:type "content_block_delta"
              :index 0
              :delta {:type "signature_delta" :signature "enc-123"}})
  ;; Now stream assistant text
  (sse-send! ch "content_block_delta"
             {:type "content_block_delta"
              :index 1
              :delta {:type "text_delta" :text "hello"}})
  (sse-send! ch "content_block_delta"
             {:type "content_block_delta"
              :index 1
              :delta {:type "text_delta" :text " there!"}})
  (sse-send! ch "message_delta"
             {:type "message_delta"
              :delta {:stop_reason "end_turn"}
              :usage {:input_tokens 5
                      :output_tokens 30}})
  (hk/close ch))

(defn ^:private reasoning-1 [ch]
  ;; Start thinking block
  (sse-send! ch "content_block_start"
             {:type "content_block_start"
              :index 0
              :content_block {:type "thinking"}})
  (sse-send! ch "content_block_delta"
             {:type "content_block_delta"
              :index 0
              :delta {:type "thinking_delta" :thinking "I should say"}})
  (sse-send! ch "content_block_delta"
             {:type "content_block_delta"
              :index 0
              :delta {:type "thinking_delta" :thinking " fine"}})
  (sse-send! ch "content_block_delta"
             {:type "content_block_delta"
              :index 0
              :delta {:type "signature_delta" :signature "enc-234"}})
  ;; Now stream assistant text
  (sse-send! ch "content_block_delta"
             {:type "content_block_delta"
              :index 1
              :delta {:type "text_delta" :text "I'm "}})
  (sse-send! ch "content_block_delta"
             {:type "content_block_delta"
              :index 1
              :delta {:type "text_delta" :text " fine"}})
  (sse-send! ch "message_delta"
             {:type "message_delta"
              :delta {:stop_reason "end_turn"}
              :usage {:input_tokens 10
                      :output_tokens 20}})
  (hk/close ch))

(defn ^:private tool-calling-0 [ch]
  (let [body llm.mocks/*last-req-body*
        second-stage? (some (fn [{:keys [content]}]
                              (some #(= "tool_result" (:type %)) content))
                            (:messages body))]
    (if-not second-stage?
      (do
        ;; Thinking prelude
        (sse-send! ch "content_block_start"
                   {:type "content_block_start"
                    :index 0
                    :content_block {:type "thinking"}})
        (sse-send! ch "content_block_delta"
                   {:type "content_block_delta"
                    :index 0
                    :delta {:type "thinking_delta" :thinking "I should call tool"}})
        (sse-send! ch "content_block_delta"
                   {:type "content_block_delta"
                    :index 0
                    :delta {:type "thinking_delta" :thinking " eca_directory_tree"}})
        (sse-send! ch "content_block_delta"
                   {:type "content_block_delta"
                    :index 0
                    :delta {:type "signature_delta" :signature "enc-123"}})
        ;; Short assistant text before tool use
        (sse-send! ch "content_block_delta"
                   {:type "content_block_delta"
                    :index 1
                    :delta {:type "text_delta" :text "I will list files"}})
        ;; Tool use block start
        (sse-send! ch "content_block_start"
                   {:type "content_block_start"
                    :index 2
                    :content_block {:type "tool_use"
                                    :id "tool-1"
                                    :name "eca_directory_tree"}})
        ;; Stream JSON args in two chunks
        (sse-send! ch "content_block_delta"
                   {:type "content_block_delta"
                    :index 2
                    :delta {:type "input_json_delta"
                            :partial_json "{\"pat"}})
        (sse-send! ch "content_block_delta"
                   {:type "content_block_delta"
                    :index 2
                    :delta {:type "input_json_delta"
                            :partial_json (str "h\":\"" (h/project-path->canon-path "resources") "\"}")}})
        ;; Finish the message indicating a tool_use stop so the client triggers tools
        (sse-send! ch "message_delta"
                   {:type "message_delta"
                    :delta {:stop_reason "tool_use"}
                    :usage {:input_tokens 5
                            :output_tokens 30}})
        (hk/close ch))
      ;; Second stage after tool results are provided back
      (do
        (sse-send! ch "content_block_delta"
                   {:type "content_block_delta"
                    :index 0
                    :delta {:type "text_delta" :text "The files I see:\n"}})
        (sse-send! ch "content_block_delta"
                   {:type "content_block_delta"
                    :index 0
                    :delta {:type "text_delta" :text "file1\nfile2\n"}})
        (sse-send! ch "message_delta"
                   {:type "message_delta"
                    :delta {:stop_reason "end_turn"}
                    :usage {:input_tokens 5
                            :output_tokens 30}})
        (hk/close ch)))))

(defn ^:private chat-title-text-0 [ch]
  (sse-send! ch "content_block_delta"
             {:type "content_block_delta"
              :index 0
              :delta {:type "text_delta" :text "Some Cool"}})
  (sse-send! ch "content_block_delta"
             {:type "content_block_delta"
              :index 0
              :delta {:type "text_delta" :text " Title"}})
  (sse-send! ch "message_delta"
             {:type "message_delta"
              :delta {:stop_reason "end_turn"}
              :usage {:input_tokens 5
                      :output_tokens 10}})
  (hk/close ch))

(defn handle-anthropic-messages [req]
  (let [body (some-> (slurp (:body req))
                     (json/parse-string true))]
    (llm.mocks/set-last-req-body! body)
    (hk/as-channel
     req
     {:on-open (fn [ch]
                 (hk/send! ch {:status 200
                               :headers {"Content-Type" "text/event-stream; charset=utf-8"
                                         "Cache-Control" "no-cache"
                                         "Connection" "keep-alive"}}
                           false)
                 (if (string/includes? (:text (last (:system body))) llm.mocks/chat-title-generator-str)
                   (do
                     (Thread/sleep 2000) ;; avoid tests failing with mismatch order of contents
                     (chat-title-text-0 ch))
                   (case llm.mocks/*case*
                     :simple-text-0 (simple-text-0 ch)
                     :simple-text-1 (simple-text-1 ch)
                     :simple-text-2 (simple-text-2 ch)
                     :reasoning-0 (reasoning-0 ch)
                     :reasoning-1 (reasoning-1 ch)
                     :tool-calling-0 (tool-calling-0 ch))))})))
