(ns eca.digest
  (:import
   [java.nio.charset StandardCharsets]
   [java.security MessageDigest]))

(set! *warn-on-reflection* true)

(defn sha-256-bytes ^bytes [^String s]
  (-> (MessageDigest/getInstance "SHA-256")
      (.digest (.getBytes s StandardCharsets/UTF_8))))

(defn sha-256-hex ^String [^String s]
  (->> (sha-256-bytes s)
       (map #(format "%02x" (bit-and % 0xff)))
       (apply str)))
