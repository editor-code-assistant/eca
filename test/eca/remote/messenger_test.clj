(ns eca.remote.messenger-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.messenger :as messenger]
   [eca.remote.messenger :as remote.messenger]
   [eca.remote.sse :as sse]
   [eca.test-helper :as h]))

(h/reset-components-before-test)

(deftest broadcast-messenger-delegates-and-broadcasts-test
  (let [inner (h/messenger)
        sse-connections* (sse/create-connections)
        broadcast-messenger (remote.messenger/make-broadcast-messenger inner sse-connections*)
        os (java.io.ByteArrayOutputStream.)
        _client (sse/add-client! sse-connections* os)]

    (testing "chat-content-received delegates to inner and broadcasts camelCase"
      (let [data {:chat-id "c1" :role :assistant :content {:type :text :text "hi"}}]
        (messenger/chat-content-received broadcast-messenger data)
        (Thread/sleep 100)
        (is (seq (:chat-content-received (h/messages))))
        (let [output (.toString os "UTF-8")]
          (is (.contains output "chat:content-received"))
          (is (.contains output "\"chatId\"") "SSE broadcast should use camelCase keys")
          (is (not (.contains output "\"chat-id\"")) "SSE broadcast should not use kebab-case keys"))))

    (testing "chat-status-changed delegates and broadcasts camelCase"
      (let [params {:chat-id "c1" :status :running}]
        (messenger/chat-status-changed broadcast-messenger params)
        (Thread/sleep 100)
        (is (seq (:chat-status-changed (h/messages))))
        (let [output (.toString os "UTF-8")]
          (is (.contains output "chat:status-changed"))
          (is (.contains output "\"chatId\"")))))

    (testing "chat-deleted delegates and broadcasts camelCase"
      (let [params {:chat-id "c1"}]
        (messenger/chat-deleted broadcast-messenger params)
        (Thread/sleep 100)
        (is (seq (:chat-deleted (h/messages))))
        (let [output (.toString os "UTF-8")]
          (is (.contains output "chat:deleted"))
          (is (.contains output "\"chatId\"")))))

    (testing "editor-diagnostics delegates to inner only (no broadcast)"
      (let [os2 (java.io.ByteArrayOutputStream.)
            _client2 (sse/add-client! sse-connections* os2)]
        (messenger/editor-diagnostics broadcast-messenger nil)
        (Thread/sleep 100)
        (is (not (.contains (.toString os2 "UTF-8") "editor")))))

    (testing "rewrite-content-received delegates to inner only (no broadcast)"
      (let [os3 (java.io.ByteArrayOutputStream.)
            _client3 (sse/add-client! sse-connections* os3)
            data {:chat-id "c1" :content {:type :text :text "rewritten"}}]
        (messenger/rewrite-content-received broadcast-messenger data)
        (Thread/sleep 100)
        (is (seq (:rewrite-content-received (h/messages))))
        (is (not (.contains (.toString os3 "UTF-8") "rewrite")))))

    (sse/close-all! sse-connections*)))

(deftest ask-question-broadcasts-and-resolves-via-answer-test
  (testing "ask-question registers a promise, broadcasts SSE, and answer-question! resolves it"
    (let [inner (h/messenger)
          ;; Editor doesn't answer; isolates the SSE path (inner is also asked).
          _ (reset! (:ask-question-response* inner) :block)
          sse-connections* (sse/create-connections)
          broadcast-messenger (remote.messenger/make-broadcast-messenger inner sse-connections*)
          os (java.io.ByteArrayOutputStream.)
          _client (sse/add-client! sse-connections* os)
          p (messenger/ask-question broadcast-messenger {:chat-id "c1" :question "Why?"})]
      (Thread/sleep 100)
      (is (not (realized? p)) "promise should not be realized before answer")
      (let [output (.toString os "UTF-8")]
        (is (.contains output "chat:ask-question") "SSE event name should be chat:ask-question")
        (is (.contains output "\"chatId\":\"c1\"") "payload should be camel-cased")
        (is (.contains output "\"requestId\"") "payload should include a generated requestId"))
      (let [pending @(:pending-questions* broadcast-messenger)
            [request-id _] (first pending)]
        (is (= 1 (count pending)) "exactly one pending question should be registered")
        (is (string? request-id))
        (is (= true (remote.messenger/answer-question! broadcast-messenger request-id "because" false)))
        (is (realized? p) "promise should be realized after answer-question!")
        (is (= {:answer "because" :cancelled false} @p))
        (is (empty? @(:pending-questions* broadcast-messenger))
            "registry should be cleared after delivery"))
      (sse/close-all! sse-connections*))))

