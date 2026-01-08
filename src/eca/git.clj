(ns eca.git
  (:require
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [eca.logger :as logger]))

(set! *warn-on-reflection* true)

(defn ^:private git-command
  "Execute a git command and return the trimmed output if successful."
  [& args]
  (try
    (let [{:keys [out exit err]} (apply shell/sh "git" args)]
      (when (= 0 exit)
        (string/trim out))
      (when-not (= 0 exit)
        (logger/debug "Git command failed:" args "Error:" err))
      (when (= 0 exit)
        (string/trim out)))
    (catch Exception e
      (logger/debug "Git command exception:" (ex-message e))
      nil)))

(defn root
  "Get the top-level directory of the current git repository or worktree.
  Returns the absolute path to the working directory root, which is the correct
  location for finding .eca config files in both regular repos and worktrees."
  []
  (git-command "rev-parse" "--show-toplevel"))

(defn in-worktree?
  "Check if the current directory is in a git worktree (not the main working tree)."
  []
  (when-let [git-dir (git-command "rev-parse" "--git-dir")]
    (and git-dir
         (string/includes? git-dir "/worktrees/"))))

(defn main-repo-root
  "Get the root of the main repository (not the worktree).
  This is useful if you need to access the main repo's directory.
  Returns nil if not in a git repository or if already in the main repo."
  []
  (when-let [common-dir (git-command "rev-parse" "--git-common-dir")]
    (when (not= common-dir ".git")
      ;; Common dir points to the main .git directory
      ;; Get the parent directory of .git and then get its toplevel
      (let [parent-dir (.getParent (clojure.java.io/file common-dir))]
        (git-command "-C" parent-dir "rev-parse" "--show-toplevel")))))
