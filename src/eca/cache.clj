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

(defn workspace-cache-file
  "Returns a File object for a workspace-specific cache file."
  [workspaces filename uri->filename-fn]
  (io/file (global-dir)
           (workspaces-hash workspaces uri->filename-fn)
           filename))

(def ^:private logger-tag "[CACHE]")
(def ^:private tool-call-outputs-dir-name "toolCallOutputs")

(defn tool-call-outputs-dir
  "Returns the File object for the tool call outputs cache directory."
  ^File []
  (io/file (global-dir) tool-call-outputs-dir-name))

(defn save-tool-call-output!
  "Saves the full tool call output text to a cache file.
   Returns the absolute path of the saved file as a string."
  ^String [^String tool-call-id ^String text]
  (let [dir (tool-call-outputs-dir)
        file (io/file dir (str tool-call-id ".txt"))]
    (io/make-parents file)
    (spit file text)
    (str (.getAbsolutePath file))))

(def ^:private seven-days-ms (* 7 24 60 60 1000))

(defn cleanup-tool-call-outputs!
  "Deletes tool call output cache files older than 7 days.
   Runs silently, logging warnings on errors."
  []
  (try
    (let [dir (tool-call-outputs-dir)]
      (when (fs/exists? dir)
        (let [now (System/currentTimeMillis)
              files (fs/list-dir dir)]
          (doseq [f files]
            (try
              (let [last-modified (.lastModified ^File (fs/file f))]
                (when (> (- now last-modified) seven-days-ms)
                  (fs/delete f)))
              (catch Exception e
                (logger/warn logger-tag "Failed to delete old tool call output file:" (str f) (.getMessage ^Exception e))))))))
    (catch Exception e
      (logger/warn logger-tag "Failed to cleanup tool call outputs directory:" (.getMessage ^Exception e)))))
