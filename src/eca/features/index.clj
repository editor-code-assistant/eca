(ns eca.features.index
  (:require
   [babashka.fs :as fs]
   [clojure.core.memoize :as memoize]
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private ttl-git-ls-files-ms 5000)

(defn ^:private git-ls-files* [root-path]
  (try
    (let [{:keys [out exit]} (shell/sh "git" "ls-files" "--others" "--exclude-standard" "--cached"
                                       :dir root-path)]
      (when (= 0 exit)
        (string/split out #"\n")))
    (catch Exception _ nil)))

(def ^:private git-ls-files (memoize/ttl git-ls-files* :ttl/threshold ttl-git-ls-files-ms))

(defn filter-allowed [file-paths root-filename config]
  (reduce
   (fn [files {:keys [type]}]
     (case type
       :gitignore (if-let [git-files (git-ls-files root-filename)]
                    (let [git-paths (some->> git-files
                                             (mapv (comp str fs/canonicalize #(fs/file root-filename %)))
                                             set)]
                      (if (seq git-paths)
                        (filter (fn [file]
                                  (contains? git-paths (str file)))
                                files)
                        files))
                    files)
       files))
   file-paths
   (get-in config [:index :ignoreFiles])))

(defn ^:private insert-path [tree parts]
  (if (empty? parts)
    tree
    (let [head (first parts)
          tail (rest parts)]
      (update tree head #(insert-path (or % {}) tail)))))

;; Count how many nodes (files/dirs) a tree would render
(defn ^:private tree-count [tree]
  (reduce (fn [cnt [_k v]]
            (let [self 1
                  children (if (seq v) (tree-count v) 0)]
              (+ cnt self children)))
          0
          tree))

;; Render tree using a single-line, brace-compressed format per workspace root.
;; Example: "/repo/root/{README.md,src/{eca/{core.clj}},test/{eca/{core_test.clj}}}\n"
;; Applies limits: global maxTotalEntries and per-directory maxEntriesPerDir (non-root only).
(defn ^:private tree->brace-str-limited
  [tree max-total-entries max-entries-per-dir]
  (let [remaining (atom max-total-entries)
        printed-actual (atom 0) ; only actual nodes (files/dirs), not punctuation
        sb (StringBuilder.)
        ;; Emits a node token if there is remaining budget
        emit-node (fn [] (when (pos? @remaining)
                           (swap! remaining dec)
                           (swap! printed-actual inc)
                           true))
        ;; Recursive renderer for a directory map -> "{...}" string, respecting budgets/limits
        render-dir (fn render-dir [m root?]
                     (let [entries (sort m)
                           entries-vec (vec entries)
                           ;; if not root, cap how many entries to attempt to render here
                           max-here (if root? (count entries-vec)
                                        (min (count entries-vec) max-entries-per-dir))
                           total-here (count entries-vec)]
                       (.append sb "{")
                       (loop [idx 0
                              printed-in-dir 0
                              first? true]
                         (let [stop-per-dir (and (not root?) (>= printed-in-dir max-entries-per-dir))
                               stop-global (<= @remaining 0)]
                           (if (or stop-per-dir stop-global (>= idx total-here))
                             (do
                               (let [not-shown (- total-here printed-in-dir)]
                                 (when (pos? not-shown)
                                   (when-not first? (.append sb ","))
                                   (.append sb "... +")
                                   (.append sb (str not-shown))))
                               (.append sb "}")
                               nil)
                             (let [[k v] (nth entries-vec idx)]
                               (if (>= printed-in-dir max-here)
                                 (recur (inc idx) printed-in-dir first?)
                                 (do
                                   (when-not first? (.append sb ","))
                                   ;; emit this entry if we have budget
                                   (if (emit-node)
                                     (do
                                       (.append sb k)
                                       (if (seq v)
                                         (do
                                           (.append sb "/")
                                           (render-dir v false))
                                         nil)
                                       (recur (inc idx) (inc printed-in-dir) false))
                                     (recur idx printed-in-dir first?))))))))))]
    ;; One line per workspace root
    (doseq [[root m] (sort tree)]
      (when (emit-node)
        (.append sb root)
        (when (seq m)
          (.append sb "/")
          (render-dir m true))
        (.append sb "\n")))
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
         (tree->brace-str-limited tree maxTotalEntries maxEntriesPerDir))
       tree))))

(comment
  (require 'user)
  (user/with-workspace-root "file:///home/greg/dev/nu/stormshield"
    (println (repo-map user/*db*
                       {:index {:repoMap {:maxTotalEntries 800 :maxEntriesPerDir 50}}}
                       {:as-string? true}))))
