(ns eca.llm-util
  (:require
   [cheshire.core :as json]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.logger :as logger])
  (:import
   [java.io BufferedReader]
   [java.nio.charset StandardCharsets]
   [java.security MessageDigest SecureRandom]
   [java.util Base64]))

(defn event-data-seq [^BufferedReader rdr]
  (letfn [(next-group []
            (loop [event-line nil]
              (let [line (.readLine rdr)]
                (cond
                  ;; EOF
                  (nil? line)
                  nil

                  ;; skip blank lines
                  (string/blank? line)
                  (recur event-line)

                  ;; event: <event>
                  (string/starts-with? line "event:")
                  (recur line)

                  ;; data: <data>
                  (string/starts-with? line "data:")
                  (let [data-str (subs line 6)]
                    (if (= data-str "[DONE]")
                      (recur event-line) ; skip [DONE]
                      (let [event-type (if event-line
                                         (subs event-line 7)
                                         (-> (json/parse-string data-str true)
                                             :type))]
                        (cons [event-type (json/parse-string data-str true)]
                              (lazy-seq (next-group))))))

                  ;; data directly
                  (string/starts-with? line "{")
                  (cons ["data" (json/parse-string line true)]
                        (lazy-seq (next-group)))

                  :else
                  (recur event-line)))))]
    (next-group)))

(defn gen-rid
  "Generates a request-id for tracking requests"
  []
  (str (rand-int 9999)))

(defn stringfy-tool-result [result]
  (reduce
   #(str %1 (:text %2) "\n")
   ""
   (-> result :output :contents)))

(defn log-request [tag rid url body]
  (logger/debug tag (format "[%s] Sending body: '%s', url: '%s'" rid body url)))

(defn log-response [tag rid event data]
  (logger/debug tag (format "[%s] %s %s" rid (or event "") data)))

(defn ^:private rand-bytes
  "Returns a random byte array of the specified size."
  [size]
  (let [seed (byte-array size)]
    (.nextBytes (SecureRandom.) seed)
    seed))

(defn <-base64 ^String [^String s]
  (String. (.decode (Base64/getDecoder) s)))

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

(defn provider-api-key [provider provider-auth config]
  (or (get-in config [:providers (name provider) :key])
      (:api-key provider-auth)
      (some-> (get-in config [:providers (name provider) :keyEnv]) config/get-env)))

(defn provider-api-url [provider config]
  (or (get-in config [:providers (name provider) :url])
      (some-> (get-in config [:providers (name provider) :urlEnv]) config/get-env)))
