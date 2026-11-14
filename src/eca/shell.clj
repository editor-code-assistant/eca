(ns eca.shell
  "Starts shell commands for ECA's tools, hooks and modules. `before-exec`
   runs the `:shell/before-exec` event first, so modules can change the
   command, its working directory or its env."
  (:require
   [babashka.process :as p]
   [clojure.string :as string]
   [eca.logger :as logger]
   [eca.modules.events :as events]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[SHELL]")

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
   - :chat-id     Chat id exported as ECA_CHAT_ID env var (optional)
   - :env         Extra env vars, name → value (optional, e.g. from modules)

   The spawned process env always includes ECA_AGENT=1 so external tooling
   can detect ECA-spawned commands.

   Returns: babashka.process process object (deref-able)"
  [{:keys [cwd script file input out-mode shell-path shell-args chat-id env]
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
                        :continue true
                        :extra-env (merge
                                    (into {} (map (fn [[k v]] [(name k) (str v)])) env)
                                    (shared/assoc-some
                                     {"ECA_EXECUTABLE" @shared/eca-executable*
                                      "ECA_AGENT" "1"}
                                     "ECA_CHAT_ID" chat-id))}
                 input (assoc :in input)))))

(defn ^:private file-command-line
  "The command line that runs the script `file`, as `start-shell-process!`
   runs it with the default shell."
  [file]
  (if (string/starts-with? (System/getProperty "os.name") "Windows")
    (str "powershell.exe -ExecutionPolicy Bypass -File \"" file "\"")
    (str "bash '" (string/replace (str file) "'" "'\\''") "'")))

(defn ^:private changed-value
  "`[value]`, the value `:shell/before-exec` handlers set for `k`, when it
   differs from `old` and `valid?` accepts it; else nil, with a warning for
   an invalid value."
  [event k old valid?]
  (let [new (get event k)]
    (when (not= old new)
      (if (valid? new)
        [new]
        (do (logger/warn logger-tag (format "Ignoring the %s a :shell/before-exec handler set: %s" k (pr-str new)))
            nil)))))

(defn before-exec
  "Runs the `:shell/before-exec` event for the command `opts` would start,
   `opts` being options of `start-shell-process!`, and returns `opts` with the
   modules' changes: `:command` (run as a script), `:cwd` and `:env`. Values of
   the wrong type are ignored. A script `:file` is offered as the command line
   that runs it.
   `ctx` carries `:config` and what the handlers get as their ctx; without
   `:config` no event runs. `info` adds to the event what runs the command:
   `:source` (`:tool`, `:hook` or `:module`) with its `:tool`, `:hook` or
   `:module` name, plus `:timeout-ms` and `:background?`."
  [ctx {:keys [script file cwd env] :as opts} info]
  (if-not (:config ctx)
    opts
    (let [command (or script (file-command-line file))
          event (events/emit! ctx :shell/before-exec
                              (merge {:command command :cwd cwd :env env :timeout-ms nil :background? false}
                                     (shared/assoc-some info :chat-id (:chat-id ctx))))
          [new-command] (changed-value event :command command #(and (string? %) (not (string/blank? %))))
          [new-cwd] (changed-value event :cwd cwd #(and (string? %) (not (string/blank? %))))
          [new-env :as env-change] (changed-value event :env env #(or (nil? %) (map? %)))]
      (cond-> opts
        new-command (-> (dissoc :file) (assoc :script new-command))
        new-cwd (assoc :cwd new-cwd)
        env-change (assoc :env new-env)))))
