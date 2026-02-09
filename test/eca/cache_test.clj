(ns eca.cache-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [eca.cache :as cache]))

(defmacro ^:private with-temp-cache-dir
  "Runs body with cache/global-dir redirected to a temporary directory."
  [& body]
  `(let [tmp-dir# (fs/create-temp-dir {:prefix "eca-cache-test"})]
     (try
       (with-redefs [cache/global-dir (fn [] (io/file (str tmp-dir#)))]
         ~@body)
       (finally
         (fs/delete-tree tmp-dir#)))))

(deftest cleanup-tool-call-outputs-test
  (testing "deletes files older than 7 days and keeps recent files"
    (with-temp-cache-dir
      (let [dir (cache/tool-call-outputs-dir)
            old-file (java.io.File. (str dir "/old-call.txt"))
            recent-file (java.io.File. (str dir "/recent-call.txt"))]
        ;; Setup: create files
        (fs/create-dirs dir)
        (spit old-file "old output")
        (spit recent-file "recent output")
        ;; Set old file to 8 days ago
        (.setLastModified old-file (- (System/currentTimeMillis) (* 8 24 60 60 1000)))

        (cache/cleanup-tool-call-outputs!)

        (is (not (fs/exists? old-file)) "Old file should be deleted")
        (is (fs/exists? recent-file) "Recent file should be kept"))))

  (testing "does not throw when directory does not exist"
    (with-temp-cache-dir
      (is (nil? (cache/cleanup-tool-call-outputs!))))))

(deftest save-tool-call-output-test
  (testing "saves text to a file and returns path"
    (with-temp-cache-dir
      (let [tool-call-id "save-test-call"
            text "full output here"
            path (cache/save-tool-call-output! tool-call-id text)]
        (is (string? path))
        (is (fs/exists? path))
        (is (= text (slurp path)))))))