(deftest ask-question-uses-caller-supplied-request-id-test
  (testing "caller-supplied :request-id is used as the SSE requestId and pending-questions* key"
    (let [inner (h/messenger)
          _ (reset! (:ask-question-response* inner) :block)
          sse-connections* (sse/create-connections)
          broadcast-messenger (remote.messenger/make-broadcast-messenger inner sse-connections*)
          os (java.io.ByteArrayOutputStream.)
          _client (sse/add-client! sse-connections* os)
          supplied-id "fixed-id-for-test"
          p (messenger/ask-question broadcast-messenger {:chat-id "c1" :question "Q?" :request-id supplied-id})]
      (Thread/sleep 100)
      (let [output (.toString os "UTF-8")
            pending @(:pending-questions* broadcast-messenger)]
        (is (contains? pending supplied-id) "pending-questions* should be keyed by the supplied id")
        (is (.contains output (str "\"requestId\":\"" supplied-id "\""))
            "SSE payload should carry the supplied requestId")
        (is (not (.contains output "\"request-id\"")) ":request-id should not appear in the SSE wire payload"))
      (is (true? (remote.messenger/answer-question! broadcast-messenger supplied-id "ok" false)))
      (is (= {:answer "ok" :cancelled false} @p))
      (sse/close-all! sse-connections*))))

(deftest ask-question-editor-answer-with-no-sse-clients-test
  (testing "an editor answer also resolves a question without SSE clients"
    (let [inner (h/messenger)
          sse-connections* (sse/create-connections)
          broadcast-messenger (remote.messenger/make-broadcast-messenger inner sse-connections*)]
      (reset! (:ask-question-response* inner) {:answer "from-inner" :cancelled false})
      (let [result (messenger/ask-question broadcast-messenger {:chat-id "c1" :question "Why?"})]
        (is (= {:answer "from-inner" :cancelled false} @result))
        (is (empty? @(:pending-questions* broadcast-messenger))
            "editor resolution should remove the pending registration")))))

(deftest answer-question-returns-nil-for-unknown-id-test
  (testing "answer-question! returns nil when the request-id is unknown"
    (let [inner (h/messenger)
          sse-connections* (sse/create-connections)
          broadcast-messenger (remote.messenger/make-broadcast-messenger inner sse-connections*)]
      (is (nil? (remote.messenger/answer-question! broadcast-messenger "nonexistent" "x" false))))))

(deftest question-remains-remotely-answerable-without-subscribers-test
  (let [editor-result (java.util.concurrent.CompletableFuture.)
        inner (reify messenger/IMessenger
                (ask-question [_ _] editor-result))
        connections (sse/create-connections)
        m (remote.messenger/make-broadcast-messenger inner connections)
        result (messenger/ask-question m {:request-id "offline"})]
    (try
      (is (contains? @(:pending-questions* m) "offline"))
      (is (true? (remote.messenger/answer-question! m "offline" "remote" false)))
      (is (= {:answer "remote" :cancelled false} (deref result 1000 :timeout)))
      (is (future-cancelled? editor-result))
      (is (nil? (remote.messenger/answer-question! m "offline" "late" false)))
      (finally (.cancel editor-result true)))))

(deftest remote-answer-during-editor-attachment-test
  (let [editor-result (java.util.concurrent.CompletableFuture.)
        m* (atom nil)
        inner (reify messenger/IMessenger
                (ask-question [_ _]
                  (is (true? (remote.messenger/answer-question! @m* "early" "remote" false)))
                  editor-result))
        m (remote.messenger/make-broadcast-messenger inner (sse/create-connections))
        broadcasts* (atom [])]
    (reset! m* m)
    (with-redefs [sse/broadcast! (fn [& args] (swap! broadcasts* conj args))]
      (let [result (messenger/ask-question m {:request-id "early"})]
        (try
          (is (= {:answer "remote" :cancelled false} (deref result 1000 :timeout)))
          (is (empty? @broadcasts*) "an early HTTP claim must suppress the ask event")
          (is (future-cancelled? editor-result))
          (is (empty? @(:pending-questions* m)))
          (finally
            (remote.messenger/answer-question! m "early" nil true)
            (.cancel editor-result true)))))))

