(ns eca.llm-util-secrets-test
  (:require
   [clojure.test :refer :all]
   [clojure.java.io :as io]
   [eca.llm-util :as llm-util]
   [eca.secrets :as secrets]))

(deftest provider-api-key-with-key-netrc-test
  (testing "provider-api-key uses keyNetrc when configured"
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create a test netrc file
        (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-test-from-netrc\n")
        
        ;; Mock credential-file-paths to return our test file
        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          (let [config {:providers
                        {"openai" {:url "https://api.openai.com"
                                   :key-netrc "api.openai.com"}}}
                result (llm-util/provider-api-key "openai" nil config)]
            (is (= "sk-test-from-netrc" result))))
        (finally
          (.delete temp-file))))))

(deftest provider-api-key-priority-order-test
  (testing "provider-api-key respects priority order: key > keyNetrc > keyEnv"
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create a test netrc file
        (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-test-from-netrc\n")
        
        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          ;; Test 1: explicit key takes precedence over keyNetrc
          (let [config {:providers
                        {"openai" {:key "sk-explicit-key"
                                   :key-netrc "api.openai.com"}}}
                result (llm-util/provider-api-key "openai" nil config)]
            (is (= "sk-explicit-key" result)))
          
          ;; Test 2: oauth token takes precedence over keyNetrc
          (let [config {:providers
                        {"openai" {:key-netrc "api.openai.com"}}}
                provider-auth {:api-key "sk-oauth-token"}
                result (llm-util/provider-api-key "openai" provider-auth config)]
            (is (= "sk-oauth-token" result)))
          
          ;; Test 3: keyNetrc is used when key and oauth are not available
          (let [config {:providers
                        {"openai" {:key-netrc "api.openai.com"}}}
                result (llm-util/provider-api-key "openai" nil config)]
            (is (= "sk-test-from-netrc" result))))
        (finally
          (.delete temp-file))))))

(deftest provider-api-key-with-login-prefix-test
  (testing "provider-api-key works with login prefix in keyNetrc"
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create a test netrc file with multiple entries
        (spit temp-path (str "machine api.anthropic.com\nlogin work\npassword sk-ant-work-key\n\n"
                             "machine api.anthropic.com\nlogin personal\npassword sk-ant-personal-key\n"))
        
        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          ;; Test with specific login
          (let [config {:providers
                        {"anthropic" {:key-netrc "work@api.anthropic.com"}}}
                result (llm-util/provider-api-key "anthropic" nil config)]
            (is (= "sk-ant-work-key" result)))
          
          ;; Test with different login
          (let [config {:providers
                        {"anthropic" {:key-netrc "personal@api.anthropic.com"}}}
                result (llm-util/provider-api-key "anthropic" nil config)]
            (is (= "sk-ant-personal-key" result))))
        (finally
          (.delete temp-file))))))

(deftest provider-api-key-missing-credential-test
  (testing "provider-api-key returns nil when credential not found"
    (with-redefs [secrets/credential-file-paths (constantly ["/nonexistent/file"])]
      (let [config {:providers
                    {"openai" {:key-netrc "api.openai.com"}}}
            result (llm-util/provider-api-key "openai" nil config)]
        (is (nil? result))))))
