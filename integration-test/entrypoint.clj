(ns entrypoint
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell] :as p]
   [clojure.java.io :as io]
   [clojure.test :as t]
   [integration.eca :as eca]
   [integration.chat.hooks-test]
   [llm-mock.server :as llm-mock.server]))

(def namespaces
  '[
    ;; integration.initialize-test
    ;; integration.chat.hooks-test
    ;; integration.chat.openai-test
    ;; integration.chat.anthropic-test
    ;; integration.chat.github-copilot-test
    ;; integration.chat.google-test
    ;; integration.chat.ollama-test
    ;; integration.chat.custom-provider-test
    integration.chat.commands-test
    ;;integration.rewrite.openai-test
    ])

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

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn run-all
  "Run all tests in given namespaces NSES or all `namespaces` if not provided,
  using the specified BINARY eca server.

  Sets `eca/*eca-binary-path*` to BINARY and `eca/*eca-out-dir*` to
  `./integration-test/out`

  If PROXY is provided, sets `eca/*http-proxy*` to it and starts a
  transient Tinyproxy server.

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
    (when proxy
      (alter-var-root #'eca/*http-proxy* (constantly proxy-http))
      (println "Routing requests through proxy:" eca/*http-proxy*)
      (tinyproxy-start!))

    (println "Preparing mcp-server-sample")
    (shell {:out nil :dir "integration-test/mcp-server-sample"} "clojure -Stree")
    (llm-mock.server/start!)

    (let [timeout-minutes (if (re-find #"(?i)win|mac" (System/getProperty "os.name"))
                            10 ;; win and mac ci runs take longer
                            5)
          test-results (timeout (* timeout-minutes 60 1000)
                                #(with-log-tail-report
                                   (apply t/run-tests nses)))]

      (llm-mock.server/stop!)

      (when (= test-results :timed-out)
        (println)
        (println (format "Timeout after %d minutes running integration tests!" timeout-minutes))
        (System/exit 1))

      (let [{:keys [fail error]} test-results]
        (System/exit (+ fail error))))))
