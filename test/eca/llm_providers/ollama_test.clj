(ns eca.llm-providers.ollama-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [eca.client-test-helpers :refer [blocking-input-stream with-client-proxied]]
   [eca.llm-providers.ollama :as llm-providers.ollama]
   [hato.client :as http]
   [matcher-combinators.test :refer [match?]]))

(deftest list-models-test
  (testing "fetches available Ollama models"
    (let [req* (atom nil)
          fake-api-url "http://localhost:99"
          fake-response {:status 200
                         :body {:models [{:name "model-a"}
                                         {:name "model-b"}]}}]
      (with-client-proxied {}

        (fn handler [req]
          (reset! req* req)
          fake-response)

        (let [result (#'eca.llm-providers.ollama/list-models {:api-url fake-api-url})]
          (is (= {:method "GET"
                  :uri    "/api/tags"} ;; matches list-models-url "%s/api/tags"
                 (select-keys @req* [:method :uri])))

          ;; response parsing
          (is (= [{:name "model-a"} {:name "model-b"}] result)))))))

(deftest model-capabilities-test
  (testing "fetches capabilities for a specific Ollama model"
    (let [req* (atom nil)
          fake-api-url "http://localhost:99"
          fake-model "test-model"
          fake-response {:status 200
                         :body {:capabilities [:chat :completion]}}]
      (with-client-proxied {}

        (fn handler [req]
          (reset! req* req)
          fake-response)

        (let [result (#'eca.llm-providers.ollama/model-capabilities
                      {:model fake-model :api-url fake-api-url})]

          ;; request validation
          (is (= {:method "POST"
                  :uri    "/api/show"} ;; matches show-model-url "%s/api/show"
                 (select-keys @req* [:method :uri])))

          (is (= (json/generate-string {:model fake-model})
                 (:body @req*))
              "Outgoing payload should contain the model")

          (is (= ["chat" "completion"] result)))))))

(deftest base-chat-request-test
  (testing "sends Ollama chat request and extracts output text"
    (let [req* (atom nil)
          fake-url "http://localhost:99/api/chat"
          rid "test-rid"
          body {:model "test-model" :input "Hello"}
          fake-response {:status 200
                         :body {:message {:content "Hello world"}}}]
      (with-client-proxied {}

        (fn handler [req]
          (reset! req* req)
          fake-response)

        (let [result (#'eca.llm-providers.ollama/base-chat-request!
                       {:rid rid
                        :url fake-url
                        :body body})]

          ;; request validation
          (is (= {:method "POST"
                  :uri    "/api/chat"}
                 (select-keys @req* [:method :uri])))

          (is (= {:output-text "Hello world"} result)))))))

(deftest chat-request-enforces-max-output-tokens-test
  (let [requests* (atom [])]
    (with-client-proxied {}
      (fn handler [req]
        (swap! requests* conj req)
        {:status 200
         :body {:message {:content "ok"}}})
      (let [base-opts {:model "test-model"
                       :instructions "System prompt"
                       :user-messages [{:role "user" :content "hello"}]
                       :past-messages []
                       :tools nil
                       :api-url "http://localhost:1"
                       :max-output-tokens 512}]
        (llm-providers.ollama/chat! base-opts nil)
        (llm-providers.ollama/chat!
         (assoc base-opts :extra-payload {:options {:num_predict 99}}) nil)))
    (let [bodies (mapv #(json/parse-string (:body %) true) @requests*)]
      (is (= 512 (get-in bodies [0 :options :num_predict])))
      (is (= 99 (get-in bodies [1 :options :num_predict]))
          "Configured extraPayload must remain the final override"))))

(deftest ->normalize-messages-test
  (testing "no previous history"
    (is (match?
         []
         (#'llm-providers.ollama/normalize-messages []))))
  (testing "With basic text history"
    (is (match?
         [{:role "user" :content "Count with me: 1"}
          {:role "assistant" :content "2"}]
         (#'llm-providers.ollama/normalize-messages
          [{:role "user" :content "Count with me: 1"}
           {:role "assistant" :content "2"}]))))
  (testing "With tool_call history"
    (is (match?
         [{:role "user" :content "List the files you are allowed"}
          {:role "assistant" :content "Ok!"}
          {:role "assistant" :tool-calls [{:type "function"
                                           :function {:name "eca__list_allowed_directories"
                                                      :arguments {}}}]}
          {:role "tool" :content "Allowed directories: /foo/bar\n"}
          {:role "assistant" :content "I see /foo/bar"}]
         (#'llm-providers.ollama/normalize-messages
          [{:role "user" :content "List the files you are allowed"}
           {:role "assistant" :content "Ok!"}
           {:role "tool_call" :content {:id "call-1" :full-name "eca__list_allowed_directories" :arguments {}}}
           {:role "tool_call_output" :content {:id "call-1"
                                               :full-name "eca__list_allowed_directories"
                                               :arguments {}
                                               :output {:contents [{:type :text
                                                                    :error false
                                                                    :text "Allowed directories: /foo/bar"}]}}}
           {:role "assistant" :content "I see /foo/bar"}])))))

(deftest chat-stream-cancelled-test
  (testing "watchdog aborts a hung stream when cancelled, surfacing a silent error"
    (let [errors* (atom [])
          messages* (atom [])
          stream-body (blocking-input-stream)]
      (with-redefs [http/post (fn [_url opts]
                                (is (= :stream (:as opts)))
                                {:status 200
                                 :body stream-body})]
        (llm-providers.ollama/chat!
         {:model "test-model"
          :instructions "System prompt"
          :user-messages [{:role "user" :content "hello"}]
          :past-messages []
          :tools []
          :api-url "http://localhost:1"
          :cancelled? (constantly true)}
         {:on-message-received (fn [msg] (swap! messages* conj msg))
          :on-error (fn [err] (swap! errors* conj err))
          :on-prepare-tool-call (fn [_])
          :on-tools-called (fn [_] {:new-messages [] :tools []})
          :on-reason (fn [_])}))
      (is (= 1 (count @errors*)))
      (is (= "Stream cancelled" (ex-message (:exception (first @errors*)))))
      (is (true? (:silent? (ex-data (:exception (first @errors*))))))
      (is (empty? @messages*)))))

(defn ^:private ndjson-stream
  "Builds an in-memory NDJSON stream body like Ollama's streaming responses."
  [& chunks]
  (let [lines (apply str (map #(str (json/generate-string %) "\n") chunks))]
    (java.io.ByteArrayInputStream. (.getBytes ^String lines "UTF-8"))))

(defn ^:private run-stream-chat!
  "Runs a streamed chat! against a mocked Ollama response of a text chunk
   followed by `final-chunk`, returning the collected callback events."
  ([final-chunk] (run-stream-chat! final-chunk true))
  ([final-chunk usage-callback?]
   (let [events* (atom [])
         stream-body (ndjson-stream
                      {:message {:role "assistant" :content "Hello"} :done false}
                      final-chunk)
         callbacks (cond-> {:on-message-received (fn [msg] (swap! events* conj [:msg msg]))
                            :on-error (fn [err] (swap! events* conj [:error err]))
                            :on-prepare-tool-call (fn [_])
                            :on-tools-called (fn [_] nil)
                            :on-reason (fn [_])}
                     usage-callback?
                     (assoc :on-usage-updated (fn [usage] (swap! events* conj [:usage usage]))))]
     (with-redefs [http/post (fn [_url _opts] {:status 200 :body stream-body})]
       (llm-providers.ollama/chat!
        {:model "test-model"
         :instructions "System prompt"
         :user-messages [{:role "user" :content "hello"}]
         :past-messages []
         :tools []
         :api-url "http://localhost:1"}
        callbacks))
     @events*)))

(deftest chat-stream-usage-metrics-test
  (testing "emits usage from prompt_eval_count and eval_count before finish"
    (is (= [[:msg {:type :text :text "Hello"}]
            [:usage {:input-tokens 16 :output-tokens 224}]
            [:msg {:type :finish :finish-reason "stop"}]]
           (run-stream-chat! {:message {:role "assistant" :content ""}
                              :done true
                              :done_reason "stop"
                              :prompt_eval_count 16
                              :eval_count 224}))))
  (testing "missing count defaults to 0"
    (is (= [[:msg {:type :text :text "Hello"}]
            [:usage {:input-tokens 0 :output-tokens 42}]
            [:msg {:type :finish :finish-reason "stop"}]]
           (run-stream-chat! {:done true :done_reason "stop" :eval_count 42}))))
  (testing "no usage event when response has no token counts"
    (is (= [[:msg {:type :text :text "Hello"}]
            [:msg {:type :finish :finish-reason "stop"}]]
           (run-stream-chat! {:done true :done_reason "stop"}))))
  (testing "works without on-usage-updated callback"
    (is (= [[:msg {:type :text :text "Hello"}]
            [:msg {:type :finish :finish-reason "stop"}]]
           (run-stream-chat! {:done true
                              :done_reason "stop"
                              :prompt_eval_count 16
                              :eval_count 224}
                             false)))))
