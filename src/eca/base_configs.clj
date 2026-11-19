(ns eca.base-configs
  "Base configs: shared configs the `baseConfigs` entries point to, e.g. a
   company's providers and MCP servers, fetched from HTTPS URLs and merged
   right above ECA's defaults (`eca.config`), so every other config source
   overrides them. Each one is cached on disk as its last good copy: a
   session starts with the copies, then fetches the configs and refreshes
   them hourly. A failed fetch keeps the copy, never an empty config, so
   what it defines keeps working, e.g. its MCP servers keep running and
   their logins stay."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.cache :as cache]
   [eca.client-http :as client]
   [eca.config :as config]
   [eca.db.io :as db.io]
   [eca.digest :as digest]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [hato.client :as http])
  (:import
   [java.io File]
   [java.net URI]
   [java.time Instant ZoneId]
   [java.time.format DateTimeFormatter]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[BASE-CONFIGS]")

(def ^:private fetch-timeout-ms 10000)

(def ^:private refresh-interval-ms
  "How often a session fetches its base configs again."
  (* 60 60 1000))

(def ^:private loopback-hosts
  "The hosts a base config may be fetched from over plain HTTP: tests and
   local development."
  #{"localhost" "127.0.0.1" "[::1]" "::1"})

(def state*
  "Each base config's state, by URL: `:config`, its last good copy (string
   keys, as in config JSON); `:fetched-at`, when that copy was fetched (epoch
   ms); `:status`: `:cached` when the copy came from disk and wasn't fetched
   yet, `:fetched` when the last fetch got it, `:stale` when the last fetch
   failed and the copy stays, `:failed` when it failed without a copy;
   `:error`, why the last fetch failed."
  (atom {}))

(def ^:private warned*
  "The warnings the user was shown, so each shows once: a base config's URL
   until it's fetched again, `[:invalid url]` for a skipped entry."
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

(defn ^:private entry-url [entry]
  (some-> (cond
            (string? entry) entry
            (map? entry) (:url entry))
          str
          string/trim))

(defn ^:private invalid-reason
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

(defn sources
  "The base configs `config` lists in `baseConfigs`, in order and without
   repeats, as `{:url :headers}`: an entry is a URL, or a map with `url` and
   optional `headers`. Entries that aren't HTTPS URLs (plain HTTP only for
   localhost) are left out."
  [config]
  (reduce (fn [sources entry]
            (let [url (entry-url entry)]
              (if (or (invalid-reason url)
                      (some #(= url (:url %)) sources))
                sources
                (conj sources (cond-> {:url url}
                                (entry-headers entry) (assoc :headers (entry-headers entry)))))))
          []
          (entries config)))

(defn ^:private invalid-entries
  "The `baseConfigs` entries `sources` leaves out, as `[url reason]`."
  [config]
  (for [entry (entries config)
        :let [url (entry-url entry)
              reason (invalid-reason url)]
        :when reason]
    [url reason]))

(defn ^:private cache-file ^File [url]
  (io/file (cache/global-dir) (str "base-config-" (subs (digest/sha-256-hex url) 0 16) ".json")))

(defn ^:private save-copy!
  "Saves `base-config` as `url`'s last good copy, through a temp file renamed
   over the previous copy, so a crash midway leaves that one. Best-effort:
   failures only log."
  [url base-config fetched-at]
  (let [file (cache-file url)
        tmp (io/file (str file "." (random-uuid) ".tmp"))]
    (try
      (io/make-parents file)
      (spit tmp (json/generate-string {"url" url "fetchedAt" fetched-at "config" base-config}))
      (db.io/atomic-move! tmp file)
      (catch Exception e
        (logger/warn logger-tag "Could not save the copy of" (display-url url) (ex-message e)))
      (finally
        (when (.exists tmp)
          (.delete tmp))))))

(defn ^:private read-copy
  "`url`'s last good copy saved on disk, as `{:config :fetched-at}`, or nil."
  [url]
  (let [file (cache-file url)]
    (when (fs/exists? file)
      (try
        (let [{saved-url "url" base-config "config" fetched-at "fetchedAt"} (json/parse-string (slurp file))]
          (when (and (= url saved-url) (map? base-config))
            {:config base-config :fetched-at fetched-at}))
        (catch Exception e
          (logger/warn logger-tag "Could not read the copy of" (display-url url) (ex-message e))
          nil)))))

(defn ^:private fetch
  "The config at `url`: a JSON object, comments allowed, with string keys.
   Throws when the request fails or the body isn't a JSON object."
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
    (let [base-config (try
                        (config/read-json-config body)
                        (catch Exception e
                          ;; the parser's first line, without where it was
                          (throw (ex-info (str "invalid JSON: " (first (string/split-lines (str (ex-message e)))))
                                          {}))))]
      (if (map? base-config)
        base-config
        (throw (ex-info "the response is not a JSON object" {}))))))

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
  (doseq [[url reason] (invalid-entries config)]
    (warn! messenger [:invalid url]
           (format "Skipping the baseConfigs entry %s: %s."
                   (if (string/blank? url) "without url" (display-url url))
                   reason))))

(defn ^:private sync-layers!
  "Points `config/base-config-layers*` at the copies of `sources`, in their
   order, clearing the config cache when that changes them."
  [sources]
  (let [layers (into [] (keep #(get-in @state* [(:url %) :config])) sources)
        [previous _] (reset-vals! config/base-config-layers* layers)]
    (when (not= previous layers)
      (config/clear-cache!))))

(defn load-cached!
  "Loads, from disk only, the last good copy of each base config `config`
   lists that has none loaded, so the session starts with them before any
   fetch. Returns the sources."
  [config]
  (let [sources (sources config)]
    (doseq [{:keys [url]} sources
            :when (nil? (get-in @state* [url :config]))
            :let [{copy :config :keys [fetched-at]} (read-copy url)]
            :when copy]
      (swap! state* update url merge {:config copy :fetched-at fetched-at :status :cached}))
    (sync-layers! sources)
    sources))

(defn ^:private fetched! [url base-config]
  (let [now (System/currentTimeMillis)]
    (when (contains? base-config "baseConfigs")
      (logger/warn logger-tag (display-url url) "lists baseConfigs, ignored: base configs don't chain."))
    (save-copy! url base-config now)
    (swap! state* assoc url {:config base-config :fetched-at now :status :fetched})
    (swap! warned* disj url)))

(defn ^:private fetch-failed! [messenger url ^Throwable e]
  (let [reason (error-reason e)
        {copy :config :keys [fetched-at]} (get (swap! state* update url
                                                      (fn [entry]
                                                        (assoc entry
                                                               :status (if (:config entry) :stale :failed)
                                                               :error reason)))
                                               url)]
    (warn! messenger url
           (if copy
             (format "Could not refresh the base config %s (%s), using its copy from %s."
                     (display-url url) reason (format-time fetched-at))
             (format "Could not load the base config %s (%s), ECA goes on without it: check your network or VPN connection."
                     (display-url url) reason)))))

(defn refresh!
  "Fetches the base configs `config` lists, in parallel. A fetched config
   becomes the loaded one and its copy on disk; a failed fetch keeps the
   loaded copy, if any, and warns the user once. Returns the sources."
  [config messenger]
  (let [sources (sources config)]
    (warn-invalid-entries! messenger config)
    (doseq [[{:keys [url]} {base-config :config error :error}]
            (->> sources
                 (mapv (fn [source]
                         (future [source (try
                                           {:config (fetch source)}
                                           (catch Exception e
                                             {:error e}))])))
                 (mapv deref))]
      (if base-config
        (fetched! url base-config)
        (fetch-failed! messenger url error)))
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
    (when (some #(nil? (get-in @state* [(:url %) :config])) (sources config))
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
        (for [{:keys [url]} sources
              :let [{:keys [status fetched-at error]} (get @state* url)]]
          (str "  " (display-url url) ": "
               (case status
                 :fetched (str "fetched at " (format-time fetched-at))
                 :cached (str "using its copy from " (format-time fetched-at) ", not fetched yet")
                 :stale (str "using its copy from " (format-time fetched-at) ", the last fetch failed: " error)
                 :failed (str "not loaded, the fetch failed: " error)
                 "not fetched yet")))
        (for [[url reason] invalid]
          (str "  " (if (string/blank? url) "an entry without url" (display-url url))
               ": skipped, " reason)))))))
