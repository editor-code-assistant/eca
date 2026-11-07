(ns integration.eca
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.core.async :as async]
   [clojure.test :refer [use-fixtures]]
   [integration.client :as client]
   [llm-mock.mocks :as llm.mocks]))

(def ^:dynamic *eca-binary-path* nil)
(def ^:dynamic *eca-process* nil)
(def ^:dynamic *eca-out-dir*
  "Directory for transient files, including logs and runtime configurations."
  nil)
(def ^:dynamic *mock-client* nil)
(def ^:dynamic *http-proxy*
  "Current HTTP proxy URL for routing requests, if set."
  nil)
(def ^:dynamic *models-dev-url*
  "models.dev catalog URL servers fetch instead of the live one, if set."
  nil)
(def ^:dynamic *jvm-opts*
  "Extra options for the JVM of servers started from the JVM launcher."
  [])
(def ^:dynamic *exit-on-server-error?*
  "Whether a server exiting with an error aborts the whole run."
  true)

(defn ^:private clojure-user-config-dir
  "The Clojure CLI's user config dir, resolved the way the CLI does."
  []
  (or (not-empty (System/getenv "CLJ_CONFIG"))
      (some-> (not-empty (System/getenv "XDG_CONFIG_HOME")) (fs/path "clojure") str)
      (str (fs/path (fs/home) ".clojure"))))

(defn ^:private server-env
  "The runner's environment, isolated from the developer's own ECA config and
  pointing servers at the test doubles."
  []
  (cond-> (-> (into {} (System/getenv))
              ;; Config sources servers read at startup, before the
              ;; initializationOptions' pureConfig applies: their ${cmd:...}
              ;; values run (and can hang) and e.g. OTLP metrics turn on.
              (dissoc "ECA_CONFIG")
              (assoc "XDG_CONFIG_HOME" (str (fs/path *eca-out-dir* "config"))
                     ;; ...but keep child `clojure` processes (e.g. the sample
                     ;; MCP server) on the developer's Clojure config.
                     "CLJ_CONFIG" (clojure-user-config-dir)
                     "XDG_CACHE_HOME" (str (fs/path *eca-out-dir* "cache"))))
    *http-proxy* (assoc "HTTP_PROXY" *http-proxy*)
    *models-dev-url* (assoc "ECA_MODELS_DEV_URL" *models-dev-url*)))

(defn binary-path
  "Absolute path of the eca BINARY, found on PATH or in the current dir."
  [binary]
  (str (fs/absolutize (or (fs/which binary) (fs/which (str "./" binary))
                          (throw (Exception. (str "Cannot locate eca binary: " binary)))))))

(defn jvm-launcher?
  "Whether the binary at PATH is the JVM launcher `bb prod-cli` builds, a jar
  behind a script preamble, rather than a native image."
  [path]
  (try
    (with-open [_ (java.util.zip.ZipFile. (str path))] true)
    (catch Exception _ false)))

(def ^:dynamic *extra-env*
  "Extra environment variables merged into the spawned server process env,
   e.g. {\"XDG_DATA_HOME\" <tmp>} to isolate the memory root."
  {})

(defn start-server
  "Start the ECA server from the given BINARY.

If BINARY is `clojure`, invokes the server from source via the Clojure CLI.

If BINARY is the JVM launcher, runs its jar with `java` and `*jvm-opts*`.

Logs output to `*eca-out-dir*/server.stderr.txt`.

Runs it isolated from the developer's ECA config (see `server-env`), passing
`*http-proxy*` as `HTTP_PROXY` and `*models-dev-url*` as `ECA_MODELS_DEV_URL`
when set."
  [binary]
  (let [path (binary-path binary)
        server-args ["server" "--log-level" "debug"]
        cmd-full (cond
                   (= binary "clojure")
                   (concat [path "-M" "-m" "eca.main"] server-args)

                   (jvm-launcher? path)
                   (concat [(str (or (fs/which "java") "java")) "-server"
                            ;; JVM logs go to stdout by default, the JSON-RPC
                            ;; channel, e.g. when an AOT cache can't be used.
                            "-Xlog:disable" "-Xlog:all=warning:stderr"]
                           *jvm-opts*
                           ["-jar" path]
                           server-args)

                   :else
                   (concat [path] server-args))
        log-path (str (fs/path *eca-out-dir* "server.stderr.txt"))
        ;; read now: exit-fn runs on another thread, outside any binding
        exit-on-error? *exit-on-server-error?*]

    (println :--eca.integration.start-server/starting :cmd cmd-full :log-path log-path :http-proxy *http-proxy*)
    (p/process cmd-full
               {:err  log-path
                :env (merge (server-env) *extra-env*)
                :exit-fn (fn [{:keys [cmd exit]}]
                           (when (not= exit 0)
                             (println :--eca.integration.start-server/exited :cmd cmd :exit-status exit)
                             (try
                               (println :log (slurp log-path))
                               (catch Exception _e))
                             (when exit-on-error?
                               (System/exit exit))))})))

(defn start-process! []
  (let [server (start-server *eca-binary-path*)
        client (client/client (:in server) (:out server))]
    (client/start client nil)
    (async/go-loop []
      (when-let [log (async/<! (:log-ch client))]
        (println log)
        (recur)))
    (alter-var-root #'*eca-process* (constantly server))
    (alter-var-root #'*mock-client* (constantly client))))

(defn clean! []
  (flush)
  (some-> *mock-client* client/shutdown)
  (some-> *eca-process* deref) ;; wait for shutdown of client to shutdown server
  (alter-var-root #'*eca-process* (constantly nil))
  (alter-var-root #'*mock-client* (constantly nil))
  (llm.mocks/set-case! nil)
  (llm.mocks/clean-req-bodies!))

(defn clean-after-test []
  (use-fixtures :each (fn [f] (clean!) (f)))
  (use-fixtures :once (fn [f] (f) (clean!))))

(defn notify! [[method body]]
  (client/send-notification *mock-client* method body))

(defn request! [[method body]]
  (client/request-and-await-server-response! *mock-client* method body))

(defn client-awaits-server-notification [method]
  (client/await-server-notification *mock-client* method))

(defn client-awaits-server-request [method]
  (client/await-server-request *mock-client* method))

(defn mock-response [method resp]
  (client/mock-response *mock-client* method resp))
