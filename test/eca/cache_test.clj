(ns eca.cache-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [eca.cache :as cache]
   [eca.test-helper :as h]))

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
  (testing "deletes files older than retention period and keeps recent files"
    (with-temp-cache-dir
      (let [dir (cache/tool-call-outputs-dir)
            old-file (java.io.File. (str dir "/old-call.txt"))
            recent-file (java.io.File. (str dir "/recent-call.txt"))]
        ;; Setup: create files
        (fs/create-dirs dir)
        (spit old-file "old output")
        (spit recent-file "recent output")
        ;; Set old file to 15 days ago
        (.setLastModified old-file (- (System/currentTimeMillis) (* 15 24 60 60 1000)))

        (cache/cleanup-tool-call-outputs! 14)

        (is (not (fs/exists? old-file)) "Old file should be deleted")
        (is (fs/exists? recent-file) "Recent file should be kept"))))

  (testing "does not throw when directory does not exist"
    (with-temp-cache-dir
      (is (nil? (cache/cleanup-tool-call-outputs! 14))))))

(deftest cleanup-tool-call-outputs-disabled-test
  (testing "does not delete any files when retention-days is 0"
    (with-temp-cache-dir
      (let [dir (cache/tool-call-outputs-dir)
            old-file (java.io.File. (str dir "/old-call.txt"))]
        (fs/create-dirs dir)
        (spit old-file "old output")
        (.setLastModified old-file (- (System/currentTimeMillis) (* 15 24 60 60 1000)))

        (cache/cleanup-tool-call-outputs! 0)

        (is (fs/exists? old-file) "Old file should be kept when cleanup is disabled")))))

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

    (testing "uses sorted-first workspace name regardless of folder order"
      (let [a {:uri "/home/user/first-project"}
            b {:uri "/home/user/second-project"}]
        (is (re-matches #"first-project_.{8}" (dir-name [a b] identity)))
        (is (= (dir-name [a b] identity) (dir-name [b a] identity)))))))

(deftest workspaces-hash-order-independent-test
  (testing "the same set of folders hashes the same regardless of order"
    (let [a {:uri "/home/user/aaa"}
          b {:uri "/home/user/bbb"}]
      (is (= (cache/workspaces-hash [a b] identity)
             (cache/workspaces-hash [b a] identity))))))

(defn ^:private setup-worktree!
  "Creates `<base>/repo` as a main checkout and a linked worktree at `wt-path`
   whose `.git` file contains `gitdir: <gitdir-str>`. Returns the repo path."
  [base wt-name gitdir-str commondir-str]
  (let [repo (fs/file base "repo")
        wt-gitdir (fs/file repo ".git" "worktrees" wt-name)]
    (fs/create-dirs wt-gitdir)
    (spit (fs/file wt-gitdir "commondir") (str commondir-str "\n"))
    (spit (fs/file base wt-name ".git") (str "gitdir: " gitdir-str "\n"))
    (str repo)))

