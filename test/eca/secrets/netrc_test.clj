(ns eca.secrets.netrc-test
  (:require
   [clojure.test :refer :all]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.secrets.netrc :as netrc]))

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

(defspec netrc-file-roundtrip-test 100
  (prop/for-all [entries gen-credential-entries]
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")]
      (try
        ;; 1. Generate credential entries (plain data)
        (let [original-entries entries

              ;; 2. Write entries to netrc file
              _ (netrc/write-file (.getPath temp-file) original-entries)

              ;; 3. Read the netrc file back
              parsed-entries (netrc/read-file (.getPath temp-file))]

          ;; 4. Verify round-trip: should get same entries back
          (= (set original-entries) (set parsed-entries)))
        (finally
          (.delete temp-file))))))

(defspec netrc-entries-have-required-fields-test 100
  (prop/for-all [entries gen-credential-entries]
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")]
      (try
        (netrc/write-file (.getPath temp-file) entries)
        (let [parsed-entries (netrc/read-file (.getPath temp-file))]
          ;; All parsed entries must have machine, login, password
          (every? (fn [entry]
                    (and (:machine entry)
                         (:login entry)
                         (:password entry)))
                  parsed-entries))
        (finally
          (.delete temp-file))))))

(defspec netrc-handles-multiple-entries-test 100
  (prop/for-all [entries gen-credential-entries]
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")]
      (try
        (netrc/write-file (.getPath temp-file) entries)
        (let [parsed-entries (netrc/read-file (.getPath temp-file))]
          ;; Should parse same number of entries
          (= (count entries) (count parsed-entries)))
        (finally
          (.delete temp-file))))))

;; === Traditional unit tests ===

(deftest parse-single-entry-test
  (testing "Parse single valid entry"
    (let [content "machine api.openai.com\nlogin apikey\npassword sk-proj-123\n"
          result (netrc/parse content)]
      (is (= 1 (count result)))
      (is (= {:machine "api.openai.com"
              :login "apikey"
              :password "sk-proj-123"
              :port nil}
             (first result))))))

(deftest parse-multiple-entries-test
  (testing "Parse multiple valid entries"
    (let [content (str "machine api.openai.com\n"
                       "login apikey\n"
                       "password sk-proj-123\n"
                       "\n"
                       "machine api.anthropic.com\n"
                       "login work\n"
                       "password sk-ant-456\n")
          result (netrc/parse content)]
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
    (let [content "machine custom.api\nlogin admin\npassword key-789\nport 8443\n"
          result (netrc/parse content)]
      (is (= 1 (count result)))
      (is (= {:machine "custom.api"
              :login "admin"
              :password "key-789"
              :port "8443"}
             (first result))))))

(deftest parse-entry-without-port-test
  (testing "Parse entry without port field"
    (let [content "machine api.openai.com\nlogin apikey\npassword sk-proj-123\n"
          result (netrc/parse content)]
      (is (= 1 (count result)))
      (is (nil? (:port (first result)))))))

(deftest parse-with-comments-test
  (testing "Handle full-line comments"
    (let [content (str "# This is a comment\n"
                       "machine api.openai.com\n"
                       "login apikey\n"
                       "password sk-proj-123\n"
                       "# Another comment\n")
          result (netrc/parse content)]
      (is (= 1 (count result)))
      (is (= {:machine "api.openai.com"
              :login "apikey"
              :password "sk-proj-123"
              :port nil}
             (first result))))))

(deftest parse-with-whitespace-test
  (testing "Handle leading/trailing whitespace"
    (let [content (str "  machine api.openai.com  \n"
                       "  login   apikey  \n"
                       "  password   sk-proj-123  \n")
          result (netrc/parse content)]
      (is (= 1 (count result)))
      (is (= {:machine "api.openai.com"
              :login "apikey"
              :password "sk-proj-123"
              :port nil}
             (first result))))))

(deftest parse-empty-file-test
  (testing "Handle empty file"
    (is (= [] (netrc/parse "")))
    (is (= [] (netrc/parse nil)))))

(deftest parse-whitespace-only-file-test
  (testing "Handle whitespace-only file"
    (is (= [] (netrc/parse "   \n\n  \t  \n")))))

(deftest parse-comments-only-file-test
  (testing "Handle comments-only file"
    (is (= [] (netrc/parse "# This is a comment\n# Another comment\n")))))

(deftest skip-entry-without-login-test
  (testing "Skip entry without login field"
    (let [content (str "machine api.openai.com\n"
                       "password sk-proj-123\n"
                       "\n"
                       "machine api.anthropic.com\n"
                       "login work\n"
                       "password sk-ant-456\n")
          result (netrc/parse content)]
      ;; Only the second entry should be parsed
      (is (= 1 (count result)))
      (is (= {:machine "api.anthropic.com"
              :login "work"
              :password "sk-ant-456"
              :port nil}
             (first result))))))

(deftest skip-entry-without-machine-test
  (testing "Skip entry without machine field"
    (let [content (str "login apikey\n"
                       "password sk-proj-123\n"
                       "\n"
                       "machine api.anthropic.com\n"
                       "login work\n"
                       "password sk-ant-456\n")
          result (netrc/parse content)]
      ;; Only the second entry should be parsed
      (is (= 1 (count result)))
      (is (= {:machine "api.anthropic.com"
              :login "work"
              :password "sk-ant-456"
              :port nil}
             (first result))))))

(deftest skip-entry-without-password-test
  (testing "Skip entry without password field"
    (let [content (str "machine api.openai.com\n"
                       "login apikey\n"
                       "\n"
                       "machine api.anthropic.com\n"
                       "login work\n"
                       "password sk-ant-456\n")
          result (netrc/parse content)]
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
          result (netrc/format-entry entry)]
      (is (= "machine api.openai.com\nlogin apikey\npassword sk-proj-123\n"
             result)))))

(deftest format-entry-with-port-test
  (testing "Format entry with port"
    (let [entry {:machine "custom.api"
                 :login "admin"
                 :password "key-789"
                 :port "8443"}
          result (netrc/format-entry entry)]
      (is (= "machine custom.api\nlogin admin\npassword key-789\nport 8443\n"
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
          result (netrc/format-entries entries)
          expected (str "machine api.openai.com\nlogin apikey\npassword sk-proj-123\n\n"
                        "machine api.anthropic.com\nlogin work\npassword sk-ant-456\n")]
      (is (= expected result)))))
