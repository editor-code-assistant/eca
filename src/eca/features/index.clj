(ns eca.features.index
  (:require
   [babashka.fs :as fs]
   [clojure.core.memoize :as memoize]
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private ttl-git-ls-files-ms 60000)

(defn ^:private git-ls-files* [root-path]
  (try
    (let [{:keys [out exit]} (shell/sh "git" "ls-files" "--others" "--exclude-standard" "--cached"
                                       :dir root-path)]
      (when (= 0 exit)
        (string/split out #"\n")))
    (catch Exception _ nil)))

(def ^:private git-ls-files (memoize/ttl git-ls-files* :ttl/threshold ttl-git-ls-files-ms))

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

(defn ^:private insert-path [tree parts]
  (if (empty? parts)
    tree
    (let [head (first parts)
          tail (rest parts)]
      (update tree head #(insert-path (or % {}) tail)))))

;; Count how many nodes (lines) a tree would render
(defn ^:private tree-count [tree]
  (reduce (fn [cnt [_k v]]
            (let [self 1
                  children (if (seq v) (tree-count v) 0)]
              (+ cnt self children)))
          0
          tree))

;; Render tree with limits: global max entries and per-directory max entries (non-root only)
(defn ^:private tree->str-limited
  [tree max-total-entries max-entries-per-dir]
  (let [indent-str (fn [level] (apply str (repeat (* 1 level) " ")))
        remaining (atom max-total-entries)
        printed-lines (atom 0)       ;; all printed lines (nodes + indicator lines)
        printed-actual (atom 0)      ;; only actual tree nodes
        sb (StringBuilder.)
         ;; emit a single line if we still have remaining budget
        emit-line (fn [^String s]
                    (when (pos? @remaining)
                      (.append sb s)
                      (swap! remaining dec)
                      (swap! printed-lines inc)))
        emit-node (fn [^String s]
                    (when (pos? @remaining)
                      (.append sb s)
                      (swap! remaining dec)
                      (swap! printed-lines inc)
                      (swap! printed-actual inc)))
         ;; recursive emit of a level (all entries in map m)
        emit-level (fn emit-level [m indent-level root?]
                     (let [entries (sort m)
                            ;; Apply per-dir limit to the current level if not root
                           entries-vec (vec entries)
                           [to-show hidden] (if root?
                                              [entries-vec []]
                                              [(subvec entries-vec 0 (min (count entries-vec) max-entries-per-dir))
                                               (subvec entries-vec (min (count entries-vec) max-entries-per-dir))])]
                       (doseq [[k v] to-show]
                         (when (pos? @remaining)
                           (emit-node (str (indent-str indent-level) k "\n"))
                           (when (seq v)
                             (emit-level v (inc indent-level) false))))
                       (when (and (seq hidden) (pos? @remaining) (not root?))
                         (emit-line (str (indent-str indent-level) "... truncated output ("
                                         (count hidden) " more entries)\n")))))]
    (emit-level tree 0 true)
     ;; Compute total possible nodes and append global truncation line if needed
    (let [total (tree-count tree)
          omitted (- total @printed-actual)]
      (when (pos? omitted)
        (.append sb (str "... truncated output (" omitted " more entries)\n"))))
    (str sb)))

(defn repo-map
  ([db config] (repo-map db config {}))
  ([db config {:keys [as-string?]}]
   (let [tree (reduce
               (fn [t {:keys [uri]}]
                 (let [root-filename (shared/uri->filename uri)
                       files (git-ls-files root-filename)]
                   (merge t
                          {root-filename
                           (reduce
                            (fn [tree path]
                              (insert-path tree (clojure.string/split path #"/")))
                            {}
                            files)})))
               {}
               (:workspace-folders db))]
     (if as-string?
       (let [{:keys [maxTotalEntries maxEntriesPerDir]} (get-in config [:index :repoMap])]
         (tree->str-limited tree maxTotalEntries maxEntriesPerDir))
       tree))))

(comment
  (require 'user)
  (user/with-workspace-root "file:///home/greg/dev/eca"
    (println (repo-map user/*db*
                       {:index {:repoMap {:maxTotalEntries 800 :maxEntriesPerDir 50}}}
                       {:as-string? true}))))
