(ns eca.base-configs
  "Base configs: shared configs the `baseConfigs` entries point to, e.g. a
   company's providers and MCP servers, fetched from HTTPS URLs or files in
   git repositories and merged right above ECA's defaults (`eca.config`), so
   every other config source overrides them. Each one is cached on disk as
   its last good copy: a session starts with the copies, then fetches the
   configs and refreshes them hourly. A failed fetch keeps the copy, never an
   empty config, so what it defines keeps working, e.g. its MCP servers keep
   running and their logins stay."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.cache :as cache]
   [eca.client-http :as client]
   [eca.config :as config]
   [eca.digest :as digest]
   [eca.file-io :as file-io]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [hato.client :as http])
  (:import
   [java.io File IOException]
   [java.net URI]
   [java.time Instant ZoneId]
   [java.time.format DateTimeFormatter]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[BASE-CONFIGS]")

(def ^:private fetch-timeout-ms 10000)

(def ^:private git-timeout-ms
  "How long each git command of a fetch may take."
  30000)

(def ^:private refresh-interval-ms
  "How often a session fetches its base configs again."
  (* 60 60 1000))

(def ^:private loopback-hosts
  "The hosts a base config may be fetched from over plain HTTP: tests and
   local development."
  #{"localhost" "127.0.0.1" "[::1]" "::1"})

(def state*
  "Each base config's state, by source id: `:config`, its last good copy
   (string keys, as in config JSON); `:fetched-at`, when that copy was fetched
   (epoch ms); `:commit`, for a git source, the commit it came from;
   `:status`: `:cached` when the copy came from disk and wasn't fetched yet,
   `:fetched` when the last fetch got it, `:stale` when the last fetch failed
   and the copy stays, `:failed` when it failed without a copy; `:error`, why
   the last fetch failed."
  (atom {}))

(def ^:private warned*
  "The warnings the user was shown, so each shows once: a source's id until
   it's fetched again, `[:invalid display]` for a skipped entry."
  (atom #{}))

(defn reset-state!
  "Forgets the base configs' state and stops merging them. For tests."
  []
  (reset! state* {})
  (reset! warned* #{})
  (reset! config/base-config-layers* [])
  (config/clear-cache!))

(defn ^:private entries
  "The `baseConfigs` entries of `config`, also accepting a single one."
  [config]
  (let [entries (:baseConfigs config)]
    (cond
      (sequential? entries) entries
      (some? entries) [entries]
      :else [])))

(defn ^:private trimmed [s]
  (some-> s str string/trim not-empty))

(defn display-url
  "`url` without its user info, query and fragment, which may hold secrets:
   what logs and messages show."
  [url]
  (try
    (let [uri (URI. url)]
      (str (URI. (.getScheme uri) nil (.getHost uri) (.getPort uri) (.getPath uri) nil nil)
           (when (.getRawQuery uri) "?…")))
    (catch Exception _
      (string/replace (str url) #"[?#].*" ""))))

(defn ^:private url-invalid-reason
  "Why `url` can't be fetched as a base config, or nil."
  [url]
  (if (string/blank? url)
    "it has no url"
    (try
      (let [uri (URI. url)
            scheme (some-> (.getScheme uri) string/lower-case)
            host (some-> (.getHost uri) string/lower-case)]
        (cond
          (not (contains? #{"https" "http"} scheme)) "only https:// URLs are supported (http:// only for localhost)"
          (nil? host) "it's not an absolute URL"
          (= "https" scheme) nil
          (contains? loopback-hosts host) nil
          :else "only https:// URLs are supported (http:// only for localhost)"))
      (catch Exception _
        "it's not a valid URL"))))

(def ^:private git-url-schemes
  "The URL schemes a git base config may be fetched over: encrypted or local,
   like base config URLs."
  #{"ssh" "git+ssh" "ssh+git" "https" "file"})

(defn ^:private git-invalid-reason
  "Why `path` at `ref` in the git repository `repo` can't be fetched as a base
   config, or nil. Unencrypted transports are refused, like plain HTTP URLs."
  [repo path ref]
  (let [scheme (some->> repo (re-find #"^([A-Za-z][A-Za-z0-9+.-]*)://") second string/lower-case)]
    (cond
      (nil? repo) "it has no git repository"
      (nil? path) "it has no path"
      (or (string/starts-with? repo "-")
          (some-> ref (string/starts-with? "-"))) "it's not a valid git repository or ref"
      (re-find #"^[A-Za-z][A-Za-z0-9+.-]*::" repo) "git remote helpers aren't supported"
      (and scheme (not (contains? git-url-schemes scheme))) "only ssh, https or local git repositories are supported"
      :else nil)))

(defn ^:private display-repo
  "The git repository `repo` without its URL's user info, which may be a
   token. The `user@host:path` form has no password, so it shows as is."
  [repo]
  (string/replace repo #"^([A-Za-z][A-Za-z0-9+.-]*://)[^/@]*@" "$1"))

(defn ^:private git-display
  "How messages show `path` at `ref` in the git repository `repo`."
  [repo path ref]
  (if repo
    (str (when path (str path " from "))
         (display-repo repo)
         (when ref (str " at " ref)))
    "without git repository"))

(defn ^:private entry-headers
  "An entry's `headers`, names and values as strings."
  [entry]
  (when (map? entry)
    (not-empty
     (into {}
           (keep (fn [[k v]]
                   (when (some? v)
                     [(if (keyword? k) (name k) (str k)) (str v)])))
           (:headers entry)))))

(defn ^:private parse-entry
  "A `baseConfigs` entry as a source: its `:id` and `:display`, and `:url` (and
   `:headers`) or `:git`, `:path` (and `:ref`); with `:invalid`, why it can't
   be fetched."
  [entry]
  (if (and (map? entry) (contains? entry :git))
    (let [repo (trimmed (:git entry))
          path (some-> (trimmed (:path entry)) (string/replace #"^/+" "") not-empty)
          ref (trimmed (:ref entry))
          reason (git-invalid-reason repo path ref)]
      (cond-> {:id (pr-str [:git repo ref path])
               :display (git-display repo path ref)
               :git repo
               :path path}
        ref (assoc :ref ref)
        reason (assoc :invalid reason)))
    (let [url (trimmed (cond
                         (string? entry) entry
                         (map? entry) (:url entry)))
          reason (url-invalid-reason url)]
      (cond-> {:id url
               :display (if url (display-url url) "without url")
               :url url}
        (entry-headers entry) (assoc :headers (entry-headers entry))
        reason (assoc :invalid reason)))))

(defn sources
  "The base configs `config` lists in `baseConfigs`, in order and without
   repeats: an entry is a URL, a map with `url` and optional `headers`, or a
   map with a `git` repository, the `path` of the config in it and an
   optional `ref`. Entries that can't be fetched, e.g. plain HTTP URLs other
   than localhost, are left out."
  [config]
  (reduce (fn [sources source]
            (if (or (:invalid source)
                    (some #(= (:id source) (:id %)) sources))
              sources
              (conj sources source)))
          []
          (map parse-entry (entries config))))

(defn ^:private invalid-entries
  "The `baseConfigs` entries `sources` leaves out, as sources with `:invalid`."
  [config]
  (filter :invalid (map parse-entry (entries config))))

(defn ^:private cache-file ^File [id]
  (io/file (cache/global-dir) (str "base-config-" (subs (digest/sha-256-hex id) 0 16) ".json")))

(defn ^:private save-copy!
  "Saves `base-config` as the source's last good copy, through a temp file
   renamed over the previous copy, so a crash midway leaves that one. The
   copy names its source by the id's hash: a URL may hold a token.
   Best-effort: failures only log."
  [{:keys [id display]} base-config fetched-at commit]
  (try
    (file-io/replace-file!
     (cache-file id)
     #(spit % (json/generate-string (cond-> {"source" (digest/sha-256-hex id)
                                             "fetchedAt" fetched-at
                                             "config" base-config}
                                      commit (assoc "commit" commit)))))
    (catch Exception e
      (logger/warn logger-tag "Could not save the copy of" display (ex-message e)))))

(defn ^:private read-copy
  "The source's last good copy saved on disk, as `{:config :fetched-at
   :commit}`, or nil."
  [{:keys [id display]}]
  (let [file (cache-file id)]
    (when (fs/exists? file)
      (try
        (let [{source "source" base-config "config" fetched-at "fetchedAt" commit "commit"}
              (json/parse-string (slurp file))]
          (when (and (= (digest/sha-256-hex id) source) (map? base-config))
            {:config base-config :fetched-at fetched-at :commit commit}))
        (catch Exception e
          (logger/warn logger-tag "Could not read the copy of" display (ex-message e))
          nil)))))

(defn ^:private parse-config
  "`text` as a base config: a JSON object, comments allowed, with string keys.
   Throws when it isn't one, saying `what` wasn't."
  [text what]
  (let [base-config (try
                      (config/read-json-config text)
                      (catch Exception e
                        ;; the parser's first line, without where it was
                        (throw (ex-info (str "invalid JSON: " (first (string/split-lines (str (ex-message e)))))
                                        {}))))]
    (if (map? base-config)
      base-config
      (throw (ex-info (str what " is not a JSON object") {})))))

(defn ^:private fetch-url
  "The config at the source's `url`. Throws when the request fails or the body
   isn't a JSON object."
  [{:keys [url headers]}]
  (let [{:keys [status body]} (http/get url {:http-client (client/merge-with-global-http-client
                                                           ;; follows redirects, but never from HTTPS to HTTP
                                                           {:redirect-policy :normal})
                                             :headers headers
                                             :timeout fetch-timeout-ms
                                             :throw-exceptions? false
                                             :as :string})]
    (when-not (and (int? status) (<= 200 status 299))
      (throw (ex-info (str "HTTP status " status) {:status status})))
    {:config (parse-config body "the response")}))

(def ^:private git-unset-env
  "Environment variables git must not get from ECA: those that would point it
   at another repository, and the askpass programs, which git and ssh run to
   prompt in a window, e.g. for an HTTPS password."
  ["GIT_DIR" "GIT_WORK_TREE" "GIT_COMMON_DIR" "GIT_INDEX_FILE" "GIT_OBJECT_DIRECTORY"
   "GIT_ALTERNATE_OBJECT_DIRECTORIES" "GIT_NAMESPACE" "GIT_CONFIG_COUNT" "GIT_CONFIG_PARAMETERS"
   "GIT_ASKPASS" "SSH_ASKPASS"])

(defn ^:private git-env
  "The environment git runs with: ECA's `env` without `git-unset-env`, with
   prompts off: git's, ssh's askpass and Git Credential Manager's."
  [env]
  (assoc (apply dissoc env git-unset-env)
         "GIT_TERMINAL_PROMPT" "0"
         "SSH_ASKPASS_REQUIRE" "never"
         "GCM_INTERACTIVE" "never"
         "GIT_OPTIONAL_LOCKS" "0"))

(defn ^:private git-error
  "Why git failed, from what it printed on `err`, without credentials: its
   first line other than warnings and hints, which says what happened, e.g.
   `Permission denied (publickey).`, while the last ones only add
   `Please make sure you have the correct access rights...`."
  [err]
  (let [lines (remove string/blank? (string/split-lines (str err)))]
    (-> (or (first (remove #(re-find #"(?i)^\s*(?:warning:|hint:|\*\*)" %) lines))
            (last lines)
            "")
        (string/replace #"(?i)([a-z][a-z0-9+.-]*://)[^/\s@]+@" "$1")
        (string/replace #"\b(?:gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+)" "[token]")
        (string/replace #"(?i)^(?:fatal|error): " "")
        string/trim)))

(defn ^:private stop-process! [proc]
  (p/destroy-tree proc)
  (let [^Process process (:proc proc)]
    (when (.isAlive process)
      (.destroyForcibly process))))

(defn ^:private run-git!
  "Runs git with `args` in `dir` and returns what it printed. Credentials come
   from the user's SSH keys or git credential helpers: git's terminal prompt,
   askpass windows and Git Credential Manager's are off. Hooks, fsmonitor and
   auto maintenance stay off, and only the encrypted or local transports are
   allowed, redirects included. Throws with git's error when it fails or takes
   longer than `git-timeout-ms`."
  [dir & args]
  (let [proc (try
               (p/process (into ["git" "-c" "core.hooksPath=/dev/null" "-c" "core.fsmonitor=false"
                                 "-c" "maintenance.auto=false" "-c" "gc.auto=0"
                                 ;; the user's askpass program too; helpers honoring it don't prompt
                                 "-c" "core.askPass=" "-c" "credential.interactive=false"
                                 "-c" "protocol.allow=never" "-c" "protocol.ssh.allow=always"
                                 "-c" "protocol.https.allow=always" "-c" "protocol.file.allow=always"]
                                args)
                          {:dir (str dir)
                           :out :string
                           :err :string
                           :env (git-env (into {} (System/getenv)))})
               (catch IOException e
                 (throw (ex-info (str "could not run git: " (ex-message e)) {} e))))]
    (try
      (let [{:keys [exit out err] :as result} (deref proc git-timeout-ms nil)]
        (cond
          (nil? result) (throw (ex-info (str "git " (first args) " timed out") {}))
          (not (zero? exit)) (throw (ex-info (str "git " (first args) " failed"
                                                  (if-let [error (not-empty (git-error err))]
                                                    (str ": " error)
                                                    (str " (exit " exit ")")))
                                             {:exit exit}))
          :else out))
      (finally
        (when (.isAlive ^Process (:proc proc))
          (stop-process! proc))))))

(defn ^:private remote-commit
  "The commit `ref` (the default branch when nil) is at in the git repository
   `repo`, resolved as `git clone --branch` does: a branch, else a tag's
   commit. Nil when there's no such ref."
  [repo ref]
  (let [ref (or ref "HEAD")
        ;; `ref^{}` too: the peeled line, an annotated tag's commit, only
        ;; shows when asked for
        commits (into {}
                      (keep #(let [[commit ref-name] (string/split % #"\t")]
                               (when ref-name [ref-name commit])))
                      (string/split-lines (run-git! (fs/temp-dir) "ls-remote" "--" repo ref (str ref "^{}"))))]
    ;; the patterns also match refs ending in `/ref`, e.g. `refs/heads/x/ref`
    (some commits [(str "refs/heads/" ref) (str "refs/tags/" ref "^{}") (str "refs/tags/" ref) ref])))

(defn ^:private delete-clone!
  "Deletes a fetch's clone, read-only git objects too. Best-effort: failures
   only log."
  [dir]
  (try
    (fs/delete-tree dir {:force true})
    (catch Exception e
      (logger/warn logger-tag "Could not delete the temporary clone" (str dir) (ex-message e)))))

(defn ^:private fetch-git
  "The config at the source's `path` in its `git` repository, at its `ref`
   (the default branch when nil), as `{:config :commit}`. When the ref is
   still at the commit of `previous`, the state of the source's last fetch,
   that config, with no clone. Otherwise a shallow clone without files, so git
   fetches only the commit, its trees and the config file."
  [{:keys [git path ref]} previous]
  (let [commit (when (:config previous)
                 (remote-commit git ref))]
    (if (and commit (= commit (:commit previous)))
      {:config (:config previous) :commit commit}
      (let [dir (fs/create-temp-dir {:prefix "eca-base-config-"})]
        (try
          (apply run-git! dir "clone" "--quiet" "--no-local" "--template=" "--depth=1"
                 "--filter=blob:none" "--no-checkout" "--single-branch"
                 (concat (when ref ["--branch" ref]) ["--" git "repo"]))
          (let [repo-dir (fs/file dir "repo")]
            {:config (parse-config (run-git! repo-dir "cat-file" "blob" (str "HEAD:" path)) "the file")
             :commit (string/trim (run-git! repo-dir "rev-parse" "HEAD"))})
          (finally
            (delete-clone! dir)))))))

(defn ^:private fetch
  "The source's config, as `{:config}` and, for a git source, `:commit`.
   `previous` is the state of the source's last fetch."
  [source previous]
  (if (:git source)
    (fetch-git source previous)
    (fetch-url source)))

(defn ^:private error-reason ^String [^Throwable e]
  (if (instance? clojure.lang.ExceptionInfo e)
    (ex-message e)
    (str (.getSimpleName (class e)) (some->> (ex-message e) (str ": ")))))

(defn ^:private format-time [fetched-at]
  (if (int? fetched-at)
    (.format (.withZone (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm") (ZoneId/systemDefault))
             (Instant/ofEpochMilli (long fetched-at)))
    "an unknown time"))

(defn ^:private warn!
  "Logs `text`, showing it to the user too the first time for `key`."
  [messenger key text]
  (logger/warn logger-tag text)
  (let [[warned _] (swap-vals! warned* conj key)]
    (when (and messenger (not (contains? warned key)))
      (try
        (messenger/showMessage messenger {:type "warning" :message text})
        (catch Exception e
          (logger/warn logger-tag "Could not show the warning:" (ex-message e)))))))

(defn ^:private warn-invalid-entries! [messenger config]
  (doseq [{:keys [display invalid]} (invalid-entries config)]
    (warn! messenger [:invalid display]
           (format "Skipping the baseConfigs entry %s: %s." display invalid))))

(defn ^:private sync-layers!
  "Points `config/base-config-layers*` at the copies of `sources`, in their
   order, clearing the config cache when that changes them."
  [sources]
  (let [layers (into [] (keep #(get-in @state* [(:id %) :config])) sources)
        [previous _] (reset-vals! config/base-config-layers* layers)]
    (when (not= previous layers)
      (config/clear-cache!))))

(defn load-cached!
  "Loads, from disk only, the last good copy of each base config `config`
   lists that has none loaded, so the session starts with them before any
   fetch. Returns the sources."
  [config]
  (let [sources (sources config)]
    (doseq [{:keys [id] :as source} sources
            :when (nil? (get-in @state* [id :config]))
            :let [{copy :config :keys [fetched-at commit]} (read-copy source)]
            :when copy]
      (swap! state* update id merge (cond-> {:config copy :fetched-at fetched-at :status :cached}
                                      commit (assoc :commit commit))))
    (sync-layers! sources)
    sources))

(defn ^:private fetched! [{:keys [id display] :as source} {base-config :config :keys [commit]}]
  (let [now (System/currentTimeMillis)]
    (when (contains? base-config "baseConfigs")
      (logger/warn logger-tag display "lists baseConfigs, ignored: base configs don't chain."))
    (save-copy! source base-config now commit)
    (swap! state* assoc id (cond-> {:config base-config :fetched-at now :status :fetched}
                             commit (assoc :commit commit)))
    (swap! warned* disj id)))

(defn ^:private fetch-failed! [messenger {:keys [id display]} ^Throwable e]
  (let [reason (error-reason e)
        {copy :config :keys [fetched-at]} (get (swap! state* update id
                                                      (fn [entry]
                                                        (assoc entry
                                                               :status (if (:config entry) :stale :failed)
                                                               :error reason)))
                                               id)]
    (warn! messenger id
           (if copy
             (format "Could not refresh the base config %s (%s), using its copy from %s."
                     display reason (format-time fetched-at))
             (format "Could not load the base config %s (%s), ECA goes on without it: check your connection and your access to it."
                     display reason)))))

(defn refresh!
  "Fetches the base configs `config` lists, in parallel. A fetched config
   becomes the loaded one and its copy on disk; a failed fetch keeps the
   loaded copy, if any, and warns the user once. Returns the sources."
  [config messenger]
  (let [sources (sources config)]
    (warn-invalid-entries! messenger config)
    (doseq [[source {:keys [result error]}]
            (->> sources
                 (mapv (fn [source]
                         (future [source (try
                                           {:result (fetch source (get @state* (:id source)))}
                                           (catch Exception e
                                             {:error e}))])))
                 (mapv deref))]
      (if result
        (fetched! source result)
        (fetch-failed! messenger source error)))
    (sync-layers! sources)
    sources))

(defn ^:private refresh-safely! [config messenger]
  (try
    (refresh! config messenger)
    (catch Exception e
      (logger/warn logger-tag "Could not fetch the base configs:" (ex-message e)))))

(defonce ^:private refresh-loop-started* (atom false))

(defn ^:private start-refresh-loop!
  "Fetches the base configs every `refresh-interval-ms`, until ECA stops."
  [db* messenger]
  (when (compare-and-set! refresh-loop-started* false true)
    (doto (Thread. ^Runnable (fn []
                               (loop []
                                 (Thread/sleep ^long refresh-interval-ms)
                                 (when-not (:stopping @db*)
                                   (refresh-safely! (config/all @db*) messenger)
                                   (recur)))))
      (.setName "eca-base-configs-refresh")
      (.setDaemon true)
      (.start))))

(defn start!
  "Fetches the session's base configs, then hourly. Waits for the first
   fetch only when a base config has no copy loaded, i.e. on its first use:
   with every copy loaded, the session goes on with them while the fetch
   updates them, which the config change polling then applies."
  [db* messenger]
  (let [config (config/all @db*)
        first-fetch (future (refresh-safely! config messenger))]
    (when (some #(nil? (get-in @state* [(:id %) :config])) (sources config))
      @first-fetch)
    (start-refresh-loop! db* messenger)
    nil))

(defn on-config-changed!
  "Fetches the base configs when `new-config` lists other ones than
   `prev-config`, e.g. right after the user adds one, so it applies without
   restarting ECA. Returns the fetch's future, if any."
  [prev-config new-config messenger]
  (when (and prev-config
             (not= (:baseConfigs prev-config) (:baseConfigs new-config)))
    (future
      (load-cached! new-config)
      (refresh-safely! new-config messenger))))

(defn status-text
  "The base configs `config` lists and how each stands, for `/doctor`, or
   nil when it lists none."
  [config]
  (let [sources (sources config)
        invalid (invalid-entries config)]
    (when (or (seq sources) (seq invalid))
      (string/join
       "\n"
       (concat
        ["Base configs:"]
        (for [{:keys [id display]} sources
              :let [{:keys [status fetched-at commit error]} (get @state* id)
                    copy-time (str (format-time fetched-at)
                                   (when commit (str ", commit " (subs commit 0 (min 7 (count commit))))))]]
          (str "  " display ": "
               (case status
                 :fetched (str "fetched at " copy-time)
                 :cached (str "using its copy from " copy-time ", not fetched yet")
                 :stale (str "using its copy from " copy-time ", the last fetch failed: " error)
                 :failed (str "not loaded, the fetch failed: " error)
                 "not fetched yet")))
        (for [{:keys [display invalid]} invalid]
          (str "  " display ": skipped, " invalid)))))))
