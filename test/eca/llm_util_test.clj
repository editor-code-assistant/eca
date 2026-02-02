(ns eca.llm-util-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.llm-util :as llm-util]
   [eca.secrets :as secrets]
   [matcher-combinators.test :refer [match?]])
  (:import
   [java.io ByteArrayInputStream]))

(deftest event-data-seq-test
  (testing "when there is a event line and another data line"
    (with-open [r (io/reader (ByteArrayInputStream. (.getBytes (str "event: foo.bar\n"
                                                                    "data: {\"type\": \"foo.bar\"}\n"
                                                                    "\n"
                                                                    "event: foo.baz\n"
                                                                    "data: {\"type\": \"foo.baz\"}"))))]
      (is (match?
           [["foo.bar" {:type "foo.bar"}]
            ["foo.baz" {:type "foo.baz"}]]
           (llm-util/event-data-seq r)))))
  (testing "when there is no event line, only a data line"
    (with-open [r (io/reader (ByteArrayInputStream. (.getBytes (str "data: {\"type\": \"foo.bar\"}\n"
                                                                    "\n"
                                                                    "data: {\"type\": \"foo.baz\"}"))))]
      (is (match?
           [["foo.bar" {:type "foo.bar"}]
            ["foo.baz" {:type "foo.baz"}]]
           (llm-util/event-data-seq r)))))
  (testing "when there is no event line, only a data line with the content directly in each line"
    (with-open [r (io/reader (ByteArrayInputStream. (.getBytes (str "{\"bar\": \"baz\"}\n"
                                                                    "{\"bar\": \"foo\"}"))))]
      (is (match?
           [["data" {:bar "baz"}]
            ["data" {:bar "foo"}]]
           (llm-util/event-data-seq r)))))
  (testing "Ignore [DONE] when exists"
    (with-open [r (io/reader (ByteArrayInputStream. (.getBytes (str "data: {\"type\": \"foo.bar\"}\n"
                                                                    "\n"
                                                                    "data: {\"type\": \"foo.baz\"}\n"
                                                                    "\n"
                                                                    "data: [DONE]\n"))))]
      (is (match?
           [["foo.bar" {:type "foo.bar"}]
            ["foo.baz" {:type "foo.baz"}]]
           (llm-util/event-data-seq r)))))
  (testing "when no extra space after data:"
    (with-open [r (io/reader (ByteArrayInputStream. (.getBytes (str "data:{\"type\": \"foo.bar\"}\n"
                                                                    "\n"
                                                                    "data:{\"type\": \"foo.baz\"}\n"
                                                                    "\n"
                                                                    "data:[DONE]\n"))))]
      (is (match?
           [["foo.bar" {:type "foo.bar"}]
            ["foo.baz" {:type "foo.baz"}]]
           (llm-util/event-data-seq r)))))
  (testing "when no extra space after event: (moonshot/kimi format)"
    (with-open [r (io/reader (ByteArrayInputStream. (.getBytes (str "event:content_block_start\n"
                                                                    "data:{\"type\":\"content_block_start\",\"index\":0}\n"
                                                                    "\n"
                                                                    "event:content_block_delta\n"
                                                                    "data:{\"type\":\"content_block_delta\",\"index\":0}\n"
                                                                    "\n"
                                                                    "event:message_delta\n"
                                                                    "data:{\"type\":\"message_delta\"}"))))]
      (is (match?
           [["content_block_start" {:type "content_block_start" :index 0}]
            ["content_block_delta" {:type "content_block_delta" :index 0}]
            ["message_delta" {:type "message_delta"}]]
           (llm-util/event-data-seq r))))))

(deftest provider-api-key-with-key-rc-test
  (testing "provider-api-key uses keyRc when configured"
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create a test netrc file
        (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-test-from-netrc\n")

        ;; Mock credential-file-paths to return our test file
        (with-redefs [secrets/credential-file-paths (constantly [temp-path])
                      config/get-env (constantly nil)]
          (let [config {:providers
                        {"openai" {:url "https://api.openai.com"
                                   :keyRc "api.openai.com"}}}
                result (llm-util/provider-api-key "openai" nil config)]
            (is (= [:auth/token "sk-test-from-netrc"] result))))
        (finally
          (.delete temp-file)))))
  (testing "provider-api-key uses :netrcFile in config when configured"
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create a test netrc file
        (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-test-from-custom-netrc\n")

        (with-redefs [config/get-env (constantly nil)]
          (let [config {:providers
                        {"openai" {:url "https://api.openai.com"
                                   :keyRc "api.openai.com"}}
                        :netrcFile temp-path}
                result (llm-util/provider-api-key "openai" nil config)]
            (is (= [:auth/token "sk-test-from-custom-netrc"] result))))
        (finally
          (.delete temp-file))))))

(deftest provider-api-key-priority-order-test
  (testing "provider-api-key respects priority order: key > keyRc > keyEnv"
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create a test netrc file
        (spit temp-path "machine api.openai.com\nlogin apikey\npassword sk-test-from-netrc\n")

        (with-redefs [secrets/credential-file-paths (constantly [temp-path])
                      config/get-env (constantly nil)]
          ;; Test 1: explicit key takes precedence over keyRc
          (let [config {:providers
                        {"openai" {:key "sk-explicit-key"
                                   :keyRc "api.openai.com"}}}
                result (llm-util/provider-api-key "openai" nil config)]
            (is (= [:auth/token "sk-explicit-key"] result)))

          ;; Test 2: oauth token takes precedence over keyRc
          (let [config {:providers
                        {"openai" {:keyRc "api.openai.com"}}}
                provider-auth {:api-key "sk-oauth-token"}
                result (llm-util/provider-api-key "openai" provider-auth config)]
            (is (= [:auth/oauth "sk-oauth-token"] result)))

          ;; Test 3: keyRc is used when key and oauth are not available
          (let [config {:providers
                        {"openai" {:keyRc "api.openai.com"}}}
                result (llm-util/provider-api-key "openai" nil config)]
            (is (= [:auth/token "sk-test-from-netrc"] result))))
        (finally
          (.delete temp-file))))))

(deftest provider-api-key-with-login-prefix-test
  (testing "provider-api-key works with login prefix in keyRc"
    (let [temp-file (java.io.File/createTempFile "netrc-test" ".netrc")
          temp-path (.getPath temp-file)]
      (try
        ;; Create a test netrc file with multiple entries
        (spit temp-path (str "machine api.anthropic.com\nlogin work\npassword sk-ant-work-key\n\n"
                             "machine api.anthropic.com\nlogin personal\npassword sk-ant-personal-key\n"))

        (with-redefs [secrets/credential-file-paths (constantly [temp-path])]
          ;; Test with specific login
          (let [config {:providers
                        {"anthropic" {:keyRc "work@api.anthropic.com"}}}
                result (llm-util/provider-api-key "anthropic" nil config)]
            (is (= [:auth/token "sk-ant-work-key"] result)))

          ;; Test with different login
          (let [config {:providers
                        {"anthropic" {:keyRc "personal@api.anthropic.com"}}}
                result (llm-util/provider-api-key "anthropic" nil config)]
            (is (= [:auth/token "sk-ant-personal-key"] result))))
        (finally
          (.delete temp-file))))))

(deftest provider-api-key-missing-credential-test
  (testing "provider-api-key returns nil when credential not found"
    (with-redefs [config/get-env (constantly nil)]
      (let [config {:providers
                    {"openai" {:keyRc "api.openai.com"}}
                    :netrcFile "/nonexistent/file"}
            result (llm-util/provider-api-key "openai" nil config)]
        (is (nil? result))))))
