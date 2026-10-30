(ns eca.message-sanitize-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.message-sanitize :as message-sanitize]))

(def ^:private memory-block
  {:type :memory-context
   :text "<memory-index description=\"x\">\n- a\n</memory-index>"})

(deftest strip-internal-message-fields-test
  (testing "removes every internal top-level key"
    (is (= {:role "user" :content [{:type :text :text "hi"}]}
           (message-sanitize/strip-internal-message-fields
            {:role "user"
             :content [{:type :text :text "hi"}]
             :created-at 1
             :content-id "c1"
             :memory {"/a.md" {:hash "abc"}}}))))
  (testing "keeps provider payload keys"
    (is (= {:role "reason" :content {:id "r1" :api :anthropic}}
           (message-sanitize/strip-internal-message-fields
            {:role "reason" :content {:id "r1" :api :anthropic}})))))

(deftest sanitize-outbound-message-test
  (testing "memory blocks become plain text blocks and :memory goes away"
    (is (= {:role "user"
            :content [{:type :text :text "hi"}
                      {:type :text :text (:text memory-block)}]}
           (message-sanitize/sanitize-outbound-message
            {:role "user"
             :content [{:type :text :text "hi"} memory-block]
             :memory {"/a.md" {:hash "abc"}}
             :created-at 1
             :content-id "c1"}))))
  (testing "string content is kept as is"
    (is (= {:role "user" :content "hi"}
           (message-sanitize/sanitize-outbound-message
            {:role "user" :content "hi" :content-id "c1"}))))
  (testing "nil content is kept as is"
    (is (= {:role "assistant" :content nil}
           (message-sanitize/sanitize-outbound-message
            {:role "assistant" :content nil :created-at 1}))))
  (testing "messages without memory keep every block"
    (is (= {:role "user" :content [{:type :text :text "hi"}
                                   {:type :image :media-type "image/png" :base64 "aa"}]}
           (message-sanitize/sanitize-outbound-message
            {:role "user"
             :content [{:type :text :text "hi"}
                       {:type :image :media-type "image/png" :base64 "aa"}]})))))

(deftest sanitize-outbound-messages-test
  (testing "returns a vector of fully sanitized messages"
    (is (= [{:role "user" :content [{:type :text :text "hi"}]}
            {:role "user" :content [{:type :text :text (:text memory-block)}]}]
           (message-sanitize/sanitize-outbound-messages
            [{:role "user" :content [{:type :text :text "hi"}] :content-id "c1"}
             {:role "user" :content [memory-block] :memory {"/a.md" {:hash "abc"}}}])))))

(deftest remove-memory-context-test
  (testing "removes memory blocks and the :memory key, keeps the rest"
    (is (= {:role "user" :content [{:type :text :text "hi"}] :content-id "c1"}
           (message-sanitize/remove-memory-context
            {:role "user"
             :content [{:type :text :text "hi"} memory-block]
             :memory {"/a.md" {:hash "abc"}}
             :content-id "c1"}))))
  (testing "removes every memory block"
    (is (= {:role "user" :content [{:type :text :text "hi"}]}
           (message-sanitize/remove-memory-context
            {:role "user" :content [memory-block {:type :text :text "hi"} memory-block]}))))
  (testing "string content is kept as is"
    (is (= {:role "user" :content "hi"}
           (message-sanitize/remove-memory-context
            {:role "user" :content "hi" :memory {"/a.md" {:hash "abc"}}}))))
  (testing "messages without memory are unchanged"
    (is (= {:role "assistant" :content [{:type :text :text "ok"}]
            :content-id "c2"}
           (message-sanitize/remove-memory-context
            {:role "assistant" :content [{:type :text :text "ok"}]
             :content-id "c2"})))))
