(ns eca.secrets
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.logger :as logger]
   [eca.secrets.netrc :as secrets.netrc]
   [eca.secrets.authinfo :as secrets.authinfo])
  (:import
   [java.io File]
   [java.nio.file Files LinkOption]
   [java.nio.file.attribute PosixFilePermission]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[secrets]")

(defn- home-dir
  "Returns the user's home directory path."
  []
  (System/getProperty "user.home"))

(defn credential-file-paths
  "Returns ordered list of credential file paths to check."
  []
  (let [home (home-dir)]
    [(str home "/.authinfo.gpg")
     (str home "/.authinfo")
     (str home "/.netrc")
     (str home "/_netrc")]))

(defn- file-exists?
  "Checks if a file exists and is readable."
  [^String path]
  (let [file (io/file path)]
    (and (.exists file)
         (.isFile file)
         (.canRead file))))

(defn- validate-permissions
  "Checks if file has secure permissions (Unix only).
   Returns true if secure, false otherwise.
   Logs warning if permissions are too open."
  [^File file]
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

(defn- load-credentials-from-file
  "Loads and parses credentials from a file.
   Returns vector of credential maps or nil on error."
  [^String file-path]
  (try
    (logger/debug logger-tag "Checking credential file:" file-path)
    (when (file-exists? file-path)
      ;; Skip .authinfo.gpg files for now (Phase 1 doesn't include GPG decryption)
      (when-not (string/ends-with? file-path ".authinfo.gpg")
        (let [file (io/file file-path)
              _ (validate-permissions file)
              content (slurp file)
              ;; Determine format based on filename
              parser (cond
                       (string/ends-with? file-path ".netrc") secrets.netrc/parse
                       (string/ends-with? file-path "_netrc") secrets.netrc/parse
                       (string/ends-with? file-path ".authinfo") secrets.authinfo/parse
                       :else secrets.authinfo/parse) ; default to authinfo
              credentials (parser content)]
          (when (seq credentials)
            (logger/debug logger-tag "Loaded" (count credentials) "credentials from" file-path)
            credentials))))
    (catch Exception e
      (logger/warn logger-tag "Failed to load credentials from" file-path ":" (.getMessage e))
      nil)))

(defn- load-all-credentials
  "Loads credentials from all available credential files.
   Returns vector of credential maps from first available file."
  []
  (some load-credentials-from-file (credential-file-paths)))

(defn parse-key-netrc
  "Parses keyNetrc value in format [login@]machine[:port].
   Returns map with :machine, :login, and :port keys."
  [key-netrc]
  (when-not (string/blank? key-netrc)
    (let [;; Split on @ to separate login from machine:port
          [login-part machine-port] (if (string/includes? key-netrc "@")
                                       (string/split key-netrc #"@" 2)
                                       [nil key-netrc])
          ;; Split machine:port on :
          [machine port] (if (string/includes? machine-port ":")
                          (string/split machine-port #":" 2)
                          [machine-port nil])]
      {:machine machine
       :login login-part
       :port port})))

(defn- match-credential
  "Matches a credential entry against parsed keyNetrc spec.
   Returns true if the credential matches the spec."
  [credential {:keys [machine login port]}]
  (and
   ;; Machine must match
   (= (:machine credential) machine)
   ;; If login specified, it must match
   (or (nil? login)
       (= (:login credential) login))
   ;; Port must match exactly (both nil or both equal)
   (= (:port credential) port)))

(defn get-credential
  "Retrieves password for keyNetrc specifier from credential files.
   Format: [login@]machine[:port]
   Returns password string or nil if not found."
  [key-netrc]
  (when-let [spec (parse-key-netrc key-netrc)]
    (when-let [credentials (load-all-credentials)]
      (when-let [matched (first (filter #(match-credential % spec) credentials))]
        (logger/debug logger-tag "Found credential for" key-netrc)
        (:password matched)))))
