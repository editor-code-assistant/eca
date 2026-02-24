(ns eca.network
  "Network configuration: proxy settings from environment variables and
  TLS/SSL context built from custom CA certificates and mTLS client
  certificates configured via the `network` config key or environment
  variables."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.logger :as logger])
  (:import
   [java.io FileInputStream]
   [java.net URI URLDecoder]
   [java.security KeyFactory KeyStore]
   [java.security.cert CertificateFactory]
   [java.security.spec PKCS8EncodedKeySpec]
   [java.util Base64]
   [javax.crypto Cipher EncryptedPrivateKeyInfo SecretKeyFactory]
   [javax.crypto.spec PBEKeySpec]
   [javax.net.ssl KeyManagerFactory SSLContext TrustManagerFactory X509TrustManager]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[NETWORK]")

(defn parse-proxy-url
  "Parses a proxy URL-STR of the form `scheme://[user:pass@]host:port` into a map.

  Returns a map with the following keys:

  :host     - the host part of the URL

  :port     - the port part. Defaults to 80 for HTTP, 443 for HTTPS,
              and throws `IllegalArgumentException` if the scheme is neither

  :username - the user part of the URL, if present; URL-decoded

  :password - the password part of the URL, if present; URL-decoded

  Both username and password are automatically URL-decoded if they contain percent encoded characters."
  [url-str]
  (when (and url-str (not (string/blank? url-str)))
    (let [^URI uri (URI. url-str)
          host (.getHost uri)
          port (if (neg? (.getPort uri))
                 (case (.getScheme uri)
                   "http" 80
                   "https" 443
                   (throw (IllegalArgumentException. (str "Unsupported scheme: " (.getScheme uri)))))
                 (.getPort uri))
          userinfo (.getUserInfo uri)
          [user pass] (when userinfo
                        (string/split userinfo #":" 2))
          decode (fn [^String s] (when s (URLDecoder/decode s "UTF-8")))]
      {:host host
       :port port
       :username (decode user)
       :password (decode pass)})))

(defn proxy-urls-system-env-get
  "Returns a map of the HTTP and HTTPS proxy environment variables,
  preferring lowercase variable names over uppercase.

  The map includes:

  :http  - string value of http_proxy or HTTP_PROXY.
  :https - string value of https_proxy or HTTPS_PROXY."
  []
  {:http  (or (config/get-env "http_proxy")
              (config/get-env "HTTP_PROXY"))
   :https  (or (config/get-env "https_proxy")
               (config/get-env "HTTPS_PROXY"))})

(defn proxy-urls-parse
  "Parses the HTTP and HTTPS proxy URL strings from URLS-MAP and returns a map of parsed values.

  The input map may contain the keys:

  :http  - the HTTP proxy URL string, or nil
  :https - the HTTPS proxy URL string, or nil

  Returns a map with the same keys:

  :http  - a map with keys :host, :port, :username, :password for the HTTP proxy, or nil if not set
  :https - a map with keys :host, :port, :username, :password for the HTTPS proxy, or nil if not set

  Ports default to 80 for HTTP and 443 for HTTPS if not specified. Usernames and passwords are URL-decoded if present."
  [urls-map]
  (update-vals (select-keys urls-map [:http :https]) parse-proxy-url))

(defn env-proxy-urls-parse
  "Fetches the HTTP and HTTPS proxy URLs from environment variables and returns a map of parsed values.

  The function looks for these environment variables, preferring lowercase over uppercase:

  :http  - from `http_proxy` or `HTTP_PROXY`
  :https - from `https_proxy` or `HTTPS_PROXY`

  The returned map contains:

  :http  - a map with keys :host, :port, :username, :password for the HTTP proxy, or nil if not set
  :https - a map with keys :host, :port, :username, :password for the HTTPS proxy, or nil if not set

  Port defaults to 80 for HTTP and 443 for HTTPS if not specified. Usernames and passwords are URL-decoded if present."
  []
  (proxy-urls-parse (proxy-urls-system-env-get)))

(defn ^:private non-blank [^String s]
  (when-not (or (nil? s) (string/blank? s)) s))

(defn read-network-config
  "Reads network TLS configuration from the config `network` section
  (when available) and falls back to well-known environment variables.

  Config values (camelCase as from JSON):
    :caCertFile          - path to a PEM CA certificate bundle
    :clientCert          - path to a PEM client certificate for mTLS
    :clientKey           - path to a PEM client private key for mTLS
    :clientKeyPassphrase - passphrase for an encrypted client key

  Environment variable fallbacks (lowest priority):
    SSL_CERT_FILE / NODE_EXTRA_CA_CERTS  -> :ca-cert-file
    ECA_CLIENT_CERT                      -> :client-cert
    ECA_CLIENT_KEY                       -> :client-key
    ECA_CLIENT_KEY_PASSPHRASE            -> :client-key-passphrase"
  [file-config]
  (let [net (:network file-config)]
    {:ca-cert-file (or (non-blank (:caCertFile net))
                       (non-blank (config/get-env "SSL_CERT_FILE"))
                       (non-blank (config/get-env "NODE_EXTRA_CA_CERTS")))
     :client-cert (or (non-blank (:clientCert net))
                      (non-blank (config/get-env "ECA_CLIENT_CERT")))
     :client-key (or (non-blank (:clientKey net))
                     (non-blank (config/get-env "ECA_CLIENT_KEY")))
     :client-key-passphrase (or (non-blank (:clientKeyPassphrase net))
                                (non-blank (config/get-env "ECA_CLIENT_KEY_PASSPHRASE")))}))

(defn load-pem-certificates
  "Loads X.509 certificates from a PEM file at PEM-PATH.
  Returns a vector of `java.security.cert.Certificate` instances."
  [^String pem-path]
  (let [f (io/file pem-path)]
    (when-not (.exists ^java.io.File f)
      (throw (ex-info (str "CA certificate file not found: " pem-path)
                      {:path pem-path})))
    (let [cf (CertificateFactory/getInstance "X.509")]
      (with-open [is (FileInputStream. ^java.io.File f)]
        (into [] (.generateCertificates cf is))))))

(defn ^:private strip-pem-armour
  "Removes PEM header/footer lines and whitespace, returning raw base64 content."
  [^String pem-text]
  (->> (string/split-lines pem-text)
       (remove (fn [line]
                 (or (string/starts-with? line "-----BEGIN")
                     (string/starts-with? line "-----END"))))
       (map string/trim)
       (remove string/blank?)
       (string/join)))

(defn ^:private try-key-factory [^bytes key-bytes ^String algorithm]
  (try
    (let [kf (KeyFactory/getInstance algorithm)
          spec (PKCS8EncodedKeySpec. key-bytes)]
      (.generatePrivate kf spec))
    (catch Exception _e nil)))

(defn ^:private decrypt-pkcs8-key [^bytes encrypted-bytes ^String passphrase]
  (let [epki (EncryptedPrivateKeyInfo. encrypted-bytes)
        alg-name (.getAlgName epki)
        skf (SecretKeyFactory/getInstance alg-name)
        pbe-key (.generateSecret skf (PBEKeySpec. (.toCharArray passphrase)))
        cipher (Cipher/getInstance alg-name)]
    (.init cipher Cipher/DECRYPT_MODE pbe-key (.getAlgParameters epki))
    (.getKeySpec epki cipher)))

(defn load-private-key
  "Loads a PKCS8 private key from a PEM file at KEY-PATH.
  If PASSPHRASE is non-nil the key is decrypted first.
  Tries RSA, then EC key factories for untyped PKCS8 keys."
  [^String key-path ^String passphrase]
  (let [f (io/file key-path)]
    (when-not (.exists ^java.io.File f)
      (throw (ex-info (str "Client key file not found: " key-path)
                      {:path key-path})))
    (let [pem-text (slurp f)
          encrypted? (string/includes? pem-text "ENCRYPTED")
          b64 (strip-pem-armour pem-text)
          raw-bytes (.decode (Base64/getDecoder) ^String b64)]
      (if encrypted?
        (do (when-not passphrase
              (throw (ex-info "Client key is encrypted but no passphrase was provided"
                              {:path key-path})))
            (let [^PKCS8EncodedKeySpec spec (decrypt-pkcs8-key raw-bytes passphrase)
                  spec-bytes (.getEncoded spec)]
              (or (try-key-factory spec-bytes "RSA")
                  (try-key-factory spec-bytes "EC")
                  (throw (ex-info "Unable to load encrypted private key (unsupported algorithm)"
                                  {:path key-path})))))
        (or (try-key-factory raw-bytes "RSA")
            (try-key-factory raw-bytes "EC")
            (throw (ex-info "Unable to load private key (unsupported algorithm)"
                            {:path key-path})))))))

(defn ^:private default-trust-managers
  "Returns the default JVM trust managers (system CA trust store)."
  []
  (let [^KeyStore null-ks nil
        tmf (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))]
    (.init tmf null-ks)
    (.getTrustManagers tmf)))

(defn ^:private build-trust-managers
  "Builds trust managers that trust both the system CAs and custom
  CA certificates loaded from CA-CERT-FILE (PEM)."
  [^String ca-cert-file]
  (let [custom-certs (load-pem-certificates ca-cert-file)
        ks (KeyStore/getInstance (KeyStore/getDefaultType))]
    (.load ks nil nil)
    (doseq [^X509TrustManager tm (default-trust-managers)]
      (doseq [cert (.getAcceptedIssuers tm)]
        (.setCertificateEntry
         ks
         (str "default-"
              (.hashCode
               (.getSubjectX500Principal
                ^java.security.cert.X509Certificate cert)))
         cert)))
    (doseq [[idx cert] (map-indexed vector custom-certs)]
      (.setCertificateEntry ks (str "custom-" idx) cert))
    (let [tmf (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))]
      (.init tmf ks)
      (.getTrustManagers tmf))))

(defn ^:private build-key-managers
  "Builds key managers for mTLS from a client certificate and private key."
  [^String client-cert ^String client-key passphrase]
  (let [certs (load-pem-certificates client-cert)
        pk (load-private-key client-key passphrase)
        ks (KeyStore/getInstance (KeyStore/getDefaultType))
        empty-pass (char-array 0)]
    (.load ks nil nil)
    (.setKeyEntry ks "client" pk empty-pass
                  (into-array java.security.cert.Certificate certs))
    (let [kmf (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))]
      (.init kmf ks empty-pass)
      (.getKeyManagers kmf))))

(defn build-ssl-context
  "Builds a `javax.net.ssl.SSLContext` from the given network config map.

  Supports:
    :ca-cert-file          - PEM file with CA certificates (additive to JVM defaults)
    :client-cert           - PEM file with client certificate for mTLS
    :client-key            - PEM file with client private key for mTLS
    :client-key-passphrase - optional passphrase for encrypted client key

  Returns nil when no TLS settings are configured."
  [{:keys [ca-cert-file client-cert client-key client-key-passphrase]}]
  (let [has-ca? (some? ca-cert-file)
        has-mtls? (and client-cert client-key)]
    (when (or has-ca? has-mtls?)
      (let [trust-managers (when has-ca?
                             (build-trust-managers ca-cert-file))
            key-managers (when has-mtls?
                           (build-key-managers client-cert client-key client-key-passphrase))
            ctx (SSLContext/getInstance "TLS")]
        (.init ctx key-managers trust-managers nil)
        ctx))))

(def ^:dynamic *ssl-context*
  "Global `javax.net.ssl.SSLContext` built from the network configuration.
  `nil` when no custom TLS is configured (uses JVM defaults)."
  nil)

(defn setup!
  "Reads the network TLS configuration from FILE-CONFIG (already-parsed
  config map) and environment variables, builds an `SSLContext` when
  custom CA or mTLS settings are present, and stores it in
  `*ssl-context*`."
  [file-config]
  (let [net-cfg (read-network-config file-config)]
    (try
      (when-let [ctx (build-ssl-context net-cfg)]
        (logger/info logger-tag "Custom SSL context configured"
                     (cond-> {}
                       (:ca-cert-file net-cfg) (assoc :ca-cert-file (:ca-cert-file net-cfg))
                       (:client-cert net-cfg) (assoc :client-cert (:client-cert net-cfg))))
        (alter-var-root #'*ssl-context* (constantly ctx)))
      (catch Exception e
        (logger/error logger-tag "Failed to build SSL context:" (.getMessage e))))))
