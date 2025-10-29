(ns integration.helper
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [is]]
   [integration.eca :as eca]
   [matcher-combinators.test :refer [match?]]))

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

(def mcp-server-sample-path
  (-> (io/file *file*)
      .getParentFile
      .getParentFile
      (fs/path "mcp-server-sample")
      fs/canonicalize
      str))

(defn project-path->canon-path
  "Returns the canonical name of the root project's SUB-PATH."
  [sub-path]
  (.getCanonicalPath (io/file default-root-project-path sub-path)))

(defn escape-uri
  "Escapes enough URI characters for testing purposes and returns it.

  On MS-Windows, it will also escape the drive colon, mimicking
  VS-Code/Calva's behavior."
  [uri]
  ;; Do a better escape considering more chars
  (cond-> (string/replace uri "::" "%3A%3A")
    windows?
    (string/replace  #"/([a-zA-Z]):/" "/$1%3A/")))

(defn json-escape-path
  "Escapes the filesystem PATH string for safe use in JSON and returns the
  result."
  [path]
  (string/replace path "\\" "\\\\"))

(defn file->uri [file]
  (let [uri (-> file fs/canonicalize .toUri .toString)]
    (if *escape-uris?*
      (escape-uri uri)
      uri)))

(defn deep-merge [& maps]
  (apply merge-with (fn [& args]
                      (if (every? #(or (map? %) (nil? %)) args)
                        (apply deep-merge args)
                        (last args)))
         maps))

(defn match-content [chat-id role content]
  (is (match?
        {:chatId chat-id
         :role role
         :content content}
        (eca/client-awaits-server-notification :chat/contentReceived))))
