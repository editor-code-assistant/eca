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

(deftest workspace-dir-name-test
  (let [dir-name #'cache/workspace-dir-name]
    (testing "prefixes hash with project name from first workspace URI"
      (let [workspaces [{:uri "/home/user/my-project"}]
            result (dir-name workspaces identity)]
        (is (re-matches #"my-project_.{8}" result))))

    (testing "sanitizes unsafe filesystem characters"
      (let [workspaces [{:uri "/home/user/my project@v2!"}]
            result (dir-name workspaces identity)]
        (is (re-matches #"my_project_v2__.{8}" result))
        (is (nil? (re-find #"[ @!]" result)))))

    (testing "truncates long project names to 30 chars"
      (let [long-name (apply str (repeat 50 "a"))
            workspaces [{:uri (str "/home/user/" long-name)}]
            result (dir-name workspaces identity)]
        (is (<= (count result) (+ 30 1 8)))))

    (testing "falls back to hash-only when no workspace name available"
      (let [result (dir-name [] identity)]
        (is (re-matches #".{8}" result))))

    (testing "uses first workspace name when multiple workspaces"
      (let [workspaces [{:uri "/home/user/first-project"}
                        {:uri "/home/user/second-project"}]
            result (dir-name workspaces identity)]
        (is (re-matches #"first-project_.{8}" result))))))

(deftest workspace-cache-file-migration-test
  (testing "migrates old hash-only directory to new format"
    (with-temp-cache-dir
      (let [workspaces [{:uri "/home/user/my-project"}]
            hash-only (cache/workspaces-hash workspaces identity)
            old-dir (io/file (cache/global-dir) hash-only)]
        ;; Create old-format directory with a cache file
        (fs/create-dirs old-dir)
        (spit (io/file old-dir "db.transit.json") "{}")

        (let [result (cache/workspace-cache-file workspaces "db.transit.json" identity)]
          (is (not (fs/exists? old-dir)) "Old directory should be renamed")
          (is (fs/exists? (.getParentFile result)) "New directory should exist")
          (is (= "{}" (slurp result)) "Migrated file content should be preserved")
          (is (re-find #"my-project_" (str result)) "New path should contain project name")))))

  (testing "does not migrate when new directory already exists"
    (with-temp-cache-dir
      (let [workspaces [{:uri "/home/user/my-project"}]
            hash-only (cache/workspaces-hash workspaces identity)
            old-dir (io/file (cache/global-dir) hash-only)
            result-before (cache/workspace-cache-file workspaces "db.transit.json" identity)
            new-dir (.getParentFile result-before)]
        ;; Create both directories
        (fs/create-dirs old-dir)
        (spit (io/file old-dir "db.transit.json") "old")
        (fs/create-dirs new-dir)
        (spit (io/file new-dir "db.transit.json") "new")

        (let [result (cache/workspace-cache-file workspaces "db.transit.json" identity)]
          (is (fs/exists? old-dir) "Old directory should remain untouched")
          (is (= "new" (slurp result)) "Should use new directory content"))))))
