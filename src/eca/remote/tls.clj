(ns eca.remote.tls
  "Resolves the *.local.eca.dev TLS material for the remote HTTPS server.

   The certificate is intentionally NOT bundled. ECA fetches it from
   tls.eca.dev (overridable), caches it under the global cache dir, and on each
   start serves the freshest valid certificate it has. Precedence:

     1. Explicit cert/key files from `remote.tls` config (escape hatch).
     2. The on-disk cache, when its leaf cert is still valid; a background
        refresh runs when it is close to expiry.
     3. A synchronous fetch from tls.eca.dev (cold start, no usable cache).

   When nothing valid is available the caller starts the server without TLS
   (plain HTTP), and a later start will retry the fetch."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.cache :as cache]
   [eca.client-http :as client]
   [eca.logger :as logger]
   [hato.client :as http])
  (:import
   [java.io ByteArrayOutputStream File]
   [java.security KeyFactory KeyStore PrivateKey]
   [java.security.cert CertificateFactory X509Certificate]
   [java.security.spec PKCS8EncodedKeySpec]
   [java.time Duration Instant]
   [java.util Arrays Base64]
   [javax.net.ssl KeyManagerFactory SSLContext]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[REMOTE-TLS]")

(def ^:private default-cert-url "https://tls.eca.dev/local-eca-dev-fullchain.pem")
(def ^:private default-key-url "https://tls.eca.dev/local-eca-dev-privkey.pem")

(def ^:private cert-domain "*.local.eca.dev")

(def ^:private fetch-timeout-ms 5000)
(def ^:private fetch-attempts 2)

(def ^:private refresh-before-expiry-days
  "Background-refresh the cached cert once it is within this many days of
   expiring, so renewals propagate without anyone upgrading ECA."
  21)

(defn ^:private tls-cache-dir ^File []
  (io/file (cache/global-dir) "tls"))

(defn ^:private cached-cert-file ^File []
  (io/file (tls-cache-dir) "local-eca-dev-fullchain.pem"))

(defn ^:private cached-key-file ^File []
  (io/file (tls-cache-dir) "local-eca-dev-privkey.pem"))

(defn ^:private parse-certs
  "Parses all X.509 certificates from a PEM string. Returns a vector, possibly empty."
  [^String pem]
  (try
    (with-open [is (io/input-stream (.getBytes pem "UTF-8"))]
      (let [cf (CertificateFactory/getInstance "X.509")]
        (vec (.generateCertificates cf is))))
    (catch Exception _ [])))

(defn ^:private der-tlv
  "Reads the DER TLV starting at `offset` in `bs`. Returns {:tag :content-offset
   :end} or nil when malformed or out of bounds."
  [^bytes bs offset]
  (let [total (alength bs)
        offset (int offset)]
    (when (< (inc offset) total)
      (let [tag (bit-and 0xff (aget bs offset))
            b0 (bit-and 0xff (aget bs (inc offset)))]
        (if (< b0 0x80)
          (let [content-offset (+ offset 2)
                end (+ content-offset b0)]
            (when (<= end total)
              {:tag tag :content-offset content-offset :end end}))
          (let [len-size (bit-and 0x7f b0)]
            (when (and (<= 1 len-size 4) (<= (+ offset 2 len-size) total))
              (let [len (reduce (fn [acc i]
                                  (+ (* acc 256) (bit-and 0xff (aget bs (int (+ offset 2 i))))))
                                0
                                (range len-size))
                    content-offset (+ offset 2 len-size)
                    end (+ content-offset len)]
                (when (<= end total)
                  {:tag tag :content-offset content-offset :end end})))))))))

(defn ^:private sec1-curve-oid
  "Full DER TLV bytes of the namedCurve OID inside a SEC1 ECPrivateKey DER, or
   nil when the key has no embedded curve parameters."
  ^bytes [^bytes sec1]
  (when-let [top (der-tlv sec1 0)]
    (when (= 0x30 (:tag top))
      (loop [offset (:content-offset top)]
        (when (< offset (:end top))
          (when-let [child (der-tlv sec1 offset)]
            (if (= 0xa0 (:tag child))
              (when-let [oid (der-tlv sec1 (:content-offset child))]
                (when (= 0x06 (:tag oid))
                  (Arrays/copyOfRange sec1 (int (:content-offset child)) (int (:end oid)))))
              (recur (:end child)))))))))

(defn ^:private der-len
  "DER length bytes for a content of `len` bytes."
  ^bytes [len]
  (cond
    (< len 0x80) (byte-array [(unchecked-byte len)])
    (< len 0x100) (byte-array [(unchecked-byte 0x81) (unchecked-byte len)])
    :else (byte-array [(unchecked-byte 0x82)
                       (unchecked-byte (quot len 0x100))
                       (unchecked-byte (mod len 0x100))])))

