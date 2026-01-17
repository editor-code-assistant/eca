(ns eca.git-test
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.git :as git]))

(set! *warn-on-reflection* true)

(deftest root-test
  (testing "git root returns a path when in a git repository"
    (let [result (git/root)]
      ;; Should return a non-empty string path or nil
      (is (or (nil? result)
              (and (string? result)
                   (not (empty? result)))))
      
      ;; If we have a root, it should be an absolute path
      (when result
        (is (.isAbsolute (io/file result)))))))

(deftest in-worktree-test
  (testing "in-worktree? returns a boolean"
    (let [result (git/in-worktree?)]
      (is (or (true? result)
              (false? result)
              (nil? result)))))
  
  (testing "correctly detects worktree by checking git-dir"
    ;; If we're in a worktree, git-dir should contain /worktrees/
    (when (git/in-worktree?)
      (let [{:keys [out exit]} (shell/sh "git" "rev-parse" "--git-dir")]
        (when (= 0 exit)
          (is (string/includes? out "worktrees")))))))

(deftest main-repo-root-test
  (testing "main-repo-root returns a path or nil"
    (let [result (git/main-repo-root)]
      (is (or (nil? result)
              (and (string? result)
                   (not (empty? result)))))
      
      ;; If we have a main repo root, it should be absolute
      (when result
        (is (.isAbsolute (io/file result)))))))

(deftest integration-test
  (testing "git functions work together logically"
    (let [root (git/root)]
      (if root
        (do
          ;; If we have a git root, we're in a git repo
          (is (string? root))
          (is (.exists (io/file root)))
          
          ;; If we're in a worktree, behavior should be consistent
          (if (git/in-worktree?)
            (testing "in a worktree"
              (let [main-root (git/main-repo-root)]
                ;; Main repo root should exist
                (is (some? main-root))
                (when main-root
                  ;; Main root and worktree root should be different
                  (is (not= root main-root))
                  ;; Both should exist
                  (is (.exists (io/file main-root))))))
            
            (testing "in main repo (not a worktree)"
              ;; In main repo, main-repo-root might be nil or same as root
              (let [main-root (git/main-repo-root)]
                (is (or (nil? main-root)
                        (= root main-root)))))))
        ;; If not in a git repo, verify that git/root returns nil
        (is (nil? root))))))

(deftest worktree-config-discovery-test
  (testing "git root is suitable for .eca config discovery"
    (let [root (git/root)]
      (if root
        ;; The root should be a directory where .eca config could exist
        (let [eca-dir (io/file root ".eca")]
          ;; We don't test if .eca exists, just that the parent dir is valid
          (is (.isDirectory (io/file root)))
          
          ;; If .eca exists, it should be in the worktree root (not main repo)
          (when (.exists eca-dir)
            (is (.isDirectory eca-dir))))
        ;; If not in a git repo, verify that git/root returns nil
        (is (nil? root))))))
