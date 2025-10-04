(ns eca.secrets-test
  (:require
   [clojure.test :refer :all]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.secrets :as secrets]))

;; === Test parse-key-netrc ===

(deftest parse-key-netrc-simple-machine-test
  (testing "Parse simple machine name"
    (is (= {:machine "api.openai.com" :login nil :port nil}
           (secrets/parse-key-netrc "api.openai.com")))))

(deftest parse-key-netrc-with-login-test
  (testing "Parse with login prefix"
    (is (= {:machine "api.anthropic.com" :login "work" :port nil}
           (secrets/parse-key-netrc "work@api.anthropic.com")))))

(deftest parse-key-netrc-with-port-test
  (testing "Parse with port suffix"
    (is (= {:machine "api.custom.com" :login nil :port "8443"}
           (secrets/parse-key-netrc "api.custom.com:8443")))))

(deftest parse-key-netrc-full-format-test
  (testing "Parse full format with login and port"
    (is (= {:machine "api.anthropic.com" :login "personal" :port "443"}
           (secrets/parse-key-netrc "personal@api.anthropic.com:443")))))

(deftest parse-key-netrc-empty-string-test
  (testing "Handle empty string"
    (is (nil? (secrets/parse-key-netrc "")))
    (is (nil? (secrets/parse-key-netrc nil)))))

;; === Test credential matching logic ===

(deftest get-credential-simple-machine-test
  (testing "Get credential with simple machine name"
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create a test netrc file
        (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-proj-123\n")
        
        ;; Mock credential-file-paths to return our test file
        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          (is (= "sk-proj-123" (secrets/get-credential "api.openai.com"))))
        (finally
          (.delete temp-file))))))

(deftest get-credential-with-login-test
  (testing "Get credential with specific login"
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create a test netrc file with multiple entries for same machine
        (spit temp-path (str "machine api.anthropic.com\nlogin work\npassword sk-ant-work-123\n\n"
                             "machine api.anthropic.com\nlogin personal\npassword sk-ant-personal-456\n"))
        
        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          (is (= "sk-ant-work-123" (secrets/get-credential "work@api.anthropic.com")))
          (is (= "sk-ant-personal-456" (secrets/get-credential "personal@api.anthropic.com"))))
        (finally
          (.delete temp-file))))))

(deftest get-credential-first-match-test
  (testing "Get first matching credential when no login specified"
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create a test netrc file with multiple entries for same machine
        (spit temp-path (str "machine api.anthropic.com\nlogin work\npassword sk-ant-work-123\n\n"
                             "machine api.anthropic.com\nlogin personal\npassword sk-ant-personal-456\n"))
        
        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          ;; Should return first matching entry
          (is (= "sk-ant-work-123" (secrets/get-credential "api.anthropic.com"))))
        (finally
          (.delete temp-file))))))

(deftest get-credential-with-port-test
  (testing "Get credential with specific port"
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create a test netrc file with port
        (spit temp-path "machine custom.api\nlogin admin\npassword custom-key-789\nport 8443\n")
        
        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          (is (= "custom-key-789" (secrets/get-credential "custom.api:8443"))))
        (finally
          (.delete temp-file))))))

(deftest get-credential-no-match-test
  (testing "Return nil when no match found"
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-proj-123\n")
        
        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          (is (nil? (secrets/get-credential "api.anthropic.com")))
          (is (nil? (secrets/get-credential "different@api.openai.com"))))
        (finally
          (.delete temp-file))))))

(deftest get-credential-missing-file-test
  (testing "Handle missing file gracefully"
    (with-redefs [secrets/credential-file-paths (constantly ["/nonexistent/file"])]
      (is (nil? (secrets/get-credential "api.openai.com"))))))

(deftest get-credential-empty-file-test
  (testing "Handle empty file"
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        (spit temp-path "")
        
        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          (is (nil? (secrets/get-credential "api.openai.com"))))
        (finally
          (.delete temp-file))))))

;; === Test file format detection ===

(deftest load-netrc-format-test
  (testing "Load credentials from .netrc format"
    (let [temp-file (java.io.File/createTempFile "test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-proj-123\n")
        
        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          (is (= "sk-proj-123" (secrets/get-credential "api.openai.com"))))
        (finally
          (.delete temp-file))))))

