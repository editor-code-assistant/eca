(ns eca.main
  (:refer-clojure :exclude [run!])
  (:gen-class)
  (:require
   [babashka.cli :as cli]
   [borkdude.dynaload]
   [clojure.string :as string]
   [eca.client-http :as client]
   [eca.config :as config]
   [eca.features.agents] ;; TODO how to fix this cyclic dep
   [eca.logger :as logger]
   [eca.server :as server]))

(set! *warn-on-reflection* true)

(defn ^:private exit [status msg]
  (when msg
    (println msg))
  (System/exit (or status 1)))

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
        ""
        "See https://eca.dev/settings/ for detailed documentation."]
       (string/join \newline)))

(defn ^:private error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

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
          :verbose {:desc "Use stdout for eca logs instead of default log settings"}}})

(defn ^:private parse-opts
  [args]
  (let [errors (atom [])
        {:keys [args opts]} (cli/parse-args args (assoc cli-spec
                                                        :error-fn (fn [error] (swap! errors conj error))))]
    {:options (-> opts
                  (assoc :dry? (:dry opts))
                  (dissoc :dry)
                  (assoc :raw? (:raw opts))
                  (dissoc :raw))
     :arguments args
     :errors (when (seq @errors)
               @errors)
     :summary (cli/format-opts cli-spec)}))

(defn ^:private parse [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args)]
    (cond
      (:help options)
      {:exit-message (help summary) :ok? true}

      (:version options)
      {:exit-message (version) :ok? true}

      errors
      {:exit-message (error-msg errors)}

      (and (= 1 (count arguments))
           (#{"server"} (first arguments)))
      {:action (first arguments) :options options}

      :else
      {:exit-message (help summary)})))

(defn ^:private handle-action!
  [action options]
  (client/hato-client-global-setup! {})
  (when (= "server" action)
    (when-some [cfg-file (:config-file options)]
      (reset! config/custom-config-file-path* cfg-file))
    (alter-var-root #'logger/*level* (constantly (keyword (:log-level options))))
    (let [finished @(server/run-io-server! (:verbose options))]
      {:result-code (if (= :done finished) 0 1)})))

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
