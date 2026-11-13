(ns eca.modules.loader
  "Loads external modules: Clojure code interpreted with SCI, in one context
   shared by all of them, so they can require each other.

   A module is a namespace that defines `module`. A file module is one `.clj`
   file. A folder module has a `module.clj`, and its other namespaces load
   from the folder: `acme.jira.client` from `<folder>/acme/jira/client.clj`.
   Inside SCI, modules see only the `eca.api*` namespaces of ECA.

   Sources, the first one wins when two define the same module id:
   - `--module` paths, for the session;
   - `modules.<id>.source` in config;
   - each workspace's `.eca/modules/`;
   - `~/.config/eca/modules/`;
   - the `modules/` folder of installed plugins.

   `sync!` loads new sources, reloads changed ones (by content hash) and
   drops the ones gone; `watch!` runs it every few seconds, so a module
   reloads on save."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [eca.api]
   [eca.api.fs]
   [eca.api.http]
   [eca.api.json]
   [eca.config :as config]
   [eca.logger :as logger]
   [eca.modules :as modules]
   [eca.shared :as shared]
   [sci.core :as sci])
  (:import
   [java.io File Writer]
   [java.security MessageDigest]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[MODULES]")

(def ^:private kind-rank
  "Where a source comes from, by precedence: lower first."
  {:cli 0 :config 1 :workspace 2 :global 3 :plugin 4})

;; --- Sources

(defonce ^:private cli-paths* (atom []))

(defn set-cli-paths!
  "The paths given with `--module` for this session, relative to the working
   directory."
  [paths]
  (reset! cli-paths* (mapv #(str (fs/absolutize (fs/expand-home (str %)))) paths)))

(defn ^:private canonical [path]
  (str (fs/canonicalize path)))

(defn ^:private resolve-config-path
  "A `modules.<id>.source` path: `~` expands, and a relative path resolves
   against the first workspace root, else the working directory."
  [path db]
  (let [expanded (fs/expand-home (str path))]
    (canonical (if (fs/absolute? expanded)
                 expanded
                 (if-let [root (some-> (:workspace-folders db) first :uri shared/uri->filename)]
                   (fs/path root (str expanded))
                   expanded)))))

(defn ^:private sources-in
  "The modules in folder `dir`: its `.clj` and `.cljc` files, and its folders
   that have a `module.clj`. Hidden entries are skipped."
  [dir kind]
  (when (fs/directory? dir)
    (->> (fs/list-dir dir)
         (remove #(string/starts-with? (str (fs/file-name %)) "."))
         (filter #(if (fs/directory? %)
                    (fs/regular-file? (fs/path % "module.clj"))
                    (and (fs/regular-file? %) (contains? #{"clj" "cljc"} (fs/extension %)))))
         (map (fn [path] {:path (canonical path) :kind kind}))
         (sort-by :path))))

(defn sources
  "The external module sources under `config`, by precedence, one per path:
   `{:path :kind}`, with `:expected-id` for a `modules.<id>.source` and
   `:plugin` for a plugin's module. `pureConfig` keeps only the `--module`
   paths and the configured sources."
  [config db]
  (let [all (concat
             (for [path @cli-paths*]
               {:path (canonical path) :kind :cli})
             (for [[id {:keys [source]}] (sort-by key (modules/modules-config config))
                   :when (and (string? source) (not (string/blank? source)))]
               {:path (resolve-config-path source db) :kind :config :expected-id id})
             (when-not (:pureConfig config)
               (concat
                (mapcat #(sources-in (fs/path (shared/uri->filename (:uri %)) ".eca" "modules") :workspace)
                        (:workspace-folders db))
                (sources-in (fs/path (shared/global-config-dir) "modules") :global)
                (for [{:keys [path plugin]} (:modules @config/plugin-components*)]
                  {:path (canonical path) :kind :plugin :plugin plugin}))))]
    (:sources (reduce (fn [{:keys [seen] :as acc} {:keys [path] :as source}]
                        (if (contains? seen path)
                          acc
                          (-> acc
                              (update :seen conj path)
                              (update :sources conj source))))
                      {:seen #{} :sources []}
                      all))))

(defn ^:private source-files
  "The files whose content makes up a source, in a stable order: the file
   itself, or the folder's `.clj`, `.cljc` and `.edn` files."
  [path]
  (cond
    (fs/regular-file? path) [path]
    (fs/directory? path) (->> (fs/glob path "**.{clj,cljc,edn}")
                              (filter fs/regular-file?)
                              (map str)
                              sort
                              vec)
    :else nil))

(defn ^:private content-hash [files]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (doseq [^String file files]
      (.update md (.getBytes file "UTF-8"))
      (.update md ^bytes (fs/read-all-bytes file)))
    (.toString (BigInteger. 1 (.digest md)) 16)))

;; --- SCI

;; :ctx the SCI context; :entries entry namespace → its file; :roots the folder
;; modules; :ns-files every namespace loaded → its file; :reloading the source
;; being loaded; :loaded path → {:hash :source :result}
(defonce ^:private state* (atom {}))

(def ^:private lock (Object.))

(defn ^:private under? [^String file ^String path]
  (or (= file path) (string/starts-with? file (str path File/separator))))

(defn ^:private ns-file
  "The file namespace `ns-sym` loads from: a module's entry file, else a file
   in a folder module."
  [{:keys [entries roots]} ns-sym]
  (or (get entries ns-sym)
      (let [base (string/replace (namespace-munge ns-sym) "." "/")]
        (some (fn [root]
                (some (fn [ext]
                        (let [file (fs/path root (str base "." ext))]
                          (when (fs/regular-file? file) (str file))))
                      ["clj" "cljc"]))
              roots))))

(defn ^:private load-fn
  "Finds the source of a namespace a module requires. While a source loads,
   its own namespaces load again; the others load only once."
  [{:keys [namespace]}]
  (let [{:keys [ns-files reloading] :as state} @state*
        file (ns-file state namespace)]
    (when (and file
               (or (not (contains? ns-files namespace))
                   (and reloading (under? file reloading))))
      (swap! state* assoc-in [:ns-files namespace] file)
      {:file file :source (slurp file)})))

(defn ^:private log-writer
  "A Writer that logs each line written to it: modules' `*out*` and `*err*`,
   since stdout is the JSON-RPC channel."
  ^Writer [log-fn]
  (let [buf (StringBuilder.)
        emit! (fn [all?]
                (let [^String text (locking buf
                                     (let [s (.toString buf)
                                           end (int (if all? (.length s) (inc (.lastIndexOf s "\n"))))]
                                       (.delete buf 0 end)
                                       (subs s 0 end)))]
                  (doseq [line (string/split-lines text)
                          :when (not (string/blank? line))]
                    (log-fn logger-tag "Module output:" line))))]
    (proxy [Writer] []
      (write
        ([x]
         (locking buf
           (cond
             (string? x) (.append buf ^String x)
             (integer? x) (.append buf (char (int x)))
             :else (.append buf ^chars x)))
         (emit! false))
        ([x off len]
         (locking buf
           (if (string? x)
             (.append buf ^String x (int off) (int (+ (int off) (int len))))
             (.append buf ^chars x (int off) (int len))))
         (emit! false)))
      (flush [] (emit! true))
      (close [] (emit! true)))))

(defn ^:private new-ctx []
  (sci/alter-var-root sci/out (constantly (log-writer logger/info)))
  (sci/alter-var-root sci/err (constantly (log-writer logger/warn)))
  (sci/init {:namespaces {'eca.api (sci/copy-ns eca.api (sci/create-ns 'eca.api))
                          'eca.api.fs (sci/copy-ns eca.api.fs (sci/create-ns 'eca.api.fs))
                          'eca.api.http (sci/copy-ns eca.api.http (sci/create-ns 'eca.api.http))
                          'eca.api.json (sci/copy-ns eca.api.json (sci/create-ns 'eca.api.json))}
             :load-fn load-fn}))