(deftest workspaces-hash-worktree-canonicalization-test
  (let [tmp (fs/create-temp-dir {:prefix "eca-worktree-test"})]
    (try
      (testing "a linked worktree hashes like its repository's main checkout"
        (let [wt (str (fs/file tmp "wt1"))
              _ (fs/create-dirs wt)
              repo (setup-worktree! tmp "wt1" (str (fs/file tmp "repo" ".git" "worktrees" "wt1")) "../..")]
          (is (= (cache/workspaces-hash [{:uri repo}] identity)
                 (cache/workspaces-hash [{:uri wt}] identity)))
          (testing "and shares the main checkout's cache dir name"
            (is (= (#'cache/workspace-dir-name [{:uri repo}] identity)
                   (#'cache/workspace-dir-name [{:uri wt}] identity))))))
      (testing "relative gitdir and absolute commondir pointers also resolve"
        (let [base (fs/file tmp "rel")
              wt (str (fs/file base "wt2"))
              _ (fs/create-dirs wt)
              repo (setup-worktree! base "wt2"
                                    "../repo/.git/worktrees/wt2"
                                    (str (fs/file base "repo" ".git")))]
          (is (= (cache/workspaces-hash [{:uri repo}] identity)
                 (cache/workspaces-hash [{:uri wt}] identity)))))
      (finally
        (fs/delete-tree tmp)))))

(deftest workspaces-hash-worktree-no-canonicalization-test
  (let [tmp (fs/create-temp-dir {:prefix "eca-worktree-neg-test"})
        raw-hash (fn [path] (#'cache/paths-hash [path]))]
    (try
      (testing "a normal checkout (.git directory) keeps its own hash"
        (let [repo (str (fs/file tmp "normal"))]
          (fs/create-dirs (fs/file repo ".git"))
          (is (= (raw-hash repo) (cache/workspaces-hash [{:uri repo}] identity)))))
      (testing "a worktree of a bare repo keeps its own hash (common dir is not named .git)"
        (let [bare-gitdir (fs/file tmp "bare.git" "worktrees" "wt")
              wt (str (fs/file tmp "bare-wt"))]
          (fs/create-dirs bare-gitdir)
          (fs/create-dirs wt)
          (spit (fs/file bare-gitdir "commondir") "../..\n")
          (spit (fs/file wt ".git") (str "gitdir: " bare-gitdir "\n"))
          (is (= (raw-hash wt) (cache/workspaces-hash [{:uri wt}] identity)))))
      (testing "a submodule checkout keeps its own hash (no commondir file)"
        (let [module-gitdir (fs/file tmp "parent" ".git" "modules" "sub")
              sub (str (fs/file tmp "parent" "sub"))]
          (fs/create-dirs module-gitdir)
          (fs/create-dirs sub)
          (spit (fs/file sub ".git") "gitdir: ../.git/modules/sub\n")
          (is (= (raw-hash sub) (cache/workspaces-hash [{:uri sub}] identity)))))
      (testing "a dir without .git keeps its own hash"
        (let [plain (str (fs/file tmp "plain"))]
          (fs/create-dirs plain)
          (is (= (raw-hash plain) (cache/workspaces-hash [{:uri plain}] identity)))))
      (finally
        (fs/delete-tree tmp)))))

(deftest workspace-cache-file-stable-test
  (testing "resolves to the same canonical file regardless of folder order"
    (with-temp-cache-dir
      (let [a {:uri "/home/user/aaa"}
            b {:uri "/home/user/bbb"}]
        (is (= (str (cache/workspace-cache-file [a b] "db.transit.json" identity))
               (str (cache/workspace-cache-file [b a] "db.transit.json" identity))))))))

(deftest redundant-workspace-cache-files-test
  (testing "finds legacy hash-only and differently-prefixed dirs for the same workspace, excluding canonical"
    (with-temp-cache-dir
      (let [workspaces [{:uri "/home/user/my-project"}]
            ws-hash (cache/workspaces-hash workspaces identity)
            base (cache/global-dir)
            canonical (cache/workspace-cache-file workspaces "db.transit.json" identity)
            hash-only-file (io/file base ws-hash "db.transit.json")
            other-prefixed-file (io/file base (str "old_" ws-hash) "db.transit.json")]
        (fs/create-dirs (.getParentFile canonical))
        (spit canonical "canonical")
        (fs/create-dirs (.getParentFile hash-only-file))
        (spit hash-only-file "legacy")
        (fs/create-dirs (.getParentFile other-prefixed-file))
        (spit other-prefixed-file "other")
        (let [redundant (set (map str (cache/redundant-workspace-cache-files workspaces "db.transit.json" identity)))]
          (is (contains? redundant (str hash-only-file)))
          (is (contains? redundant (str other-prefixed-file)))
          (is (not (contains? redundant (str canonical)))))))))

(deftest redundant-workspace-cache-files-worktree-migration-test
  (testing "a worktree's pre-canonicalization cache dir is redundant for the repo-level cache"
    (with-temp-cache-dir
      (let [tmp (fs/create-temp-dir {:prefix "eca-worktree-migration-test"})]
        (try
          (let [wt (str (fs/file tmp "wt1"))
                _ (fs/create-dirs wt)
                repo (setup-worktree! tmp "wt1" (str (fs/file tmp "repo" ".git" "worktrees" "wt1")) "../..")
                workspaces [{:uri wt}]
                raw-hash (#'cache/paths-hash [wt])
                base (cache/global-dir)
                ;; chats saved before canonicalization, when the worktree was its own bucket
                legacy-file (io/file base (str "wt1_" raw-hash) "db.transit.json")
                canonical (cache/workspace-cache-file workspaces "db.transit.json" identity)]
            (fs/create-dirs (.getParentFile legacy-file))
            (spit legacy-file "legacy worktree chats")
            (is (= (str (cache/workspace-cache-file [{:uri repo}] "db.transit.json" identity))
                   (str canonical))
                "worktree resolves to the repo-level cache file")
            (is (= [(str legacy-file)]
                   (map str (cache/redundant-workspace-cache-files workspaces "db.transit.json" identity)))
                "the old worktree bucket is folded into the repo-level cache"))
          (finally
            (fs/delete-tree tmp)))))))

(deftest first-valid-home-test
  (let [first-valid-home #'cache/first-valid-home]
    (testing "skips the \"?\" placeholder and uses the next absolute path"
      (is (= (h/file-path "/home/user") (first-valid-home ["?" (h/file-path "/home/user") nil]))))
    (testing "skips nil and blank candidates"
      (is (= (h/file-path "/home/user") (first-valid-home [nil "" "   " (h/file-path "/home/user")]))))
    (testing "skips relative paths"
      (is (= (h/file-path "/abs") (first-valid-home ["relative/path" (h/file-path "/abs")]))))
    (testing "returns nil when no candidate is a valid absolute path"
      (is (nil? (first-valid-home ["?" "" "relative"]))))
    (testing "returns the first valid absolute path"
      (is (= (h/file-path "/first") (first-valid-home [(h/file-path "/first") (h/file-path "/second")]))))))

(deftest user-home-test
  (testing "uses user.home when it is a valid absolute path"
    (let [tmp (str (fs/create-temp-dir {:prefix "eca-home-test"}))
          prev (System/getProperty "user.home")]
      (try
        (System/setProperty "user.home" tmp)
        (is (= tmp (cache/user-home)))
        (finally
          (System/setProperty "user.home" prev)
          (fs/delete-tree tmp)))))
  (testing "falls back to an absolute env path when user.home is the \"?\" placeholder"
    (let [prev (System/getProperty "user.home")]
      (try
        (System/setProperty "user.home" "?")
        ;; In normal environments HOME/USERPROFILE is set and absolute; assert the
        ;; placeholder is never returned. Guarded so exotic CI envs don't flake.
        (when-let [env-home (or (System/getenv "HOME") (System/getenv "USERPROFILE"))]
          (when (.isAbsolute (io/file env-home))
            (let [resolved (cache/user-home)]
              (is (not= "?" resolved))
              (is (.isAbsolute (io/file resolved)))
              (is (= env-home resolved)))))
        (finally
          (System/setProperty "user.home" prev))))))

(deftest global-dir-not-relative-test
  (testing "global-dir is absolute (no literal \"?\" segment) when user.home is the placeholder"
    (let [prev (System/getProperty "user.home")]
      (try
        (System/setProperty "user.home" "?")
        (when (and (not (System/getenv "XDG_CACHE_HOME"))
                   (or (System/getenv "HOME") (System/getenv "USERPROFILE")))
          (let [dir (cache/global-dir)]
            (is (.isAbsolute dir))
            (is (nil? (re-find #"/\?/" (str dir))))))
        (finally
          (System/setProperty "user.home" prev))))))
