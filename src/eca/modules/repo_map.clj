(ns eca.modules.repo-map
  "The `:eca/repo-map` module: a map of the workspaces' files, from `git
   ls-files`, for the `repoMap` context and `/repo-map-show`. Deprecated: no
   client uses it."
  (:require
   [clojure.string]
   [eca.features.built-in-command :as built-in-command]
   [eca.features.index :as f.index]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

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
                       files (f.index/git-ls-files root-filename)]
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

(def module
  {:id :eca/repo-map
   :description "A map of the repository, for the repoMap context."
   :config-spec {:index {:type :map
                         :default {:ignoreFiles [{:type :gitignore}]
                                   :repoMap {:maxTotalEntries 800
                                             :maxEntriesPerDir 50}}}}
   ;; the repoMap context itself waits for module contexts
   :commands (built-in-command/commands
              [["repo-map-show" "Actual repoMap of current session." []]])})
