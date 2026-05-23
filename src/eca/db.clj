(ns eca.db
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [cognitect.transit :as transit]
   [eca.cache :as cache]
   [eca.logger :as logger]
   [eca.metrics :as metrics]
   [eca.shared :as shared])
  (:import
   [java.io OutputStream RandomAccessFile]
   [java.nio.channels FileChannel FileLock]
   [java.nio.file AtomicMoveNotSupportedException CopyOption Files StandardCopyOption]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[DB]")

(def version 6)

(def ^:private _db-spec
  "Used for documentation only"
  {:client-info {:name :string
                 :version :string}
   :workspace-folders [{:name :string :uri :string}]
   :client-capabilities {:code-assistant {:editor {:diagnostics :boolean}
                                          :chat-capabilities {:ask-question :boolean}}}
   :config-hash :string
   :providers-config-hash :string
   :last-config-notified ::any-map
   :stopping :boolean
   :models {"<model-name>" {:web-search :boolean
                            :tools :boolean
                            :reason? :boolean
                            :image-input? :boolean
                            :max-output-tokens :number
                            :model-name :string ;; real model name used for requests
                            :limit {:context :number :output :number}
                            :input-token-cost (or :number nil)
                            :output-token-cost (or :number nil)
                            :input-cache-read-token-cost (or :number nil)
                            :input-cache-creation-token-cost (or :number nil)}}
   :mcp-clients {"<client-id>" {:client :McpSyncClient
                                :status (or :requires-auth :starting :running :failed :stopping :stopped)
                                :version :string
                                :tools [{:name :string
                                         :description :string
                                         :parameters ::any-map}]
                                :prompts [{:name :string
                                           :description :string
                                           :arguments [{:name :string
                                                        :description :string
                                                        :required :boolean}]}]
                                :resources [{:uri :string
                                             :name :string
                                             :description :string
                                             :mime-type :string}]}}
   :chats {"<chat-id>" {:id :string
                        :title (or :string nil)
                        :title-custom? :boolean ;; user manually renamed the chat
                        :status (or :idle :running :stopping :login)
                        :created-at :number
                        :updated-at :number
                        :login-provider :string
                        :model :string ;; last full model id used for this chat, e.g. "anthropic/claude-sonnet-4-6"
                        :last-api :keyword
                        :trust :boolean
                        :prompt-id :uuid
                        :user-prompt-count :number
                        :subagent :boolean
                        :parent-chat-id :string
                        :startup-context :string
                        :prompt-cache ::any-map
                        :messages [{:role (or "user" "assistant" "tool_call" "tool_call_output" "reason" "compact_marker" "flag" "server_tool_use" "server_tool_result")
                                    :content (or :string [::any-map]) ;; string for simple text, map/vector for structured content
                                    :content-id :string}]
                        :task {:next-id :number
                               :active-summary (or :string nil)
                               :tasks [{:id :number
                                        :subject :string
                                        :description :string
                                        :status (or :pending :in-progress :done)
                                        :priority (or :high :medium :low)
                                        :blocked-by #{:number}}]}
                        :tool-calls {"<tool-call-id>"
                                     {:status (or :initial :preparing :check-approval :waiting-approval
                                                  :execution-approved :executing :rejected :cleanup
                                                  :completed :stopping)

                                      :name :string
                                      :full-name :string
                                      :server :string
                                      :origin (or :native :mcp)
                                      :arguments ::any-map
                                      :decision-reason {:code :keyword :text :string}
                                      :approved?* :promise
                                      :future-cleanup-complete?* :promise
                                      :start-time :long
                                      :future :future
                                      :resources ::any-map
                                      :rollback-changes [{:path :string
                                                          :content (or :string nil)}]}}}}
   :auth {"<provider-name>" {:step (or :login/start :login/waiting-login-method
                                       :login/waiting-provider-code :login/waiting-api-key
                                       :login/waiting-user-confirmation :login/done :login/renew-token)
                             :type (or :auth/token :auth/oauth nil)
                             :mode (or :manual :console :max nil)
                             :api-key :string
                             :access-token :string
                             :refresh-token :string
                             :expires-at :long
                             :verifier :string
                             :device-code :string}}
   :mcp-auth {"<mcp-server-name>" {:type :auth/oauth
                                   :access-token :string
                                   :refresh-token :string
                                   :expires-at :long}}})

(defonce initial-db
  {:client-info {}
   :workspace-folders []
   :client-capabilities {}
   :config-hash nil
   :providers-config-hash nil
   :last-config-notified {}
   :stopping false
   :models {}
   :mcp-clients {}

   ;; cacheable, bump db `version` when changing any below
   :chats {}
   :auth {"anthropic" {}
          "azure" {}
          "deepseek" {}
          "github-copilot" {}
          "google" {}
          "litellm" {}
          "lmstudio" {}
          "mistral" {}
          "moonshot" {}
          "openai" {}
          "openrouter" {}
          "z-ai" {}}
   :mcp-auth {}})

(defonce db* (atom initial-db))

(defn ^:private no-flush-output-stream [^OutputStream os]
  (proxy [java.io.BufferedOutputStream] [os]
    (flush [])
    (close []
      (let [^java.io.BufferedOutputStream this this]
        (proxy-super flush)
        (proxy-super close)))))

(defn ^:private transit-global-db-file []
  (io/file (cache/global-dir) "db.transit.json"))

(defn ^:private transit-global-by-workspaces-db-file [workspaces]
  (cache/workspace-cache-file workspaces "db.transit.json" shared/uri->filename))

(defn ^:private read-cache [cache-file metrics]
  (try
    (metrics/task metrics :db/read-cache
      (if (fs/exists? cache-file)
        (let [cache (with-open [is (io/input-stream cache-file)]
                      (transit/read (transit/reader is :json)))]
          (when (= version (:version cache))
            cache))
        (logger/info logger-tag (str "No existing DB cache found for " cache-file))))
    (catch Throwable e
      (logger/error logger-tag "Could not load global cache from DB" e))))

(defn ^:private atomic-move!
  "Rename `src` to `dest`. Tries an atomic move first so a crash mid-rename
   cannot leave the destination half-written; falls back to a non-atomic
   replace on filesystems that do not support ATOMIC_MOVE."
  [^java.io.File src ^java.io.File dest]
  (try
    (Files/move (.toPath src)
                (.toPath dest)
                (into-array CopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    (catch AtomicMoveNotSupportedException _
      (Files/move (.toPath src)
                  (.toPath dest)
                  (into-array CopyOption
                              [StandardCopyOption/REPLACE_EXISTING])))))

(defn ^:private upsert-cache!
  "Persist `cache` to `cache-file` durably.

   The payload is written to a sibling `<file>.tmp` first and then renamed
   atomically over the destination. If the JVM/process dies mid-write the
   original file stays intact, so we never trade a corrupted cache for a
   more frequent save cadence."
  [cache cache-file metrics]
  (try
    (metrics/task metrics :db/upsert-cache
      (io/make-parents cache-file)
      (let [dest ^java.io.File cache-file
            tmp  ^java.io.File (io/file (str (.getPath dest) ".tmp"))]
        (try
          ;; https://github.com/cognitect/transit-clj/issues/43
          (with-open [os ^OutputStream (no-flush-output-stream (io/output-stream tmp))]
            (let [writer (transit/writer os :json)]
              (transit/write writer cache)))
          (atomic-move! tmp dest)
          (finally
            (when (.exists tmp)
              (.delete tmp))))))
    (catch Throwable e
      (logger/error logger-tag (str "Could not upsert db cache to " cache-file) e))))

(defn ^:private read-global-cache [metrics]
  (let [cache (read-cache (transit-global-db-file) metrics)]
    (when (= version (:version cache))
      cache)))

(defn ^:private read-global-by-workspaces-cache [workspaces metrics]
  (let [cache (read-cache (transit-global-by-workspaces-db-file workspaces) metrics)]
    (when (= version (:version cache))
      cache)))

(defn load-db-from-cache! [db* config metrics]
  (when-not (:pureConfig config)
    (when-let [global-cache (read-global-cache metrics)]
      (logger/info logger-tag "Loading from global-cache caches...")
      (swap! db* shared/deep-merge global-cache))
    (when-let [global-by-workspace-cache (read-global-by-workspaces-cache (:workspace-folders @db*) metrics)]
      (logger/info logger-tag "Loading from workspace-cache caches...")
      (swap! db* shared/deep-merge global-by-workspace-cache))))

(defn ^:private normalize-db-for-workspace-write [db]
  (-> (select-keys db [:chats])
      (update :chats (fn [chats]
                       (into {}
                             ;; Persist every chat that lives in memory.
                             ;; We used to drop chats with empty :messages
                             ;; here, but that erased chats that were
                             ;; intentionally rolled back to empty and also
                             ;; (combined with the late add-to-history!
                             ;; behaviour) erased chats that hit a provider
                             ;; error before any token arrived. Cleanup of
                             ;; stale chats is handled by
                             ;; cleanup-old-chats! instead.
                             (map (fn [[k v]]
                                    [k (dissoc v :tool-calls)]))
                             chats)))))

(defn ^:private normalize-db-for-global-write [db]
  (select-keys db [:auth :mcp-auth]))

(defn update-workspaces-cache! [db metrics]
  (-> (normalize-db-for-workspace-write db)
      (assoc :version version)
      (upsert-cache! (transit-global-by-workspaces-db-file (or (:initial-workspace-folders db)
                                                               (:workspace-folders db))) metrics)))

(defn update-global-cache! [db metrics]
  (-> (normalize-db-for-global-write db)
      (assoc :version version)
      (upsert-cache! (transit-global-db-file) metrics)))

(def ^:private global-cache-lock-sentinel (Object.))

(defn ^:private global-cache-lock-file []
  (io/file (cache/global-dir) "db.transit.json.lock"))

(defn with-global-cache-lock-fn
  "Run `f` while holding both a JVM-wide mutex and an OS advisory exclusive
   lock on a sidecar of the global cache file. The JVM mutex avoids
   `OverlappingFileLockException` when two threads in the same ECA server
   race a renew; the file lock serializes across `eca server` processes
   that share `~/.cache/eca/`. Blocks until both are acquired."
  [f]
  (locking global-cache-lock-sentinel
    (let [^java.io.File lock-file (global-cache-lock-file)
          _ (io/make-parents lock-file)
          ^RandomAccessFile raf (RandomAccessFile. lock-file "rw")
          ^FileChannel channel (.getChannel raf)
          lock-ref (volatile! nil)]
      (try
        (vreset! lock-ref ^FileLock (.lock channel))
        (f)
        (finally
          (when-let [^FileLock lock @lock-ref]
            (try (.release lock)
                 (catch Throwable e
                   (logger/warn logger-tag "Could not release global cache lock" e))))
          (try (.close channel) (catch Throwable _))
          (try (.close raf) (catch Throwable _)))))))

(defmacro with-global-cache-lock
  "See `with-global-cache-lock-fn`. Runs `body` while holding the lock."
  [& body]
  `(with-global-cache-lock-fn (fn [] ~@body)))

(defn sync-auth-from-cache!
  "Re-read the global cache from disk and, if its `:auth` entry for `provider`
   has a different `:expires-at` than the in-memory copy, overwrite the
   in-memory `[:auth provider]` with the disk version. This lets a process
   that lost a token-refresh race adopt the winner's freshly rotated tokens
   instead of POSTing with a stale refresh token.

   Returns a truthy value when in-memory state was updated."
  [db* provider metrics]
  (try
    (when-let [disk-auth (some-> (read-global-cache metrics) :auth (get provider))]
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

(defn cleanup-old-chats!
  "Deletes chats older than retention-days from the db and flushes the workspace cache.
   When retention-days is non-positive, cleanup is disabled."
  [db* metrics retention-days]
  (when (pos? retention-days)
    (let [retention-ms (* retention-days 24 60 60 1000)
          cutoff (- (System/currentTimeMillis) retention-ms)
          removed (atom 0)]
      (swap! db* update :chats
             (fn [chats]
               (into {}
                     (filter (fn [[_id chat]]
                               (let [created-at (:created-at chat)]
                                 (if (and created-at (< created-at cutoff))
                                   (do (swap! removed inc) false)
                                   true))))
                     chats)))
      (when (pos? @removed)
        (logger/info logger-tag (str "Cleaned up " @removed " chat(s) older than " retention-days " days"))
        (update-workspaces-cache! @db* metrics)))))
