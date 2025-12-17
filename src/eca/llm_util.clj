(ns eca.llm-util
  (:require
   [clojure.string :as string]
   [camel-snake-kebab.core :as csk]
   [cheshire.core :as json]
   [eca.config :as config]
   [eca.logger :as logger]
   [eca.secrets :as secrets]
   [eca.shared :as shared])
  (:import
   [java.io BufferedReader]
   [java.nio.charset StandardCharsets]
   [java.security MessageDigest SecureRandom]
   [java.util Base64]))

(set! *warn-on-reflection* true)

(defn find-last-msg-idx
  "Returns the index of the last message in `msgs` for which `(pred msg)` is true."
  [pred msgs]
  (->> msgs
       (keep-indexed (fn [i msg] (when (pred msg) i)))
       last))

(defn find-last-user-msg-idx [messages]
  ;; Returns the index of the last :role "user" message, or nil if none.
  (find-last-msg-idx #(= "user" (:role %)) messages))

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
                  (let [data-str (string/triml (subs line 5))]
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

(defn log-request [tag rid url body headers]
  (let [obfuscated-headers (-> headers
                               (shared/update-some "Authorization" #(shared/obfuscate % {:preserve-num 8}))
                               (shared/update-some "x-api-key" shared/obfuscate))]
    (logger/debug tag (format "[%s] Sending body: '%s', headers: '%s', url: '%s'" rid body obfuscated-headers url))))

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
  (or (when-let [key (not-empty (get-in config [:providers (name provider) :key]))]
        [:auth/token key])
      (when-let [key (:api-key provider-auth)]
        [:auth/oauth key])
      (when-let [key (config/get-env (str (csk/->SCREAMING_SNAKE_CASE (name provider)) "_API_KEY"))]
        [:auth/token key])
      ;; legacy
      (when-let [key (some-> (get-in config [:providers (name provider) :keyRc])
                             (secrets/get-credential (:netrcFile config)))]
        [:auth/token key])
      (when-let [key (some-> (get-in config [:providers (name provider) :keyEnv])
                             config/get-env)]
        [:auth/token key])))

(defn provider-api-url [provider config]
  (or (not-empty (get-in config [:providers (name provider) :url]))
      (config/get-env (str (csk/->SCREAMING_SNAKE_CASE (name provider)) "_API_URL"))
      (some-> (get-in config [:providers (name provider) :urlEnv]) config/get-env) ;; legacy
      ))
