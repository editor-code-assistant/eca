(ns eca.secrets.authinfo-test
  (:require
   [clojure.test :refer :all]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.secrets.authinfo :as authinfo]))

;; === Generators for property-based testing ===

(def gen-hostname
  "Generates valid hostnames"
  (gen/fmap
   (fn [parts] (string/join "." parts))
   (gen/vector (gen/such-that #(not (string/blank? %))
                              (gen/resize 10 gen/string-alphanumeric))
               1 4)))

(def gen-login-name
  "Generates login field values"
  (gen/elements ["apikey" "api-key" "x-api-key" "work" "personal" "admin"]))

(def gen-password
  "Generates password-like strings"
  (gen/fmap
   (fn [prefix] (str prefix "-" (apply str (repeatedly 32 #(rand-nth "abcdefghijklmnopqrstuvwxyz0123456789")))))
   (gen/elements ["sk-proj" "sk-ant" "key"])))

(def gen-port
  "Generates port numbers as strings"
  (gen/fmap str (gen/choose 1 65535)))

(def gen-credential-entry
  "Generates a single credential entry as a plain map"
  (gen/hash-map
   :machine gen-hostname
   :login gen-login-name
   :password gen-password
   :port (gen/one-of [(gen/return nil) gen-port])))

(def gen-credential-entries
  "Generates multiple credential entries (1-10 entries)"
  (gen/vector gen-credential-entry 1 10))

;; === Property-based tests ===

(defspec authinfo-file-roundtrip-test 100
  (prop/for-all [entries gen-credential-entries]
    (let [temp-file (java.io.File/createTempFile "authinfo-test" ".authinfo")]
      (try
        ;; 1. Generate credential entries (plain data)
        (let [original-entries entries

              ;; 2. Write entries to authinfo file
              _ (authinfo/write-file (.getPath temp-file) original-entries)

              ;; 3. Read the authinfo file back
              parsed-entries (authinfo/read-file (.getPath temp-file))]

          ;; 4. Verify round-trip: should get same entries back
          (= (set original-entries) (set parsed-entries)))
        (finally
          (.delete temp-file))))))

(defspec authinfo-entries-have-required-fields-test 100
  (prop/for-all [entries gen-credential-entries]
    (let [temp-file (java.io.File/createTempFile "authinfo-test" ".authinfo")]
      (try
        (authinfo/write-file (.getPath temp-file) entries)
        (let [parsed-entries (authinfo/read-file (.getPath temp-file))]
          ;; All parsed entries must have machine, login, password
          (every? (fn [entry]
                    (and (:machine entry)
                         (:login entry)
                         (:password entry)))
                  parsed-entries))
        (finally
          (.delete temp-file))))))

(defspec authinfo-handles-multiple-entries-test 100
  (prop/for-all [entries gen-credential-entries]
    (let [temp-file (java.io.File/createTempFile "authinfo-test" ".authinfo")]
      (try
        (authinfo/write-file (.getPath temp-file) entries)
        (let [parsed-entries (authinfo/read-file (.getPath temp-file))]
          ;; Should parse same number of entries
          (= (count entries) (count parsed-entries)))
        (finally
          (.delete temp-file))))))

;; Helper function for field order independence test
(defn- write-file-random-order
  "Write entries with randomized field order to test order-independence"
  [filename entries]
  (let [content (string/join "\n"
                  (for [{:keys [machine login password port]} entries]
                    (let [fields (cond-> [["machine" machine]
                                          ["login" login]
                                          ["password" password]]
                               port (conj ["port" port]))
                          shuffled (shuffle fields)]
                      (string/join " " (mapcat identity shuffled)))))]
    (spit filename content)))

(defspec authinfo-field-order-independence-test 100
  (prop/for-all [entries gen-credential-entries]
    (let [temp-file (java.io.File/createTempFile "authinfo-test" ".authinfo")]
      (try
        ;; Write with random field order
        (write-file-random-order (.getPath temp-file) entries)

        ;; Read should still work
        (let [parsed-entries (authinfo/read-file (.getPath temp-file))]
          ;; Should get same entries regardless of field order
          (= (set entries) (set parsed-entries)))
        (finally
          (.delete temp-file))))))

;; === Traditional unit tests ===

(deftest parse-single-entry-test
  (testing "Parse single valid entry"
    (let [content "machine api.openai.com login apikey password sk-proj-123"
          result (authinfo/parse content)]
      (is (= 1 (count result)))
      (is (= {:machine "api.openai.com"
              :login "apikey"
              :password "sk-proj-123"
              :port nil}
             (first result))))))

(deftest parse-multiple-entries-test
  (testing "Parse multiple valid entries"
    (let [content (str "machine api.openai.com login apikey password sk-proj-123\n"
                       "machine api.anthropic.com login work password sk-ant-456\n")
          result (authinfo/parse content)]
      (is (= 2 (count result)))
      (is (= {:machine "api.openai.com"
              :login "apikey"
              :password "sk-proj-123"
              :port nil}
             (first result)))
      (is (= {:machine "api.anthropic.com"
              :login "work"
              :password "sk-ant-456"
              :port nil}
             (second result))))))

(deftest parse-entry-with-port-test
  (testing "Parse entry with port field"
    (let [content "machine custom.api login admin password key-789 port 8443"
          result (authinfo/parse content)]
      (is (= 1 (count result)))
      (is (= {:machine "custom.api"
              :login "admin"
              :password "key-789"
              :port "8443"}
             (first result))))))

(deftest parse-entry-without-port-test
  (testing "Parse entry without port field"
    (let [content "machine api.openai.com login apikey password sk-proj-123"
          result (authinfo/parse content)]
      (is (= 1 (count result)))
      (is (nil? (:port (first result)))))))

(deftest parse-with-different-field-orders-test
  (testing "Handle fields in different orders"
    (let [content1 "machine api.openai.com login apikey password sk-proj-123"
          content2 "login apikey machine api.openai.com password sk-proj-123"
          content3 "password sk-proj-123 login apikey machine api.openai.com"
          result1 (authinfo/parse content1)
          result2 (authinfo/parse content2)
          result3 (authinfo/parse content3)]
      (is (= result1 result2 result3))
      (is (= {:machine "api.openai.com"
              :login "apikey"
              :password "sk-proj-123"
              :port nil}
             (first result1))))))

(deftest parse-with-comments-test
  (testing "Handle full-line comments"
    (let [content (str "# This is a comment\n"
                       "machine api.openai.com login apikey password sk-proj-123\n"
                       "# Another comment\n"
                       "machine api.anthropic.com login work password sk-ant-456\n")
          result (authinfo/parse content)]
      (is (= 2 (count result)))
      (is (= {:machine "api.openai.com"
              :login "apikey"
              :password "sk-proj-123"
              :port nil}
             (first result)))
      (is (= {:machine "api.anthropic.com"
              :login "work"
              :password "sk-ant-456"
              :port nil}
             (second result))))))

(deftest parse-with-whitespace-test
  (testing "Handle leading/trailing whitespace"
    (let [content "  machine api.openai.com   login   apikey   password   sk-proj-123  "
          result (authinfo/parse content)]
      (is (= 1 (count result)))
      (is (= {:machine "api.openai.com"
              :login "apikey"
              :password "sk-proj-123"
              :port nil}
             (first result))))))

(deftest parse-empty-file-test
  (testing "Handle empty file"
    (is (= [] (authinfo/parse "")))
    (is (= [] (authinfo/parse nil)))))

(deftest parse-whitespace-only-file-test
  (testing "Handle whitespace-only file"
    (is (= [] (authinfo/parse "   \n\n  \t  \n")))))

(deftest parse-comments-only-file-test
  (testing "Handle comments-only file"
    (is (= [] (authinfo/parse "# This is a comment\n# Another comment\n")))))

(deftest skip-entry-without-login-test
  (testing "Skip entry without login field"
    (let [content (str "machine api.openai.com password sk-proj-123\n"
                       "machine api.anthropic.com login work password sk-ant-456\n")
          result (authinfo/parse content)]
      ;; Only the second entry should be parsed
      (is (= 1 (count result)))
      (is (= {:machine "api.anthropic.com"
              :login "work"
              :password "sk-ant-456"
              :port nil}
             (first result))))))

(deftest skip-entry-without-machine-test
  (testing "Skip entry without machine field"
    (let [content (str "login apikey password sk-proj-123\n"
                       "machine api.anthropic.com login work password sk-ant-456\n")
          result (authinfo/parse content)]
      ;; Only the second entry should be parsed
      (is (= 1 (count result)))
      (is (= {:machine "api.anthropic.com"
              :login "work"
              :password "sk-ant-456"
              :port nil}
             (first result))))))

(deftest skip-entry-without-password-test
  (testing "Skip entry without password field"
    (let [content (str "machine api.openai.com login apikey\n"
                       "machine api.anthropic.com login work password sk-ant-456\n")
          result (authinfo/parse content)]
      ;; Only the second entry should be parsed
      (is (= 1 (count result)))
      (is (= {:machine "api.anthropic.com"
              :login "work"
              :password "sk-ant-456"
              :port nil}
             (first result))))))

(deftest format-entry-test
  (testing "Format single entry"
    (let [entry {:machine "api.openai.com"
                 :login "apikey"
                 :password "sk-proj-123"
                 :port nil}
          result (authinfo/format-entry entry)]
      (is (= "machine api.openai.com login apikey password sk-proj-123"
             result)))))

(deftest format-entry-with-port-test
  (testing "Format entry with port"
    (let [entry {:machine "custom.api"
                 :login "admin"
                 :password "key-789"
                 :port "8443"}
          result (authinfo/format-entry entry)]
      (is (= "machine custom.api login admin password key-789 port 8443"
             result)))))

(deftest format-entries-test
  (testing "Format multiple entries"
    (let [entries [{:machine "api.openai.com"
                    :login "apikey"
                    :password "sk-proj-123"
                    :port nil}
                   {:machine "api.anthropic.com"
                    :login "work"
                    :password "sk-ant-456"
                    :port nil}]
          result (authinfo/format-entries entries)
          expected (str "machine api.openai.com login apikey password sk-proj-123\n"
                        "machine api.anthropic.com login work password sk-ant-456")]
      (is (= expected result)))))
