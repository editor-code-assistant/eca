(ns eca.remote.tls-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.cache :as cache]
   [eca.remote.tls :as tls])
  (:import
   [java.security.interfaces ECPrivateKey]
   [javax.net.ssl SSLContext]))

(defn ^:private res
  "Reads a fixture PEM as a string."
  [name]
  (slurp (io/resource (str "eca/remote/fixtures/" name))))

(defn ^:private res-path
  "Filesystem path of a fixture PEM."
  [name]
  (.getPath (io/file (io/resource (str "eca/remote/fixtures/" name)))))

(defn ^:private with-temp-cache
  "Runs (f cache-dir-file) with eca.cache/global-dir pointed at a fresh temp dir."
  [f]
  (let [dir (fs/create-temp-dir)]
    (try
      (with-redefs [cache/global-dir (constantly (io/file (str dir)))]
        (f (io/file (str dir))))
      (finally (fs/delete-tree dir)))))

(deftest parse-certs-test
  (is (seq (#'tls/parse-certs (res "valid-fullchain.pem"))))
  (is (= [] (#'tls/parse-certs "not a pem"))))

(deftest parse-private-key-test
  (let [pk (#'tls/parse-private-key (res "valid-privkey.pem"))]
    (is (some? pk))
    (is (= "EC" (.getAlgorithm pk))))
  (testing "SEC1 EC key parses and matches its PKCS#8 twin"
    (let [^ECPrivateKey sec1-pk (#'tls/parse-private-key (res "valid-privkey-sec1.pem"))
          ^ECPrivateKey pkcs8-pk (#'tls/parse-private-key (res "valid-privkey.pem"))]
      (is (some? sec1-pk))
      (is (= "EC" (.getAlgorithm sec1-pk)))
      (is (= (.getS pkcs8-pk) (.getS sec1-pk)))))
  (is (nil? (#'tls/parse-private-key "garbage"))))

(deftest leaf-valid?-test
  (testing "valid cert for our domain"
    (is (true? (#'tls/leaf-valid? (#'tls/parse-certs (res "valid-fullchain.pem"))))))
  (testing "expired cert"
    (is (false? (#'tls/leaf-valid? (#'tls/parse-certs (res "expired-fullchain.pem"))))))
  (testing "valid but wrong domain"
    (is (false? (#'tls/leaf-valid? (#'tls/parse-certs (res "wrong-domain-fullchain.pem"))))))
  (testing "no certs"
    (is (false? (#'tls/leaf-valid? [])))))

(deftest valid-material-test
  (testing "valid material gets a :not-after"
    (is (some? (:not-after (#'tls/valid-material {:cert (res "valid-fullchain.pem")
                                                 :key (res "valid-privkey.pem")}))))
    (is (some? (:not-after (#'tls/valid-material {:cert (res "valid-fullchain.pem")
                                                  :key (res "valid-privkey-sec1.pem")})))))
  (testing "valid cert but unparseable key is rejected"
    (is (nil? (#'tls/valid-material {:cert (res "valid-fullchain.pem")
                                     :key "garbage"}))))
  (testing "expired / wrong-domain / nil are rejected"
    (is (nil? (#'tls/valid-material {:cert (res "expired-fullchain.pem")
                                     :key (res "expired-privkey.pem")})))
    (is (nil? (#'tls/valid-material {:cert (res "wrong-domain-fullchain.pem")
                                     :key (res "wrong-domain-privkey.pem")})))
    (is (nil? (#'tls/valid-material nil)))))

(deftest build-ssl-context-test
  (is (instance? SSLContext (#'tls/build-ssl-context (res "valid-fullchain.pem")
                                                     (res "valid-privkey.pem"))))
  (is (instance? SSLContext (#'tls/build-ssl-context (res "valid-fullchain.pem")
                                                     (res "valid-privkey-sec1.pem"))))
  (is (nil? (#'tls/build-ssl-context "bad" "bad"))))

(deftest fresher?-test
  (let [now (java.time.Instant/now)
        later (.plusSeconds now 1000)]
    (is (true? (#'tls/fresher? {:not-after later} {:not-after now})))
    (is (false? (#'tls/fresher? {:not-after now} {:not-after later})))
    (is (true? (#'tls/fresher? {:not-after now} {:not-after nil})))))

(deftest ssl-context-config-override-test
  (testing "explicit cert/key files take precedence and build a context"
    (is (instance? SSLContext
                   (tls/ssl-context {:tls {:certFile (res-path "valid-fullchain.pem")
                                           :keyFile (res-path "valid-privkey.pem")}}))))
  (testing "invalid override falls through to (here empty) cache/fetch -> nil"
    (with-temp-cache
      (fn [_]
        (with-redefs [tls/fetch-pem (fn [_] nil)]
          (is (nil? (tls/ssl-context {:tls {:certFile "/nope/fullchain.pem"
                                            :keyFile "/nope/privkey.pem"}}))))))))

(deftest ssl-context-cold-fetch-test
  (testing "cold start fetches, builds a context, and writes the cache"
    (with-temp-cache
      (fn [dir]
        (with-redefs [tls/fetch-pem (fn [url]
                                      (if (re-find #"fullchain" url)
                                        (res "valid-fullchain.pem")
                                        (res "valid-privkey.pem")))]
          (is (instance? SSLContext (tls/ssl-context {})))
          (is (.exists (io/file dir "tls" "local-eca-dev-fullchain.pem")))
          (is (.exists (io/file dir "tls" "local-eca-dev-privkey.pem"))))))))

(deftest ssl-context-uses-cache-test
  (testing "a valid cache is used even when fetching yields nothing"
    (with-temp-cache
      (fn [dir]
        (let [tls-dir (io/file dir "tls")]
          (io/make-parents (io/file tls-dir "x"))
          (spit (io/file tls-dir "local-eca-dev-fullchain.pem") (res "valid-fullchain.pem"))
          (spit (io/file tls-dir "local-eca-dev-privkey.pem") (res "valid-privkey.pem"))
          (with-redefs [tls/fetch-pem (fn [_] nil)]
            (is (instance? SSLContext (tls/ssl-context {})))))))))

(deftest ssl-context-degrades-test
  (testing "no cache + failed fetch returns nil so the server runs HTTP-only"
    (with-temp-cache
      (fn [_]
        (with-redefs [tls/fetch-pem (fn [_] nil)]
          (is (nil? (tls/ssl-context {}))))))))

(deftest ssl-context-cold-fetch-sec1-test
  (testing "cold start with a SEC1 EC key builds a context and caches it"
    (with-temp-cache
      (fn [dir]
        (with-redefs [tls/fetch-pem (fn [url]
                                      (if (re-find #"fullchain" url)
                                        (res "valid-fullchain.pem")
                                        (res "valid-privkey-sec1.pem")))]
          (is (instance? SSLContext (tls/ssl-context {})))
          (is (.exists (io/file dir "tls" "local-eca-dev-privkey.pem"))))))))

(deftest ssl-context-invalid-key-not-cached-test
  (testing "material with an unparseable key is rejected and never cached"
    (with-temp-cache
      (fn [dir]
        (with-redefs [tls/fetch-pem (fn [url]
                                      (if (re-find #"fullchain" url)
                                        (res "valid-fullchain.pem")
                                        "garbage"))]
          (is (nil? (tls/ssl-context {})))
          (is (not (.exists (io/file dir "tls" "local-eca-dev-fullchain.pem")))))))))

(deftest ssl-context-poisoned-cache-recovers-test
  (testing "a cached unparseable key is rejected and replaced by a fresh fetch"
    (with-temp-cache
      (fn [dir]
        (let [tls-dir (io/file dir "tls")]
          (io/make-parents (io/file tls-dir "x"))
          (spit (io/file tls-dir "local-eca-dev-fullchain.pem") (res "valid-fullchain.pem"))
          (spit (io/file tls-dir "local-eca-dev-privkey.pem") "garbage")
          (with-redefs [tls/fetch-pem (fn [url]
                                        (if (re-find #"fullchain" url)
                                          (res "valid-fullchain.pem")
                                          (res "valid-privkey.pem")))]
            (is (instance? SSLContext (tls/ssl-context {})))
            (is (string/starts-with? (slurp (io/file tls-dir "local-eca-dev-privkey.pem"))
                                     "-----BEGIN PRIVATE KEY-----"))))))))
