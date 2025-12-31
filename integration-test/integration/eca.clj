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

(defn start-server
  "Start the ECA server from the given BINARY.

If BINARY is `clojure`, invokes the server from source via the Clojure CLI.

Logs output to `*eca-out-dir*/server.stderr.txt`.

If `*http-proxy*` is set, passes it as the `HTTP_PROXY` environment variable."
  [binary]
  (let [binary-path (str (fs/absolutize (or (fs/which binary) (fs/which (str "./" binary))
                                            (throw (Exception. (str "Cannot locate eca binary: " binary))))))
        args-extra (when (= binary "clojure")
                     ["-M" "-m" "eca.main"])
        cmd-full (concat [binary-path] args-extra ["server" "--log-level" "debug"])
        log-path (str (fs/path *eca-out-dir* "server.stderr.txt"))]

    (println :--eca.integration.start-server/starting :cmd cmd-full :log-path log-path :http-proxy *http-proxy*)
    (p/process cmd-full
               (cond-> {:err  log-path
                        :exit-fn (fn [{:keys [cmd exit]}]
                                   (when (not= exit 0)
                                     (println :--eca.integration.start-server/exited :cmd cmd :exit-status exit)
                                     (try
                                       (println :log (slurp log-path))
                                       (catch Exception _e))
                                     (System/exit exit)))}
                 *http-proxy*
                 (assoc :extra-env {"HTTP_PROXY" *http-proxy*})))))

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
