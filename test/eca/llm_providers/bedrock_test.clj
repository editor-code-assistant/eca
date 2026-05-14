(ns eca.llm-providers.bedrock-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [eca.client-test-helpers :refer [with-client-proxied]]
   [eca.llm-providers.bedrock :as llm-providers.bedrock]
   [hato.client :as http]
   [matcher-combinators.test :refer [match?]])
  (:import
   [java.io ByteArrayInputStream ByteArrayOutputStream]))

;; --- event-stream binary frame helpers ---

(defn ^:private write-u32! [^ByteArrayOutputStream baos n]
  (doseq [shift [24 16 8 0]]
    (.write baos (bit-and (bit-shift-right (long n) shift) 0xff))))

(defn ^:private string-header [name value]
  (let [name-bytes (.getBytes ^String name "UTF-8")
        value-bytes (.getBytes ^String value "UTF-8")
        baos (ByteArrayOutputStream.)]
    (.write baos (alength name-bytes))
    (.write baos name-bytes)
    (.write baos 7) ;; string header type
    (.write baos (bit-and (bit-shift-right (alength value-bytes) 8) 0xff))
    (.write baos (bit-and (alength value-bytes) 0xff))
    (.write baos value-bytes)
    (.toByteArray baos)))

(defn ^:private make-frame
  "Builds a single AWS event-stream frame. CRCs are written as zero since the
   decoder does not validate them."
  [event-type payload-map]
  (let [headers (string-header ":event-type" event-type)
        payload (.getBytes ^String (json/generate-string payload-map) "UTF-8")
        baos (ByteArrayOutputStream.)]
    (write-u32! baos (+ 12 (alength headers) (alength payload) 4))
    (write-u32! baos (alength headers))
    (write-u32! baos 0) ;; prelude crc
    (.write baos headers)
    (.write baos payload)
    (write-u32! baos 0) ;; message crc
    (.toByteArray baos)))

(defn ^:private frames-stream [& frames]
  (ByteArrayInputStream. (byte-array (mapcat seq frames))))

;; --- decoder ---

