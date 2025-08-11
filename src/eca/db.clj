(ns eca.db
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [cognitect.transit :as transit]
   [eca.config :as config :refer [get-env get-property]]
   [eca.logger :as logger]
   [eca.shared :as shared])
  (:import
   [java.io OutputStream]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[DB]")

(def version 1)

(defonce initial-db
  {:client-info {}
   :workspace-folders []
   :client-capabilities {}
   :chats {}
   :chat-behaviors ["agent" "plan"]
   :chat-default-behavior "agent"
   :models {}
   :mcp-clients {}})

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

(defn ^:private transit-global-db-file [workspaces]
  (io/file (global-cache-dir) (workspaces-hash workspaces)  "db.transit.json"))

(defn ^:private read-cache [cache-file]
  (try
    (logger/logging-task
     :db/read-cache
     (if (fs/exists? cache-file)
       (let [cache (with-open [is (io/input-stream cache-file)]
                     (transit/read (transit/reader is :json)))]
         (when (= version (:version cache))
           cache))
       (logger/info logger-tag (str "No existing DB cache found for " cache-file))))
    (catch Throwable e
      (logger/error logger-tag "Could not load global cache from DB" e))))

(defn ^:private upsert-cache! [cache cache-file]
  (try
    (logger/logging-task
     :db/upsert-cache
     (io/make-parents cache-file)
      ;; https://github.com/cognitect/transit-clj/issues/43
     (with-open [os ^OutputStream (no-flush-output-stream (io/output-stream cache-file))]
       (let [writer (transit/writer os :json)]
         (transit/write writer cache))))
    (catch Throwable e
      (logger/error logger-tag (str "Could not upsert db cache to " cache-file) e))))

(defn ^:private read-workspaces-cache [workspaces]
  (let [cache (read-cache (transit-global-db-file workspaces))]
    (when (= version (:version cache))
      cache)))

(defn load-db-from-cache! [db*]
  (when-let [global-cache (read-workspaces-cache (:workspace-folders @db*))]
    (swap! db* (fn [state-db]
                 (merge state-db
                        (select-keys global-cache [:chats]))))))

(defn ^:private normalize-db-for-write [db]
  (-> (select-keys db [:chats])
      (update :chats (fn [chats]
                       (into {}
                             (map (fn [[k v]]
                                    [k (dissoc v :tool-calls)]))
                             chats)))))

(defn update-workspaces-cache! [db]
  (-> (normalize-db-for-write db)
      (assoc :version version)
      (upsert-cache! (transit-global-db-file (:workspace-folders db)))))
