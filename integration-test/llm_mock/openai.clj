(ns llm-mock.openai
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

(defn ^:private simple-text-0 [ch]
  (sse-send! ch "response.output_text.delta"
             {:type "response.output_text.delta" :delta "Knock"})
  (sse-send! ch "response.output_text.delta"
             {:type "response.output_text.delta" :delta " knock!"})
  (sse-send! ch "response.completed"
             {:type "response.completed"
              :response {:output []
                         :usage {:input_tokens 10
                                 :output_tokens 20}
                         :status "completed"}})
  (hk/close ch))

(defn ^:private simple-text-1 [ch]
  (sse-send! ch "response.output_text.delta"
             {:type "response.output_text.delta" :delta "Foo"})
  (sse-send! ch "response.completed"
             {:type "response.completed"
              :response {:output []
                         :usage {:input_tokens 10
                                 :output_tokens 5}
                         :status "completed"}})
  (hk/close ch))

(defn ^:private simple-text-2 [ch]
  (sse-send! ch "response.output_text.delta"
             {:type "response.output_text.delta" :delta "Foo"})
  (sse-send! ch "response.output_text.delta"
             {:type "response.output_text.delta" :delta " bar!"})
  (sse-send! ch "response.output_text.delta"
             {:type "response.output_text.delta" :delta "\n\n"})
  (sse-send! ch "response.output_text.delta"
             {:type "response.output_text.delta" :delta "Ha!"})
  (sse-send! ch "response.completed"
             {:type "response.completed"
              :response {:output []
                         :usage {:input_tokens 5
                                 :output_tokens 15}
                         :status "completed"}})
  (hk/close ch))

(defn ^:private reasoning-0 [ch]
  (sse-send! ch "response.output_item.added"
             {:type "response.output_item.added"
              :item {:type "reasoning" :id "123"}})
  (sse-send! ch "response.reasoning_summary_text.delta"
             {:type "response.reasoning_summary_text.delta"
              :item_id "123"
              :delta "I should say"})
  (sse-send! ch "response.reasoning_summary_text.delta"
             {:type "response.reasoning_summary_text.delta"
              :item_id "123"
              :delta " hello"})
  (sse-send! ch "response.output_item.done"
             {:type "response.output_item.done"
              :item {:type "reasoning"
                     :id "123"
                     :encrypted_content "enc-123"}})
  (sse-send! ch "response.output_text.delta"
             {:type "response.output_text.delta" :delta "hello"})
  (sse-send! ch "response.output_text.delta"
             {:type "response.output_text.delta" :delta " there!"})
  (sse-send! ch "response.completed"
             {:type "response.completed"
              :response {:output []
                         :usage {:input_tokens 5
                                 :output_tokens 30}
                         :status "completed"}})
  (hk/close ch))

(defn ^:private reasoning-1 [ch]
  (sse-send! ch "response.output_item.added"
             {:type "response.output_item.added"
              :item {:type "reasoning" :id "234"}})
  (sse-send! ch "response.reasoning_summary_text.delta"
             {:type "response.reasoning_summary_text.delta"
              :item_id "234"
              :delta "I should say"})
  (sse-send! ch "response.reasoning_summary_text.delta"
             {:type "response.reasoning_summary_text.delta"
              :item_id "234"
              :delta " fine"})
  (sse-send! ch "response.output_item.done"
             {:type "response.output_item.done"
              :item {:type "reasoning"
                     :id "234"
                     :encrypted_content "enc-234"}})
  (sse-send! ch "response.output_text.delta"
             {:type "response.output_text.delta" :delta "I'm "})
  (sse-send! ch "response.output_text.delta"
             {:type "response.output_text.delta" :delta " fine"})
  (sse-send! ch "response.completed"
             {:type "response.completed"
              :response {:output []
                         :usage {:input_tokens 10
                                 :output_tokens 20}
                         :status "completed"}})
  (hk/close ch))

(defn ^:private tool-calling-0 [ch body]
  (let [second-stage? (some #(= "function_call_output" (:type %)) (:input body))]
    (if-not second-stage?
      (let [args-json (json/generate-string {:path (h/project-path->canon-path "resources")})]
        ;; Reasoning prelude
        (sse-send! ch "response.output_item.added"
                   {:type "response.output_item.added"
                    :item {:type "reasoning" :id "123"}})
        (sse-send! ch "response.reasoning_summary_text.delta"
                   {:type "response.reasoning_summary_text.delta"
                    :item_id "123"
                    :delta "I should call tool"})
        (sse-send! ch "response.reasoning_summary_text.delta"
                   {:type "response.reasoning_summary_text.delta"
                    :item_id "123"
                    :delta " eca_directory_tree"})
        (sse-send! ch "response.output_item.done"
                   {:type "response.output_item.done"
                    :item {:type "reasoning"
                           :id "123"
                           :encrypted_content "enc-123"}})
        ;; Short text before tool call
        (sse-send! ch "response.output_text.delta"
                   {:type "response.output_text.delta" :delta "I will list files"})
        ;; Function call announced
        (sse-send! ch "response.output_item.added"
                   {:type "response.output_item.added"
                    :item {:type "function_call"
                           :id "item-1"
                           :call_id "tool-1"
                           :name "eca_directory_tree"
                           :arguments ""}})
        ;; Stream arguments in two chunks
        (sse-send! ch "response.function_call_arguments.delta"
                   {:type "response.function_call_arguments.delta"
                    :item_id "item-1"
                    :delta "{\"pat"})
        (sse-send! ch "response.function_call_arguments.delta"
                   {:type "response.function_call_arguments.delta"
                    :item_id "item-1"
                    :delta (str "h\":\"" (h/project-path->canon-path "resources") "\"}")})
        ;; Complete with the function call present so the client triggers tools
        (sse-send! ch "response.completed"
                   {:type "response.completed"
                    :response {:output [{:type "function_call"
                                         :id "item-1"
                                         :call_id "tool-1"
                                         :name "eca_directory_tree"
                                         :arguments args-json}]
                               :usage {:input_tokens 5
                                       :output_tokens 30}
                               :status "completed"}})
        (hk/close ch))
      ;; Second stage: after tool outputs are supplied back to the model
      (do
        (sse-send! ch "response.output_text.delta"
                   {:type "response.output_text.delta" :delta "The files I see:\n"})
        (sse-send! ch "response.output_text.delta"
                   {:type "response.output_text.delta" :delta "file1\nfile2\n"})
        (sse-send! ch "response.completed"
                   {:type "response.completed"
                    :response {:output []
                               :usage {:input_tokens 5
                                       :output_tokens 30}
                               :status "completed"}})
        (hk/close ch)))))

(defn ^:private chat-title-text-0 [ch]
  (hk/send! ch
            (json/generate-string
             {:output [{:content [{:text "Some Cool Title"}]}]})
            true))

(defn handle-openai-responses [req]
  (let [body (some-> (slurp (:body req))
                     (json/parse-string true))]
    (hk/as-channel
     req
     {:on-open (fn [ch]
                  ;; initial SSE handshake
                 (hk/send! ch {:status 200
                               :headers {"Content-Type" "text/event-stream; charset=utf-8"
                                         "Cache-Control" "no-cache"
                                         "Connection" "keep-alive"}}
                           false)
                 (if (string/includes? (:instructions body) llm.mocks/chat-title-generator-str)
                   (chat-title-text-0 ch)
                   (do
                     (llm.mocks/set-req-body! llm.mocks/*case* body)
                     (case llm.mocks/*case*
                       :simple-text-0 (simple-text-0 ch)
                       :simple-text-1 (simple-text-1 ch)
                       :simple-text-2 (simple-text-2 ch)
                       :reasoning-0 (reasoning-0 ch)
                       :reasoning-1 (reasoning-1 ch)
                       :tool-calling-0 (tool-calling-0 ch body)))))})))