(defn ^:private concat-bytes
  ^bytes [& arrays]
  (let [out (ByteArrayOutputStream.)]
    (doseq [^bytes a arrays]
      (.write out a))
    (.toByteArray out)))

(def ^:private ec-public-key-oid
  "DER TLV of OID 1.2.840.10045.2.1 (id-ecPublicKey)."
  (byte-array (map unchecked-byte [0x06 0x07 0x2a 0x86 0x48 0xce 0x3d 0x02 0x01])))

(defn ^:private der-node
  "DER TLV with `tag` wrapping `content`."
  ^bytes [tag ^bytes content]
  (let [out (ByteArrayOutputStream.)]
    (.write out (int tag))
    (.write out ^bytes (der-len (alength content)))
    (.write out content)
    (.toByteArray out)))

(defn ^:private sec1->pkcs8
  "Wraps a SEC1 ECPrivateKey DER into an unencrypted PKCS#8 DER, or nil when the
   curve OID is absent."
  ^bytes [^bytes sec1]
  (when-let [curve-oid (sec1-curve-oid sec1)]
    (der-node 0x30
              (concat-bytes (byte-array (map unchecked-byte [0x02 0x01 0x00]))
                            (der-node 0x30 (concat-bytes ec-public-key-oid curve-oid))
                            (der-node 0x04 sec1)))))