(deftest editor-completes-before-watcher-attachment-test
  (doseq [fails? [false true]]
    (let [editor-result (reify clojure.lang.IDeref
                          (deref [_]
                            (if fails?
                              (throw (ex-info "editor failed" {}))
                              {:answer "editor" :cancelled false})))
          inner (reify messenger/IMessenger
                  (ask-question [_ _] editor-result))
          m (remote.messenger/make-broadcast-messenger inner (sse/create-connections))
          watcher (java.util.concurrent.CompletableFuture.)
          broadcasts* (atom [])]
      ;; Force the watcher to finish before future-call returns its handle.
      (with-redefs [sse/broadcast! (fn [& args] (swap! broadcasts* conj args))
                    clojure.core/future-call (fn [f] (f) watcher)]
        (try
          (let [result (messenger/ask-question m {:request-id "immediate"})]
            (if fails?
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"editor failed"
                                    (deref result 1000 :timeout)))
              (is (= {:answer "editor" :cancelled false} (deref result 1000 :timeout))))
            (is (empty? @(:pending-questions* m)))
            (is (empty? @broadcasts*)
                (str "immediate editor " (if fails? "error" "success") " must suppress the ask event"))
            (is (nil? (remote.messenger/answer-question! m "immediate" "late" false)))
            (is (future-cancelled? watcher)))
          (finally
            (remote.messenger/answer-question! m "immediate" nil true)
            (.cancel watcher true)))))))

(deftest editor-dispatch-error-completes-question-test
  (let [inner (reify messenger/IMessenger
                (ask-question [_ _] (throw (ex-info "dispatch failed" {}))))
        m (remote.messenger/make-broadcast-messenger inner (sse/create-connections))
        broadcasts* (atom [])]
    (with-redefs [sse/broadcast! (fn [& args] (swap! broadcasts* conj args))]
      (let [result (messenger/ask-question m {:request-id "error"})]
        (is (realized? result))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dispatch failed" @result))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dispatch failed"
                              (deref result 0 :timeout))))
      (is (empty? @broadcasts*) "a synchronous dispatch throw must suppress the ask event")
      (is (empty? @(:pending-questions* m))))))

(deftest pending-question-invokes-broadcast-test
  (let [editor-result (java.util.concurrent.CompletableFuture.)
        inner (reify messenger/IMessenger
                (ask-question [_ _] editor-result))
        connections (sse/create-connections)
        m (remote.messenger/make-broadcast-messenger inner connections)
        broadcasts* (atom [])]
    (with-redefs [sse/broadcast! (fn [& args] (swap! broadcasts* conj args))]
      (try
        (let [result (messenger/ask-question m {:request-id "pending" :chat-id "c1" :question "Why?"})]
          (is (not (realized? result)))
          (let [registered (get-in @(:pending-questions* m) ["pending" :promise])]
            (is (not (realized? registered)))
            (doseq [timeout-value [nil :timeout (Object.) (ex-info "timeout value" {})]]
              (is (identical? timeout-value (deref result 0 timeout-value))))
            (is (= [[connections "chat:ask-question"
                     {:requestId "pending" :chatId "c1" :question "Why?"}]]
                   @broadcasts*))
            (is (true? (remote.messenger/answer-question! m "pending" nil true)))
            (is (realized? result))
            (is (= {:answer nil :cancelled true} @registered @result (deref result 0 :timeout)))))
        (finally
          (remote.messenger/answer-question! m "pending" nil true)
          (.cancel editor-result true))))))

(deftest asynchronous-editor-error-or-cancellation-test
  (doseq [response [(ex-info "editor failed asynchronously" {}) {:cancelled true}]]
    (let [release (promise)
          editor-result (reify clojure.lang.IDeref
                          (deref [_]
                            @release
                            (if (instance? Exception response)
                              (throw response)
                              response)))
          inner (reify messenger/IMessenger
                  (ask-question [_ _] editor-result))
          m (remote.messenger/make-broadcast-messenger inner (sse/create-connections))
          result (messenger/ask-question m {:request-id "async"})
          watcher (get-in @(:pending-questions* m) ["async" :watcher])]
      (try
        (is (not (realized? result)))
        (deliver release true)
        (is (true? (deref watcher 1000 :timeout)))
        (is (realized? result))
        (if (instance? Exception response)
          (do
            (is (identical? response (try @result (catch Exception e e))))
            (is (identical? response (try (deref result 0 :timeout) (catch Exception e e)))))
          (is (= response @result (deref result 0 :timeout))))
        (is (empty? @(:pending-questions* m)))
        (finally
          (deliver release true)
          (remote.messenger/answer-question! m "async" nil true)
          (future-cancel watcher))))))

(deftest remote-answer-survives-losing-editor-dispatch-error-test
  (let [m* (atom nil)
        inner (reify messenger/IMessenger
                (ask-question [_ _]
                  (is (true? (remote.messenger/answer-question! @m* "dispatch-race" "remote" false)))
                  (throw (ex-info "late dispatch failure" {}))))
        m (remote.messenger/make-broadcast-messenger inner (sse/create-connections))]
    (reset! m* m)
    (let [result (messenger/ask-question m {:request-id "dispatch-race"})]
      (is (= {:answer "remote" :cancelled false} (deref result 1000 :timeout)))
      (is (empty? @(:pending-questions* m))))))

