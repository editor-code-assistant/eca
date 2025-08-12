(ns integration.helper
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]))

(def windows?
  "Whether is running on MS-Windows."
  (string/starts-with? (System/getProperty "os.name") "Windows"))

(def ^:dynamic *escape-uris?* false)

(def default-root-project-path
  (-> (io/file *file*)
      .getParentFile
      .getParentFile
      (fs/path "sample-test")
      fs/canonicalize
      str))

(defn escape-uri
  "Escapes enough URI characters for testing purposes and returns it.

  On MS-Windows, it will also escape the drive colon, mimicking
  VS-Code/Calva's behavior."
  [uri]
  ;; Do a better escape considering more chars
  (cond-> (string/replace uri "::" "%3A%3A")
    windows?
    (string/replace  #"/([a-zA-Z]):/" "/$1%3A/")))

(defn file->uri [file]
  (let [uri (-> file fs/canonicalize .toUri .toString)]
    (if *escape-uris?*
      (escape-uri uri)
      uri)))