(defn ^:private parse-private-key
  "Parses a PEM private key: PKCS#8 (RSA or EC), or SEC1 EC (`BEGIN EC PRIVATE
   KEY`) which is converted to PKCS#8 first. Returns a PrivateKey or nil."
  ^PrivateKey [^String pem]
  (try
    (let [b64 (->> (string/split-lines pem)
                   (remove #(or (string/starts-with? % "-----BEGIN")
                                (string/starts-with? % "-----END")))
                   (map string/trim)
                   (remove string/blank?)
                   (string/join))
          der (.decode (Base64/getDecoder) ^String b64)
          key-bytes (if (string/includes? pem "-----BEGIN EC PRIVATE KEY-----")
                      (sec1->pkcs8 der)
                      der)]
      (when key-bytes
        (let [spec (PKCS8EncodedKeySpec. ^bytes key-bytes)]
          (or (try (.generatePrivate (KeyFactory/getInstance "RSA") spec) (catch Exception _ nil))
              (try (.generatePrivate (KeyFactory/getInstance "EC") spec) (catch Exception _ nil))))))
    (catch Exception _ nil)))

(defn ^:private cert-dns-names
  "Set of DNS names (CN + SAN dNSName entries) the cert is valid for."
  [^X509Certificate cert]
  (let [sans (try
               (->> (.getSubjectAlternativeNames cert)
                    (keep (fn [entry]
                            ;; SAN entry is a 2-list [type value]; type 2 = dNSName.
                            (when (= 2 (int (first entry)))
                              (str (second entry))))))
               (catch Exception _ nil))
        cn (try
             (some->> (.getName (.getSubjectX500Principal cert))
                      (re-find #"CN=([^,]+)")
                      second)
             (catch Exception _ nil))]
    (set (remove nil? (conj (vec sans) cn)))))

(defn ^:private leaf-valid?
  "True when the first (leaf) cert matches `cert-domain` and is currently valid."
  [certs]
  (boolean
   (when-let [^X509Certificate leaf (first certs)]
     (and (contains? (cert-dns-names leaf) cert-domain)
          (try (.checkValidity leaf) true (catch Exception _ false))))))

(defn ^:private leaf-not-after ^Instant [certs]
  (when-let [^X509Certificate leaf (first certs)]
    (.toInstant (.getNotAfter leaf))))

(defn ^:private build-ssl-context
  "Builds an SSLContext from cert PEM + key PEM, or nil when either is invalid."
  ^SSLContext [cert-pem key-pem]
  (try
    (let [certs (parse-certs cert-pem)
          pk (parse-private-key key-pem)]
      (when (and (seq certs) pk)
        (let [ks (doto (KeyStore/getInstance (KeyStore/getDefaultType))
                   (.load nil nil)
                   (.setKeyEntry "server" pk (char-array 0)
                                 (into-array java.security.cert.Certificate certs)))
              kmf (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
                    (.init ks (char-array 0)))]
          (doto (SSLContext/getInstance "TLS")
            (.init (.getKeyManagers kmf) nil nil)))))
    (catch Exception e
      (logger/warn logger-tag "Failed to build SSL context:" (.getMessage e))
      nil)))

(defn ^:private context-for
  "Builds the SSLContext for `material`, logging the TLS outcome truthfully.
   Returns the context, or nil to run HTTP-only."
  ^SSLContext [{:keys [cert key]} source-desc]
  (if-let [ctx (build-ssl-context cert key)]
    (do (logger/info logger-tag (str "TLS enabled with " source-desc))
        ctx)
    (do (logger/warn logger-tag (str "Failed to build TLS context with " source-desc
                                     "; remote server will run HTTP-only."))
        nil)))

(defn ^:private read-material
  "Reads {:cert <pem> :key <pem>} from the given files, or nil when unreadable."
  [^File cert-f ^File key-f]
  (when (and (.exists cert-f) (.exists key-f))
    (try
      {:cert (slurp cert-f) :key (slurp key-f)}
      (catch Exception _ nil))))

(defn ^:private valid-material
  "Returns `material` augmented with :not-after when its leaf cert is valid for
   our domain and its private key parses, else nil."
  [{:keys [cert key] :as material}]
  (when material
    (let [certs (parse-certs cert)]
      (when (and (leaf-valid? certs)
                 (parse-private-key key))
        (assoc material :not-after (leaf-not-after certs))))))

(defn ^:private fetch-pem [url]
  (try
    (let [{:keys [status body]} (http/get url
                                          {:throw-exceptions? false
                                           :http-client (client/merge-with-global-http-client {})
                                           :timeout fetch-timeout-ms})]
      (when (and (= 200 status) (string? body) (not (string/blank? body)))
        body))
    (catch Exception e
      (logger/debug logger-tag "Fetch failed for" url "-" (.getMessage e))
      nil)))

(defn ^:private fetch-material
  "Fetches and validates the cert/key from the given URLs, retrying a few times.
   Returns valid material (with :not-after) or nil."
  [cert-url key-url]
  (loop [attempt 1]
    (or (valid-material {:cert (fetch-pem cert-url) :key (fetch-pem key-url)})
        (when (< attempt fetch-attempts)
          (Thread/sleep 1000)
          (recur (inc attempt))))))

(defn ^:private write-cache! [{:keys [cert key]}]
  (try
    (let [cert-f (cached-cert-file)
          key-f (cached-key-file)]
      (io/make-parents cert-f)
      (spit cert-f cert)
      (spit key-f key)
      true)
    (catch Exception e
      (logger/warn logger-tag "Failed to write TLS cache -" (.getMessage e))
      false)))

(defn ^:private fresher?
  "True when `a` expires strictly later than `b` (or `b` has no expiry)."
  [a b]
  (let [^Instant na (:not-after a)
        ^Instant nb (:not-after b)]
    (or (nil? nb)
        (and na (.isAfter na nb)))))

(defn ^:private needs-refresh? [{:keys [^Instant not-after]}]
  (or (nil? not-after)
      (.isBefore not-after (.plus (Instant/now) (Duration/ofDays refresh-before-expiry-days)))))

(defn ^:private config-override
  "Valid material from explicit cert/key files in `remote.tls`, or nil."
  [{:keys [certFile keyFile]}]
  (when (and certFile keyFile)
    (or (valid-material (read-material (io/file certFile) (io/file keyFile)))
        (do (logger/warn logger-tag "Configured remote.tls cert/key missing or invalid:" certFile)
            nil))))

(defn ssl-context
  "Resolves an SSLContext for the remote HTTPS server, or nil to run HTTP-only.
   See namespace docstring for the precedence rules."
  ^SSLContext [remote-config]
  (try
    (let [tls (:tls remote-config)
          cert-url (or (:certUrl tls) default-cert-url)
          key-url (or (:keyUrl tls) default-key-url)]
      (if-let [override (config-override tls)]
        (context-for override "cert/key from remote.tls config")
        (let [cached (valid-material (read-material (cached-cert-file) (cached-key-file)))]
          (cond
            cached
            (do
              (when (needs-refresh? cached)
                (future
                  (when-let [fetched (fetch-material cert-url key-url)]
                    (when (fresher? fetched cached)
                      (when (write-cache! fetched)
                        (logger/info logger-tag (str "Refreshed cached " cert-domain " certificate")))))))
              (context-for cached (str "cached " cert-domain " certificate")))

            :else
            (if-let [fetched (fetch-material cert-url key-url)]
              (do (write-cache! fetched)
                  (context-for fetched (str cert-domain " certificate fetched from " cert-url)))
              (do (logger/warn logger-tag
                               (str "Could not obtain " cert-domain " certificate (no cache and fetch failed); "
                                    "remote server will run HTTP-only and retry on next start."))
                  nil))))))
    (catch Exception e
      (logger/warn logger-tag "Unexpected error resolving TLS context -" (.getMessage e))
      nil)))
