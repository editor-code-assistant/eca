(ns eca.features.tools.shell
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[TOOLS-SHELL]")

(def ^:private default-timeout 60000)
(def ^:private max-timeout (* 60000 10))

(defn ^:private shell-command [arguments {:keys [db tool-call-id call-state-fn state-transition-fn]}]
  (let [command-args (get arguments "command")
        user-work-dir (get arguments "working_directory")
        timeout (min (or (get arguments "timeout") default-timeout) max-timeout)]
    (or (tools.util/invalid-arguments arguments [["working_directory" #(or (nil? %)
                                                                           (fs/exists? %)) "working directory $working_directory does not exist"]])
        (let [work-dir (or (some-> user-work-dir fs/canonicalize str)
                           (some-> (:workspace-folders db)
                                   first
                                   :uri
                                   shared/uri->filename)
                           (config/get-property "user.home"))
              _ (logger/debug logger-tag "Running command:" command-args)
              result (try
                       (let [proc (when-not (= :stopping (:status (call-state-fn)))
                                    (p/process {:dir work-dir
                                                :out :string
                                                :err :string
                                                :timeout timeout
                                                :continue true} "bash -c" command-args))]
                         (when proc
                           (state-transition-fn :resources-created {:resources {:process proc}})
                           (try (deref proc
                                       timeout
                                       ::timeout)
                                (catch InterruptedException e
                                  (let [msg (or (.getMessage e) "Shell tool call was interrupted")]
                                    (logger/debug logger-tag "Shell tool call was interrupted" {:tool-call-id tool-call-id :message msg})
                                    (tools.util/tool-call-destroy-resource! "eca_shell_command" :process proc)
                                    (state-transition-fn :resources-destroyed {:resources [:process]})
                                    {:exit 1 :err msg})))))
                       (catch Exception e
                         ;; Process did not start, or had an Exception (other than InterruptedException) during execution.
                         (let [msg (or (.getMessage e) "Caught an Exception during execution of the shell tool")]
                           (logger/warn logger-tag "Got an Exception during execution" {:message msg})
                           {:exit 1 :err msg}))
                       (finally
                         ;; If any resources remain, destroy them.
                         (let [state (call-state-fn)]
                           (when-let [resources (:resources state)]
                             (doseq [[res-kwd res] resources]
                               (tools.util/tool-call-destroy-resource! "eca_shell_command" res-kwd res))
                             (when (#{:executing :stopping} (:status state))
                               (state-transition-fn :resources-destroyed {:resources (keys resources)}))))))
              err (some-> (:err result) string/trim)
              out (some-> (:out result) string/trim)]
          (cond
            (= result ::timeout)
            (do
              (logger/debug logger-tag "Command timed out after " timeout " ms")
              (tools.util/single-text-content (str "Command timed out after " timeout " ms") true))

            (zero? (:exit result))
            (do (logger/debug logger-tag "Command executed:" result)
                (tools.util/single-text-content (:out result)))

            :else
            (do
              (logger/debug logger-tag "Command executed:" result)
              {:error true
               :contents (remove nil?
                                 (concat [{:type :text
                                           :text (str "Exit code " (:exit result))}]
                                         (when-not (string/blank? err)
                                           [{:type :text
                                             :text (str "Stderr:\n" err)}])
                                         (when-not (string/blank? out)
                                           [{:type :text
                                             :text (str "Stdout:\n" out)}])))}))))))

(defn shell-command-summary [{:keys [args config]}]
  (let [max-length (get-in config [:toolCall :shellCommand :summaryMaxLength])]
    (if-let [command (get args "command")]
      (if (> (count command) max-length)
        (format "Running '%s...'" (subs command 0 max-length))
        (format "Running '%s'" command))
      "Running shell command")))

(def definitions
  {"eca_shell_command"
   {:description (tools.util/read-tool-description "eca_shell_command")
    :parameters {:type "object"
                 :properties {"command" {:type "string"
                                         :description "The shell command to execute."}
                              "working_directory" {:type "string"
                                                   :description "The directory to run the command in. (Default: first workspace-root)"}
                              "timeout" {:type "integer"
                                         :description (format "Optional timeout in milliseconds (Default: %s)" default-timeout)}}
                 :required ["command"]}
    :handler #'shell-command
    :require-approval-fn (fn [args {:keys [db]}]
                           (when-let [wd (and args (get args "working_directory"))]
                             (when-let [wd (and (fs/exists? wd) (str (fs/canonicalize wd)))]
                               (let [workspace-roots (mapv (comp shared/uri->filename :uri) (:workspace-folders db))]
                                 (not-any? #(fs/starts-with? wd %) workspace-roots)))))
    :summary-fn #'shell-command-summary}})

(defmethod tools.util/tool-call-destroy-resource! :eca_shell_command [name resource-kwd resource]
  (logger/debug logger-tag "About to destroy resource" {:resource-kwd resource-kwd})
  (case resource-kwd
    :process (p/destroy-tree resource)
    (logger/warn logger-tag "Unknown resource keyword" {:tool-name name
                                                        :resource-kwd resource-kwd})))