(defn ^:private ctx []
  (or (:ctx @state*)
      (:ctx (swap! state* assoc :ctx (new-ctx)))))

(defn ^:private error-text
  "What went wrong, and where when SCI knows it."
  [^Throwable e]
  (let [{:keys [file line column]} (ex-data e)]
    (str (or (ex-message e) (str e))
         (when line
           (str " (" (if file (fs/file-name file) "?") ":" line (when column (str ":" column)) ")")))))

(defn ^:private entry-ns
  "The namespace an entry file declares in its first form."
  [sci-ctx entry]
  (let [form (sci/parse-string sci-ctx (slurp entry))]
    (if (and (seq? form) (= 'ns (first form)) (simple-symbol? (second form)))
      (second form)
      (throw (ex-info (str (fs/file-name entry) " must start with an ns form") {})))))

(defn ^:private evaluate!
  "Loads `entry-ns` and the namespaces under `path` again, and returns the
   value of its `module` var."
  [sci-ctx path entry-ns]
  (swap! state* assoc :reloading path)
  (try
    (sci/eval-string* sci-ctx (str "(require '" entry-ns " :reload-all)"))
    (sci/eval-string* sci-ctx (str "(some-> (resolve '" entry-ns "/module) deref)"))
    (finally
      (swap! state* dissoc :reloading))))

(defn ^:private load-source
  "Evaluates `source`: `{:module ..}`, or `{:error ..}` when it doesn't load."
  [{:keys [path expected-id]}]
  (try
    (let [folder? (fs/directory? path)
          entry (if folder? (str (fs/path path "module.clj")) path)]
      (if-not (fs/regular-file? entry)
        {:error (if folder? "The folder has no module.clj" "File not found")}
        (let [sci-ctx (ctx)
              entry-ns (entry-ns sci-ctx entry)
              claimed-by (get-in @state* [:entries entry-ns])]
          (if (and claimed-by (not (under? claimed-by path)))
            ;; e.g. a --module copy of an installed module: the first source keeps it
            {:error (format "Not loaded: %s already loads namespace %s" claimed-by entry-ns)
             :refused? true}
            (do
              (swap! state* (fn [state]
                              (cond-> (-> state
                                          ;; an edit may have renamed the namespace
                                          (update :entries #(into {} (remove (fn [[_ file]] (= file entry))) %))
                                          (assoc-in [:entries entry-ns] entry))
                                folder? (update :roots (fnil conj (sorted-set)) path))))
              (let [module (evaluate! sci-ctx path entry-ns)]
                (cond
                  (nil? module)
                  {:error (str "Namespace " entry-ns " defines no module var")}

                  (not (map? module))
                  {:error (str entry-ns "/module must be a map")}

                  (and expected-id (not= expected-id (:id module)))
                  {:error (format "It defines %s, not %s, the id modules.%s.source names"
                                  (pr-str (:id module)) expected-id (symbol expected-id))}

                  :else
                  {:module module})))))))
    (catch Exception e
      {:error (error-text e)})))

(defn ^:private forget!
  "Drops what the loader knows of source `path`: its entry, folder and the
   namespaces it loaded, so loading it again starts fresh."
  [path]
  (swap! state* (fn [state]
                  (-> state
                      (update :entries #(into {} (remove (fn [[_ file]] (under? file path))) %))
                      (update :roots #(some-> % (disj path)))
                      (update :ns-files #(into {} (remove (fn [[_ file]] (under? file path))) %))
                      (update :loaded dissoc path)))))

(defn ^:private register! [{:keys [path kind expected-id plugin]} {:keys [module error refused?]}]
  (modules/add-external! path module (cond-> {:kind kind :rank (kind-rank kind)}
                                       expected-id (assoc :expected-id expected-id)
                                       plugin (assoc :plugin plugin)
                                       error (assoc :error error)
                                       refused? (assoc :refused? true))))

(defn ^:private hash-of [path]
  (try
    (some-> (seq (source-files path)) content-hash)
    (catch Exception _ nil)))

(defn sync!
  "Makes the loaded external modules match their sources under `config`:
   loads new sources, reloads changed ones (by content hash, and the paths in
   `force` anyway) and drops the ones gone. Returns what changed:
   `[{:path :status :id :error}]`, `:status` being `:loaded`, `:failed`,
   `:refused` (another source loads its namespace) or `:removed`."
  ([config db] (sync! config db #{}))
  ([config db force]
   (locking lock
     (let [wanted (sources config db)
           wanted-paths (set (map :path wanted))
           loaded (:loaded @state*)
           removed (mapv (fn [path]
                           (forget! path)
                           (modules/remove-external! path)
                           (logger/info logger-tag (format "Removed module %s" path))
                           {:path path :status :removed})
                         (sort (remove wanted-paths (keys loaded))))
           changed (into []
                         (keep (fn [{:keys [path] :as source}]
                                 (let [hash (hash-of path)
                                       previous (get loaded path)
                                       described (select-keys source [:kind :expected-id :plugin])
                                       reload? (or (nil? previous)
                                                   (contains? force path)
                                                   (not= hash (:hash previous))
                                                   (not= (:expected-id described)
                                                         (get-in previous [:source :expected-id])))]
                                   (when (or reload? (not= described (:source previous)))
                                     (let [result (if reload? (load-source source) (:result previous))
                                           id (or (:id (:module result)) (:expected-id source))]
                                       (register! source result)
                                       (swap! state* assoc-in [:loaded path]
                                              {:hash hash :source described :result result})
                                       (cond
                                         (:refused? result)
                                         (do (logger/info logger-tag (format "Module %s: %s" path (:error result)))
                                             {:path path :status :refused :id id :reason (:error result)})

                                         (:error result)
                                         (do (logger/warn logger-tag (format "Module %s failed to load: %s"
                                                                             path (:error result)))
                                             {:path path :status :failed :id id :error (:error result)})

                                         :else
                                         (do (logger/info logger-tag (format "Loaded module %s from %s" id path))
                                             {:path path :status :loaded :id id})))))))
                         wanted)]
       (into removed changed)))))

(defn reload!
  "Evaluates the source of external module `id` again, even if unchanged: the
   active one when several sources define `id`. Returns what changed, as
   `sync!`, or nil when `id` isn't external."
  [id config db]
  (let [entries (filter #(and (= id (:id %)) (not (:built-in? %))) (modules/statuses config))]
    (when-let [path (:source (or (first (filter #(= :active (:status %)) entries))
                                 (first entries)))]
      (sync! config db #{path}))))

(defn watch!
  "Runs `sync!` every `interval-ms` while `(running?)`, and calls
   `(on-changes changes)` after a sync that changed something."
  [{:keys [running? config-fn db-fn on-changes interval-ms] :or {interval-ms 2000}}]
  (loop []
    (when (running?)
      (Thread/sleep (long interval-ms))
      (when (running?)
        (try
          (let [changes (sync! (config-fn) (db-fn))]
            (when (seq changes)
              (on-changes changes)))
          (catch Exception e
            (logger/warn logger-tag "Could not reload modules:" e))))
      (recur))))

(defn reset-state!
  "Forgets every source and starts a new SCI context. For tests."
  []
  (locking lock
    (reset! state* {})
    (reset! cli-paths* [])))
