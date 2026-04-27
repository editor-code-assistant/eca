(ns eca.interpolation
  "Expands dynamic placeholder patterns in strings:
  - `${env:SOME-ENV:default-value}`: environment variable with optional default
  - `${file:/some/path}`: file content (relative paths resolved from cwd)
  - `${classpath:path/to/file}`: classpath resource content
  - `${netrc:api.provider.com}`: credential from Unix netrc files
  - `${cmd:some command}`: stdout of an arbitrary shell command

  For `${cmd:...}` on macOS, the inherited PATH for GUI-launched apps
  (Finder/Dock) is minimal, so commands like `pass` and `op` won't resolve.
  We spawn the user's interactive login shell once and capture its `$PATH`
  -- this picks up everything sourced from `.zshrc`, `.zprofile`,
  `.bash_profile`, etc. (Homebrew, mise/asdf shims, custom directories).
  The query result is cached for the process lifetime; on failure we fall
  back to a hardcoded augmentation with Homebrew/user-local paths."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.logger :as logger]
   [eca.secrets :as secrets]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[INTERPOLATION]")

(defn get-env [env] (System/getenv env))

(def ^:private cmd-default-timeout-ms 30000)
(def ^:private shell-query-timeout-ms 5000)
(def ^:private shell-query-delim "__ECA_PATH_DELIM__")

(def ^:private mac-default-path-entries
  ["/opt/homebrew/bin" "/usr/local/bin"])

(def ^:private supported-query-shells
  #{"bash" "zsh" "sh" "dash" "ksh"})

(defn ^:private mac? [^String os-name]
  (boolean (and os-name (string/starts-with? os-name "Mac"))))

(defn ^:private windows? [^String os-name]
  (boolean (and os-name (string/starts-with? os-name "Windows"))))

(defn ^:private posix-shell-name [^String shell-path]
  (some-> shell-path
          (string/replace #".*/" "")
          string/lower-case))

(defn supported-query-shell?
  "True when the shell's basename is a POSIX shell we know how to query
  (bash/zsh/sh/dash/ksh). Other shells (notably fish) use different syntax
  for `$PATH` expansion and are skipped -- we fall back to hardcoded
  augmentation for those users."
  [shell-path]
  (contains? supported-query-shells (posix-shell-name shell-path)))

(defn augment-path
  "Pure helper. On macOS, returns a PATH string with Homebrew, /usr/local/bin
  and `<home>/.local/bin` prepended (only entries not already present in
  `existing-path`). On other OSes returns `existing-path` unchanged."
  [os-name existing-path home path-separator]
  (if (mac? os-name)
    (let [sep (or path-separator ":")
          existing (or existing-path "")
          existing-set (set (string/split existing
                                          (re-pattern (java.util.regex.Pattern/quote sep))))
          extras (cond-> (vec mac-default-path-entries)
                   home (conj (str home "/.local/bin")))
          missing (vec (remove existing-set extras))]
      (cond
        (empty? missing) existing
        (string/blank? existing) (string/join sep missing)
        :else (str (string/join sep missing) sep existing)))
    existing-path))

(defn run-process!
  "Spawns `cmd-vector` via babashka.process with `extra-env` merged into the
  child env, deref'd with `timeout-ms`. Returns {:exit :out :err}.
  On timeout destroys the process tree and throws ex-info.

  Public so tests can `with-redefs` it without spawning real subprocesses."
  [cmd-vector extra-env timeout-ms]
  (let [proc (p/process {:cmd cmd-vector
                         :out :string
                         :err :string
                         :continue true
                         :extra-env extra-env})
        result (deref proc timeout-ms ::timeout)]
    (if (= result ::timeout)
      (do
        (p/destroy-tree proc)
        (throw (ex-info "Command timed out"
                        {:cmd cmd-vector :timeout-ms timeout-ms})))
      result)))

(defn query-user-shell-path*
  "Spawns `shell-path` as `<shell> -ilc 'printf DELIM%sDELIM \"$PATH\"'` (an
  interactive login shell, which sources the user's rc/profile files) and
  extracts the captured PATH from between the delimiters. Returns the PATH
  string or nil on any failure (unsupported shell, non-zero exit, missing
  delimiter, exception/timeout). Empty PATH is treated as failure.

  Public for testing -- the production path goes through `query-user-shell-path`
  which reads `$SHELL` from the environment."
  [shell-path]
  (try
    (when (supported-query-shell? shell-path)
      (let [delim shell-query-delim
            script (str "printf '" delim "%s" delim "' \"$PATH\"")
            {:keys [exit out]} (run-process! [shell-path "-ilc" script]
                                             {}
                                             shell-query-timeout-ms)
            quoted-delim (java.util.regex.Pattern/quote delim)]
        (when (and (zero? exit) (string? out))
          (when-let [[_ path] (re-find (re-pattern (str "(?s)" quoted-delim "(.*?)" quoted-delim))
                                       out)]
            (when (seq path) path)))))
    (catch Exception e
      (logger/debug logger-tag "Shell PATH query failed:" (.getMessage e))
      nil)))

(defn query-user-shell-path
  "Reads `$SHELL` from the environment (defaulting to /bin/bash) and queries it
  for the user's PATH. Returns the PATH string or nil on failure. Public for
  test redefinition."
  []
  (query-user-shell-path* (or (System/getenv "SHELL") "/bin/bash")))

(defonce ^:private user-shell-path-cache* (atom ::unset))

(defn user-shell-path
  "Returns the user's interactive shell PATH or nil. Lazily queried on first
  call and cached for the process lifetime. Both successful and unsuccessful
  results are cached -- we don't retry the shell query on every miss."
  []
  (let [v @user-shell-path-cache*]
    (if (= v ::unset)
      (let [p (query-user-shell-path)]
        (reset! user-shell-path-cache* (or p ::miss))
        p)
      (when-not (= v ::miss) v))))

(defn reset-shell-path-cache!
  "Clears the cached shell PATH. Test helper."
  []
  (reset! user-shell-path-cache* ::unset))

(defn effective-path
  "Returns the PATH to use for the cmd resolver child process.

  On macOS, prefers the user's interactive shell PATH (queried once and cached);
  falls back to augmenting the inherited PATH with hardcoded Homebrew/user-local
  entries when the shell query is unavailable. On other OSes returns
  `existing-path` unchanged."
  [os-name existing-path home sep]
  (if (mac? os-name)
    (or (user-shell-path)
        (augment-path os-name existing-path home sep))
    existing-path))

(defn ^:private build-cmd-vector [^String os-name cmd-string]
  (if (windows? os-name)
    ["powershell.exe" "-NoProfile" "-Command" cmd-string]
    ["bash" "-c" cmd-string]))

(defn resolve-cmd
  "Runs `cmd-string` via the platform shell (bash on POSIX, PowerShell on Windows)
  and returns its stdout with trailing whitespace trimmed.

  On non-zero exit, exception, or timeout: logs a warning and returns \"\" --
  matching the failure mode of the other dynamic string backends so a missing
  secret does not crash config load."
  [cmd-string]
  (try
    (let [os-name (System/getProperty "os.name")
          existing-path (System/getenv "PATH")
          home (System/getProperty "user.home")
          sep (System/getProperty "path.separator")
          new-path (effective-path os-name existing-path home sep)
          extra-env (cond-> {}
                      (and new-path (not= new-path existing-path))
                      (assoc "PATH" new-path))
          {:keys [exit out err]} (run-process! (build-cmd-vector os-name cmd-string)
                                               extra-env
                                               cmd-default-timeout-ms)]
      (if (zero? exit)
        (some-> out string/trimr)
        (do
          (logger/warn logger-tag
                       "Command exited non-zero:"
                       {:cmd cmd-string
                        :exit exit
                        :err (some-> err string/trim)})
          "")))
    (catch Exception e
      (logger/warn logger-tag
                   "Command failed:"
                   {:cmd cmd-string :error (.getMessage e)})
      "")))

(defn replace-dynamic-strings
  "Given a string and a current working directory, look for patterns replacing its content:
  - `${env:SOME-ENV:default-value}`: Replace with a env falling back to a optional default value
  - `${file:/some/path}`: Replace with a file content checking from cwd if relative
  - `${classpath:path/to/file}`: Replace with a file content found checking classpath
  - `${netrc:api.provider.com}`: Replace with the content from Unix net RC [credential files](https://eca.dev/config/models/#credential-file-authentication)
  - `${cmd:some command}`: Replace with the trimmed stdout of an arbitrary shell command"
  [s cwd config]
  (some-> s
          (string/replace #"\$\{env:([^:}]+)(?::([^}]*))?\}"
                          (fn [[_match env-var default-value]]
                            (or (get-env env-var) default-value "")))
          (string/replace #"\$\{file:([^}]+)\}"
                          (fn [[_match file-path]]
                            (try
                              (let [file-path (fs/expand-home file-path)]
                                (slurp (str (if (fs/absolute? file-path)
                                              file-path
                                              (if cwd
                                                (fs/path cwd file-path)
                                                (fs/path file-path))))))
                              (catch Exception _
                                (logger/warn logger-tag "File not found when parsing string:" s)
                                ""))))
          (string/replace #"\$\{classpath:([^}]+)\}"
                          (fn [[_match resource-path]]
                            (try
                              (slurp (io/resource resource-path))
                              (catch Exception e
                                (logger/warn logger-tag "Error reading classpath resource:" (.getMessage e))
                                ""))))
          (string/replace #"\$\{netrc:([^}]+)\}"
                          (fn [[_match key-rc]]
                            (try
                              (or (secrets/get-credential key-rc (get config "netrcFile")) "")
                              (catch Exception e
                                (logger/warn logger-tag "Error reading netrc credential:" (.getMessage e))
                                ""))))
          (string/replace #"\$\{cmd:([^}]+)\}"
                          (fn [[_match cmd-string]]
                            (try
                              (or (resolve-cmd cmd-string) "")
                              (catch Exception e
                                (logger/warn logger-tag "Error executing cmd:" (.getMessage e))
                                ""))))))
