(ns eca.features.tools.shell
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.shared :as shared])
  (:import
   [java.security MessageDigest]
   [java.util Base64]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[TOOLS-SHELL]")

(def ^:private default-timeout 60000)
(def ^:private max-timeout (* 60000 10))

(defn start-shell-process!
  "Start a shell process, returning the process object for deref/management.

   Options:
   - :cwd      Working directory (required)
   - :script   Inline script string (mutually exclusive with :file)
   - :file     Script file path (mutually exclusive with :script)
   - :input    String to pass as stdin (optional)

   Returns: babashka.process process object (deref-able)"
  [{:keys [cwd script file input]}]
  {:pre [(some? cwd)
         (or (some? script) (some? file))
         (not (and script file))]}
  (let [win? (string/starts-with? (System/getProperty "os.name") "Windows")
        cmd (cond
              (and win? file)
              ["powershell.exe" "-ExecutionPolicy" "Bypass" "-File" file]

              (and win? script)
              ["powershell.exe" "-NoProfile" "-Command" script]

              file
              ["bash" file]

              :else
              ["bash" "-c" script])]
    (p/process (cond-> {:cmd cmd
                        :dir cwd
                        :out :string
                        :err :string
                        :continue true}
                 input (assoc :in input)))))

(defn ^:private workspaces-hash
  "Return an 8-char base64 (URL-safe, no padding) key for the given workspace set."
  [workspaces]
  (let [paths (->> workspaces
                   (map #(str (fs/absolutize (fs/file (shared/uri->filename (:uri %))))))
                   (distinct)
                   (sort))
        joined (string/join ":" paths)
        md (MessageDigest/getInstance "SHA-256")
        digest (.digest (doto md (.update (.getBytes ^String joined "UTF-8"))))
        encoder (-> (Base64/getUrlEncoder)
                    (.withoutPadding))
        key (.encodeToString encoder digest)]
    (subs key 0 (min 8 (count key)))))

(defn ^:private shell-output-cache-dir
  "Get the directory path for storing shell output files.
   Returns: ~/.cache/eca/{workspace-hash}/shell-output/"
  [workspaces]
  (let [ws-hash (workspaces-hash workspaces)]
    (io/file (tools.util/eca-cache-base-dir) ws-hash "shell-output")))

(defn ^:private write-output-to-file
  "Write shell command output to a file in the cache directory.
   Returns the file path on success, or nil on failure."
  [output chat-id tool-call-id workspaces]
  (try
    (let [dir (shell-output-cache-dir workspaces)
          filename (str chat-id "-" tool-call-id ".txt")
          file-path (io/file dir filename)]
      (io/make-parents file-path)
      (spit file-path output)
      (str file-path))
    (catch Exception e
      (logger/warn logger-tag "Failed to write output to file"
                   {:chat-id chat-id
                    :tool-call-id tool-call-id
                    :error (.getMessage e)})
      nil)))

(defn ^:private format-file-based-response
  ([file-path output exit-code file-size]
   (format-file-based-response file-path output exit-code file-size 20))
  ([file-path output exit-code file-size tail]
   (let [lines (string/split-lines output)
         tail-lines (take-last tail lines)
         tail-str (string/join "\n" tail-lines)
         formatted-size (format "%,d" file-size)]
     (str "Shell command output saved to file due to size, you should inspect the file if needed.\n\n"
          "File: " file-path "\n"
          "Size: " formatted-size " characters\n"
          "Exit code: " exit-code "\n\n"
          (format "Last %s lines:\n" (count tail-lines))
          tail-str))))

(defn ^:private shell-command [arguments {:keys [db tool-call-id chat-id config call-state-fn state-transition-fn]}]
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
                       (if-let [proc (when-not (= :stopping (:status (call-state-fn)))
                                       (start-shell-process! {:cwd work-dir
                                                              :script command-args}))]
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
                         ;; Process did not start, or had an Exception (other than InterruptedException) during execution.
                         (let [msg (or (.getMessage e) "Caught an Exception during execution of the shell tool")]
                           (logger/warn logger-tag "Got an Exception during execution" {:message msg})
                           {:exit 1 :err msg}))
                       (finally
                         ;; If any resources remain, destroy them.
                         (let [state (call-state-fn)]
                           (when-let [resources (:resources state)]
                             (doseq [[res-kwd res] resources]
                               (tools.util/tool-call-destroy-resource! "eca__shell_command" res-kwd res))
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
            (let [output (or out "")
                  output-size (count output)
                  threshold (get-in config [:toolCall :shellCommand :outputFileThreshold] 1000)]
              (logger/debug logger-tag "Command executed:" result)
              (if (> output-size threshold)
                ;; Large output: write to file and return summary
                (if (and chat-id tool-call-id)
                  (if-let [file-path (write-output-to-file output chat-id tool-call-id (:workspace-folders db))]
                    (let [response (tools.util/single-text-content
                                    (format-file-based-response file-path output (:exit result) output-size))]
                      (logger/debug logger-tag "Returning file-based response")
                      response)
                    ;; Fallback: if file write failed, inline the output with a warning
                    (do
                      (logger/warn logger-tag "File write failed, falling back to inline output")
                      (tools.util/single-text-content
                       (str "Warning: Failed to write output to file, showing inline instead.\n\n"
                            output))))
                  ;; chat-id or tool-call-id is nil, inline the output
                  (do
                    (logger/warn logger-tag "chat-id or tool-call-id is nil, inlining large output"
                                 {:chat-id chat-id :tool-call-id tool-call-id})
                    (tools.util/single-text-content output)))
                ;; Small output: inline with structured response
                {:error false
                 :contents (remove nil?
                                   (concat [{:type :text
                                             :text (str "Exit code: " (:exit result))}]
                                           (when-not (string/blank? err)
                                             [{:type :text
                                               :text (str "Stderr:\n" err)}])
                                           (when-not (string/blank? out)
                                             [{:type :text
                                               :text (str "Stdout:\n" out)}])))}))

            :else
            (let [output (str (when-not (string/blank? out) out)
                              (when (and (not (string/blank? out))
                                         (not (string/blank? err)))
                                "\n")
                              (when-not (string/blank? err) err))
                  output-size (count output)
                  threshold (get-in config [:toolCall :shellCommand :outputFileThreshold] 1000)]
              (logger/debug logger-tag "Command executed:" result)
              (if (> output-size threshold)
                ;; Large error output: write to file and return summary
                (if (and chat-id tool-call-id)
                  (if-let [file-path (write-output-to-file output chat-id tool-call-id (:workspace-folders db))]
                    (let [response-text (str "Command failed with exit code " (:exit result) ".\n\n"
                                             "Output saved to file due to size: " file-path "\n"
                                             "Size: " (format "%,d" output-size) " characters\n\n"
                                             (format "Last 20 lines:\n")
                                             (string/join "\n" (take-last 20 (string/split-lines output))))]
                      (logger/debug logger-tag "Returning file-based error response")
                      {:error (not (zero? (:exit result)))
                       :contents [{:type :text
                                   :text response-text}]})
                    ;; Fallback: if file write failed, inline the output with a warning
                    (do
                      (logger/warn logger-tag "File write failed for error output, falling back to inline")
                      {:error (not (zero? (:exit result)))
                       :contents (remove nil?
                                         (concat [{:type :text
                                                   :text (str "Warning: Failed to write output to file, showing inline instead.\n\n"
                                                              "Exit code: " (:exit result))}]
                                                 (when-not (string/blank? err)
                                                   [{:type :text
                                                     :text (str "Stderr:\n" err)}])
                                                 (when-not (string/blank? out)
                                                   [{:type :text
                                                     :text (str "Stdout:\n" out)}])))}))
                  ;; chat-id or tool-call-id is nil, inline the output
                  (do
                    (logger/warn logger-tag "chat-id or tool-call-id is nil, inlining large error output"
                                 {:chat-id chat-id :tool-call-id tool-call-id})
                    {:error (not (zero? (:exit result)))
                     :contents (remove nil?
                                       (concat [{:type :text
                                                 :text (str "Exit code: " (:exit result))}]
                                               (when-not (string/blank? err)
                                                 [{:type :text
                                                   :text (str "Stderr:\n" err)}])
                                               (when-not (string/blank? out)
                                                 [{:type :text
                                                   :text (str "Stdout:\n" out)}])))}))
                ;; Small error output: inline as before
                {:error (not (zero? (:exit result)))
                 :contents (remove nil?
                                   (concat [{:type :text
                                             :text (str "Exit code: " (:exit result))}]
                                           (when-not (string/blank? err)
                                             [{:type :text
                                               :text (str "Stderr:\n" err)}])
                                           (when-not (string/blank? out)
                                             [{:type :text
                                               :text (str "Stdout:\n" out)}])))})))))))

(defn shell-command-summary [{:keys [args config]}]
  (let [max-length (get-in config [:toolCall :shellCommand :summaryMaxLength])]
    (if-let [command (get args "command")]
      (if (> (count command) max-length)
        (format "Running '%s...'" (subs command 0 max-length))
        (format "Running '%s'" command))
      "Running shell command")))

(def definitions
  {"shell_command"
   {:description (tools.util/read-tool-description "shell_command")
    :parameters {:type "object"
                 :properties {"command" {:type "string"
                                         :description "The shell command to execute."}
                              "working_directory" {:type "string"
                                                   :description "The directory to run the command in. (Default: first workspace-root)"}
                              "timeout" {:type "integer"
                                         :description (format "Optional timeout in milliseconds (Default: %s)" default-timeout)}}
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
