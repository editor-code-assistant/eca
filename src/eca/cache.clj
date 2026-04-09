(ns eca.cache
  "Cache directory and file management utilities."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.logger :as logger])
  (:import
   [java.io File]))

(set! *warn-on-reflection* true)

(defn global-dir
  "Returns the File object for ECA's global cache directory."
  []
  (let [cache-home (or (System/getenv "XDG_CACHE_HOME")
                       (io/file (System/getProperty "user.home") ".cache"))]
    (io/file cache-home "eca")))

(defn workspaces-hash
  "Returns an 8-char base64 (URL-safe, no padding) hash key for the given workspace set."
  [workspaces uri->filename-fn]
  (let [paths (->> workspaces
                   (map #(str (fs/absolutize (fs/file (uri->filename-fn (:uri %))))))
                   (distinct)
                   (sort))
        joined (string/join ":" paths)
        md (java.security.MessageDigest/getInstance "SHA-256")
        digest (.digest (doto md (.update (.getBytes joined "UTF-8"))))
        encoder (-> (java.util.Base64/getUrlEncoder)
                    (.withoutPadding))
        key (.encodeToString encoder digest)]
    (subs key 0 (min 8 (count key)))))

(def ^:private logger-tag "[CACHE]")

(def ^:private max-prefix-length 30)

(defn ^:private workspace-dir-name
  "Returns a human-readable directory name for the workspace cache.
   Format: <sanitized-project-name>_<hash>, or just <hash> if no name is available."
  [workspaces uri->filename-fn]
  (let [hash (workspaces-hash workspaces uri->filename-fn)
        first-uri (some-> workspaces first :uri)
        project-name (when first-uri
                       (some-> (uri->filename-fn first-uri)
                               fs/file-name
                               str
                               not-empty))
        sanitized (when project-name
                    (let [s (string/replace project-name #"[^a-zA-Z0-9._-]" "_")]
                      (subs s 0 (min max-prefix-length (count s)))))]
    (if (not-empty sanitized)
      (str sanitized "_" hash)
      hash)))

(defn ^:private migrate-workspace-cache-dir!
  "Migrates old hash-only workspace cache directory to new human-readable format."
  [^File old-dir ^File new-dir]
  (try
    (fs/move old-dir new-dir)
    (logger/info logger-tag (str "Migrated workspace cache from " old-dir " to " new-dir))
    (catch Exception e
      (logger/warn logger-tag "Failed to migrate workspace cache directory:" (.getMessage e)))))

(defn workspace-cache-file
  "Returns a File object for a workspace-specific cache file."
  [workspaces filename uri->filename-fn]
  (let [dir-name (workspace-dir-name workspaces uri->filename-fn)
        hash-only (workspaces-hash workspaces uri->filename-fn)
        base (global-dir)
        new-dir (io/file base dir-name)
        old-dir (io/file base hash-only)]
    (when (and (not= dir-name hash-only)
               (not (fs/exists? new-dir))
               (fs/exists? old-dir))
      (migrate-workspace-cache-dir! old-dir new-dir))
    (io/file new-dir filename)))

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