(deftest question-resolution-is-first-claim-wins-test
  (doseq [[editor-first? fails?] [[true false] [false false] [false true]]]
    (let [editor-result (promise)
          inner (reify messenger/IMessenger
                  (ask-question [_ _]
                    (reify clojure.lang.IDeref
                      (deref [_]
                        (let [response @editor-result]
                          (if fails?
                            (throw (ex-info "losing editor failure" {}))
                            response))))))
          m (remote.messenger/make-broadcast-messenger inner (sse/create-connections))
          result (messenger/ask-question m {:request-id "race"})
          watcher (get-in @(:pending-questions* m) ["race" :watcher])
          claimed (promise)
          publish (promise)]
      ;; Pause the atomic winner after removal, before it can deliver.
      (add-watch (:pending-questions* m) ::claim
                 (fn [_ _ old new]
                   (when (and (contains? old "race") (not (contains? new "race")))
                     (deliver claimed true)
                     @publish)))
      (let [remote-result (when-not editor-first?
                            (future (remote.messenger/answer-question! m "race" "remote" false)))]
        (try
          (when editor-first? (deliver editor-result {:answer "editor" :cancelled false}))
          (is (= true (deref claimed 1000 :timeout)))
          (if editor-first?
            (is (nil? (remote.messenger/answer-question! m "race" "remote" false)))
            (do
              (deliver editor-result {:answer "editor" :cancelled false})
              (is (nil? (deref watcher 1000 :timeout)))))
          (is (not (realized? result)) "the losing transport must not deliver")
          (deliver publish true)
          (is (= {:answer (if editor-first? "editor" "remote") :cancelled false}
                 (deref result 1000 :timeout)))
          (when remote-result (is (true? (deref remote-result 1000 :timeout))))
          (is (empty? @(:pending-questions* m)))
          (finally
            (deliver publish true)
            (remove-watch (:pending-questions* m) ::claim)
            (future-cancel watcher)
            (when remote-result (future-cancel remote-result))))))))

(deftest watcher-attachment-waits-for-winning-publication-test
  (let [inner (reify messenger/IMessenger
                (ask-question [_ _] (doto (promise) (deliver {:answer "editor" :cancelled false}))))
        m (remote.messenger/make-broadcast-messenger inner (sse/create-connections))
        claimed (promise)
        attaching (promise)
        publish (promise)
        watcher* (atom nil)
        start-future clojure.core/future-call]
    (add-watch (:pending-questions* m) ::publication
               (fn [_ _ old new]
                 (cond
                   (and (contains? old "publication") (empty? new))
                   (do (deliver claimed true) @publish)

                   (and (empty? old) (empty? new))
                   (deliver attaching true))))
    (let [caller (start-future
                  (fn []
                    (with-redefs [clojure.core/future-call
                                  (fn [f]
                                    (let [watcher (start-future f)]
                                      (reset! watcher* watcher)
                                      @claimed
                                      watcher))]
                      (messenger/ask-question m {:request-id "publication"}))))]
      (try
        (is (= true (deref attaching 1000 :timeout)))
        (is (not (future-cancelled? @watcher*)))
        (deliver publish true)
        (let [result (deref caller 1000 nil)]
          (is (some? result))
          (when result
            (is (= {:answer "editor" :cancelled false} (deref result 1000 :timeout)))))
        (is (empty? @(:pending-questions* m)))
        (finally
          (deliver publish true)
          (deliver claimed true)
          (remove-watch (:pending-questions* m) ::publication)
          (future-cancel caller)
          (when-let [watcher @watcher*] (future-cancel watcher)))))))

(deftest cancellation-error-does-not-prevent-watcher-cleanup-test
  (let [editor-result (proxy [java.util.concurrent.CompletableFuture] []
                        (cancel [_] (throw (ex-info "cancel failed" {}))))
        inner (reify messenger/IMessenger
                (ask-question [_ _] editor-result))
        m (remote.messenger/make-broadcast-messenger inner (sse/create-connections))
        result (messenger/ask-question m {:request-id "cancel-error"})
        watcher (get-in @(:pending-questions* m) ["cancel-error" :watcher])]
    (try
      (is (true? (remote.messenger/answer-question! m "cancel-error" "remote" false)))
      (is (= {:answer "remote" :cancelled false} (deref result 1000 :timeout)))
      (is (future-cancelled? watcher))
      (is (empty? @(:pending-questions* m)))
      (finally
        (.complete editor-result {:cancelled true})
        (future-cancel watcher)))))