(deftest load-authinfo-format-test
  (testing "Load credentials from .authinfo format"
    (let [temp-file (java.io.File/createTempFile "test" ".authinfo")
          temp-path (.getPath temp-file)]
      (try
        (spit temp-path "machine api.openai.com login apikey password sk-proj-123")
        
        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          (is (= "sk-proj-123" (secrets/get-credential "api.openai.com"))))
        (finally
          (.delete temp-file))))))

;; === Test file priority order ===

(deftest file-priority-order-test
  (testing "Files are checked in priority order"
    (let [authinfo-gpg-file (java.io.File/createTempFile "test" ".authinfo.gpg")
          authinfo-file (java.io.File/createTempFile "test" ".authinfo")
          netrc-file (java.io.File/createTempFile "test" ".netrc")
          authinfo-gpg-path (.getPath authinfo-gpg-file)
          authinfo-path (.getPath authinfo-file)
          netrc-path (.getPath netrc-file)]
      (try
        ;; Create different passwords in each file
        (spit authinfo-gpg-path "machine api.openai.com login apikey password from-authinfo-gpg")
        (spit authinfo-path "machine api.openai.com login apikey password from-authinfo")
        (spit netrc-path "machine api.openai.com\nlogin apikey\npassword from-netrc\n")
        
        ;; authinfo.gpg should be checked first (but we can't decrypt in test, so it will fail)
        ;; authinfo should be checked second
        (with-redefs [secrets/credential-file-paths (constantly [authinfo-gpg-path authinfo-path netrc-path])]
          ;; Since we can't decrypt .gpg in test, it should fall through to .authinfo
          (is (= "from-authinfo" (secrets/get-credential "api.openai.com"))))
        
        ;; If only netrc exists, should use that
        (.delete authinfo-gpg-file)
        (.delete authinfo-file)
        (with-redefs [secrets/credential-file-paths (constantly [authinfo-gpg-path authinfo-path netrc-path])]
          (is (= "from-netrc" (secrets/get-credential "api.openai.com"))))
        (finally
          (.delete authinfo-gpg-file)
          (.delete authinfo-file)
          (.delete netrc-file))))))

;; === Test multiple credentials for same machine ===

(deftest multiple-credentials-same-machine-test
  (testing "Handle multiple credentials for same machine"
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create entries with same machine but different logins
        (spit temp-path (str "machine api.anthropic.com\nlogin work\npassword sk-ant-work-123\n\n"
                             "machine api.anthropic.com\nlogin personal\npassword sk-ant-personal-456\n\n"
                             "machine api.openai.com\nlogin apikey\npassword sk-proj-789\n"))
        
        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          ;; Specific login should match exact entry
          (is (= "sk-ant-work-123" (secrets/get-credential "work@api.anthropic.com")))
          (is (= "sk-ant-personal-456" (secrets/get-credential "personal@api.anthropic.com")))
          
          ;; No login specified should return first match
          (is (= "sk-ant-work-123" (secrets/get-credential "api.anthropic.com")))
          
          ;; Different machine should still work
          (is (= "sk-proj-789" (secrets/get-credential "api.openai.com"))))
        (finally
          (.delete temp-file))))))

;; === Test port matching ===

(deftest port-matching-test
  (testing "Port matching with exact match required"
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create entry with port
        (spit temp-path "machine custom.api\nlogin admin\npassword custom-key-789\nport 8443\n")
        
        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          ;; With matching port should work
          (is (= "custom-key-789" (secrets/get-credential "custom.api:8443")))
          
          ;; Without port should not match entry with port
          (is (nil? (secrets/get-credential "custom.api")))
          
          ;; With different port should not match
          (is (nil? (secrets/get-credential "custom.api:443"))))
        (finally
          (.delete temp-file))))))

(deftest port-nil-matching-test
  (testing "Port nil matching when port not specified"
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create entry without port
        (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-proj-123\n")
        
        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          ;; Without port in query should match entry without port
          (is (= "sk-proj-123" (secrets/get-credential "api.openai.com")))
          
          ;; With port in query should not match entry without port
          (is (nil? (secrets/get-credential "api.openai.com:443"))))
        (finally
          (.delete temp-file))))))
