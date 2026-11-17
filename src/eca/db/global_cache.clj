(ns eca.db.global-cache
  "The global cache, ~/.cache/eca/db.transit.json: the logins (auth and
   mcp-auth) all ECA processes share, under a lock."
  (:require
   [clojure.java.io :as io]
   [eca.cache :as cache]
   [eca.db.io :as db.io]
   [eca.logger :as logger]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[DB]")

(defn ^:private normalize-db-for-global-write [db]
  (select-keys db [:auth :mcp-auth]))

(defn update-global-cache! [db metrics]
  (-> (normalize-db-for-global-write db)
      (assoc :version db.io/version)
      (db.io/upsert-cache! (db.io/transit-global-db-file) metrics)))

(defn ^:private global-cache-lock-file []
  (io/file (cache/global-dir) "db.transit.json.lock"))

(def ^:private ^ThreadLocal global-cache-lock-held
  "Marks the thread currently holding the global cache lock, making the lock
   reentrant: taking the OS file lock twice in the same JVM throws
   `OverlappingFileLockException`."
  (ThreadLocal.))

(defn with-global-cache-lock-fn
  "Run `f` while holding both a JVM-wide mutex and an OS advisory exclusive
   lock on a sidecar of the global cache file. The JVM mutex avoids
   `OverlappingFileLockException` when two threads in the same ECA server
   race a renew; the file lock serializes across `eca server` processes
   that share `~/.cache/eca/`. Blocks until both are acquired. Reentrant for
   the thread already holding it."
  [f]
  (if (.get global-cache-lock-held)
    (f)
    (db.io/with-os-file-lock-fn
      (global-cache-lock-file)
      (fn []
        (.set global-cache-lock-held true)
        (try
          (f)
          (finally
            (.remove global-cache-lock-held)))))))

(defmacro with-global-cache-lock
  "See `with-global-cache-lock-fn`. Runs `body` while holding the lock."
  [& body]
  `(with-global-cache-lock-fn (fn [] ~@body)))

(defn update-global-auth-cache!
  "Persists only `provider`'s auth entry to the global cache, keeping every
   other entry as currently on disk, so ECA processes using different
   providers don't overwrite each other's freshly rotated tokens. Falls back
   to a whole write when the disk cache can't be read."
  [db provider metrics]
  (with-global-cache-lock
    (if-let [disk-cache (db.io/read-global-cache metrics)]
      (db.io/upsert-cache! (if-let [provider-auth (get-in db [:auth provider])]
                             (assoc-in disk-cache [:auth provider] provider-auth)
                             (update disk-cache :auth dissoc provider))
                           (db.io/transit-global-db-file)
                           metrics)
      (update-global-cache! db metrics))))

(defn sync-auth-from-cache!
  "Re-read the global cache from disk and, if its `:auth` entry for `provider`
   has a different `:expires-at` than the in-memory copy, overwrite the
   in-memory `[:auth provider]` with the disk version. This lets a process
   that lost a token-refresh race adopt the winner's freshly rotated tokens
   instead of POSTing with a stale refresh token.

   Returns a truthy value when in-memory state was updated."
  [db* provider metrics]
  (try
    (when-let [disk-auth (some-> (db.io/read-global-cache metrics) :auth (get provider))]
      (let [in-mem-auth (get-in @db* [:auth provider])]
        (when (and (:expires-at disk-auth)
                   (not= (:expires-at disk-auth) (:expires-at in-mem-auth)))
          (logger/info logger-tag
                       (format "Adopting %s auth tokens refreshed by peer process (expires-at %s)"
                               provider (:expires-at disk-auth)))
          (swap! db* assoc-in [:auth provider] disk-auth)
          true)))
    (catch Throwable e
      (logger/warn logger-tag "Could not sync auth from cache" e)
      false)))
