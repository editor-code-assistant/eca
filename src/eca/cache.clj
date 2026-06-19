(ns eca.cache
  "Cache directory and file management utilities."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.digest :as digest]
   [eca.logger :as logger])
  (:import
   [java.io File]))

(set! *warn-on-reflection* true)

(defn ^:private first-valid-home
  "First candidate that is a non-blank, non-\"?\" absolute path, or nil."
  [candidates]
  (some (fn [^String p]
          (when (and (not (string/blank? p))
                     (not= "?" p)
                     (.isAbsolute (io/file p)))
            p))
        candidates))

(defn user-home
  "Resolves the user's home directory defensively.
   In some environments (e.g. a GraalVM native image with an email-style
   username) `user.home` can be blank or the literal \"?\", which would make
   relative paths resolve against the process CWD. Falls back to the HOME /
   USERPROFILE env vars, returning the first candidate that is an absolute path."
  ^String []
  (or (first-valid-home [(System/getProperty "user.home")
                         (System/getenv "HOME")
                         (System/getenv "USERPROFILE")])
      (System/getProperty "user.home")))

(defn global-dir
  "Returns the File object for ECA's global cache directory."
  []
  (let [cache-home (or (System/getenv "XDG_CACHE_HOME")
                       (io/file (user-home) ".cache"))]
    (io/file cache-home "eca")))

(defn ^:private sorted-workspace-paths
  "Absolute workspace paths, de-duplicated and sorted, so the result is stable
   regardless of the order the editor reports its workspace folders."
  [workspaces uri->filename-fn]
  (->> workspaces
       (map #(str (fs/absolutize (fs/file (uri->filename-fn (:uri %))))))
       (distinct)
       (sort)))

(defn workspaces-hash
  "Returns an 8-char base64 (URL-safe, no padding) hash key for the given workspace set.
   Order-independent: the same set of folders always yields the same hash."
  [workspaces uri->filename-fn]
  (let [joined (string/join ":" (sorted-workspace-paths workspaces uri->filename-fn))
        digest-bytes (digest/sha-256-bytes joined)
        encoder (-> (java.util.Base64/getUrlEncoder)
                    (.withoutPadding))
        key (.encodeToString encoder digest-bytes)]
    (subs key 0 (min 8 (count key)))))

(def ^:private logger-tag "[CACHE]")

(def ^:private max-prefix-length 30)

(defn ^:private workspace-dir-name
  "Returns a human-readable directory name for the workspace cache.
   Format: <sanitized-project-name>_<hash>, or just <hash> if no name is available.
   The prefix comes from the sorted-first workspace path so it stays stable
   regardless of folder order; it is purely cosmetic - the <hash> is the identity."
  [workspaces uri->filename-fn]
  (let [hash (workspaces-hash workspaces uri->filename-fn)
        project-name (some-> (first (sorted-workspace-paths workspaces uri->filename-fn))
                             fs/file-name
                             str
                             not-empty)
        sanitized (when project-name
                    (let [s (string/replace project-name #"[^a-zA-Z0-9._-]" "_")]
                      (subs s 0 (min max-prefix-length (count s)))))]
    (if (not-empty sanitized)
      (str sanitized "_" hash)
      hash)))

(defn workspace-cache-file
  "Returns a File object for a workspace-specific cache file.
   The directory identity is the order-independent <hash>; the human-readable
   prefix is cosmetic. Healing of caches fragmented across differently-named
   dirs for the same workspace is handled by eca.db/consolidate-workspace-cache!."
  [workspaces filename uri->filename-fn]
  (io/file (global-dir) (workspace-dir-name workspaces uri->filename-fn) filename))

(defn redundant-workspace-cache-files
  "Returns the cache files named `filename` that live in directories belonging to
   the same workspace set as the canonical dir but under a different name - i.e.
   legacy hash-only dirs, or dirs prefixed from a different folder order. The
   canonical dir is excluded. Used to heal fragmented chat caches."
  [workspaces filename uri->filename-fn]
  (let [hash (workspaces-hash workspaces uri->filename-fn)
        canonical-dir-name (workspace-dir-name workspaces uri->filename-fn)
        base (global-dir)]
    (if (fs/exists? base)
      (->> (fs/list-dir base)
           (filter fs/directory?)
           (map #(str (fs/file-name %)))
           (filter (fn [n] (or (= n hash)
                               (string/ends-with? n (str "_" hash)))))
           (remove #(= % canonical-dir-name))
           (map #(io/file base % filename))
           (filter fs/exists?)
           (vec))
      [])))

(def ^:private tool-call-outputs-dir-name "toolCallOutputs")
(def ^:private plugins-dir-name "plugins")

(defn tool-call-outputs-dir
  "Returns the File object for the tool call outputs cache directory."
  ^File []
  (io/file (global-dir) tool-call-outputs-dir-name))

(defn plugins-dir
  "Returns the base directory for caching cloned plugin sources."
  ^java.io.File []
  (io/file (global-dir) plugins-dir-name))

(defn save-tool-call-output!
  "Saves the full tool call output text to a cache file.
   Returns the absolute path of the saved file as a string."
  ^String [^String tool-call-id ^String text]
  (let [dir (tool-call-outputs-dir)
        file (io/file dir (str tool-call-id ".txt"))]
    (io/make-parents file)
    (spit file text)
    (str (.getAbsolutePath file))))

(defn cleanup-tool-call-outputs!
  "Deletes tool call output cache files older than retention-days.
   When retention-days is non-positive, cleanup is disabled.
   Runs silently, logging warnings on errors."
  [retention-days]
  (when (pos? retention-days)
    (try
      (let [dir (tool-call-outputs-dir)
            retention-ms (* retention-days 24 60 60 1000)]
        (when (fs/exists? dir)
          (let [now (System/currentTimeMillis)
                files (fs/list-dir dir)]
            (doseq [f files]
              (try
                (let [last-modified (.lastModified ^File (fs/file f))]
                  (when (> (- now last-modified) retention-ms)
                    (fs/delete f)))
                (catch Exception e
                  (logger/warn logger-tag "Failed to delete old tool call output file:" (str f) (.getMessage ^Exception e))))))))
      (catch Exception e
        (logger/warn logger-tag "Failed to cleanup tool call outputs directory:" (.getMessage ^Exception e))))))
