(ns eca.shared
  (:require
   [clojure.string :as string])
  (:import
   [java.net URI]
   [java.nio.file Paths]))

(defn uri->filename [uri]
  (let [uri (URI. uri)]
    (-> uri Paths/get .toString
        ;; WINDOWS drive letters
        (string/replace #"^[a-z]:\\" string/upper-case))))
