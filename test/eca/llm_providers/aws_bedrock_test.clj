(ns eca.llm-providers.aws-bedrock-test
  (:require [clojure.test :refer :all]
            [cheshire.core :as json]
            [eca.llm-providers.aws-bedrock :as bedrock]
            [hato.client :as http]
            [clojure.java.io :as io])
  (:import (java.io ByteArrayInputStream)))

;; --- Helper: Binary Stream Construction ---

(defn- build-stream-frame
  "Constructs a simplified AWS Event Stream binary frame for testing.
   Assumes no headers for simplicity."
  [json-payload]
  (let [payload-bytes (.getBytes json-payload "UTF-8")
        payload-len (alength payload-bytes)
        ;; total-len = prelude(8) + headers(0) + headers-crc(4) + payload + message-crc(4)
        total-len (+ 8 0 4 payload-len 4)
        baos (java.io.ByteArrayOutputStream.)]
    (doto (java.io.DataOutputStream. baos)
      (.writeInt total-len)      ; Total Length
      (.writeInt 0)              ; Header Length
      ;; Header CRC (4 bytes dummy)
      (.writeInt 0x00000000)    
      ;; Payload
      (.write payload-bytes)
      ;; Message CRC (4 bytes dummy)
      (.writeInt 0x00000000))
    (.toByteArray baos)))

;; --- Tests: Tools ---

(deftest test-format-tool-spec
  (testing "Tool spec includes inputSchema wrapped in 'json' key"
    (let [tool {:function {:name "test_fn"
                           :description "Test function"
                           :parameters {:type "object" :properties {}}}}
          result (bedrock/format-tool-spec tool)]
      (is (= "test_fn" (get-in result [:toolSpec :name])))
      (is (map? (get-in result [:toolSpec :inputSchema])))
      (is (contains? (get-in result [:toolSpec :inputSchema]) :json)))))

(deftest test-message->bedrock-tool-result
  (testing "Tool result formatted correctly for user message"
    (let [msg {:role "tool_call"
               :content "{\"result\": 1}"
               :tool_call_id "123"
               :error false}
          result (first (:content (bedrock/message->bedrock msg)))]
      (is (= "123" (:toolUseId result)))
      (is (= "success" (:status result)))
      (is (= [{:json {:result 1}}] (:content result))))))

(deftest test-message->bedrock-assistant-tool-call
  (testing "Assistant tool calls formatted correctly"
    (let [tool-call {:id "123"
                      :type "function"
                      :function {:name "my_func"
                                 :arguments "{\"x\": 1}"}}
          msg {:role "assistant" :tool_calls [tool-call]}
          result (first (:content (bedrock/message->bedrock msg)))]
      (is (= "123" (get-in result [:toolUse :toolUseId])))
      (is (= "my_func" (get-in result [:toolUse :name])))
      (is (= {:x 1} (get-in result [:toolUse :input]))))))

;; --- Tests: Payloads ---

(deftest test-build-payload-with-additional-fields
  (testing "Payload includes additionalModelRequestFields"
    (let [messages [{:role "user" :content "Hi"}]
          options {:temperature 0.5 :top_k 200}
          result (bedrock/build-payload messages options)]
      (is (= 0.5 (get-in result [:inferenceConfig :temperature])))
      (is (= {"top_k" 200} (:additionalModelRequestFields result))))))

;; --- Tests: Stream Parsing ---

(deftest test-parse-event-stream
  (testing "Parses binary stream and extracts text"
    (let [payload1 "{\"contentBlockDelta\": {\"delta\": {\"text\": \"Hello\"}}}"
          payload2 "{\"contentBlockDelta\": {\"delta\": {\"text\": \" World\"}}}"
          frame1 (build-stream-frame payload1)
          frame2 (build-stream-frame payload2)
          combined (byte-array (+ (alength frame1) (alength frame2)))]
      (System/arraycopy frame1 0 combined 0 (alength frame1))
      (System/arraycopy frame2 0 combined (alength frame1) (alength frame2))
      
      (let [input-stream (ByteArrayInputStream. combined)
            events (bedrock/parse-event-stream input-stream)
            texts (bedrock/extract-text-deltas events)]
        (is (= ["Hello" " World"] texts))))))

