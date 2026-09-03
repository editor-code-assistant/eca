(ns eca.test-helper
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [use-fixtures]]
   [eca.cache :as cache]
   [eca.config :as config]
   [eca.db :as db]
   [eca.messenger :as messenger]
   [eca.metrics :as metrics]
   [eca.shared :as shared]))

(def ^:private isolated-cache-dir
  "Tests use `user.dir` (this repo) as their workspace folder, so without
   isolation every test run writes chat caches into the developer's real
   ~/.cache/eca history for the eca workspace (#557). Redirect ECA's cache
   dir to a per-run temp dir for the whole test JVM."
  (io/file (str (fs/create-temp-dir {:prefix "eca-test-cache"}))))

(alter-var-root #'cache/global-dir (constantly (constantly isolated-cache-dir)))

(def windows? (string/starts-with? (System/getProperty "os.name") "Windows"))

(def windows-drive-letter
  (when windows?
    (second (re-find #"^([A-Za-z]).+" (System/getProperty "user.dir")))))

(defn file-path [path]
  (cond-> path windows?
          (-> (string/replace-first #"^/" (str windows-drive-letter ":\\\\"))
              (->> (re-matches #"(.+?)(\.jar:.*)?"))
              (update 1 string/replace "/" "\\")
              rest
              (->> (apply str)))))

(defn file-uri [uri]
  (cond-> uri windows?
          (string/replace #"^(file):///(?!\w:/)" (str "$1:///" windows-drive-letter ":/"))))

(defrecord TestMessenger [messages* diagnostics* ask-question-response* definition-response* references-response*]
  messenger/IMessenger
  (chat-content-received [_ data] (swap! messages* update :chat-content-received (fnil conj []) data))
  (chat-cleared [_ params] (swap! messages* update :chat-clear (fnil conj []) params))
  (chat-status-changed [_ params] (swap! messages* update :chat-status-changed (fnil conj []) params))
  (chat-deleted [_ params] (swap! messages* update :chat-deleted (fnil conj []) params))
  (chat-opened [_ params] (swap! messages* update :chat-opened (fnil conj []) params))
  (rewrite-content-received [_ data] (swap! messages* update :rewrite-content-received (fnil conj []) data))
  (config-updated [_ data] (swap! messages* update :config-updated (fnil conj []) data))
  (tool-server-updated [_ data] (swap! messages* update :tool-server-update (fnil conj []) data))
  (tool-server-removed [_ data] (swap! messages* update :tool-server-removed (fnil conj []) data))
  (provider-updated [_ data] (swap! messages* update :provider-updated (fnil conj []) data))
  (jobs-updated [_ data] (swap! messages* update :jobs-updated (fnil conj []) data))
  (showMessage [_ data] (swap! messages* update :show-message (fnil conj []) data))
  (progress [_ data] (swap! messages* update :progress (fnil conj []) data))
  (editor-diagnostics [_ _uri] (future {:diagnostics @diagnostics*}))
  (editor-definition [_ uri position]
    (let [v @definition-response*]
      (cond
        (= v :block) (promise)
        (fn? v) (future (v uri position))
        :else (future v))))
  (editor-references [_ uri position include-declaration]
    (let [v @references-response*]
      (cond
        (= v :block) (promise)
        (fn? v) (future (v uri position include-declaration))
        :else (future v))))
  (ask-question [_ _params]
    (let [v @ask-question-response*]
      (cond
        (instance? Throwable v) (throw v)
        (= v :block) (promise)
        :else (future v)))))

(defn ^:private make-components []
  {:db* (atom db/initial-db)
   :messenger (->TestMessenger (atom {}) (atom []) (atom nil) (atom nil) (atom nil))
   :metrics (metrics/->NoopMetrics)
   :config config/initial-config})

(def components* (atom (make-components)))
(defn components [] @components*)

(defn db* [] (:db* (components)))
(defn db [] (deref (db*)))

(defn messages [] @(:messages* (:messenger (components))))
(defn messenger [] (:messenger (components)))

(defn config [] (:config (components)))

(defn metrics [] (:metrics (components)))

(defn config! [config]
  (swap! components* update :config shared/deep-merge config))

(defn reset-components! []
  (reset! config/initialization-config* {})
  (reset! config/plugin-components* nil)
  (config/clear-cache!)
  (reset! components* (make-components))
  ;; Set default workspace folder for tests
  (swap! (db*) assoc :workspace-folders [{:uri (shared/filename->uri (System/getProperty "user.dir"))}]))

(defn reset-components-before-test []
  (use-fixtures :each (fn [f] (reset-components!) (f))))
(defn reset-messenger! [] (swap! components* assoc :messenger (:messenger (make-components))))
