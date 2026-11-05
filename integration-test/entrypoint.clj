(ns entrypoint
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell] :as p]
   [clojure.java.io :as io]
   [clojure.test :as t]
   [integration.eca :as eca]
   [integration.chat.hooks-test]
   [integration.fixture :as fixture]
   [llm-mock.server :as llm-mock.server]
   [mcp-mock.server :as mcp-mock.server]))

(def namespaces
  '[integration.initialize-test
    integration.chat.openai-test
    integration.chat.anthropic-test
    integration.chat.github-copilot-test
    integration.chat.google-test
    integration.chat.ollama-test
    integration.chat.custom-provider-test
    integration.chat.hooks-test
    integration.chat.commands-test
    integration.chat.mcp-remote-test
    integration.chat.editor-lsp-test
    integration.chat.invalid-image-test
    integration.chat.background-jobs-test
    integration.chat.subagent-test
    integration.chat.list-test
    integration.chat.rollback-test
    integration.chat.inline-prompt-test
    integration.rewrite.openai-test])

(defn timeout [timeout-ms callback]
  (let [fut (future (callback))
        ret (deref fut timeout-ms :timed-out)]
    (when (= ret :timed-out)
      (future-cancel fut))
    ret))

(declare ^:dynamic original-report)

(defn log-tail-report [data]
  (original-report data)
  (when (contains? #{:fail :error} (:type data))
    (println "Integration tests failed!")))

(defmacro with-log-tail-report
  "Execute body with modified test reporting functions that prints log tail on failure."
  [& body]
  `(binding [original-report t/report
             t/report log-tail-report]
     ~@body))

(def tinyproxy-dir-env-var
  "Env var name pointing to the directory of the Tinyproxy executable."
  "ECA_TINYPROXY_DIR")
(def proxy-conf
  "Default Tinyproxy host, port, and credentials."
  {:host "127.0.0.1" :port 8864 :user "tiny" :pass "pass"})
(def tinyproxy-conf
  "Tinyproxy configuration content."
  (format "Listen %s
Port %d
Timeout 600
Allow 127.0.0.1
BasicAuth %s %s
LogLevel Info" (:host proxy-conf) (:port proxy-conf) (:user proxy-conf) (:pass proxy-conf)))
(def proxy-http
  "Proxy URL with credentials."
  (format "http://%s:%s@%s:%s"
          (:user proxy-conf) (:pass proxy-conf) (:host proxy-conf) (:port proxy-conf)))

(defn tinyproxy-start!
  "Start a transient Tinyproxy process using the `tinyrpoxy-conf`; looks
  for the executable `tinyproxy` in `tinyproxy-dir-env-var` or system PATH.

  Writes `tinyproxy.conf` and `tinyproxy.log` in `eca/*eca-out-dir*`."
  []

  (let [tp-str "tinyproxy"
        tp (or (System/getenv tinyproxy-dir-env-var) (fs/which tp-str))]
    (if-not tp
      (throw (ex-info "No tinyproxy executables found." {:searched-for tp-str
                                                         (keyword tinyproxy-dir-env-var) (System/getenv tinyproxy-dir-env-var)}))
      (let [out-conf (str (fs/path eca/*eca-out-dir* "tinyproxy.conf"))
            out-log  (str (fs/path eca/*eca-out-dir* "tinyproxy.log"))
            cmd-full [(str tp) "-d" "-c" out-conf]]
        (spit out-conf tinyproxy-conf)
        (println :--entrypoint.tinyproxy-server/starting :cmd cmd-full :log-path out-log)
        (println :---tinyproxy :conf out-conf)
        (println (slurp out-conf))
        (println :---tinyrpoxy :end)
        (p/process cmd-full
                   {:out (io/file out-log)
                    :exit-fn
                    (fn [{:keys [cmd exit]}]
                      (when (not= exit 0)
                        (println :--entrypoint.tinyproxy-server/exited :cmd cmd :exit-status exit)
                        (try
                          (println :log (slurp out-log))
                          (catch Exception _e))
                        (System/exit exit)))
                    :shutdown p/destroy-tree
                    :err :out})))))

(defn ^:private java-major-version
  "Major version of the `java` on PATH, or nil."
  []
  (try
    (let [{:keys [out err]} (shell {:out :string :err :string :continue true} "java" "-version")]
      (some->> (str err out) (re-find #"version \"(\d+)") second parse-long))
    (catch Exception _ nil)))

(defn ^:private train-jvm-aot-cache!
  "Records a JVM AOT cache (JDK 25+) from one server session, so the servers
  tests start load and link most classes from it, cutting their startup by
  ~40%. Best-effort: returns the cache path, or nil when training failed and
  tests should just run without it."
  []
  (let [cache (str (fs/path eca/*eca-out-dir* "eca.aot"))
        session #(binding [eca/*jvm-opts* [(str "-XX:AOTCacheOutput=" cache)]
                           eca/*exit-on-server-error?* false]
                   (eca/start-process!)
                   (eca/request! (fixture/initialize-request))
                   (eca/notify! (fixture/initialized-notification))
                   (eca/client-awaits-server-notification :config/updated)
                   (eca/client-awaits-server-notification :tool/serverUpdated)
                   (eca/request! (fixture/shutdown-request))
                   (eca/notify! (fixture/exit-notification))
                   ;; the JVM writes the cache while exiting
                   (eca/clean!))
        fail! (fn [reason]
                (println "JVM AOT cache training failed, running without it:" reason)
                (some-> eca/*eca-process* p/destroy-tree)
                (alter-var-root #'eca/*eca-process* (constantly nil))
                (alter-var-root #'eca/*mock-client* (constantly nil))
                (try (fs/delete-if-exists cache) (catch Exception _))
                nil)]
    (try
      (fs/delete-if-exists cache)
      (println "Training JVM AOT cache:" cache)
      (let [result (timeout (* 2 60 1000) session)]
        (cond
          (= :timed-out result) (fail! "timed out")
          (not (fs/exists? cache)) (fail! "no cache written")
          :else (do (println (format "JVM AOT cache ready: %s (%d MB)" cache (quot (fs/size cache) 1048576)))
                    cache)))
      (catch Exception e
        (fail! (str e))))))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn run-all
  "Run all tests in given namespaces NSES or all `namespaces` if not provided,
  using the specified BINARY eca server.

  Sets `eca/*eca-binary-path*` to BINARY, `eca/*eca-out-dir*` to
  `./integration-test/out` and `eca/*models-dev-url*` to the LLM mock's
  models.dev fixture, keeping servers off the network.

  If PROXY is provided, sets `eca/*http-proxy*` to it and starts a
  transient Tinyproxy server.

  If BINARY is the JVM launcher and `java` is 25+, first trains a JVM AOT
  cache all servers start from (see `train-jvm-aot-cache!`).

  Runs tests with a timeout, exiting with the sum of failures and
  errors, if any."

  [binary {:keys [nses proxy]}]
  (let [nses (or nses namespaces)]

    (doseq [namespace nses]
      (println :entrypoint.run-all/ns-loading namespace)
      (require namespace))
    (alter-var-root #'eca/*eca-binary-path* (constantly binary))
    (let [out-dir (str (fs/absolutize (fs/path "integration-test" "out")))]
      (fs/create-dirs out-dir)
      (alter-var-root #'eca/*eca-out-dir* (constantly out-dir)))
    (alter-var-root #'eca/*models-dev-url* (constantly llm-mock.server/models-dev-url))
    (when proxy
      (alter-var-root #'eca/*http-proxy* (constantly proxy-http))
      (println "Routing requests through proxy:" eca/*http-proxy*)
      (tinyproxy-start!))

    (println "Preparing mcp-server-sample")
    (shell {:out nil :dir "integration-test/mcp-server-sample"} "clojure -Stree")
    (llm-mock.server/start!)
    (mcp-mock.server/start!)

    (when (and (eca/jvm-launcher? (eca/binary-path binary))
               (some-> (java-major-version) (>= 25)))
      (when-let [cache (train-jvm-aot-cache!)]
        (alter-var-root #'eca/*jvm-opts* (constantly [(str "-XX:AOTCache=" cache)]))))

    ;; Safety net against hung tests, not a perf budget: keep ~2x headroom over
    ;; the slowest CI runners (JVM suite takes ~4m45s on slow Linux runners and
    ;; up to ~7m on Windows), otherwise healthy runs fail with a timeout.
    (let [timeout-minutes (if (re-find #"(?i)win|mac" (System/getProperty "os.name"))
                            15 ;; win and mac ci runs take longer
                            10)
          test-results (timeout (* timeout-minutes 60 1000)
                                #(with-log-tail-report
                                   (apply t/run-tests nses)))]

      (llm-mock.server/stop!)
      (mcp-mock.server/stop!)

      (when (= test-results :timed-out)
        (println)
        (println (format "Timeout after %d minutes running integration tests!" timeout-minutes))
        (System/exit 1))

      (let [{:keys [fail error]} test-results]
        (System/exit (+ fail error))))))
