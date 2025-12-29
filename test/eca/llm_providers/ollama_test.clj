(ns eca.llm-providers.ollama-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [eca.client-test-helpers :refer [with-client-proxied]]
   [eca.llm-providers.ollama :as llm-providers.ollama]
   [matcher-combinators.test :refer [match?]]))

(deftest list-models-test
  (testing "fetches available Ollama models"
    (let [req* (atom nil)
          fake-api-url "http://localhost:99"
          fake-response {:status 200
                         :body {:models [{:name "model-a"}
                                         {:name "model-b"}]}}]

      (with-client-proxied {}
        (fn [req]
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
        (fn [req]
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
        (fn [req]
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