(deftest event-stream-seq-test
  (testing "decodes consecutive event-stream frames into [event-type data] pairs"
    (is (= [["messageStart" {:role "assistant"}]
            ["contentBlockDelta" {:contentBlockIndex 0 :delta {:text "hi"}}]
            ["messageStop" {:stopReason "end_turn"}]]
           (vec (#'llm-providers.bedrock/event-stream-seq
                 (frames-stream (make-frame "messageStart" {:role "assistant"})
                                (make-frame "contentBlockDelta" {:contentBlockIndex 0 :delta {:text "hi"}})
                                (make-frame "messageStop" {:stopReason "end_turn"})))))))

  (testing "returns empty seq on a clean EOF"
    (is (= [] (vec (#'llm-providers.bedrock/event-stream-seq (ByteArrayInputStream. (byte-array 0)))))))

  (testing "throws on a truncated frame"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unexpected EOF"
                          (vec (#'llm-providers.bedrock/event-stream-seq
                                (ByteArrayInputStream. (byte-array 6)))))))

  (testing "throws on a frame with negative payload length"
    (let [baos (ByteArrayOutputStream.)]
      (write-u32! baos 20) ;; total-len < headers-len + 16
      (write-u32! baos 8)  ;; headers-len
      (write-u32! baos 0)
      (.write baos (byte-array 8))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"negative payload length"
                            (vec (#'llm-providers.bedrock/event-stream-seq
                                  (ByteArrayInputStream. (.toByteArray baos)))))))))

;; --- normalization / body ---

(deftest normalize-conversation-test
  (testing "string content becomes a single text block"
    (is (match? [{:role "user" :content [{:text "hello"}]}]
                (#'llm-providers.bedrock/normalize-conversation
                 [] [{:role "user" :content "hello"}] false))))

  (testing "tool call + output become Converse toolUse / toolResult blocks"
    (is (match? [{:role "assistant"
                  :content [{:toolUse {:toolUseId "t1" :name "get_weather" :input {:city "Paris"}}}]}
                 {:role "user"
                  :content [{:toolResult {:toolUseId "t1"
                                          :status "success"
                                          :content [{:text string?}]}}]}]
                (#'llm-providers.bedrock/normalize-conversation
                 [{:role "tool_call" :content {:id "t1" :full-name "get_weather" :arguments {:city "Paris"}}}
                  {:role "tool_call_output" :content {:id "t1" :output {:contents [{:type :text :text "sunny"}]}}}]
                 nil false))))

  (testing "consecutive same-role messages are merged"
    (is (match? [{:role "user" :content [{:text "a"} {:text "b"}]}]
                (#'llm-providers.bedrock/normalize-conversation
                 [{:role "user" :content "a"}] [{:role "user" :content "b"}] false)))))

(deftest build-body-test
  (testing "filters extra-payload to Converse-valid keys and deep-merges inferenceConfig"
    (let [body (#'llm-providers.bedrock/build-body
                {:messages []
                 :instructions "sys"
                 :max-output-tokens 500
                 :extra-payload {:thinking {:type "adaptive"}
                                 :inferenceConfig {:temperature 0.5}}})]
      (is (nil? (:thinking body)))
      (is (match? {:inferenceConfig {:maxTokens 500 :temperature 0.5}
                   :system [{:text "sys"}]}
                  body))))

  (testing "reasoning adds reasoning_config to additionalModelRequestFields"
    (is (match? {:additionalModelRequestFields {:reasoning_config {:type "enabled"}}}
                (#'llm-providers.bedrock/build-body {:messages [] :reason? true})))))

;; --- chat! non-streaming ---

(deftest chat!-non-streaming-test
  (testing "constructs a Converse request and parses the response"
    (let [req* (atom nil)]
      (with-client-proxied {}
        (fn handler [req]
          (reset! req* req)
          {:status 200
           :body {:output {:message {:role "assistant" :content [{:text "Hello!"}]}}
                  :stopReason "end_turn"
                  :usage {:inputTokens 10 :outputTokens 3}}})

        (let [result (llm-providers.bedrock/chat!
                      {:model "us.anthropic.claude-sonnet-4-5-20250929-v1:0"
                       :api-url "http://localhost:1"
                       :api-key "fake-token"
                       :instructions "be terse"
                       :user-messages [{:role "user" :content [{:type :text :text "hi"}]}]
                       :past-messages []
                       :max-output-tokens 1000}
                      nil)]
          (is (match? {:method "POST"
                       :uri "/model/us.anthropic.claude-sonnet-4-5-20250929-v1%3A0/converse"}
                      (select-keys @req* [:method :uri])))
          (is (= "Bearer fake-token" (get-in @req* [:headers "Authorization"])))
          (is (match? {:messages [{:role "user" :content [{:text "hi"}]}]
                       :system [{:text "be terse"}]
                       :inferenceConfig {:maxTokens 1000}}
                      (:body @req*)))
          (is (match? {:output-text "Hello!"
                       :usage {:input-tokens 10 :output-tokens 3}}
                      result)))))))

(deftest chat!-non-streaming-tool-loop-test
  (testing "a toolUse response yields tools-to-call and call-tools-fn re-issues the request"
    (let [call-count* (atom 0)]
      (with-client-proxied {}
        (fn handler [_]
          (swap! call-count* inc)
          {:status 200
           :body (if (= 1 @call-count*)
                   {:output {:message {:content [{:toolUse {:toolUseId "t1"
                                                            :name "get_weather"
                                                            :input {:city "Paris"}}}]}}
                    :stopReason "tool_use"
                    :usage {:inputTokens 5 :outputTokens 2}}
                   {:output {:message {:content [{:text "It is sunny."}]}}
                    :stopReason "end_turn"
                    :usage {:inputTokens 8 :outputTokens 4}})})

        (let [result (llm-providers.bedrock/chat!
                      {:model "model-x" :api-url "http://localhost:1" :api-key "k"
                       :user-messages [{:role "user" :content "weather?"}] :past-messages []}
                      nil)]
          (is (match? [{:id "t1" :full-name "get_weather" :arguments {:city "Paris"}}]
                      (:tools-to-call result)))
          (let [next-result ((:call-tools-fn result)
                             (constantly {:new-messages [{:role "user" :content "weather?"}]
                                          :tools nil}))]
            (is (= 2 @call-count*))
            (is (match? {:output-text "It is sunny."} next-result))))))))

;; --- chat! streaming ---

(defn ^:private collecting-callbacks [events*]
  {:on-message-received #(swap! events* conj [:msg %])
   :on-error #(swap! events* conj [:error %])
   :on-reason #(swap! events* conj [:reason %])
   :on-prepare-tool-call #(swap! events* conj [:prepare %])
   :on-tools-called (constantly nil)
   :on-usage-updated #(swap! events* conj [:usage %])})

(deftest chat!-streaming-test
  (testing "decodes a streamed response and drives text + finish + usage callbacks"
    (let [events* (atom [])
          frames (frames-stream
                  (make-frame "messageStart" {:role "assistant"})
                  (make-frame "contentBlockDelta" {:contentBlockIndex 0 :delta {:text "Hello!"}})
                  (make-frame "messageStop" {:stopReason "end_turn"})
                  (make-frame "metadata" {:usage {:inputTokens 7 :outputTokens 2}}))]
      (with-redefs [http/post (fn [_ _] {:status 200 :body frames})]
        (llm-providers.bedrock/chat!
         {:model "model-x" :api-url "http://localhost:1" :api-key "k"
          :user-messages [{:role "user" :content "hi"}] :past-messages []}
         (collecting-callbacks events*)))
      (is (= [[:msg {:type :text :text "Hello!"}]
              [:msg {:type :finish :finish-reason "end_turn"}]
              [:usage {:input-tokens 7 :output-tokens 2 :input-cache-read-tokens 0 :input-cache-creation-tokens 0}]]
             @events*)))))

(deftest chat!-streaming-tool-loop-test
  (testing "a streamed tool_use turn re-issues the request with the updated history"
    (let [events* (atom [])
          call-count* (atom 0)
          tool-frames (frames-stream
                       (make-frame "messageStart" {:role "assistant"})
                       (make-frame "contentBlockStart" {:contentBlockIndex 0
                                                        :start {:toolUse {:toolUseId "t1" :name "get_weather"}}})
                       (make-frame "contentBlockDelta" {:contentBlockIndex 0
                                                        :delta {:toolUse {:input "{\"city\": \"Paris\"}"}}})
                       (make-frame "contentBlockStop" {:contentBlockIndex 0})
                       (make-frame "messageStop" {:stopReason "tool_use"}))
          final-frames (frames-stream
                        (make-frame "messageStart" {:role "assistant"})
                        (make-frame "contentBlockDelta" {:contentBlockIndex 0 :delta {:text "done"}})
                        (make-frame "messageStop" {:stopReason "end_turn"}))
          callbacks (assoc (collecting-callbacks events*)
                           :on-tools-called (fn [tool-calls]
                                              (swap! events* conj [:tools tool-calls])
                                              {:new-messages [{:role "user" :content "weather?"}]
                                               :tools nil}))]
      (with-redefs [http/post (fn [_ _]
                                (swap! call-count* inc)
                                {:status 200 :body (if (= 1 @call-count*) tool-frames final-frames)})]
        (llm-providers.bedrock/chat!
         {:model "model-x" :api-url "http://localhost:1" :api-key "k"
          :user-messages [{:role "user" :content "weather?"}] :past-messages []}
         callbacks))
      (is (= 2 @call-count*))
      (is (match? [[:prepare {:full-name "get_weather" :id "t1" :arguments-text ""}]
                   [:prepare {:full-name "get_weather" :id "t1" :arguments-text "{\"city\": \"Paris\"}"}]
                   [:tools [{:id "t1" :full-name "get_weather" :arguments {"city" "Paris"}}]]
                   [:msg {:type :text :text "done"}]
                   [:msg {:type :finish :finish-reason "end_turn"}]]
                  @events*)))))

(deftest chat!-error-paths-test
  (testing "non-200 response is surfaced as an error result"
    (with-client-proxied {}
      (fn [_] {:status 400 :body {:message "ValidationException"}})
      (let [result (llm-providers.bedrock/chat!
                    {:model "model-x" :api-url "http://localhost:1" :api-key "k"
                     :user-messages [{:role "user" :content "hi"}] :past-messages []}
                    nil)]
        (is (match? {:error {:status 400}} result)))))

  (testing "a transport exception is surfaced as an error result"
    (with-redefs [http/post (fn [_ _] (throw (java.io.IOException. "connection refused")))]
      (let [result (llm-providers.bedrock/chat!
                    {:model "model-x" :api-url "http://localhost:1" :api-key "k"
                     :user-messages [{:role "user" :content "hi"}] :past-messages []}
                    nil)]
        (is (match? {:error {:exception some?}} result)))))

  (testing "a stream that ends without messageStop reports a premature stop"
    (let [events* (atom [])
          frames (frames-stream
                  (make-frame "messageStart" {:role "assistant"})
                  (make-frame "contentBlockDelta" {:contentBlockIndex 0 :delta {:text "partial"}}))]
      (with-redefs [http/post (fn [_ _] {:status 200 :body frames})]
        (llm-providers.bedrock/chat!
         {:model "model-x" :api-url "http://localhost:1" :api-key "k"
          :user-messages [{:role "user" :content "hi"}] :past-messages []}
         (collecting-callbacks events*)))
      (is (match? [[:msg {:type :text :text "partial"}]
                   [:error {:error/type :premature-stop}]]
                  @events*))))

  (testing "a modeled stream error frame surfaces one error and no premature-stop"
    (let [events* (atom [])
          frames (frames-stream
                  (make-frame "messageStart" {:role "assistant"})
                  (make-frame "throttlingException" {:message "slow down"}))]
      (with-redefs [http/post (fn [_ _] {:status 200 :body frames})]
        (llm-providers.bedrock/chat!
         {:model "model-x" :api-url "http://localhost:1" :api-key "k"
          :user-messages [{:role "user" :content "hi"}] :past-messages []}
         (collecting-callbacks events*)))
      (is (match? [[:error {:message #"throttlingException"}]]
                  (filterv #(= :error (first %)) @events*))
          "exactly one error event, no trailing premature-stop"))))

;; --- handle-stream branches ---

(deftest handle-stream-test
  (let [events* (atom [])
        ctx {:on-message-received #(swap! events* conj [:msg %])
             :on-error #(swap! events* conj [:error %])
             :on-reason #(swap! events* conj [:reason %])
             :on-prepare-tool-call #(swap! events* conj [:prepare %])
             :on-usage-updated #(swap! events* conj [:usage %])
             :on-tools-called (constantly nil)}
        run! (fn [cb* event data]
               (reset! events* [])
               (#'llm-providers.bedrock/handle-stream event data cb* ctx)
               @events*)]
    (testing "text delta emits a message"
      (is (= [[:msg {:type :text :text "hi"}]]
             (run! (atom {}) "contentBlockDelta" {:contentBlockIndex 0 :delta {:text "hi"}}))))

    (testing "reasoning deltas start, stream and finish a reason block"
      (let [cb* (atom {})]
        (is (match? [[:reason {:status :started}] [:reason {:status :thinking :text "thinking"}]]
                    (run! cb* "contentBlockDelta" {:contentBlockIndex 0
                                                   :delta {:reasoningContent {:text "thinking"}}}))
            "second event matched after first started it")
        (is (match? [[:reason {:status :thinking :text "thinking"}]]
                    (run! cb* "contentBlockDelta" {:contentBlockIndex 0
                                                   :delta {:reasoningContent {:text "thinking"}}})))
        (is (match? [[:reason {:status :finished :external-id "sig"}]]
                    (run! cb* "contentBlockDelta" {:contentBlockIndex 0
                                                   :delta {:reasoningContent {:signature "sig"}}})))))

    (testing "max_tokens stop reason emits limit-reached"
      (is (= [[:msg {:type :limit-reached}]]
             (run! (atom {}) "messageStop" {:stopReason "max_tokens"}))))

    (testing "metadata emits usage"
      (is (match? [[:usage {:input-tokens 3 :output-tokens 1}]]
                  (run! (atom {}) "metadata" {:usage {:inputTokens 3 :outputTokens 1}}))))

    (testing "an unknown/exception frame is surfaced as an error"
      (is (match? [[:error {:message #"Bedrock stream error"}]]
                  (run! (atom {}) "throttlingException" {:message "slow down"}))))

    (testing "returns true for terminal events (messageStop, error frames), false otherwise"
      (is (true? (#'llm-providers.bedrock/handle-stream "messageStop" {:stopReason "end_turn"} (atom {}) ctx)))
      (is (true? (#'llm-providers.bedrock/handle-stream "throttlingException" {:message "x"} (atom {}) ctx)))
      (is (false? (boolean (#'llm-providers.bedrock/handle-stream
                            "contentBlockDelta" {:contentBlockIndex 0 :delta {:text "x"}} (atom {}) ctx)))))))