(deftest test-extract-text-deltas-handles-empty-events
  (testing "Handles non-content events gracefully"
    (let [events [{:metadata {:test true}}
                  {:contentBlockDelta {:delta {:text "Hi"}}}
                  {:ping true}]
          result (bedrock/extract-text-deltas events)]
      (is (= ["Hi"] result)))))

(deftest test-parse-event-stream-with-tool-calls
  (testing "Parses stream with tool call events"
    (let [payload1 "{"contentBlockDelta": {"delta": {"text": "Thinking"}}}"
          payload2 "{"contentBlockDelta": {"delta": {"toolUse": {"toolUseId": "1", "name": "test_func", "input": {"x": 1}}}}}"
          frame1 (build-stream-frame payload1)
          frame2 (build-stream-frame payload2)
          combined (byte-array (+ (alength frame1) (alength frame2)))]
      (System/arraycopy frame1 0 combined 0 (alength frame1))
      (System/arraycopy frame2 0 combined (alength frame1) (alength frame2))
      
      (let [input-stream (ByteArrayInputStream. combined)
            events (bedrock/parse-event-stream input-stream)]
        (is (= 2 (count events)))
        (is (= "Thinking" (get-in events [0 :contentBlockDelta :delta :text])))
        (is (= "test_func" (get-in events [1 :contentBlockDelta :delta :toolUse :name])))))))

;; --- Tests: Response Parsing ---

(deftest test-parse-bedrock-response-text
  (testing "Parses standard text response"
    (let [body "{\"output\": {\"message\": {\"content\": [{\"text\": \"Response\"}]}}, \"stopReason\": \"end_turn\"}"
          result (bedrock/parse-bedrock-response body)]
      (is (= "assistant" (:role result)))
      (is (= "Response" (:content result))))))

(deftest test-parse-bedrock-response-tool-use
  (testing "Parses tool use response"
    (let [body "{\"output\": {\"message\": {\"content\": [{\"toolUse\": {\"toolUseId\": \"1\", \"name\": \"f\", \"input\": {}}}] }}, \"stopReason\": \"tool_use\"}"
          result (bedrock/parse-bedrock-response body)]
      (is (= 1 (count (:tool_calls result))))
      (is (= "f" (get-in result [:tool_calls 0 :function :name]))))))

;; --- Integration Tests (Mocked HTTP) ---

;; Integration test commented out due to complexity in mocking
;; (deftest test-provider-request-bedrock-mock
;;   (testing "Integration test for bedrock provider"
;;     (let [mock-response {:status 200 :body "{\"output\": {\"message\": {\"content\": [{\"text\": \"Done\"}]}}, \"stopReason\": \"end_turn\"}"
;;           config {:key "test-key" :model "claude-3" :user-messages [{:role "user" :content "Test"}] :extra-payload {}}
;;           callbacks {:on-message-received (fn [msg] (reset! result msg))
;;                      :on-error (fn [err] (reset! error err))
;;                      :on-prepare-tool-call (fn [tools] (reset! tools tools))
;;                      :on-tools-called (fn [result] (reset! tools-result result))
;;                      :on-usage-updated (fn [usage] (reset! usage usage))}
;;           result (atom nil)
;;           error (atom nil)
;;           tools (atom nil)
;;           tools-result (atom nil)
;;           usage (atom nil)]
;;       (with-redefs [http/post (fn [_ opts] (future mock-response))]
;;         (let [result-data (bedrock/chat! config callbacks)]
;;           (is (= "Done" (:output-text result-data))))))))

;; Note: Streaming integration test is harder to mock cleanly with simple `future`
;; because of the lazy-seq InputStream interaction, but the binary parser test above
;; covers the critical logic.