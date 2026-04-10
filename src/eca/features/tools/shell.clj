(ns eca.features.tools.shell
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.background-tasks :as bg]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[TOOLS-SHELL]")

(def ^:private default-timeout 60000)
(def ^:private max-timeout (* 60000 10))

(defn start-shell-process!
  "Start a shell process, returning the process object for deref/management.

   Options:
   - :cwd         Working directory (required)
   - :script      Inline script string (mutually exclusive with :file)
   - :file        Script file path (mutually exclusive with :script)
   - :input       String to pass as stdin (optional)
   - :out-mode    Output capture mode — :string (default) or :stream
   - :shell-path  Custom shell executable path (optional, overrides platform default)
   - :shell-args  Custom shell args placed before the script/file arg (optional)

   Returns: babashka.process process object (deref-able)"
  [{:keys [cwd script file input out-mode shell-path shell-args]
    :or {out-mode :string}}]
  {:pre [(some? cwd)
         (or (some? script) (some? file))
         (not (and script file))]}
  (let [cmd (if shell-path
              (into (vec (cons shell-path shell-args)) [(or script file)])
              (let [win? (string/starts-with? (System/getProperty "os.name") "Windows")]
                (cond
                  (and win? file)
                  ["powershell.exe" "-ExecutionPolicy" "Bypass" "-File" file]

                  (and win? script)
                  ["powershell.exe" "-NoProfile" "-Command" script]

                  file
                  ["bash" file]

                  :else
                  ["bash" "-c" script])))]
    (p/process (cond-> {:cmd cmd
                        :dir cwd
                        :out out-mode
                        :err out-mode
                        :continue true}
                 input (assoc :in input)))))

(def ^:private initial-output-wait-ms 2000)
(def ^:private initial-output-poll-ms 200)

(defn ^:private resolve-work-dir [db user-work-dir]
  (or (some-> user-work-dir fs/canonicalize str)
      (some-> (:workspace-folders db)
              first
              :uri
              shared/uri->filename)
      (config/get-property "user.home")))

(defn ^:private wait-for-initial-output
  "Poll for initial output, exiting early if the process exits or the tool call
   is being stopped. Returns within `initial-output-wait-ms` at most."
  [job-id call-state-fn]
  (loop [waited (long 0)]
    (when (and (< waited ^long initial-output-wait-ms)
               (= :running (:status (bg/get-job job-id)))
               (not= :stopping (:status (call-state-fn))))
      (Thread/sleep ^long initial-output-poll-ms)
      (recur (+ waited ^long initial-output-poll-ms)))))

(defn ^:private background-shell-command
  "Start a shell process in the background, register it as a background job,
   and return immediately with the job ID and any initial output."
  [arguments {:keys [db db* chat-id messenger call-state-fn]}]
  (let [command (get arguments "command")
        bg-value (get arguments "background")
        summary (when (string? bg-value) bg-value)
        user-work-dir (get arguments "working_directory")
        work-dir (resolve-work-dir db user-work-dir)
        max-jobs-msg (str "Maximum number of background jobs reached (" bg/max-jobs "). Kill an existing job first.")]
    (cond
      (= :stopping (:status (call-state-fn)))
      (tools.util/single-text-content "Tool call is stopping, background job not started." true)

      (>= (bg/running-count) bg/max-jobs)
      (tools.util/single-text-content max-jobs-msg true)

      :else
      (try
        (let [proc (start-shell-process! {:cwd work-dir
                                          :script command
                                          :out-mode :stream})
              on-exit (fn [completed-job]
                        (try
                          (let [output-lines (:lines @(:output* completed-job))
                                tail (mapv bg/format-output-line (take-last 20 output-lines))]
                            (bg/enqueue-job-notification!
                             db* chat-id
                             {:job-id (:id completed-job)
                              :status (:status completed-job)
                              :exit-code (:exit-code completed-job)
                              :label (:label completed-job)
                              :output-tail tail})
                            (messenger/jobs-updated messenger {:jobs (bg/jobs-summary db*)}))
                          (catch Exception e
                            (logger/warn logger-tag "on-exit notification error" {:message (.getMessage e)}))))]
          (if-let [job (bg/register-shell-job! {:label command
                                                :summary summary
                                                :process proc
                                                :working-directory work-dir
                                                :chat-id chat-id
                                                :on-exit on-exit})]
            (do
              (bg/start-output-capture! job (:out proc) (:err proc))
              (bg/monitor-process-exit! (:id job) proc)
              (messenger/jobs-updated messenger {:jobs (bg/jobs-summary db*)})
              (wait-for-initial-output (:id job) call-state-fn)
              (let [{:keys [lines status exit-code]} (bg/read-output! (:id job))
                    initial-output (string/join "\n" (map bg/format-output-line lines))]
                (if (and (not= :running status) exit-code (not (zero? exit-code)))
                  {:error true
                   :contents [{:type :text
                               :text (format "Background job %s failed immediately (exit code %d).\nOutput:\n%s"
                                             (:id job) exit-code initial-output)}]}
                  (tools.util/single-text-content
                   (str "Background job " (:id job) " started."
                        "\nCommand: " command
                        "\nWorking directory: " work-dir
                        "\nUse eca__bg_job with action \"read_output\" and job_id \"" (:id job) "\" to check output."
                        "\nUse eca__bg_job with action \"kill\" and job_id \"" (:id job) "\" to stop it."
                        (when (seq initial-output)
                          (str "\n\nInitial output:\n" initial-output)))))))
            (do
              (p/destroy-tree proc)
              (tools.util/single-text-content max-jobs-msg true))))
        (catch Exception e
          (let [msg (or (.getMessage e) "Failed to start background shell process")]
            (logger/warn logger-tag "Background shell start error" {:message msg})
            (tools.util/single-text-content msg true)))))))

