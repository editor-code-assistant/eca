(ns eca.modules.repl
  "The `:eca/repl` module: an nREPL server on the SCI context external
   modules run in, to use `eca.api` live, develop modules and read
   `eca.api.debug`. Off by default: `eca server --repl`, `/modules enable
   eca/repl` or `modules.eca/repl.enabled` turn it on. nREPL has no
   authentication, so it listens on a loopback address only."
  (:require
   [babashka.fs :as fs]
   [babashka.nrepl.impl.server :as nrepl.server]
   [babashka.nrepl.server.middleware :as nrepl.middleware]
   [clojure.string :as string]
   [eca.api.debug :as debug]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.modules :as modules]
   [eca.modules.data :as modules.data]
   [eca.modules.events :as events]
   [eca.modules.loader :as loader]
   [sci.core :as sci])
  (:import
   [java.io BufferedOutputStream Closeable PrintWriter PushbackInputStream Writer]
   [java.net InetAddress ServerSocket Socket]
   [java.util.concurrent LinkedBlockingQueue]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[REPL]")

(def ^:private default-host "127.0.0.1")

(defn ^:private close-quietly! [^Closeable c]
  (try
    (.close c)
    (catch Exception _ nil)))

(defn ^:private start-thread! [thread-name f]
  (doto (Thread. ^Runnable f ^String thread-name)
    (.setDaemon true)
    (.start)))

(def ^:private default-print-length
  "Values print at most this many items of a collection, so an infinite one,
   like `(range)`, doesn't take ECA down; `(set! *print-length* n)` changes
   it for the next evaluations."
  1000)

(def ^:private default-print-level 20)

(def ^:private trace-queue-size 1000)

(defn ^:private untrace-client! [traced*]
  (doseq [[chat-id tracer] @traced*]
    (events/untrace! chat-id tracer))
  (reset! traced* {}))

(defn ^:private start-trace-sender!
  "A thread sending a client the trace lines `eca.api.debug/trace!` queues,
   so a chat never waits on the client's socket: `{:send! :stop!}`. `send!`
   drops lines when the client can't keep up. When the client goes away,
   its traces stop."
  [traced*]
  (let [queue (LinkedBlockingQueue. (int trace-queue-size))
        thread (start-thread! "eca-repl-trace"
                              (fn []
                                (try
                                  (loop []
                                    (let [[^Writer writer ^String line] (.take queue)]
                                      (.write writer line)
                                      (.flush writer)
                                      ;; a PrintWriter swallows the socket's errors
                                      (if (and (instance? PrintWriter writer) (.checkError ^PrintWriter writer))
                                        (untrace-client! traced*)
                                        (recur))))
                                  (catch InterruptedException _ nil)
                                  (catch Exception e
                                    (logger/debug logger-tag "Stopped sending trace lines:" e)
                                    (untrace-client! traced*)))))]
    {:send! (fn [writer line] (.offer queue [writer line]))
     :stop! (fn [] (.interrupt ^Thread thread))}))

(defn ^:private serve-client!
  "Answers a client's nREPL messages until it disconnects, as babashka.nrepl's
   own server does, in a new `user` namespace. Its replies and the trace
   lines for it are sent one at a time."
  [sci-ctx ^Socket socket]
  (let [in (PushbackInputStream. (.getInputStream socket))
        out (BufferedOutputStream. (.getOutputStream socket))
        opts {:quiet true :xform nrepl.middleware/default-xform}
        lock (Object.)
        send (fn [os response]
               (locking lock
                 (nrepl.server/send-reduce os response)))
        rf ((:xform opts) send)
        ;; babashka.nrepl prints values with Clojure's printer: give it the
        ;; REPL's print limits, with defaults
        rf (fn
             ([result] (rf result))
             ([result input]
              (binding [*print-length* (or @sci/print-length default-print-length)
                        *print-level* (or @sci/print-level default-print-level)]
                (rf result input))))
        traced* (atom {})
        {:keys [send! stop!]} (start-trace-sender! traced*)]
    (try
      ;; eval sets *1, *2, *3 and *e
      (binding [*1 nil *2 nil *3 nil *e nil]
        (with-bindings {#'debug/*repl-connection* {:send! send! :traced traced*}}
          (sci/with-bindings {sci/ns (sci/create-ns 'user nil)
                              sci/print-length @sci/print-length
                              sci/print-level @sci/print-level
                              sci/*1 nil
                              sci/*2 nil
                              sci/*3 nil
                              sci/*e nil}
            (nrepl.server/session-loop rf in out {:opts opts :id "pre-init" :ctx sci-ctx}))))
      (finally
        (stop!)
        (untrace-client! traced*)))))

(defn ^:private accept-loop!
  "Serves each client that connects, on its own thread, until the server
   socket closes."
  [sci-ctx ^ServerSocket server-socket clients*]
  (loop []
    (when-let [socket (try
                        (.accept server-socket)
                        (catch Exception e
                          (when-not (.isClosed server-socket)
                            (logger/warn logger-tag "nREPL server stopped accepting clients:" e))
                          nil))]
      (swap! clients* conj socket)
      (if (.isClosed server-socket)
        ;; stopped while this client connected: stop-server! may not have seen it
        (do (swap! clients* disj socket)
            (close-quietly! socket))
        (start-thread! "eca-repl-client"
                       (bound-fn []
                         (try
                           (serve-client! sci-ctx socket)
                           (catch Exception e
                             (when-not (.isClosed ^Socket socket)
                               (logger/debug logger-tag "nREPL client disconnected:" e)))
                           (finally
                             (swap! clients* disj socket)
                             (close-quietly! socket))))))
      (recur))))

(defn start-server!
  "Starts an nREPL server for `sci-ctx` on `host` and `port` (0 picks a free
   one). Returns `{:host :port :socket :clients}`, for `stop-server!`."
  [sci-ctx host port]
  (let [address (InetAddress/getByName host)
        socket (ServerSocket. (int port) 0 address)
        clients* (atom #{})
        sci-ctx (assoc sci-ctx :sessions (atom #{}))]
    ;; babashka.nrepl prints some errors with println: keep them off stdout,
    ;; the JSON-RPC channel; bound-fn conveys this to the client threads
    (binding [*out* *err*]
      (start-thread! "eca-repl-accept" (bound-fn [] (accept-loop! sci-ctx socket clients*))))
    {:host (.getHostAddress address)
     :port (.getLocalPort socket)
     :socket socket
     :clients clients*}))

(defn stop-server!
  "Stops accepting clients and disconnects the connected ones."
  [{:keys [socket clients]}]
  (some-> socket close-quietly!)
  (doseq [client (some-> clients deref)]
    (close-quietly! client)))

(defn ^:private loopback? [host]
  (try
    (.isLoopbackAddress (InetAddress/getByName host))
    (catch Exception _ false)))

(defn ^:private listen-address
  "The `[host port]` to listen on, from the `repl` config."
  [config]
  (let [{:keys [host port]} (:repl config)
        host (if (string/blank? host) default-host (string/trim (str host)))
        port (if (nil? port) 0 port)]
    (when-not (loopback? host)
      (throw (ex-info (format "repl.host must be a loopback address, like %s: nREPL has no authentication. Got %s"
                              default-host host)
                      {:host host})))
    (when-not (and (integer? port) (<= 0 port 65535))
      (throw (ex-info (format "repl.port must be a port number from 0 to 65535, 0 picking a free one. Got %s" port)
                      {:port port})))
    [host port]))

(defn port-file
  "Where the module writes its port, for editors: `.nrepl-port` in its folder
   of the workspace's cache."
  [db]
  (fs/path (modules.data/module-dir db :eca/repl) ".nrepl-port"))

(defn ^:private write-port-file! [db port]
  (try
    (let [file (port-file db)]
      (fs/create-dirs (fs/parent file))
      (spit (str file) (str port))
      (str file))
    (catch Exception e
      (logger/warn logger-tag "Could not write the nREPL port file:" e)
      nil)))

(defn ^:private delete-port-file!
  "Deletes the port file, unless another ECA wrote its own port there since."
  [file port]
  (try
    (when (and file (fs/exists? file) (= (str port) (string/trim (slurp (str file)))))
      (fs/delete file))
    (catch Exception e
      (logger/debug logger-tag "Could not delete the nREPL port file:" e))))

(defn ^:private start! [{:keys [config db* messenger]}]
  (let [[host port] (listen-address config)
        server (start-server! (loader/sci-context) host port)
        file (when db* (write-port-file! @db* (:port server)))]
    (logger/info logger-tag (format "nREPL server started on %s:%d" (:host server) (:port server)))
    (when messenger
      (messenger/showMessage messenger
                             {:type :info
                              :message (format "ECA's nREPL is listening on %s:%d: /repl shows how to use it."
                                               (:host server) (:port server))}))
    (assoc server :port-file file)))

(defn ^:private stop! [ctx]
  (let [{:keys [port port-file] :as server} (modules/state (:module-id ctx))]
    (stop-server! server)
    (delete-port-file! port-file port)
    (events/untrace-all!)
    (logger/info logger-tag "nREPL server stopped")))

(defn ^:private repl-status [ctx _args]
  (if-let [{:keys [host port port-file]} (modules/state (:module-id ctx))]
    (string/join
     "\n"
     (cond-> [(format "ECA's nREPL is listening on %s:%d." host port)]
       port-file (conj (str "Port file: " port-file))
       :always (conj ""
                     "Connect your editor's nREPL client to it, e.g. CIDER's cider-connect-clj, then:"
                     ""
                     "(require '[eca.api :as eca] '[eca.api.dev :as dev] '[eca.api.debug :as debug])"
                     "(eca/notify! (dev/ctx) \"Hello from the REPL\")"
                     "(dev/modules)                 ; every module and its status"
                     "(debug/trace! \"<chat-id>\")    ; prints a chat's module events here")))
    "ECA's nREPL isn't running."))

(defn ^:private prompt-section
  "While the REPL runs, tells the model where it is, so it can evaluate code
   in this ECA. Dynamic, so the model sees a REPL turned on mid-chat, or
   restarted on another free port, on its next request."
  [{:keys [module-id]}]
  (when-let [{:keys [host port]} (modules/state module-id)]
    ["## ECA's REPL"
     ""
     (format (str "ECA's nREPL is listening on %s:%d. Code evaluated there runs inside this ECA, with the "
                  "eca.api, eca.api.dev and eca.api.debug namespaces (https://eca.dev/config/modules/). "
                  "When the user asks about ECA itself, you can evaluate code there with an nREPL client "
                  "from the shell, if one is installed, e.g. `brepl -p %d '(+ 1 2)'`.")
             host port port)
     ""]))

(def module
  {:id :eca/repl
   :description "An nREPL on ECA's module runtime: use eca.api live, develop modules, read eca.api.debug."
   :default-enabled? false
   :config-spec {:repl {:host {:type :string
                               :default default-host
                               :doc "A loopback address: nREPL has no authentication."}
                        :port {:type :number :default 0 :doc "0 picks a free port."}}}
   :start start!
   :stop stop!
   :system-prompts [{:id :eca/repl :dynamic? true :content #'prompt-section}]
   :commands {"repl" {:description "Show the address of ECA's nREPL and how to use it."
                      :arguments []
                      :handler repl-status}}})
