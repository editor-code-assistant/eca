(ns eca.main
  (:refer-clojure :exclude [run!])
  (:gen-class)
  (:require
   [babashka.cli :as cli]
   [borkdude.dynaload]
   [clojure.string :as string]
   [eca.client-http :as client]
   [eca.config :as config]
   [eca.logger :as logger]
   [eca.network :as network]
   [eca.read-chat :as read-chat]
   [eca.server :as server]))

(set! *warn-on-reflection* true)

(defn ^:private exit [status msg]
  (let [status (or status 1)]
    (when msg
      (binding [*out* (if (zero? status) *out* *err*)]
        (println msg)))
    (System/exit status)))

(defn ^:private version []
  (str "eca " (config/eca-version)))

(defn ^:private help
  [options-summary]
  (->> ["ECA - Editor Code Assistant"
        ""
        "Usage: eca <command> [<options>]"
        ""
        "All options:"
        options-summary
        ""
        "Available commands:"
        "  server        Start eca as server, listening to stdin."
        "  read-chat     Stream chat history from the DB cache as JSONL."
        ""
        "See https://eca.dev/config/introduction/ for detailed documentation."]
       (string/join \newline)))

(defn ^:private cli-error-msg [error]
  (if (map? error)
    (let [{:keys [cause msg option value]} error
          option-name (some->> option name (str "--"))]
      (case cause
        :require (str "Missing required option " option-name)
        :restrict (str "Unknown option " option-name)
        :coerce (if (true? value)
                  (str "Missing value for " option-name)
                  (str "Invalid value for " option-name ": " value ". " msg))
        :validate (str "Invalid value for " option-name ": " value ". " msg)
        (or msg (str error))))
    (str error)))

(defn ^:private error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline (map cli-error-msg errors))))

(def log-levels #{"error" "warn" "info" "debug"})

(def cli-spec
  {:order [:help :version :verbose :config-file :log-level]
   :spec {:help {:alias :h
                 :desc "Print the available commands and its options"}
          :version {:desc "Print eca version"}
          :log-level {:ref "<LEVEL>"
                      :desc "The log level of eca logs, accepts. Defaults to 'info'."
                      :default "info"
                      :validate {:pred log-levels
                                 :ex-msg (fn [{:keys [_option _value]}]
                                           (format "Must be in %s" log-levels))}}
          :config-file {:ref "<FILE>"
                        :desc "Path to a JSON config <FILE> to use instead of searching default locations"
                        :coerce :string
                        :default nil}
          :verbose {:desc "Enable verbose JSON-RPC protocol tracing"}}})

(defn ^:private parse-server-opts
  [args]
  (try
    (let [{:keys [args opts]} (cli/parse-args args cli-spec)]
      {:options opts
       :arguments args})
    (catch clojure.lang.ExceptionInfo e
      {:options {} :errors [(ex-data e)]})))

(defn ^:private read-chat-option-errors
  [opts]
  (cond-> []
    (and (:db-cache-path opts) (seq (:workspace opts)))
    (conj "Use either --db-cache-path or --workspace, not both")

    (and (not (:db-cache-path opts)) (not (seq (:workspace opts))))
    (conj "Missing required input source: pass --db-cache-path <PATH> or one or more --workspace <PATH>")

    (and (:role opts) (not (:chat-id opts)))
    (conj "--role requires --chat-id because role filtering applies to messages only")))

(defn ^:private parse-read-chat-opts
  [args]
  (try
    (let [valid-options (-> read-chat/read-chat-spec :spec keys set)
          {:keys [args opts]} (cli/parse-args args (assoc read-chat/read-chat-spec
                                                          :restrict valid-options))]
      (if (:help opts)
        {:options opts}
        (let [option-errors (read-chat-option-errors opts)
              positional-errors (when (seq args)
                                  [(str "Unexpected argument(s): " (string/join " " args)
                                        ". read-chat accepts options only; use --db-cache-path <PATH> or --workspace <PATH>, plus optional filters like --chat-id <ID>.")])]
          {:options opts
           :errors (seq (concat positional-errors option-errors))})))
    (catch clojure.lang.ExceptionInfo e
      {:options {} :errors [(ex-data e)]})))

(defn ^:private parse [args]
  (if (= "read-chat" (first args))
    (let [{:keys [options errors]} (parse-read-chat-opts (rest args))
          help-text (read-chat/help)]
      (cond
        (:help options) {:exit-message help-text :ok? true}
        errors          {:exit-message (error-msg errors)}
        :else           {:action "read-chat" :options options}))
    (let [{:keys [options arguments errors]} (parse-server-opts args)
          help-text (help (cli/format-opts cli-spec))]
      (cond
        (:help options)    {:exit-message help-text :ok? true}
        (:version options) {:exit-message (version) :ok? true}
        errors             {:exit-message (error-msg errors)}
        (and (= 1 (count arguments))
             (= "server" (first arguments)))
        {:action "server" :options options}
        :else              {:exit-message help-text}))))

(defn ^:private handle-action!
  [action options]
  (case action
    "server"
    (do
      (when-some [cfg-file (:config-file options)]
        (reset! config/custom-config-file-path* cfg-file))
      (logger/set-level! (keyword (:log-level options)))
      (network/setup! (config/read-file-configs))
      (client/hato-client-global-setup! {})
      (let [finished @(server/run-io-server! (:verbose options))]
        {:result-code (if (= :done finished) 0 1)}))

    "read-chat"
    (try
      (read-chat/run options)
      (catch clojure.lang.ExceptionInfo e
        (binding [*out* *err*]
          (println (.getMessage e)))
        {:result-code 1}))))

(defn run!
  "Entrypoint for ECA CLI."
  [& args]
  (let [{:keys [action options exit-message ok?]} (parse args)]
    (if exit-message
      {:result-code (if ok? 0 1)
       :message-fn (constantly  exit-message)}
      (handle-action! action options))))

(defn main [& args]
  (let [{:keys [result-code message-fn]} (apply run! args)]
    (exit result-code (when message-fn (message-fn)))))

(def musl?
  "Captured at compile time, to know if we are running inside a
  statically compiled executable with musl."
  (and (= "true" (System/getenv "ECA_STATIC"))
       (= "true" (System/getenv "ECA_MUSL"))))

(defmacro run [args]
  (if musl?
    ;; When running in musl-compiled static executable we lift execution of eca
    ;; inside a thread, so we have a larger than default stack size, set by an
    ;; argument to the linker. See https://github.com/oracle/graal/issues/3398
    `(let [v# (volatile! nil)
           f# (fn []
                (vreset! v# (apply main ~args)))]
       (doto (Thread. nil f# "main")
         (.start)
         (.join))
       @v#)
    `(apply main ~args)))

(defn -main
  [& args]
  (run args))
