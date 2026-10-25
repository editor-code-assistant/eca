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
      (is (= :cursor-expired (:error r)))))

  (testing "a before cursor whose message was truncated away pages the rest"
    (let [before (:before-cursor (f.chat.history/window-messages messages {:limit 3}))
          ;; The cursor points at m2; a rollback to m2 truncates m2 onwards.
          r (f.chat.history/window-messages (subvec messages 0 2) {:limit 3 :before before})]
      (is (= ["m0" "m1"] (texts r)))
      (is (nil? (:before-cursor r)))))

  (testing "a cursor whose message was removed mid-history still expires"
    (let [before (:before-cursor (f.chat.history/window-messages messages {:limit 3}))
          without-m2 (into (subvec messages 0 2) (subvec messages 3))]
      (is (= :cursor-expired
             (:error (f.chat.history/window-messages without-m2 {:before before})))))))

(deftest window-messages-keeps-reason-runs-test
  (let [reason (fn [id created-at] {:role "reason" :content {:id id :text id} :created-at created-at})
        with-runs [(msg "user" "m0" 1)
                   (reason "r1" 2)
                   (reason "r2" 3)
                   (reason "r3" 4)
                   (msg "assistant" "m4" 5)
                   (msg "user" "m5" 6)]
        ids (fn [result] (mapv #(or (get-in % [:content :id]) (get-in % [:content 0 :text])) (:messages result)))]
    (testing "a backward page starting inside a run of reasons widens to the run start"
      (let [r (f.chat.history/window-messages with-runs {:limit 3})
            older (f.chat.history/window-messages with-runs {:limit 3 :before (:before-cursor r)})]
        (is (= ["r1" "r2" "r3" "m4" "m5"] (ids r)))
        (is (= 5 (:returned r)))
        (testing "and the older page ends right before the run"
          (is (= ["m0"] (ids older))))
        (testing "a forward page ending inside a run of reasons widens to the run end"
          (is (= ["r1" "r2" "r3"]
                 (ids (f.chat.history/window-messages with-runs {:limit 1 :after (:after-cursor older)})))))))

    (testing "widening never crosses the cursor bounds"
      (let [cursor-at (fn [idx] (#'f.chat.history/encode-cursor idx (nth with-runs idx)))]
        (is (= ["r1"] (ids (f.chat.history/window-messages with-runs {:limit 1 :before (cursor-at 2)}))))
        (is (= ["r3"] (ids (f.chat.history/window-messages with-runs {:limit 1 :after (cursor-at 2)}))))))))

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
