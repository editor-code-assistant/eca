(ns eca.test-helper
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [use-fixtures]]
   [eca.cache :as cache]
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.memory :as memory]
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

(def ^:private isolated-data-home
  "Memory lives under XDG_DATA_HOME. Without isolation, a test that enables
   memory scans the developer's real ~/.local/share/eca/memory and writes its
   usage ledger. Redirect it to a per-run temp dir for the whole test JVM."
  (str (fs/create-temp-dir {:prefix "eca-test-data"})))

(alter-var-root #'config/get-env
                (fn [get-env]
                  (fn [env]
                    (if (= "XDG_DATA_HOME" env)
                      isolated-data-home
                      (get-env env)))))

(defn with-memory-root
  "Calls `f` with a fresh memory root (a canonical `java.io.File`) and deletes
   it afterwards. Canonical, because memory paths are compared in their
   resolved form and the temp dir can be a link or a short Windows name."
  [f]
  (let [root (fs/file (fs/canonicalize (fs/create-temp-dir {:prefix "eca-test-memory"})))]
    (try
      (with-redefs [memory/memory-root-dir (constantly root)]
        (f root))
      (finally
        (fs/delete-tree root)))))

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
  (chat-truncated [_ params] (swap! messages* update :chat-truncated (fnil conj []) params))
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
        :else (future v))))
  (memory-index-loaded [_ params]
    (swap! messages* update :memory-index-loaded (fnil conj []) params)))

(defn stub-messenger
  "An IMessenger for tests exercising only some methods: each method calls the
   fn under its name in `overrides` (e.g. :ask-question) with the method args,
   without `this`, and returns its result as-is. Other methods are no-ops."
  [overrides]
  (when-let [unknown (seq (remove (set (keys (:sigs messenger/IMessenger))) (keys overrides)))]
    (throw (ex-info "Unknown IMessenger methods" {:methods unknown})))
  (letfn [(call [k & args] (when-let [f (get overrides k)] (apply f args)))]
    (reify messenger/IMessenger
      (chat-content-received [_ data] (call :chat-content-received data))
      (chat-cleared [_ params] (call :chat-cleared params))
      (chat-truncated [_ params] (call :chat-truncated params))
      (chat-status-changed [_ params] (call :chat-status-changed params))
      (chat-deleted [_ params] (call :chat-deleted params))
      (chat-opened [_ params] (call :chat-opened params))
      (rewrite-content-received [_ data] (call :rewrite-content-received data))
      (tool-server-updated [_ params] (call :tool-server-updated params))
      (tool-server-removed [_ params] (call :tool-server-removed params))
      (config-updated [_ params] (call :config-updated params))
      (provider-updated [_ params] (call :provider-updated params))
      (jobs-updated [_ params] (call :jobs-updated params))
      (showMessage [_ msg] (call :showMessage msg))
      (progress [_ params] (call :progress params))
      (editor-diagnostics [_ uri] (call :editor-diagnostics uri))
      (editor-definition [_ uri position] (call :editor-definition uri position))
      (editor-references [_ uri position include-declaration]
        (call :editor-references uri position include-declaration))
      (memory-index-loaded [_ params] (call :memory-index-loaded params))
      (ask-question [_ params] (call :ask-question params)))))

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
