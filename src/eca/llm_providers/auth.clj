(ns eca.llm-providers.auth
  (:require
   [clojure.string :as string])
  (:import
   [java.nio.charset StandardCharsets]
   [java.security MessageDigest SecureRandom]
   [java.util Base64]))

(set! *warn-on-reflection* true)

(defn ^:private rand-bytes
  "Returns a random byte array of the specified size."
  [size]
  (let [seed (byte-array size)]
    (.nextBytes (SecureRandom.) seed)
    seed))

(defn ^:private ->base64 [^bytes bs]
  (.encodeToString (Base64/getEncoder) bs))

(defn ^:private ->base64url [base64-str]
  (-> base64-str (string/replace "+" "-") (string/replace "/" "_")))

(defn ^:private str->sha256 [^String s]
  (-> (MessageDigest/getInstance "SHA-256")
      (.digest (.getBytes s StandardCharsets/UTF_8))))

(defn ^:private random-verifier []
  (->base64url (->base64 (rand-bytes 63))))

(defn generate-pkce []
  (let [verifier (random-verifier)]
    {:verifier verifier
     :challenge (-> verifier str->sha256 ->base64 ->base64url (string/replace "=" ""))}))
