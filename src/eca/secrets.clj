(ns eca.secrets
  (:require
   [br.dev.zz.parc :as parc]
   [clojure.java.io :as io]
   [clojure.java.process :as process]
   [clojure.string :as string]
   [eca.logger :as logger]
   [eca.shared :as shared])
  (:import
   [java.io File StringReader]
   [java.nio.file Files LinkOption]
   [java.nio.file.attribute PosixFilePermission]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[secrets]")

(defn ^:private netrc-path? [path]
  (string/includes? (string/lower-case (.getName (io/file path))) "netrc"))

(defn ^:private normalize-port
  [port]
  (cond
    (nil? port) nil
    (string? port) (let [value (string/trim port)]
                     (when (seq value) value))
    :else (str port)))

(defn credential-file-paths
  "Returns ordered list of credential file paths to check."
  []
  (let [home (System/getProperty "user.home")
        files [".authinfo" "_authinfo" ".netrc" "_netrc"]]
    (mapcat (fn [filename]
              (let [path (str (io/file home filename))]
                [(str path ".gpg") path]))
            files)))

(defn ^:private validate-permissions [^File file]
  (try
    ;; On Unix systems, check if file is readable by others
    (when-not (string/includes? (System/getProperty "os.name") "Windows")
      (let [path (.toPath file)
            perms (Files/getPosixFilePermissions path (into-array LinkOption []))]
        (if (or (contains? perms PosixFilePermission/GROUP_READ)
                (contains? perms PosixFilePermission/OTHERS_READ))
          (do
            (logger/warn logger-tag "Credential file has insecure permissions:"
                         (.getPath file)
                         "- should be 0600 (readable only by owner)")
            false)
          true)))
    true
    (catch Exception _e
      ;; If permission check fails (e.g., on Windows), just proceed
      true)))

(defn gpg-available?
  "Checks if gpg command is available on system."
  []
  (try (string? (process/exec "gpg" "--version"))
       (catch Exception _e
         false)))

;; Cache for GPG decryption results (5-second TTL)
(def ^:private gpg-cache (atom {}))

(defn ^:private gpg-cache-key [^File file]
  (str (.getPath file) ":" (.lastModified file)))

(defn ^:private get-cached-gpg [cache-key]
  (when-let [{:keys [content timestamp]} (@gpg-cache cache-key)]
    (when (< (- (System/currentTimeMillis) timestamp) 5000) ; 5-second TTL
      (logger/debug logger-tag "GPG cache hit for" cache-key)
      content)))

(defn ^:private cache-gpg-result! [cache-key content]
  (swap! gpg-cache assoc cache-key {:content content
                                    :timestamp (System/currentTimeMillis)})
  content)

(defn decrypt-gpg
  "Decrypts a .gpg file using gpg command. Returns decrypted content or nil on failure.
   Timeout: 30 seconds (configurable via GPG_TIMEOUT env var)."
  [file-path]
  (try
    (let [file (io/file file-path)
          cache-key (gpg-cache-key file)]
      ;; Check cache first
      (or (get-cached-gpg cache-key)
          ;; Not in cache, decrypt using clojure.java.process directly
          (let [timeout-seconds (try
                                  (Long/parseLong (System/getenv "GPG_TIMEOUT"))
                                  (catch Exception _e 30)) ; default 30 seconds
                timeout-ms (* timeout-seconds 1000)
                proc (process/start {:out :pipe :err :pipe}
                                    "gpg" "--quiet" "--batch" "--decrypt" file-path)
                exit (deref (process/exit-ref proc) timeout-ms ::timeout)]
            (if (= exit ::timeout)
              (do
                (.destroy proc)
                (logger/warn logger-tag "GPG decryption timed out after" timeout-seconds "seconds for" file-path)
                nil)
              (let [out (slurp (process/stdout proc))
                    err (slurp (process/stderr proc))]
                (if (zero? exit)
                  (do
                    (logger/debug logger-tag "GPG decryption successful for" file-path)
                    (cache-gpg-result! cache-key out))
                  (do
                    (logger/warn logger-tag "GPG decryption failed for" file-path
                                 "- exit code:" exit)
                    (when-not (string/blank? err)
                      (logger/debug logger-tag "GPG error:" err))
                    nil)))))))
    (catch Exception e
      (logger/warn logger-tag "GPG command failed for" file-path ":" (.getMessage e))
      nil)))

(defn ^:private parse-credentials [path content]
  (let [entries (parc/->netrc (StringReader. (or content "")))]
    (cond->> entries
      (netrc-path? path)
      (map (fn [entry]
             (cond-> entry
               (contains? entry :port)
               (update :port normalize-port)))))))

(defn ^:private load-credentials-from-file* [^String file-path]
  (try
    (logger/debug logger-tag "Checking credential file:" file-path)
    (let [file (io/file file-path)]
      (when (and (.exists file) (.isFile file) (.canRead file))
        (let [content (if (string/ends-with? file-path ".gpg")
                        (when (gpg-available?) (decrypt-gpg file-path))
                        (do (validate-permissions file)
                            (slurp file)))]
          (when (seq content)
            (when-let [credentials (seq (parse-credentials file-path content))]
              (logger/debug logger-tag "Loaded" (count credentials) "credentials from" file-path)
              (vec credentials))))))
    (catch Exception e
      (logger/warn logger-tag "Failed to load credentials from" file-path ":" (.getMessage e))
      nil)))

(def ^:private load-credentials-from-file
  (shared/memoize-by-file-last-modified load-credentials-from-file*))

(defn ^:private load-all-credentials []
  (vec (mapcat #(or (load-credentials-from-file %) [])
               (credential-file-paths))))

(defn parse-key-rc
  "Parses keyRc value in format [login@]machine[:port].
   Returns map with :machine, :login, and :port keys."
  [key-rc]
  (when-not (string/blank? key-rc)
    (let [;; Split on @ to separate login from machine:port
          [login-part machine-port] (if (string/includes? key-rc "@")
                                      (string/split key-rc #"@" 2)
                                      [nil key-rc])
          ;; Split machine:port on :
          [machine port] (if (string/includes? machine-port ":")
                           (string/split machine-port #":" 2)
                           [machine-port nil])]
      {:machine machine
       :login login-part
       :port port})))

(defn ^:private match-credential [credential {:keys [machine login port]}]
  (let [cred-port (:port credential)
        spec-port (normalize-port port)]
    (and (= (:machine credential) machine)
         (or (nil? login)
             (= (:login credential) login))
         (= (normalize-port cred-port) spec-port))))

(defn get-credential
  "Retrieves password for keyRc specifier from credential files.
   Format: [login@]machine[:port]
   Returns password string or nil if not found."
  [key-rc]
  (when-let [spec (parse-key-rc key-rc)]
    (when-let [credentials (load-all-credentials)]
      (when-let [matched (first (filter #(match-credential % spec) credentials))]
        (logger/debug logger-tag "Found credential for" key-rc)
        (:password matched)))))

(defn check-credential-files
  "Performs diagnostic checks on credential files for /doctor command.
   Returns a map with:
   - :gpg-available - boolean indicating if GPG is available
   - :files - vector of file check results, each containing:
     - :path - file path
     - :exists - boolean
     - :readable - boolean (if exists)
     - :permissions-secure - boolean (Unix only, if exists)
     - :is-gpg - boolean
     - :credentials-count - number of valid credentials (if readable)
     - :parse-error - error message (if parse fails)
     - :suggestion - optional security suggestion"
  []
  (let [gpg-avail? (gpg-available?)
        file-paths (credential-file-paths)
        file-checks (for [path file-paths]
                      (let [file (io/file path)
                            exists (.exists file)
                            is-file (and exists (.isFile file))
                            readable (and is-file (.canRead file))
                            is-gpg (or (string/ends-with? path ".authinfo.gpg")
                                       (string/ends-with? path ".netrc.gpg"))
                            is-plaintext (and (not is-gpg)
                                              (or (string/ends-with? path ".authinfo")
                                                  (string/ends-with? path "_authinfo")
                                                  (string/ends-with? path ".netrc")
                                                  (string/ends-with? path "_netrc")))]
                        (cond-> {:path path
                                 :exists exists
                                 :is-gpg is-gpg}
                          ;; Check readability
                          exists (assoc :readable readable)

                          ;; Check permissions (Unix only, plaintext only)
                          (and is-plaintext readable)
                          (assoc :permissions-secure
                                 (try
                                   (when-not (string/includes? (System/getProperty "os.name") "Windows")
                                     (let [file-path (.toPath file)
                                           perms (Files/getPosixFilePermissions
                                                  file-path
                                                  (into-array LinkOption []))]
                                       (not (or (contains? perms PosixFilePermission/GROUP_READ)
                                                (contains? perms PosixFilePermission/OTHERS_READ)))))
                                   (catch Exception _e
                                     true))) ; On Windows or permission check failure, consider secure

                          ;; Add GPG suggestion for plaintext files
                          (and is-plaintext readable)
                          (assoc :suggestion
                                 (str "Consider encrypting with GPG: "
                                      "gpg --output "
                                      (if (string/ends-with? path ".netrc")
                                        "~/.netrc.gpg"
                                        "~/.authinfo.gpg")
                                      " --symmetric " path))

                          ;; Try to parse and count credentials
                          readable
                          (merge
                           (try
                             (let [credentials (load-credentials-from-file path)]
                               {:credentials-count (count credentials)})
                             (catch Exception e
                               {:parse-error (.getMessage e)}))))))]
    {:gpg-available gpg-avail?
     :files (vec file-checks)}))
