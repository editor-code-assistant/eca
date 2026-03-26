(ns eca.features.tools.git
  (:require
   [babashka.process :as p]
   [clojure.string :as string]
   [eca.features.tools.shell :as shell]
   [eca.features.tools.util :as tools.util]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[TOOLS-GIT]")

(def ^:private default-timeout 120000)

(defn ^:private git-command [arguments {:keys [db tool-call-id call-state-fn state-transition-fn]}]
  (let [command (get arguments "command")]
    (or (tools.util/invalid-arguments
         arguments
         [["command" #(and (string? %)
                          (let [trimmed (string/triml %)
                                cmd (if (string/starts-with? trimmed "cd ")
                                      (or (some->> (re-find #"^cd\s+\S+\s*(?:&&|;)\s*(.*)" trimmed)
                                                   second
                                                   string/triml)
                                          trimmed)
                                      trimmed)]
                            (or (string/starts-with? cmd "git ")
                                (string/starts-with? cmd "git;")
                                (string/starts-with? cmd "gh "))))
           "command must start with 'git' or 'gh' (optionally preceded by 'cd <path> &&')"]])
        (let [work-dir (or (some-> (:workspace-folders db)
                                   first
                                   :uri
                                   shared/uri->filename)
                           (System/getProperty "user.home"))
              _ (logger/debug logger-tag "Running command:" command)
              result (try
                       (if-let [proc (when-not (= :stopping (:status (call-state-fn)))
                                       (shell/start-shell-process! {:cwd work-dir
                                                                    :script command}))]
                         (do
                           (state-transition-fn :resources-created {:resources {:process proc}})
                           (try (deref proc default-timeout ::timeout)
                                (catch InterruptedException e
                                  (let [msg (or (.getMessage e) "Git tool call was interrupted")]
                                    (logger/debug logger-tag "Git tool call was interrupted"
                                                  {:tool-call-id tool-call-id :message msg})
                                    (tools.util/tool-call-destroy-resource! "eca__git" :process proc)
                                    (state-transition-fn :resources-destroyed {:resources [:process]})
                                    {:exit 1 :err msg}))))
                         {:exit 1 :err "Tool call is :stopping, so process not spawned"})
                       (catch Exception e
                         (let [msg (or (.getMessage e) "Caught an Exception during execution of the git tool")]
                           (logger/warn logger-tag "Got an Exception during execution" {:message msg})
                           {:exit 1 :err msg}))
                       (finally
                         (let [state (call-state-fn)]
                           (when-let [resources (:resources state)]
                             (doseq [[res-kwd res] resources]
                               (tools.util/tool-call-destroy-resource! "eca__git" res-kwd res))
                             (when (#{:executing :stopping} (:status state))
                               (state-transition-fn :resources-destroyed {:resources (keys resources)}))))))]
          (if (= result ::timeout)
            (do
              (logger/debug logger-tag "Command timed out after" default-timeout "ms")
              (tools.util/single-text-content (str "Command timed out after " default-timeout " ms") true))
            (do
              (logger/debug logger-tag "Command executed:" result)
              {:error (not (zero? (:exit result)))
               :contents (remove nil?
                                 (concat [{:type :text
                                           :text (str "Exit code: " (:exit result))}]
                                         (when-not (string/blank? (:err result))
                                           [{:type :text
                                             :text (str "Stderr:\n" (string/trim (:err result)))}])
                                         (when-not (string/blank? (:out result))
                                           [{:type :text
                                             :text (str "Stdout:\n" (string/trim (:out result)))}])))}))))))

(defn ^:private git-command-summary [{:keys [args]}]
  (let [operation (get args "operation")
        summary (get args "summary")]
    (if operation
      (cond-> (str "Git " operation)
        summary (str ": " summary))
      "Preparing git operation")))

(def definitions
  {"git"
   {:description (tools.util/read-tool-description "git")
    :parameters {:type "object"
                 :properties {"command" {:type "string"
                                         :description "The git or gh command to execute."}
                              "operation" {:type "string"
                                           :description "The git operation being performed."
                                           :enum ["status" "diff" "log" "add" "commit" "push"
                                                  "branch" "checkout" "merge" "rebase" "stash"
                                                  "pr_create" "pr_view" "pr_comment" "gh_other"]}
                              "summary" {:type "string"
                                         :description "Concise 2-3 word description of what is being done (e.g. \"staged changes\", \"all test files\")."}}
                 :required ["command" "operation"]}
    :handler #'git-command
    :summary-fn #'git-command-summary}})

(defmethod tools.util/tool-call-destroy-resource! :eca__git [name resource-kwd resource]
  (logger/debug logger-tag "About to destroy resource" {:resource-kwd resource-kwd})
  (case resource-kwd
    :process (p/destroy-tree resource)
    (logger/warn logger-tag "Unknown resource keyword" {:tool-name name
                                                        :resource-kwd resource-kwd})))