;;; ask-question dual-dispatch: with both an SSE client and the editor (inner)
;;; connected, the question reaches both and the first answer wins.

(deftest ask-question-reaches-editor-and-sse-when-both-connected-test
  (testing "with an SSE client connected, inner (editor) still receives chat/askQuestion"
    (let [inner-params* (atom nil)
          inner (reify messenger/IMessenger
                  (ask-question [_ params]
                    (reset! inner-params* params)
                    (promise)))
          sse-connections* (sse/create-connections)
          broadcast-messenger (remote.messenger/make-broadcast-messenger inner sse-connections*)
          os (java.io.ByteArrayOutputStream.)
          _client (sse/add-client! sse-connections* os)]
      (messenger/ask-question broadcast-messenger
                              {:chat-id "c1" :question "Why?" :request-id "req-1"})
      (Thread/sleep 100)
      (is (some? @inner-params*)
          "editor must receive the question even when an SSE client is connected")
      (is (.contains (.toString os "UTF-8") "chat:ask-question")
          "SSE clients must also receive the question"))))

(deftest ask-question-sse-answer-wins-test
  (testing "an SSE answer resolves the call when both transports are connected"
    (let [inner (reify messenger/IMessenger
                  ;; editor never answers
                  (ask-question [_ _params] (promise)))
          sse-connections* (sse/create-connections)
          broadcast-messenger (remote.messenger/make-broadcast-messenger inner sse-connections*)
          os (java.io.ByteArrayOutputStream.)
          _client (sse/add-client! sse-connections* os)
          result (messenger/ask-question broadcast-messenger
                                         {:chat-id "c1" :question "Q?" :request-id "req-1"})
          watcher (:watcher (get @(:pending-questions* broadcast-messenger) "req-1"))]
      (Thread/sleep 50)
      (is (= :pending (deref result 1 :pending))
          "result should block until someone answers")
      (remote.messenger/answer-question! broadcast-messenger "req-1" "via-sse" false)
      (is (= {:answer "via-sse" :cancelled false} (deref result 1000 :timeout)))
      ;; The editor watcher must be cancelled so it doesn't park forever on an
      ;; editor that may never answer.
      (is (future-cancelled? watcher)))))

(deftest ask-question-sse-answer-retracts-editor-request-test
  (testing "an SSE answer cancels the editor's outstanding request (→ $/cancelRequest)"
    ;; A CompletableFuture models the jsonrpc PendingRequest: future-cancellable,
    ;; and cancelling it is what fires $/cancelRequest in the real ServerMessenger.
    (let [inner-result (java.util.concurrent.CompletableFuture.)
          inner (reify messenger/IMessenger
                  (ask-question [_ _params] inner-result))
          sse-connections* (sse/create-connections)
          broadcast-messenger (remote.messenger/make-broadcast-messenger inner sse-connections*)
          os (java.io.ByteArrayOutputStream.)
          _client (sse/add-client! sse-connections* os)
          result (messenger/ask-question broadcast-messenger
                                         {:chat-id "c1" :question "Q?" :request-id "req-1"})]
      (Thread/sleep 50)
      (remote.messenger/answer-question! broadcast-messenger "req-1" "via-sse" false)
      (is (= {:answer "via-sse" :cancelled false} (deref result 1000 :timeout)))
      (is (future-cancelled? inner-result)
          "the editor's pending request must be cancelled so the server retracts it"))))

(deftest ask-question-editor-answer-wins-test
  (testing "an editor answer resolves the call and cleans up the SSE pending entry"
    (let [inner-promise (promise)
          inner (reify messenger/IMessenger
                  (ask-question [_ _params] inner-promise))
          sse-connections* (sse/create-connections)
          broadcast-messenger (remote.messenger/make-broadcast-messenger inner sse-connections*)
          os (java.io.ByteArrayOutputStream.)
          _client (sse/add-client! sse-connections* os)
          result (messenger/ask-question broadcast-messenger
                                         {:chat-id "c1" :question "Q?" :request-id "req-1"})]
      (Thread/sleep 50)
      (deliver inner-promise {:answer "via-editor" :cancelled false})
      (is (= {:answer "via-editor" :cancelled false} (deref result 1000 :timeout))
          "editor's answer must resolve the call")
      (Thread/sleep 50)
      (is (empty? @(:pending-questions* broadcast-messenger))
          "answering via the editor must clear the SSE pending entry so a late /answer is a no-op"))))
