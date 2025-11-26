(ns eca.db
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [cognitect.transit :as transit]
   [eca.config :as config :refer [get-env get-property]]
   [eca.logger :as logger]
   [eca.metrics :as metrics]
   [eca.shared :as shared])
  (:import
   [java.io OutputStream]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[DB]")

(def version 5)

(def ^:private _db-spec
  "Used for documentation only"
  {:client-info {:name :string
                 :version :string}
   :workspace-folders [{:name :string :uri :string}]
   :client-capabilities {:code-assistant {:editor {:diagnostics :boolean}}}
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
                                :status (or :starting :running :failed :stopping :stopped)
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
                        :status (or :idle :running :stopping :login)
                        :created-at :number
                        :login-provider :string
                        :messages [::chat-message]
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
                                      :resources :map}}}}
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
                             :device-code :string}}})

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
          "openai" {}
          "openrouter" {}
          "z-ai" {}}})

(defonce db* (atom initial-db))

(defn ^:private no-flush-output-stream [^OutputStream os]
  (proxy [java.io.BufferedOutputStream] [os]
    (flush [])
    (close []
      (let [^java.io.BufferedOutputStream this this]
        (proxy-super flush)
        (proxy-super close)))))

(defn ^:private global-cache-dir []
  (let [cache-home (or (get-env "XDG_CACHE_HOME")
                       (io/file (get-property "user.home") ".cache"))]
    (io/file cache-home "eca")))

(defn ^:private workspaces-hash
  "Return an 8-char base64 (URL-safe, no padding) key for the given
   workspace set."
  [workspaces]
  (let [paths (->> workspaces
                   (map #(str (fs/absolutize (fs/file (shared/uri->filename (:uri %))))))
                   (distinct)
                   (sort))
        joined (string/join ":" paths)
        md (java.security.MessageDigest/getInstance "SHA-256")
        digest (.digest (doto md (.update (.getBytes joined "UTF-8"))))
        encoder (-> (java.util.Base64/getUrlEncoder)
                    (.withoutPadding))
        key (.encodeToString encoder digest)]
    (subs key 0 (min 8 (count key)))))

(defn ^:private transit-global-by-workspaces-db-file [workspaces]
  (io/file (global-cache-dir) (workspaces-hash workspaces)  "db.transit.json"))

(defn ^:private transit-global-db-file []
  (io/file (global-cache-dir) "db.transit.json"))

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

(defn ^:private upsert-cache! [cache cache-file metrics]
  (try
    (metrics/task metrics :db/upsert-cache
      (io/make-parents cache-file)
      ;; https://github.com/cognitect/transit-clj/issues/43
      (with-open [os ^OutputStream (no-flush-output-stream (io/output-stream cache-file))]
        (let [writer (transit/writer os :json)]
          (transit/write writer cache))))
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
                             (comp
                              (filter #(seq (:messages (second %))))
                              (map (fn [[k v]]
                                     [k (dissoc v :tool-calls)])))
                             chats)))))

(defn ^:private normalize-db-for-global-write [db]
  (select-keys db [:auth]))

(defn update-workspaces-cache! [db metrics]
  (-> (normalize-db-for-workspace-write db)
      (assoc :version version)
      (upsert-cache! (transit-global-by-workspaces-db-file (:workspace-folders db)) metrics)))

(defn update-global-cache! [db metrics]
  (-> (normalize-db-for-global-write db)
      (assoc :version version)
      (upsert-cache! (transit-global-db-file) metrics)))
