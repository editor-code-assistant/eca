(ns eca.features.plugins.git
  "Durable source pins and disposable, commit-specific plugin snapshots."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [eca.cache :as cache]
   [eca.digest :as digest]
   [eca.shared :as shared])
  (:import
   [java.io File FileOutputStream RandomAccessFile]
   [java.lang ProcessHandle]
   [java.nio.charset StandardCharsets]
   [java.nio.file CopyOption Files FileVisitResult LinkOption NoSuchFileException Path SimpleFileVisitor StandardCopyOption]
   [java.nio.file.attribute BasicFileAttributes FileAttribute]
   [java.util.concurrent ConcurrentHashMap TimeUnit]))

(set! *warn-on-reflection* true)

(def ^:private git-timeout-ms 30000)
(defonce ^:private ^ConcurrentHashMap source-locks (ConcurrentHashMap.))

(defn ^:private source-lock ^Object [^File lock-file]
  (let [key (.getCanonicalPath lock-file)]
    (or (.get source-locks key)
        (let [monitor (Object.)]
          (or (.putIfAbsent source-locks key monitor) monitor)))))

(defn ^:private attributes ^BasicFileAttributes [^File file]
  (try
    (Files/readAttributes (.toPath file) BasicFileAttributes
                          ^"[Ljava.nio.file.LinkOption;"
                          (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
    (catch NoSuchFileException _ nil)))

(defn ^:private valid-oid? [oid]
  (and (string? oid) (boolean (re-matches #"(?i)(?:[0-9a-f]{40}|[0-9a-f]{64})" oid))))

(defn ^:private read-json! [^File file]
  (with-open [reader (io/reader file :encoding "UTF-8")]
    (let [values (doall (take 2 (json/parsed-seq reader true)))]
      (when-not (= 1 (count values))
        (throw (ex-info "Expected one JSON document" {:path (str file)})))
      (first values))))

(defn ^:private read-pin! [source-url ^File pin-file]
  (when-let [attrs (attributes pin-file)]
    (when-not (.isRegularFile attrs)
      (throw (ex-info "Plugin pin must be a regular file" {:path (str pin-file)})))
    (let [pin (read-json! pin-file)]
      (when-not (and (map? pin)
                     (= 1 (:version pin))
                     (= source-url (:source pin))
                     (valid-oid? (:commit pin)))
        (throw (ex-info "Invalid plugin source pin" {:path (str pin-file)})))
      (update pin :commit string/lower-case))))

(defn ^:private atomic-move! [^File from ^File to]
  (Files/move (.toPath from) (.toPath to)
              (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                      StandardCopyOption/REPLACE_EXISTING])))

(defn ^:private write-pin! [source-url commit ^File pin-file]
  (let [temp (.toFile (Files/createTempFile
                      (.toPath (.getParentFile pin-file))
                      (str (.getName pin-file) ".") ".tmp"
                      (make-array FileAttribute 0)))]
    (try
      (with-open [out (FileOutputStream. temp)]
        (.write out (.getBytes ^String (json/generate-string
                                       {:version 1 :source source-url :commit commit})
                              StandardCharsets/UTF_8))
        (.sync (.getFD out)))
      (atomic-move! temp pin-file)
      (finally
        (Files/deleteIfExists (.toPath temp))))))

(defn ^:private git-diagnostic [text]
  (let [redacted (-> (str (or text ""))
                     (string/replace #"(?i)[a-z][a-z0-9+.-]*://[^\s<>\"']+" "[redacted-url]")
                     (string/replace #"[^\s<>\"']+@[^\s<>\"']+" "[redacted-address]")
                     (string/replace #"(?im)[^\r\n]*(?:authorization|extraheader|cookie)[^\r\n]*" "[redacted-auth]")
                     (string/replace #"(?im)\b[^\s=:]*(?:token|password|passwd|secret|credential|api[-_]?key|access[-_]?key)[^\s=:]*[\"']?\s*[=:][^\r\n]*" "[redacted-credential]")
                     (string/replace #"(?i)\b(?:bearer|basic)\s+[^\s\"']+" "[redacted-auth]")
                     (string/replace #"\b(?:gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+|glpat-[A-Za-z0-9_-]+|eyJ[A-Za-z0-9_.-]+)" "[redacted-token]")
                     (string/replace #"[\p{Cntrl}\s]+" " ")
                     string/trim)]
    (subs redacted 0 (min 500 (count redacted)))))

(defn ^:private with-source-lock! [source-url f]
  (try
    (when-not (and (string? source-url) (not (string/blank? source-url)))
      (throw (ex-info "Plugin source URL must be a nonempty string" {})))
    (let [source-id (digest/sha-256-hex source-url)
          lock-dir (io/file (shared/global-config-dir) "plugin-locks")
          pin-file (io/file lock-dir (str source-id ".json"))
          lock-file (io/file lock-dir (str source-id ".lock"))]
      #_{:clj-kondo/ignore [:locking-suspicious-lock]}
      (locking (source-lock lock-file)
        (fs/create-dirs lock-dir)
        (with-open [raf (RandomAccessFile. lock-file "rw")
                    channel (.getChannel raf)
                    _lock (.lock channel)]
          (f pin-file (io/file (cache/plugins-dir) "snapshots" source-id)))))
    (catch Exception e
      (throw (ex-info (str "Could not resolve plugin Git source: " (ex-message e))
                      (assoc (ex-data e) :source (git-diagnostic source-url)) e)))))

(defn ^:private stop-process! [proc]
  (let [^Process process (:proc proc)
        descendants (with-open [stream (.descendants process)]
                      (vec (iterator-seq (.iterator stream))))]
    (p/destroy-tree proc)
    (doseq [^ProcessHandle child descendants]
      (when (.isAlive child)
        (.destroyForcibly child)))
    (when (.isAlive process)
      (.destroyForcibly process))
    (.waitFor process 1000 TimeUnit/MILLISECONDS)))

(defn ^:private run-git! [dir & args]
  (let [env (apply dissoc (into {} (System/getenv))
                   ["GIT_DIR" "GIT_WORK_TREE" "GIT_COMMON_DIR" "GIT_INDEX_FILE"
                    "GIT_OBJECT_DIRECTORY" "GIT_ALTERNATE_OBJECT_DIRECTORIES"
                    "GIT_NAMESPACE" "GIT_CONFIG_COUNT" "GIT_CONFIG_PARAMETERS"])
        proc (p/process (into ["git" "--no-replace-objects"
                               "-c" "core.fsmonitor=false"
                               "-c" "core.untrackedCache=false"
                               "-c" "core.hooksPath=/dev/null"
                               "-c" "maintenance.auto=false"
                               "-c" "gc.auto=0"] args)
                        {:dir (str dir) :out :string :err :string
                         :env (assoc env "GIT_TERMINAL_PROMPT" "0"
                                         "GIT_NO_REPLACE_OBJECTS" "1"
                                         "GIT_NO_LAZY_FETCH" "1"
                                         "GIT_OPTIONAL_LOCKS" "0")})]
    (try
      (let [result (deref proc git-timeout-ms nil)]
        (when-not result
          (stop-process! proc)
          (throw (ex-info "Plugin Git operation timed out" {:timeout-ms git-timeout-ms})))
        (when-not (zero? (:exit result))
          (let [diagnostic (git-diagnostic (:err result))]
            (throw (ex-info (str "Plugin Git operation failed (exit " (:exit result) ")"
                                 (when (seq diagnostic) (str ": " diagnostic)))
                            {:exit (:exit result) :err diagnostic}))))
        (:out result))
      (finally
        (when (.isAlive ^Process (:proc proc))
          (stop-process! proc))))))

(defn ^:private head-commit! [dir]
  (let [commit (string/trim (run-git! dir "rev-parse" "--verify" "--end-of-options" "HEAD^{commit}"))]
    (when-not (valid-oid? commit)
      (throw (ex-info "Git returned an invalid plugin commit" {:dir (str dir)})))
    commit))

(defn ^:private verify-repository! [^File dir]
  (let [attrs (attributes dir)
        git-attrs (attributes (io/file dir ".git"))]
    (when-not (and attrs (.isDirectory attrs) git-attrs (.isDirectory git-attrs))
      (throw (ex-info "Plugin cache must be a standalone Git working tree" {:dir (str dir)}))))
  (when-not (= (.getCanonicalFile dir)
               (.getCanonicalFile (io/file (string/trim (run-git! dir "rev-parse" "--show-toplevel")))))
    (throw (ex-info "Plugin cache working tree root mismatch" {:dir (str dir)}))))

(defn ^:private verify-clean! [dir]
  (when (some #(and (seq %) (or (= \S (first %)) (Character/isLowerCase ^char (first %))))
              (string/split (run-git! dir "ls-files" "-v" "-z") #"\u0000"))
    (throw (ex-info "Plugin cache has hidden index entries" {:dir (str dir)})))
  (when-not (empty? (run-git! dir "status" "--porcelain=v1" "-z" "--untracked-files=all"
                             "--ignored" "--ignore-submodules=none"))
    (throw (ex-info "Plugin cache has modified, untracked, or ignored files" {:dir (str dir)}))))

(defn ^:private contained-path!
  "Resolve each path component within root, including intermediate symlink targets.
   Only marketplace source directories may be missing; symlink targets must exist."
  ^Path [^Path root ^Path path allow-missing?]
  (letfn [(resolve-path! ^Path [^Path path missing? links]
            (when-not (.startsWith path root)
              (throw (ex-info "Plugin discovery path escapes its snapshot" {:path (str path)})))
            (loop [current root
                   parts (seq (drop (.getNameCount root) path))]
              (if-let [^Path part (first parts)]
                (let [candidate (.normalize (.resolve ^Path current part))]
                  (when (or (= ".git" (string/lower-case (str part)))
                            (not (.startsWith candidate root)))
                    (throw (ex-info "Plugin discovery path escapes its snapshot or targets .git"
                                    {:path (str path)})))
                  (let [attrs (attributes (.toFile candidate))]
                    (when (and (nil? attrs) (not missing?))
                      (throw (ex-info "Plugin snapshot has a dangling symlink" {:path (str path)})))
                    (recur (if (and attrs (.isSymbolicLink attrs))
                             (do
                               (when (or (contains? links candidate) (>= (count links) 40))
                                 (throw (ex-info "Plugin snapshot has a cyclic or excessive symlink chain"
                                                 {:path (str candidate)})))
                               (resolve-path! (.resolve (.getParent candidate)
                                                        (Files/readSymbolicLink candidate))
                                              false (conj links candidate)))
                             candidate)
                           (next parts))))
                current)))]
    (resolve-path! path allow-missing? #{})))

(defn ^:private verify-containment! [^File dir]
  (let [root (.toRealPath (.toPath dir) (make-array LinkOption 0))
        git-dir (.resolve root ".git")]
    (Files/walkFileTree
     root
     (proxy [SimpleFileVisitor] []
       (preVisitDirectory [path _attrs]
         (if (= git-dir path) FileVisitResult/SKIP_SUBTREE FileVisitResult/CONTINUE))
       (visitFile [path attrs]
         (when (.isSymbolicLink ^BasicFileAttributes attrs)
           (when (= root (contained-path! root path false))
             (throw (ex-info "Plugin snapshot symlink exposes the root .git directory"
                             {:path (str path)}))))
         FileVisitResult/CONTINUE)))
    root))

(defn ^:private validate-marketplace! [^Path root]
  (let [dir (.toFile root)
        marketplace (read-json! (io/file dir ".eca-plugin" "marketplace.json"))]
    (when-not (and (map? marketplace) (vector? (:plugins marketplace))
                   (every? map? (:plugins marketplace)))
      (throw (ex-info "Invalid plugin marketplace manifest" {:dir (str dir)})))
    (doseq [entry (:plugins marketplace)
            relative-path (keep entry [:source :path])]
      (when-not (and (string? relative-path) (not (string/blank? relative-path)))
        (throw (ex-info "Plugin marketplace source/path must be a nonempty string" {})))
      (let [path (.toPath (io/file relative-path))]
        (when (.isAbsolute path)
          (throw (ex-info "Plugin marketplace source/path must be relative" {:path relative-path})))
        (contained-path! root (.resolve root path) true)))
    true))

(defn ^:private verify-snapshot! [^File dir commit]
  (verify-repository! dir)
  (when-not (= commit (head-commit! dir))
    (throw (ex-info "Plugin snapshot does not match its pinned commit"
                    {:dir (str dir) :commit commit})))
  (when-not (= commit (string/trim (slurp (io/file dir ".git" "HEAD"))))
    (throw (ex-info "Plugin snapshot HEAD must be detached" {:dir (str dir)})))
  (when (attributes (io/file dir ".git" "objects" "info" "alternates"))
    (throw (ex-info "Plugin snapshot must not use alternate object stores" {:dir (str dir)})))
  (verify-clean! dir)
  (validate-marketplace! (verify-containment! dir))
  {:dir dir :commit commit})

(defn ^:private checkout! [dir commit]
  (when-not (valid-oid? commit)
    (throw (ex-info "Invalid plugin checkout commit" {:commit commit})))
  (run-git! dir "checkout" "--detach" "--force" commit "--")
  (verify-snapshot! dir commit))

(defn ^:private clone-head! [^File stage source]
  (run-git! (.getParentFile stage) "clone" "--depth=1" "--no-local" "--no-checkout" "--template="
            "--" source (str stage))
  (head-commit! stage))

(defn ^:private fetch-pinned! [^File stage source-url commit]
  (run-git! stage "init" "--template=" (str "--object-format=" (if (= 64 (count commit)) "sha256" "sha1")))
  (run-git! stage "fetch" "--depth=1" "--no-tags" "--" source-url commit)
  (when-not (= commit (string/trim (run-git! stage "rev-parse" "--verify" "--end-of-options" "FETCH_HEAD^{commit}")))
    (throw (ex-info "Fetched plugin commit does not match its pin" {:commit commit})))
  commit)

(defn ^:private with-stage! [^File snapshot-root f]
  (fs/create-dirs snapshot-root)
  (let [stage (.toFile (Files/createTempDirectory (.toPath snapshot-root) ".stage-"
                                                 (make-array FileAttribute 0)))]
    (try
      (f stage)
      (finally
        (when (attributes stage)
          (fs/delete-tree stage))))))

(defn ^:private publish-snapshot! [^File snapshot-root ^File stage commit]
  (let [dir (io/file snapshot-root commit)]
    (if (attributes dir)
      (let [snapshot (verify-snapshot! dir commit)]
        (fs/delete-tree stage)
        snapshot)
      (do
        (atomic-move! stage dir)
        (try
          (verify-snapshot! dir commit)
          (catch Exception e
            (fs/delete-tree dir)
            (throw e)))))))

(defn ^:private legacy-commit! [source-url ^File legacy-dir]
  (verify-repository! legacy-dir)
  (when-not (= (str source-url "\n") (run-git! legacy-dir "config" "--local" "--get-all" "remote.origin.url"))
    (throw (ex-info "Legacy plugin cache origin does not match its source"
                    {:dir (str legacy-dir)})))
  (verify-clean! legacy-dir)
  (head-commit! legacy-dir))

(defn resolve!
  "Return {:dir File :commit full-oid} without advancing an existing durable pin.
   A clean legacy cache is frozen at its current HEAD, without pulling. Errors
   never fall back to remote HEAD. A present snapshot requires no network."
  [source-url legacy-cache-dir]
  (with-source-lock!
    source-url
    (fn [pin-file snapshot-root]
      (if-let [{:keys [commit]} (read-pin! source-url pin-file)]
        (let [dir (io/file snapshot-root commit)]
          (if (attributes dir)
            (verify-snapshot! dir commit)
            (with-stage! snapshot-root
              (fn [stage]
                (fetch-pinned! stage source-url commit)
                (checkout! stage commit)
                (publish-snapshot! snapshot-root stage commit)))))
        (let [legacy-dir (some-> legacy-cache-dir io/file)
              legacy-commit (when (and legacy-dir (attributes legacy-dir))
                              (legacy-commit! source-url legacy-dir))]
          (with-stage! snapshot-root
            (fn [stage]
              (let [cloned-commit (clone-head! stage (if legacy-commit
                                                     (.getAbsolutePath ^File legacy-dir)
                                                     source-url))
                    commit (or legacy-commit cloned-commit)]
                (checkout! stage commit)
                (let [snapshot (publish-snapshot! snapshot-root stage commit)]
                  (write-pin! source-url commit pin-file)
                  snapshot)))))))))

(defn update!
  "Fetch a separate candidate and validate it before advancing the durable pin.
   validate-fn receives its directory; false/nil or an exception aborts the update.
   Return {:dir File :commit full-oid :previous-commit full-oid-or-nil}."
  [source-url validate-fn]
  (with-source-lock!
    source-url
    (fn [pin-file snapshot-root]
      (let [previous-commit (:commit (read-pin! source-url pin-file))]
        (with-stage! snapshot-root
          (fn [stage]
            (let [commit (clone-head! stage source-url)]
              (checkout! stage commit)
              (when-not (validate-fn stage)
                (throw (ex-info "Plugin update candidate validation failed" {:commit commit})))
              (let [snapshot (publish-snapshot! snapshot-root stage commit)]
                (write-pin! source-url commit pin-file)
                (assoc snapshot :previous-commit previous-commit)))))))))
