(ns eca.cache
  "Cache directory and file management utilities."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]))

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
