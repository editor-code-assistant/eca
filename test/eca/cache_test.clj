(ns eca.cache-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [eca.cache :as cache]))

(deftest cleanup-tool-call-outputs-test
  (testing "deletes files older than 7 days and keeps recent files"
    (let [dir (cache/tool-call-outputs-dir)
          old-file (java.io.File. (str dir "/old-call.txt"))
          recent-file (java.io.File. (str dir "/recent-call.txt"))]
      (try
        ;; Setup: create files
        (fs/create-dirs dir)
        (spit old-file "old output")
        (spit recent-file "recent output")
        ;; Set old file to 8 days ago
        (.setLastModified old-file (- (System/currentTimeMillis) (* 8 24 60 60 1000)))

        (cache/cleanup-tool-call-outputs!)

        (is (not (fs/exists? old-file)) "Old file should be deleted")
        (is (fs/exists? recent-file) "Recent file should be kept")
        (finally
          (when (fs/exists? old-file) (fs/delete old-file))
          (when (fs/exists? recent-file) (fs/delete recent-file))))))

  (testing "does not throw when directory does not exist"
    (is (nil? (cache/cleanup-tool-call-outputs!)))))

(deftest save-tool-call-output-test
  (testing "saves text to a file and returns path"
    (let [tool-call-id "save-test-call"
          text "full output here"
          path (cache/save-tool-call-output! tool-call-id text)]
      (try
        (is (string? path))
        (is (fs/exists? path))
        (is (= text (slurp path)))
        (finally
          (when (fs/exists? path) (fs/delete path)))))))
