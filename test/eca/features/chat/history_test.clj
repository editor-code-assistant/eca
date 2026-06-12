(ns eca.features.chat.history-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.chat.history :as f.chat.history]))

(defn- msg [role text created-at]
  {:role role :content [{:type :text :text text}] :created-at created-at})

(def ^:private messages
  [(msg "user" "m0" 1)
   (msg "assistant" "m1" 2)
   (msg "user" "m2" 3)
   (msg "assistant" "m3" 4)
   (msg "user" "m4" 5)])

(defn- texts [result]
  (mapv #(get-in % [:content 0 :text]) (:messages result)))

(deftest window-messages-test
  (testing "limit returns the newest N messages"
    (let [r (f.chat.history/window-messages messages {:limit 2})]
      (is (= ["m3" "m4"] (texts r)))
      (is (= 5 (:total r)))
      (is (= 2 (:returned r)))
      (is (some? (:before-cursor r)))
      (is (nil? (:after-cursor r)))))

  (testing "limit accepts string (HTTP) and int (JSON-RPC)"
    (is (= ["m3" "m4"] (texts (f.chat.history/window-messages messages {:limit "2"}))))
    (is (= ["m3" "m4"] (texts (f.chat.history/window-messages messages {:limit 2})))))

  (testing "no limit returns the whole window"
    (let [r (f.chat.history/window-messages messages {})]
      (is (= ["m0" "m1" "m2" "m3" "m4"] (texts r)))
      (is (nil? (:before-cursor r)))
      (is (nil? (:after-cursor r)))))

  (testing "before cursor loads the older page"
    (let [before (:before-cursor (f.chat.history/window-messages messages {:limit 2}))
          r (f.chat.history/window-messages messages {:limit 2 :before before})]
      (is (= ["m1" "m2"] (texts r)))
      (is (some? (:before-cursor r)))
      (is (some? (:after-cursor r)))))

  (testing "opaque after cursor pages forward"
    (let [first-cursor (:before-cursor
                        (f.chat.history/window-messages messages {:limit 4}))
          ;; first-cursor points at m1 (index 1); forward page after it
          r (f.chat.history/window-messages messages {:limit 2 :after first-cursor})]
      (is (= ["m2" "m3"] (texts r)))))

  (testing "expired cursor returns error"
    (let [r (f.chat.history/window-messages messages {:before "not-a-real-cursor"})]
      (is (= :cursor-expired (:error r))))))

(deftest compaction-window-test
  (let [with-marker [(msg "user" "m0" 1)
                     (msg "assistant" "m1" 2)
                     {:role "compact_marker" :content {:auto? false} :created-at 3}
                     (msg "user" "summary" 4)
                     (msg "assistant" "m4" 5)]]
    (testing "after=lastCompaction returns post-compaction messages, newest-anchored"
      (let [r (f.chat.history/window-messages with-marker {:after f.chat.history/last-compaction-sentinel})]
        (is (= ["summary" "m4"] (texts r)))
        (is (nil? (:after-cursor r)))
        (is (some? (:before-cursor r))))
      (let [r (f.chat.history/window-messages with-marker {:after f.chat.history/last-compaction-sentinel :limit 1})]
        (is (= ["m4"] (texts r)))))

    (testing "before=lastCompaction returns the summarized-away history"
      (let [r (f.chat.history/window-messages with-marker {:before f.chat.history/last-compaction-sentinel})]
        (is (= ["m0" "m1"] (texts r)))))

    (testing "compaction-cursor is present only when compacted"
      (is (some? (f.chat.history/compaction-cursor with-marker)))
      (is (nil? (f.chat.history/compaction-cursor messages))))

    (testing "lastCompaction with no marker falls back to the full window"
      (let [r (f.chat.history/window-messages messages {:after f.chat.history/last-compaction-sentinel})]
        (is (= ["m0" "m1" "m2" "m3" "m4"] (texts r)))))))
