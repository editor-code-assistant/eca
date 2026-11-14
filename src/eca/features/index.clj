(ns eca.features.index
  "The workspaces' files that the `index.ignoreFiles` config allows: for `@`
   context search and the filesystem tools."
  (:require
   [babashka.fs :as fs]
   [clojure.core.memoize :as memoize]
   [clojure.java.shell :as shell]
   [clojure.string :as string]))

(set! *warn-on-reflection* true)

(def ^:private ttl-git-ls-files-ms 60000)

(defn ^:private git-ls-files* [root-path]
  (try
    (let [{:keys [out exit]} (shell/sh "git" "ls-files" "--others" "--exclude-standard" "--cached"
                                       :dir root-path)]
      (when (= 0 exit)
        (string/split out #"\n")))
    (catch Exception _ nil)))

(def git-ls-files
  "The files `git ls-files` lists in `root-path`, tracked and untracked but not
   ignored, relative to it; nil when git fails. Cached for a minute."
  (memoize/ttl git-ls-files* :ttl/threshold ttl-git-ls-files-ms))

(def ^:private ttl-all-files-ms 60000)

(defn ^:private all-files-from* [root-filename] (fs/glob root-filename "**"))
(def ^:private all-files-from (memoize/ttl all-files-from* :ttl/threshold ttl-all-files-ms))

(defn filter-allowed [file-paths root-filename config]
  (reduce
   (fn [files {:keys [type]}]
     (case type
       :gitignore (if-let [git-files (git-ls-files root-filename)]
                    (let [git-paths (into #{}
                                          (comp
                                           (map #(fs/file root-filename %))
                                           (map str))
                                          git-files)]
                      (if (seq git-paths)
                        (filter (fn [file]
                                  (contains? git-paths (str file)))
                                files)
                        files))
                    files)
       files))
   file-paths
   (get-in config [:index :ignoreFiles])))

(def ^:private git-absolute-paths
  "Convert `git ls-files` relative paths to absolute path strings. Cached by
   content so it is only recomputed when the memoized `git-ls-files` refreshes."
  (memoize/lu
   (fn [root-filename git-files]
     (into []
           (comp
            (remove string/blank?)
            (map #(str (fs/file root-filename %))))
           git-files))
   :lu/threshold 32))

(defn allowed-files
  "All files under `root-filename` allowed by the `:index :ignoreFiles` config.
   When gitignore filtering is configured and the root is a git repo, files are
   enumerated via `git ls-files` (gitignore-aware, includes hidden tracked
   files) avoiding a full filesystem walk, otherwise falls back to globbing
   the whole tree."
  [root-filename config]
  (or (when (some #(= :gitignore (:type %)) (get-in config [:index :ignoreFiles]))
        (some->> (git-ls-files root-filename)
                 (git-absolute-paths root-filename)))
      (filter-allowed (all-files-from root-filename) root-filename config)))
