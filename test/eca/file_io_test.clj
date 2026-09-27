(ns eca.file-io-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [eca.file-io :as file-io])
  (:import
   [java.nio.file AtomicMoveNotSupportedException StandardCopyOption]))

(set! *warn-on-reflection* true)

(deftest replace-file-test
  (let [root (fs/create-temp-dir)
        dest (io/file (str root) "data.edn")
        temps (atom [])]
    (try
      (spit dest "old")
      (testing "failed serialization keeps the destination and removes its temp"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Write failed"
                              (file-io/replace-file!
                               dest
                               (fn [tmp]
                                 (swap! temps conj tmp)
                                 (spit tmp "partial")
                                 (throw (ex-info "Write failed" {}))))))
        (is (= "old" (slurp dest)))
        (is (empty? (fs/glob root "*.tmp"))))
      (testing "each replacement uses a unique sibling and leaves old data until ready"
        (dotimes [_ 2]
          (file-io/replace-file!
           dest
           (fn [tmp]
             (swap! temps conj tmp)
             (is (= (fs/parent dest) (fs/parent tmp)))
             (is (not= dest tmp))
             (is (= "old" (slurp dest)))
             (spit tmp "old"))))
        (is (= 3 (count (set @temps))))
        (is (empty? (fs/glob root "*.tmp"))))
      (testing "failed move also removes the unique temp"
        (with-redefs [file-io/atomic-move! (fn [& _] (throw (ex-info "Move failed" {})))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Move failed"
                                (file-io/replace-file! dest #(spit % "new")))))
        (is (= "old" (slurp dest)))
        (is (empty? (fs/glob root "*.tmp"))))
      (finally (fs/delete-tree root)))))

(deftest atomic-move-fallback-test
  (let [root (fs/create-temp-dir)
        src (io/file (str root) "source")
        dest (io/file (str root) "destination")
        options (atom [])
        array-fn into-array]
    (try
      (spit src "new")
      (spit dest "old")
      ;; Inject the unsupported-atomic exception at option construction inside
      ;; the try. The fallback must perform a real replace on every platform.
      (with-redefs [clojure.core/into-array
                    (fn [type xs]
                      (swap! options conj (vec xs))
                      (if (some #{StandardCopyOption/ATOMIC_MOVE} xs)
                        (throw (AtomicMoveNotSupportedException. (str src) (str dest) "test"))
                        (array-fn type xs)))]
        (file-io/atomic-move! src dest))
      (is (= [[StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]
              [StandardCopyOption/REPLACE_EXISTING]] @options))
      (is (= "new" (slurp dest)))
      (is (not (fs/exists? src)))
      (finally (fs/delete-tree root)))))

(deftest os-lock-released-after-failure-test
  (let [root (fs/create-temp-dir)
        lock-file (io/file (str root) "data.lock")]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Callback failed"
                            (file-io/with-os-file-lock-fn
                              lock-file #(throw (ex-info "Callback failed" {})))))
      (is (= :ok (file-io/with-os-file-lock-fn lock-file (constantly :ok))))
      (finally (fs/delete-tree root)))))
