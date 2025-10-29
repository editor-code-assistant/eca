(ns eca.secrets
  (:require
   [br.dev.zz.parc :as parc]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.logger :as logger]
   [eca.shared :as shared])
  (:import
   [java.io File StringReader]
   [java.nio.file Files LinkOption]
   [java.nio.file.attribute PosixFilePermission]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[secrets]")

(defn ^:private normalize-port
  [port]
  (cond
    (nil? port) nil
    (string? port) (let [value (string/trim port)]
                     (when (seq value) value))
    :else (str port)))

(defn credential-file-paths
  "Returns ordered list of credential file paths to check."
  [netrc-file]
  (if (some? netrc-file)
    [netrc-file]
    (let [home (System/getProperty "user.home")
          files [".netrc" "_netrc"]]
      (->> files
           (mapv #(str (io/file home %)))))))

(defn ^:private validate-permissions [^File file & {:keys [warn?] :or {warn? false}}]
  (try
    ;; On Unix systems, check if file is readable by others
    (when-not (string/includes? (System/getProperty "os.name") "Windows")
      (let [path (.toPath file)
            perms (Files/getPosixFilePermissions path (into-array LinkOption []))]
        (if (or (contains? perms PosixFilePermission/GROUP_READ)
                (contains? perms PosixFilePermission/OTHERS_READ))
          (do
            (when warn?
              (logger/warn logger-tag "Credential file has insecure permissions:"
                           (.getPath file)
                           "- should be 0600 (readable only by owner)"))
            false)
          true)))
    true
    (catch Exception _e
      ;; If permission check fails (e.g., on Windows), just proceed
      true)))

(defn ^:private parse-credentials [content]
  (let [entries (parc/->netrc (StringReader. (or content "")))]
    (map (fn [entry]
           (cond-> entry
             (contains? entry :port)
             (update :port normalize-port)))
         entries)))

(defn ^:private load-credentials-from-file* [^String file-path]
  (try
    (logger/debug logger-tag "Checking credential file:" file-path)
    (let [file (io/file file-path)]
      (when (and (.exists file) (.canRead file))
        (validate-permissions file :warn? true)
        (let [content (slurp file)]
          (when (seq content)
            (when-let [credentials (seq (parse-credentials content))]
              (logger/debug logger-tag "Loaded" (count credentials) "credentials from" file-path)
              (vec credentials))))))
    (catch Exception e
      (logger/warn logger-tag "Failed to load credentials from" file-path ":" (.getMessage e))
      nil)))

(def ^:private load-credentials-from-file
  (shared/memoize-by-file-last-modified load-credentials-from-file*))

(defn ^:private load-all-credentials [netrc-file]
  (vec (mapcat #(or (load-credentials-from-file %) [])
               (credential-file-paths netrc-file))))

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
  ([key-rc]
   (get-credential key-rc nil))
  ([key-rc netrc-file]
   (when-let [spec (parse-key-rc key-rc)]
     (when-let [credentials (load-all-credentials netrc-file)]
       (when-let [matched (first (filter #(match-credential % spec) credentials))]
         (logger/debug logger-tag "Found credential for" key-rc)
         (:password matched))))))

(defn check-credential-files
  "Performs diagnostic checks on credential files for /doctor command.
   Returns a map with:
   - :files - vector of file check results, each containing:
     - :path - file path
     - :exists - boolean
     - :readable - boolean (if exists)
     - :permissions-secure - boolean (Unix only, if exists)
     - :credentials-count - number of valid credentials (if readable)
     - :parse-error - error message (if parse fails)"
  [netrc-file]
  (let [file-paths (credential-file-paths netrc-file)
        file-checks (for [path file-paths]
                      (let [file (io/file path)
                            exists (.exists file)
                            readable (.canRead file)]
                        (cond-> {:path path
                                 :exists exists}
                          ;; Check readability
                          exists (assoc :readable readable)

                          ;; Check permissions (Unix only, plaintext only)
                          readable
                          (assoc :permissions-secure
                                 (validate-permissions file))

                          ;; Try to parse and count credentials
                          readable
                          (merge
                           (try
                             (let [credentials (load-credentials-from-file path)]
                               {:credentials-count (count credentials)})
                             (catch Exception e
                               {:parse-error (.getMessage e)}))))))]
    {:files (vec file-checks)}))