(defn ^:private foreground-shell-command
  "Run a shell command synchronously, blocking until completion or timeout."
  [arguments {:keys [db config tool-call-id call-state-fn state-transition-fn]}]
  (let [command-args (get arguments "command")
        user-work-dir (get arguments "working_directory")
        timeout (min (or (get arguments "timeout") default-timeout) max-timeout)
        shell-config (get-in config [:toolCall :shellCommand])
        shell-path (get shell-config :path)
        shell-args (get shell-config :args)
        work-dir (resolve-work-dir db user-work-dir)
        _ (logger/debug logger-tag "Running command:" command-args)
        result (try
                   (if-let [proc (when-not (= :stopping (:status (call-state-fn)))
                                   (start-shell-process! (cond-> {:cwd work-dir
                                                                   :script command-args}
                                                           shell-path (assoc :shell-path shell-path)
                                                           shell-args (assoc :shell-args shell-args))))]
                     (do
                       (state-transition-fn :resources-created {:resources {:process proc}})
                       (try (deref proc
                                   timeout
                                   ::timeout)
                            (catch InterruptedException e
                              (let [msg (or (.getMessage e) "Shell tool call was interrupted")]
                                (logger/debug logger-tag "Shell tool call was interrupted" {:tool-call-id tool-call-id :message msg})
                                (tools.util/tool-call-destroy-resource! "eca__shell_command" :process proc)
                                (state-transition-fn :resources-destroyed {:resources [:process]})
                                {:exit 1 :err msg}))))
                     {:exit 1 :err "Tool call is :stopping, so shell process not spawned"})
                   (catch Exception e
                     (let [msg (or (.getMessage e) "Caught an Exception during execution of the shell tool")]
                       (logger/warn logger-tag "Got an Exception during execution" {:message msg})
                       {:exit 1 :err msg}))
                   (finally
                     (let [state (call-state-fn)]
                       (when-let [resources (:resources state)]
                         (doseq [[res-kwd res] resources]
                           (tools.util/tool-call-destroy-resource! "eca__shell_command" res-kwd res))
                         (when (#{:executing :stopping} (:status state))
                           (state-transition-fn :resources-destroyed {:resources (keys resources)}))))))
          err (some-> (:err result) string/trim)
          out (some-> (:out result) string/trim)]
      (if (= result ::timeout)
        (do
          (logger/debug logger-tag "Command timed out after " timeout " ms")
          (tools.util/single-text-content (str "Command timed out after " timeout " ms") true))
        (do
          (logger/debug logger-tag "Command executed:" result)
          {:error (not (zero? (:exit result)))
           :contents (remove nil?
                             (concat [{:type :text
                                       :text (str "Exit code: " (:exit result))}]
                                     (when-not (string/blank? err)
                                       [{:type :text
                                         :text (str "Stderr:\n" err)}])
                                     (when-not (string/blank? out)
                                       [{:type :text
                                         :text (str "Stdout:\n" out)}])))}))))

(defn ^:private shell-command [arguments ctx]
  (or (tools.util/invalid-arguments arguments [["working_directory" #(or (nil? %)
                                                                         (fs/exists? %)) "working directory $working_directory does not exist"]])
      (if (get arguments "background")
        (background-shell-command arguments ctx)
        (foreground-shell-command arguments ctx))))
(defn ^:private strip-workspace-cd-prefix
  "Strips a leading 'cd <workspace-root> && ' or 'cd <workspace-root> ; ' prefix
   from a command string when the path matches one of the workspace roots."
  [command workspace-folders]
  (if-let [match (re-find #"^cd\s+(\S+)\s*(?:&&|;)\s*" command)]
    (let [cd-path (second match)
          workspace-roots (into #{}
                                (keep (comp #(some-> % shared/uri->filename) :uri))
                                workspace-folders)]
      (if (contains? workspace-roots cd-path)
        (subs command (count (first match)))
        command))
    command))

(defn shell-command-summary [{:keys [args config db]}]
  (let [max-length (get-in config [:toolCall :shellCommand :summaryMaxLength])
        workspace-folders (:workspace-folders db)
        bg? (get args "background")]
    (if-let [command (some-> (get args "command")
                             (strip-workspace-cd-prefix workspace-folders)
                             (string/replace #"\n" " ")
                             string/trim)]
      (let [prefix (if bg? "[BG] $ " "$ ")]
        (if (> (count command) max-length)
          (format "%s%s..." prefix (subs command 0 max-length))
          (format "%s%s" prefix command)))
      "Preparing shell command")))

(def definitions
  {"shell_command"
   {:description (tools.util/read-tool-description "shell_command")
    :parameters {:type "object"
                 :properties {"command" {:type "string"
                                         :description "The shell command to execute."}
                              "working_directory" {:type "string"
                                                   :description "The directory to run the command in. (Default: first workspace-root)"}
                              "timeout" {:type "integer"
                                         :description (format "Optional timeout in milliseconds (Default: %s)" default-timeout)}
                              "background" {:type "string"
                                            :description "Set to a brief description to run this command in the background (e.g., 'dev-server', 'file-watcher'). Use eca__bg_job to read output or kill the process later."}}
                 :required ["command"]}
    :handler #'shell-command
    :require-approval-fn (tools.util/require-approval-when-outside-workspace ["working_directory"])
    :summary-fn #'shell-command-summary}})

(defmethod tools.util/tool-call-destroy-resource! :eca__shell_command [name resource-kwd resource]
  (logger/debug logger-tag "About to destroy resource" {:resource-kwd resource-kwd})
  (case resource-kwd
    :process (p/destroy-tree resource)
    (logger/warn logger-tag "Unknown resource keyword" {:tool-name name
                                                        :resource-kwd resource-kwd})))