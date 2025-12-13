(ns eca.config-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.logger :as logger]
   [eca.secrets :as secrets]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]
   [clojure.string :as string]))

(h/reset-components-before-test)

(deftest all-test
  (testing "Default config"
    (reset! config/initialization-config* {:pureConfig true})
    (is (match?
         {:pureConfig true
          :providers {"github-copilot" {:key nil
                                        :models {"gpt-5" {}}}}}
         (#'config/all* {}))))
  (testing "deep merging initializationOptions with initial config"
    (reset! config/initialization-config* {:pureConfig true
                                           :providers {"githubCopilot" {:key "123"}}})
    (is (match?
         {:pureConfig true
          :providers {"github-copilot" {:key "123"
                                        :models {"gpt-5" {}}}}}
         (#'config/all* {}))))
  (testing "providers and models are updated correctly"
    (reset! config/initialization-config* {:pureConfig true
                                           :providers {"customProvider" {:key "123"
                                                                         :models {:gpt-5 {}}}
                                                       "openrouter" {:models {"openai/o4-mini" {}}}}})
    (is (match?
         {:pureConfig true
          :providers {"custom-provider" {:key "123"
                                         :models {"gpt-5" {}}}
                      "openrouter" {:models {"openai/o4-mini" {}}}}}
         (#'config/all* {})))))

(deftest deep-merge-test
  (testing "basic merge"
    (is (match?
         {:a 1
          :b 4
          :c 3
          :d 1}
         (#'config/deep-merge {:a 1}
                              {:b 2}
                              {:c 3}
                              {:b 4 :d 1}))))
  (testing "deep merging"
    (is (match?
         {:a 1
          :b {:c {:d 3
                  :e 4}}}
         (#'config/deep-merge {:a 1
                               :b {:c {:d 3}}}
                              {:b {:c {:e 4}}}))))
  (testing "deep merging maps with other keys"
    (is (match?
         {:a 1
          :b {:c {:e 3
                  :f 4}
              :d 2}}
         (#'config/deep-merge {:a 1
                               :b {:c {:e 3}
                                   :d 2}}
                              {:b {:c {:f 4}}})))
    (is (match?
         {:pureConfig true
          :providers {"github-copilot" {:models {"gpt-5" {}}
                                        :key "123"}}}
         (#'config/deep-merge {:providers {"github-copilot" {:models {"gpt-5" {}}}}}
                              {:pureConfig true
                               :providers {"github-copilot" {:key "123"}}})))))

(deftest normalize-fields-test
  (testing "stringfy only passed rules"
    (is (match?
         {:pureConfig true
          :providers {"custom-provider" {:key "123"
                                         :models {"gpt-5" {}}}
                      "openrouter" {:models {"openai/o4-mini" {}}}}}
         (#'config/normalize-fields
          {:stringfy-key
           [[:providers]
            [:providers :ANY :models]]}
          {"pureConfig" true
           "providers" {"custom-provider" {"key" "123"
                                           "models" {"gpt-5" {}}}
                        "openrouter" {"models" {"openai/o4-mini" {}}}}}))))
  (testing "kebab-case only passed rules"
    (is (match?
         {:pureConfig true
          :providers {"custom-provider" {:key "123"
                                         :models {"gpt-5" {}}}
                      "open-router" {:models {"openAi/o4-mini" {}}}}}
         (#'config/normalize-fields
          {:stringfy-key
           [[:providers]
            [:providers :ANY :models]]
           :kebab-case-key
           [[:providers]]}
          {"pureConfig" true
           "providers" {"customProvider" {"key" "123"
                                          "models" {"gpt-5" {}}}
                        "open-router" {"models" {"openAi/o4-mini" {}}}}}))))
  (testing "keywordize-vals"
    (is (match?
         {:pureConfig true
          :providers {"custom-provider" {:key "123"
                                         :models {"gpt-5" {}}
                                         :httpClient {:version :http1.1}}}}
         (#'config/normalize-fields
          {:stringfy-key
           [[:providers]
            [:providers :ANY :models]]
           :kebab-case-key
           [[:providers]]
           :keywordize-val
           [[:providers :ANY :httpClient]]}
          {"pureConfig" true
           "providers" {"customProvider" {"key" "123"
                                          "models" {"gpt-5" {}}
                                          "httpClient" {"version" "http1.1"}}}})))))

(deftest validate-behavior-test
  (testing "valid behavior returns as-is"
    (let [config {:behavior {"agent" {} "plan" {} "custom" {}}}]
      (is (= "agent" (config/validate-behavior-name "agent" config)))
      (is (= "plan" (config/validate-behavior-name "plan" config)))
      (is (= "custom" (config/validate-behavior-name "custom" config)))))

  (testing "nil behavior returns fallback"
    (let [config {:behavior {"agent" {} "plan" {}}}]
      (is (= "agent" (config/validate-behavior-name nil config)))))

  (testing "empty string behavior returns fallback"
    (let [config {:behavior {"agent" {} "plan" {}}}]
      (is (= "agent" (config/validate-behavior-name "" config)))))

  (testing "unknown behavior returns fallback"
    (let [config {:behavior {"agent" {} "plan" {}}}]
      (with-redefs [logger/warn (fn [_ _] nil)]
        (is (= "agent" (config/validate-behavior-name "nonexistent" config))))))

  (testing "behavior validation with various configs"
    ;; Config with only agent behavior
    (let [config {:behavior {"agent" {}}}]
      (is (= "agent" (config/validate-behavior-name "plan" config))))

    ;; Config with custom behaviors only
    (let [config {:behavior {"custom1" {} "custom2" {}}}]
      (with-redefs [logger/warn (fn [_ _] nil)]
        (is (= "agent" (config/validate-behavior-name "plan" config)))
        (is (= "custom1" (config/validate-behavior-name "custom1" config)))
        (is (= "custom2" (config/validate-behavior-name "custom2" config)))))

    ;; Empty behavior config
    (let [config {:behavior {}}]
      (with-redefs [logger/warn (fn [_ _] nil)]
        (is (= "agent" (config/validate-behavior-name "anything" config)))))))

(deftest diff-keeping-vectors-test
  (testing "like clojure.data/diff"
    (is (= {:b 3}
           (#'config/diff-keeping-vectors {:a 1
                                           :b 2}
                                          {:a 1
                                           :b 3})))
    (is (= nil
           (#'config/diff-keeping-vectors {:a {:b 2}
                                           :c 3}
                                          {:a {:b 2}
                                           :c 3})))
    (is (= {:a {:b 3}}
           (#'config/diff-keeping-vectors {:a {:b 2}
                                           :c 3}
                                          {:a {:b 3}
                                           :c 3}))))
  (testing "if a vector value changed, we keep vector from b"
    (is (= {:b [:bar :foo]}
           (#'config/diff-keeping-vectors {:a 1
                                           :c 3
                                           :b [:bar]}
                                          {:c 3
                                           :b [:bar :foo]})))
    (is (= {:b [:bar]}
           (#'config/diff-keeping-vectors {:a 1
                                           :c 3
                                           :b [:bar :foo]}
                                          {:c 3
                                           :b [:bar]})))
    (is (= {:b [:bar]}
           (#'config/diff-keeping-vectors {:a 1
                                           :c 3
                                           :b []}
                                          {:c 3
                                           :b [:bar]})))
    (is (= {:b []}
           (#'config/diff-keeping-vectors {:a 1
                                           :c 3
                                           :b [:bar]}
                                          {:c 3
                                           :b []})))))

(deftest parse-dynamic-string-test
  (testing "returns nil for nil input"
    (is (nil? (#'config/parse-dynamic-string nil "/tmp" {}))))

  (testing "returns string unchanged when no patterns"
    (is (= "hello world" (#'config/parse-dynamic-string "hello world" "/tmp" {}))))

  (testing "replaces environment variable patterns"
    (with-redefs [config/get-env (fn [env-var]
                                   (case env-var
                                     "TEST_VAR" "test-value"
                                     "ANOTHER_VAR" "another-value"
                                     nil))]
      (is (= "test-value" (#'config/parse-dynamic-string "${env:TEST_VAR}" "/tmp" {})))
      (is (= "prefix test-value suffix" (#'config/parse-dynamic-string "prefix ${env:TEST_VAR} suffix" "/tmp" {})))
      (is (= "test-value and another-value" (#'config/parse-dynamic-string "${env:TEST_VAR} and ${env:ANOTHER_VAR}" "/tmp" {})))))

  (testing "replaces undefined env var with empty string"
    (with-redefs [config/get-env (constantly nil)]
      (is (= "" (#'config/parse-dynamic-string "${env:UNDEFINED_VAR}" "/tmp" {})))
      (is (= "prefix  suffix" (#'config/parse-dynamic-string "prefix ${env:UNDEFINED_VAR} suffix" "/tmp" {})))))

  (testing "replaces undefined env var with default value"
    (with-redefs [config/get-env (constantly nil)]
      (is (= "default-value" (#'config/parse-dynamic-string "${env:UNDEFINED_VAR:default-value}" "/tmp" {})))
      (is (= "http://localhost:11434" (#'config/parse-dynamic-string "${env:OLLAMA_API_URL:http://localhost:11434}" "/tmp" {})))
      (is (= "prefix default-value suffix" (#'config/parse-dynamic-string "prefix ${env:UNDEFINED_VAR:default-value} suffix" "/tmp" {})))))

  (testing "uses env var value when set, ignoring default"
    (with-redefs [config/get-env (fn [env-var]
                                   (case env-var
                                     "TEST_VAR" "actual-value"
                                     "OLLAMA_API_URL" "http://custom:8080"
                                     nil))]
      (is (= "actual-value" (#'config/parse-dynamic-string "${env:TEST_VAR:default-value}" "/tmp" {})))
      (is (= "http://custom:8080" (#'config/parse-dynamic-string "${env:OLLAMA_API_URL:http://localhost:11434}" "/tmp" {})))))

  (testing "handles default values with special characters"
    (with-redefs [config/get-env (constantly nil)]
      (is (= "http://localhost:11434/api" (#'config/parse-dynamic-string "${env:API_URL:http://localhost:11434/api}" "/tmp" {})))
      (is (= "value-with-dashes" (#'config/parse-dynamic-string "${env:VAR:value-with-dashes}" "/tmp" {})))
      (is (= "value_with_underscores" (#'config/parse-dynamic-string "${env:VAR:value_with_underscores}" "/tmp" {})))
      (is (= "/path/to/file" (#'config/parse-dynamic-string "${env:VAR:/path/to/file}" "/tmp" {})))))

  (testing "handles empty default value"
    (with-redefs [config/get-env (constantly nil)]
      (is (= "" (#'config/parse-dynamic-string "${env:UNDEFINED_VAR:}" "/tmp" {})))
      (is (= "prefix  suffix" (#'config/parse-dynamic-string "prefix ${env:UNDEFINED_VAR:} suffix" "/tmp" {})))))

  (testing "handles multiple env vars with mixed default values"
    (with-redefs [config/get-env (fn [env-var]
                                   (case env-var
                                     "DEFINED_VAR" "defined"
                                     nil))]
      (is (= "defined and default-value"
             (#'config/parse-dynamic-string "${env:DEFINED_VAR:fallback1} and ${env:UNDEFINED_VAR:default-value}" "/tmp" {})))
      (is (= "defined and "
             (#'config/parse-dynamic-string "${env:DEFINED_VAR} and ${env:UNDEFINED_VAR}" "/tmp" {})))))

  (testing "replaces file pattern with file content - absolute path"
    (with-redefs [fs/absolute? (fn [path] (= path "/absolute/file.txt"))
                  fs/expand-home identity
                  slurp (fn [path]
                          (if (= (str path) "/absolute/file.txt")
                            "test file content"
                            (throw (ex-info "File not found" {}))))]
      (is (= "test file content" (#'config/parse-dynamic-string "${file:/absolute/file.txt}" "/tmp" {})))))

  (testing "replaces file pattern with file content - relative path"
    (with-redefs [fs/absolute? (fn [_] false)
                  fs/path (fn [cwd file-path] (str cwd "/" file-path))
                  fs/expand-home identity
                  slurp (fn [path]
                          (if (= path "/tmp/test.txt")
                            "relative file content"
                            (throw (ex-info "File not found" {}))))]
      (is (= "relative file content" (#'config/parse-dynamic-string "${file:test.txt}" "/tmp" {})))))

  (testing "replaces file pattern with file content - path with ~"
    (with-redefs [fs/absolute? (fn [_] true)
                  fs/path (fn [cwd file-path] (str cwd "/" file-path))
                  fs/expand-home (fn [f]
                                   (string/replace (str f) "~" "/home/user"))
                  slurp (fn [path]
                          (if (= path "/home/user/foo/test.txt")
                            "relative file content"
                            (throw (ex-info "File not found" {}))))]
      (is (= "relative file content" (#'config/parse-dynamic-string "${file:~/foo/test.txt}" "/tmp" {})))))

  (testing "replaces file pattern with empty string when file not found"
    (with-redefs [logger/warn (fn [& _] nil)
                  fs/absolute? (fn [_] true)
                  slurp (fn [_] (throw (ex-info "File not found" {})))]
      (is (= "" (#'config/parse-dynamic-string "${file:/nonexistent/file.txt}" "/tmp" {})))
      (is (= "prefix  suffix" (#'config/parse-dynamic-string "prefix ${file:/nonexistent/file.txt} suffix" "/tmp" {})))))

  (testing "handles multiple file patterns"
    (with-redefs [fs/absolute? (fn [_] true)
                  fs/expand-home identity
                  slurp (fn [path]
                          (case (str path)
                            "/file1.txt" "content1"
                            "/file2.txt" "content2"
                            (throw (ex-info "File not found" {}))))]
      (is (= "content1 and content2"
             (#'config/parse-dynamic-string "${file:/file1.txt} and ${file:/file2.txt}" "/tmp" {})))))

  (testing "handles mixed env and file patterns"
    (with-redefs [config/get-env (fn [env-var]
                                   (when (= env-var "TEST_VAR") "env-value"))
                  fs/expand-home identity
                  fs/absolute? (fn [_] true)
                  slurp (fn [path]
                          (if (= (str path) "/file.txt")
                            "file-value"
                            (throw (ex-info "File not found" {}))))]
      (is (= "env-value and file-value"
             (#'config/parse-dynamic-string "${env:TEST_VAR} and ${file:/file.txt}" "/tmp" {})))))

  (testing "handles patterns within longer strings"
    (with-redefs [config/get-env (fn [env-var]
                                   (when (= env-var "API_KEY") "secret123"))]
      (is (= "Bearer secret123" (#'config/parse-dynamic-string "Bearer ${env:API_KEY}" "/tmp" {})))))

  (testing "handles empty string input"
    (is (= "" (#'config/parse-dynamic-string "" "/tmp" {}))))

  (testing "preserves content with escaped-like patterns that don't match"
    (is (= "${notenv:VAR}" (#'config/parse-dynamic-string "${notenv:VAR}" "/tmp" {})))
    (is (= "${env:}" (#'config/parse-dynamic-string "${env:}" "/tmp" {}))))

  (testing "replaces classpath pattern with resource content"
    ;; ECA_VERSION is a real resource file
    (let [version-content (#'config/parse-dynamic-string "${classpath:ECA_VERSION}" "/tmp" {})]
      (is (string? version-content))
      (is (seq version-content))))

  (testing "replaces classpath pattern with empty string when resource not found"
    (with-redefs [logger/warn (fn [& _] nil)]
      (is (= "" (#'config/parse-dynamic-string "${classpath:nonexistent/resource.txt}" "/tmp" {})))
      (is (= "prefix  suffix" (#'config/parse-dynamic-string "prefix ${classpath:nonexistent/resource.txt} suffix" "/tmp" {})))))

  (testing "handles multiple classpath patterns"
    (with-redefs [io/resource (fn [path]
                                (case path
                                  "resource1.txt" (java.io.ByteArrayInputStream. (.getBytes "content1" "UTF-8"))
                                  "resource2.txt" (java.io.ByteArrayInputStream. (.getBytes "content2" "UTF-8"))
                                  nil))]
      (is (= "content1 and content2"
             (#'config/parse-dynamic-string "${classpath:resource1.txt} and ${classpath:resource2.txt}" "/tmp" {})))))

  (testing "handles classpath patterns within longer strings"
    (with-redefs [io/resource (fn [path]
                                (when (= path "config/prompt.md")
                                  (java.io.ByteArrayInputStream. (.getBytes "# System Prompt\nYou are helpful." "UTF-8"))))]
      (is (= "# System Prompt\nYou are helpful."
             (#'config/parse-dynamic-string "${classpath:config/prompt.md}" "/tmp" {})))))

  (testing "handles exception when reading classpath resource throws NullPointerException"
    (with-redefs [logger/warn (fn [& _] nil)
                  io/resource (constantly nil)]
      (is (= "" (#'config/parse-dynamic-string "${classpath:error/resource.txt}" "/tmp" {})))
      (is (= "prefix  suffix" (#'config/parse-dynamic-string "prefix ${classpath:error/resource.txt} suffix" "/tmp" {})))))

  (testing "replaces netrc pattern with credential password"
    (with-redefs [secrets/get-credential (fn [key-rc _]
                                           (when (= key-rc "api.openai.com")
                                             "secret-password-123"))]
      (is (= "secret-password-123" (#'config/parse-dynamic-string "${netrc:api.openai.com}" "/tmp" {})))))
(testing "replaces netrc pattern with credential password with a custom netrcFile"
    (with-redefs [secrets/get-credential (fn [key-rc netrc-file]
                                           (when (and (= key-rc "api.openai.com")
                                                      (= netrc-file "/tmp/my-file"))
                                             "secret-password-123"))]
      (is (= "secret-password-123" (#'config/parse-dynamic-string "${netrc:api.openai.com}" "/tmp" {"netrcFile" "/tmp/my-file"})))))

  (testing "replaces netrc pattern with empty string when credential not found"
    (with-redefs [secrets/get-credential (constantly nil)]
      (is (= "" (#'config/parse-dynamic-string "${netrc:nonexistent.com}" "/tmp" {})))
      (is (= "prefix  suffix" (#'config/parse-dynamic-string "prefix ${netrc:nonexistent.com} suffix" "/tmp" {})))))

  (testing "handles netrc pattern with login and port"
    (with-redefs [secrets/get-credential (fn [key-rc _]
                                           (case key-rc
                                             "user@api.example.com" "password1"
                                             "api.example.com:8080" "password2"
                                             "user@api.example.com:443" "password3"
                                             nil))]
      (is (= "password1" (#'config/parse-dynamic-string "${netrc:user@api.example.com}" "/tmp" {})))
      (is (= "password2" (#'config/parse-dynamic-string "${netrc:api.example.com:8080}" "/tmp" {})))
      (is (= "password3" (#'config/parse-dynamic-string "${netrc:user@api.example.com:443}" "/tmp" {})))))

  (testing "handles multiple netrc patterns"
    (with-redefs [secrets/get-credential (fn [key-rc _]
                                           (case key-rc
                                             "api1.example.com" "password1"
                                             "api2.example.com" "password2"
                                             nil))]
      (is (= "password1 and password2"
             (#'config/parse-dynamic-string "${netrc:api1.example.com} and ${netrc:api2.example.com}" "/tmp" {})))))

  (testing "handles mixed env, file, classpath, and netrc patterns"
    (with-redefs [config/get-env (fn [env-var]
                                   (when (= env-var "TEST_VAR") "env-value"))
                  fs/expand-home identity
                  fs/absolute? (fn [_] true)
                  slurp (fn [path]
                          (cond
                            (string? path)
                            (if (= path "/file.txt")
                              "file-value"
                              (throw (ex-info "File not found" {})))
                            :else "classpath-value"))
                  io/resource (fn [_] (java.io.ByteArrayInputStream. (.getBytes "classpath-value" "UTF-8")))
                  secrets/get-credential (fn [key-rc _]
                                           (when (= key-rc "api.example.com")
                                             "netrc-password"))
                  logger/warn (fn [& _] nil)]
      (is (= "env-value and file-value and classpath-value and netrc-password"
             (#'config/parse-dynamic-string "${env:TEST_VAR} and ${file:/file.txt} and ${classpath:resource.txt} and ${netrc:api.example.com}" "/tmp" {})))))

  (testing "handles netrc pattern within longer strings"
    (with-redefs [secrets/get-credential (fn [key-rc _]
                                           (when (= key-rc "api.openai.com")
                                             "sk-abc123"))]
      (is (= "Bearer sk-abc123" (#'config/parse-dynamic-string "Bearer ${netrc:api.openai.com}" "/tmp" {})))))

  (testing "handles exception when reading netrc credential fails"
    (with-redefs [logger/warn (fn [& _] nil)
                  secrets/get-credential (fn [_] (throw (ex-info "Netrc error" {})))]
      (is (= "" (#'config/parse-dynamic-string "${netrc:api.example.com}" "/tmp" {})))
      (is (= "prefix  suffix" (#'config/parse-dynamic-string "prefix ${netrc:api.example.com} suffix" "/tmp" {})))))

  (testing "handles netrc pattern with special characters in key-rc"
    (with-redefs [secrets/get-credential (fn [key-rc _]
                                           (case key-rc
                                             "api-gateway.example-corp.com" "password1"
                                             "api_service.example.com" "password2"
                                             nil))]
      (is (= "password1" (#'config/parse-dynamic-string "${netrc:api-gateway.example-corp.com}" "/tmp" {})))
      (is (= "password2" (#'config/parse-dynamic-string "${netrc:api_service.example.com}" "/tmp" {}))))))
