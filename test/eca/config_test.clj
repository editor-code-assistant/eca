(ns eca.config-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.interpolation :as interpolation]
   [eca.logger :as logger]
   [eca.secrets :as secrets]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(deftest all-test
  (testing "Default config"
    (reset! config/initialization-config* {:pureConfig true})
    (is (match?
         {:pureConfig true
          :providers {"github-copilot" {:key nil
                                        :models {"gpt-5.2" {}}}}}
         (#'config/all* {}))))
  (testing "deep merging initializationOptions with initial config"
    (reset! config/initialization-config* {:pureConfig true
                                           :providers {"githubCopilot" {:key "123"}}})
    (is (match?
         {:pureConfig true
          :providers {"github-copilot" {:key "123"
                                        :models {"gpt-5.2" {}}}}}
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

(deftest validate-agent-test
  (testing "valid agent returns as-is"
    (let [config {:agent {"code" {} "plan" {} "custom" {}}}]
      (is (= "code" (config/validate-agent-name "code" config)))
      (is (= "plan" (config/validate-agent-name "plan" config)))
      (is (= "custom" (config/validate-agent-name "custom" config)))))

  (testing "nil agent returns fallback"
    (let [config {:agent {"code" {} "plan" {}}}]
      (is (= "code" (config/validate-agent-name nil config)))))

  (testing "empty string agent returns fallback"
    (let [config {:agent {"code" {} "plan" {}}}]
      (is (= "code" (config/validate-agent-name "" config)))))

  (testing "unknown agent returns fallback"
    (let [config {:agent {"code" {} "plan" {}}}]
      (with-redefs [logger/warn (fn [_ _] nil)]
        (is (= "code" (config/validate-agent-name "nonexistent" config))))))

  (testing "agent validation with various configs"
    ;; Config with only code agent
    (let [config {:agent {"code" {}}}]
      (is (= "code" (config/validate-agent-name "plan" config))))

    ;; Config with custom agents only
    (let [config {:agent {"custom1" {} "custom2" {}}}]
      (with-redefs [logger/warn (fn [_ _] nil)]
        (is (= "code" (config/validate-agent-name "plan" config)))
        (is (= "custom1" (config/validate-agent-name "custom1" config)))
        (is (= "custom2" (config/validate-agent-name "custom2" config)))))

    ;; Empty agent config
    (let [config {:agent {}}]
      (with-redefs [logger/warn (fn [_ _] nil)]
        (is (= "code" (config/validate-agent-name "anything" config)))))))

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
    (is (nil? (interpolation/replace-dynamic-strings nil "/tmp" {}))))

  (testing "returns string unchanged when no patterns"
    (is (= "hello world" (interpolation/replace-dynamic-strings "hello world" "/tmp" {}))))

  (testing "replaces environment variable patterns"
    (with-redefs [interpolation/get-env (fn [env-var]
                                   (case env-var
                                     "TEST_VAR" "test-value"
                                     "ANOTHER_VAR" "another-value"
                                     nil))]
      (is (= "test-value" (interpolation/replace-dynamic-strings "${env:TEST_VAR}" "/tmp" {})))
      (is (= "prefix test-value suffix" (interpolation/replace-dynamic-strings "prefix ${env:TEST_VAR} suffix" "/tmp" {})))
      (is (= "test-value and another-value" (interpolation/replace-dynamic-strings "${env:TEST_VAR} and ${env:ANOTHER_VAR}" "/tmp" {})))))

  (testing "replaces undefined env var with empty string"
    (with-redefs [interpolation/get-env (constantly nil)]
      (is (= "" (interpolation/replace-dynamic-strings "${env:UNDEFINED_VAR}" "/tmp" {})))
      (is (= "prefix  suffix" (interpolation/replace-dynamic-strings "prefix ${env:UNDEFINED_VAR} suffix" "/tmp" {})))))

  (testing "replaces undefined env var with default value"
    (with-redefs [interpolation/get-env (constantly nil)]
      (is (= "default-value" (interpolation/replace-dynamic-strings "${env:UNDEFINED_VAR:default-value}" "/tmp" {})))
      (is (= "http://localhost:11434" (interpolation/replace-dynamic-strings "${env:OLLAMA_API_URL:http://localhost:11434}" "/tmp" {})))
      (is (= "prefix default-value suffix" (interpolation/replace-dynamic-strings "prefix ${env:UNDEFINED_VAR:default-value} suffix" "/tmp" {})))))

  (testing "uses env var value when set, ignoring default"
    (with-redefs [interpolation/get-env (fn [env-var]
                                   (case env-var
                                     "TEST_VAR" "actual-value"
                                     "OLLAMA_API_URL" "http://custom:8080"
                                     nil))]
      (is (= "actual-value" (interpolation/replace-dynamic-strings "${env:TEST_VAR:default-value}" "/tmp" {})))
      (is (= "http://custom:8080" (interpolation/replace-dynamic-strings "${env:OLLAMA_API_URL:http://localhost:11434}" "/tmp" {})))))

  (testing "handles default values with special characters"
    (with-redefs [interpolation/get-env (constantly nil)]
      (is (= "http://localhost:11434/api" (interpolation/replace-dynamic-strings "${env:API_URL:http://localhost:11434/api}" "/tmp" {})))
      (is (= "value-with-dashes" (interpolation/replace-dynamic-strings "${env:VAR:value-with-dashes}" "/tmp" {})))
      (is (= "value_with_underscores" (interpolation/replace-dynamic-strings "${env:VAR:value_with_underscores}" "/tmp" {})))
      (is (= "/path/to/file" (interpolation/replace-dynamic-strings "${env:VAR:/path/to/file}" "/tmp" {})))))

  (testing "handles empty default value"
    (with-redefs [interpolation/get-env (constantly nil)]
      (is (= "" (interpolation/replace-dynamic-strings "${env:UNDEFINED_VAR:}" "/tmp" {})))
      (is (= "prefix  suffix" (interpolation/replace-dynamic-strings "prefix ${env:UNDEFINED_VAR:} suffix" "/tmp" {})))))

  (testing "handles multiple env vars with mixed default values"
    (with-redefs [interpolation/get-env (fn [env-var]
                                   (case env-var
                                     "DEFINED_VAR" "defined"
                                     nil))]
      (is (= "defined and default-value"
             (interpolation/replace-dynamic-strings "${env:DEFINED_VAR:fallback1} and ${env:UNDEFINED_VAR:default-value}" "/tmp" {})))
      (is (= "defined and "
             (interpolation/replace-dynamic-strings "${env:DEFINED_VAR} and ${env:UNDEFINED_VAR}" "/tmp" {})))))

  (testing "replaces file pattern with file content - absolute path"
    (with-redefs [fs/absolute? (fn [path] (= path "/absolute/file.txt"))
                  fs/expand-home identity
                  slurp (fn [path]
                          (if (= (str path) "/absolute/file.txt")
                            "test file content"
                            (throw (ex-info "File not found" {}))))]
      (is (= "test file content" (interpolation/replace-dynamic-strings "${file:/absolute/file.txt}" "/tmp" {})))))

  (testing "replaces file pattern with file content - relative path"
    (with-redefs [fs/absolute? (fn [_] false)
                  fs/path (fn [cwd file-path] (str cwd "/" file-path))
                  fs/expand-home identity
                  slurp (fn [path]
                          (if (= path "/tmp/test.txt")
                            "relative file content"
                            (throw (ex-info "File not found" {}))))]
      (is (= "relative file content" (interpolation/replace-dynamic-strings "${file:test.txt}" "/tmp" {})))))

  (testing "replaces file pattern with file content - path with ~"
    (with-redefs [fs/absolute? (fn [_] true)
                  fs/path (fn [cwd file-path] (str cwd "/" file-path))
                  fs/expand-home (fn [f]
                                   (string/replace (str f) "~" "/home/user"))
                  slurp (fn [path]
                          (if (= path "/home/user/foo/test.txt")
                            "relative file content"
                            (throw (ex-info "File not found" {}))))]
      (is (= "relative file content" (interpolation/replace-dynamic-strings "${file:~/foo/test.txt}" "/tmp" {})))))

  (testing "replaces file pattern with empty string when file not found"
    (with-redefs [logger/warn (fn [& _] nil)
                  fs/absolute? (fn [_] true)
                  slurp (fn [_] (throw (ex-info "File not found" {})))]
      (is (= "" (interpolation/replace-dynamic-strings "${file:/nonexistent/file.txt}" "/tmp" {})))
      (is (= "prefix  suffix" (interpolation/replace-dynamic-strings "prefix ${file:/nonexistent/file.txt} suffix" "/tmp" {})))))

  (testing "handles multiple file patterns"
    (with-redefs [fs/absolute? (fn [_] true)
                  fs/expand-home identity
                  slurp (fn [path]
                          (case (str path)
                            "/file1.txt" "content1"
                            "/file2.txt" "content2"
                            (throw (ex-info "File not found" {}))))]
      (is (= "content1 and content2"
             (interpolation/replace-dynamic-strings "${file:/file1.txt} and ${file:/file2.txt}" "/tmp" {})))))

  (testing "handles mixed env and file patterns"
    (with-redefs [interpolation/get-env (fn [env-var]
                                   (when (= env-var "TEST_VAR") "env-value"))
                  fs/expand-home identity
                  fs/absolute? (fn [_] true)
                  slurp (fn [path]
                          (if (= (str path) "/file.txt")
                            "file-value"
                            (throw (ex-info "File not found" {}))))]
      (is (= "env-value and file-value"
             (interpolation/replace-dynamic-strings "${env:TEST_VAR} and ${file:/file.txt}" "/tmp" {})))))

  (testing "handles patterns within longer strings"
    (with-redefs [interpolation/get-env (fn [env-var]
                                   (when (= env-var "API_KEY") "secret123"))]
      (is (= "Bearer secret123" (interpolation/replace-dynamic-strings "Bearer ${env:API_KEY}" "/tmp" {})))))

  (testing "handles empty string input"
    (is (= "" (interpolation/replace-dynamic-strings "" "/tmp" {}))))

  (testing "preserves content with escaped-like patterns that don't match"
    (is (= "${notenv:VAR}" (interpolation/replace-dynamic-strings "${notenv:VAR}" "/tmp" {})))
    (is (= "${env:}" (interpolation/replace-dynamic-strings "${env:}" "/tmp" {}))))

  (testing "replaces classpath pattern with resource content"
    ;; ECA_VERSION is a real resource file
    (let [version-content (interpolation/replace-dynamic-strings "${classpath:ECA_VERSION}" "/tmp" {})]
      (is (string? version-content))
      (is (seq version-content))))

  (testing "replaces classpath pattern with empty string when resource not found"
    (with-redefs [logger/warn (fn [& _] nil)]
      (is (= "" (interpolation/replace-dynamic-strings "${classpath:nonexistent/resource.txt}" "/tmp" {})))
      (is (= "prefix  suffix" (interpolation/replace-dynamic-strings "prefix ${classpath:nonexistent/resource.txt} suffix" "/tmp" {})))))

  (testing "handles multiple classpath patterns"
    (with-redefs [io/resource (fn [path]
                                (case path
                                  "resource1.txt" (java.io.ByteArrayInputStream. (.getBytes "content1" "UTF-8"))
                                  "resource2.txt" (java.io.ByteArrayInputStream. (.getBytes "content2" "UTF-8"))
                                  nil))]
      (is (= "content1 and content2"
             (interpolation/replace-dynamic-strings "${classpath:resource1.txt} and ${classpath:resource2.txt}" "/tmp" {})))))

  (testing "handles classpath patterns within longer strings"
    (with-redefs [io/resource (fn [path]
                                (when (= path "config/prompt.md")
                                  (java.io.ByteArrayInputStream. (.getBytes "# System Prompt\nYou are helpful." "UTF-8"))))]
      (is (= "# System Prompt\nYou are helpful."
             (interpolation/replace-dynamic-strings "${classpath:config/prompt.md}" "/tmp" {})))))

  (testing "handles exception when reading classpath resource throws NullPointerException"
    (with-redefs [logger/warn (fn [& _] nil)
                  io/resource (constantly nil)]
      (is (= "" (interpolation/replace-dynamic-strings "${classpath:error/resource.txt}" "/tmp" {})))
      (is (= "prefix  suffix" (interpolation/replace-dynamic-strings "prefix ${classpath:error/resource.txt} suffix" "/tmp" {})))))

  (testing "replaces netrc pattern with credential password"
    (with-redefs [secrets/get-credential (fn [key-rc _]
                                           (when (= key-rc "api.openai.com")
                                             "secret-password-123"))]
      (is (= "secret-password-123" (interpolation/replace-dynamic-strings "${netrc:api.openai.com}" "/tmp" {})))))
  (testing "replaces netrc pattern with credential password with a custom netrcFile"
    (with-redefs [secrets/get-credential (fn [key-rc netrc-file]
                                           (when (and (= key-rc "api.openai.com")
                                                      (= netrc-file "/tmp/my-file"))
                                             "secret-password-123"))]
      (is (= "secret-password-123" (interpolation/replace-dynamic-strings "${netrc:api.openai.com}" "/tmp" {"netrcFile" "/tmp/my-file"})))))

  (testing "replaces netrc pattern with empty string when credential not found"
    (with-redefs [secrets/get-credential (constantly nil)]
      (is (= "" (interpolation/replace-dynamic-strings "${netrc:nonexistent.com}" "/tmp" {})))
      (is (= "prefix  suffix" (interpolation/replace-dynamic-strings "prefix ${netrc:nonexistent.com} suffix" "/tmp" {})))))

  (testing "handles netrc pattern with login and port"
    (with-redefs [secrets/get-credential (fn [key-rc _]
                                           (case key-rc
                                             "user@api.example.com" "password1"
                                             "api.example.com:8080" "password2"
                                             "user@api.example.com:443" "password3"
                                             nil))]
      (is (= "password1" (interpolation/replace-dynamic-strings "${netrc:user@api.example.com}" "/tmp" {})))
      (is (= "password2" (interpolation/replace-dynamic-strings "${netrc:api.example.com:8080}" "/tmp" {})))
      (is (= "password3" (interpolation/replace-dynamic-strings "${netrc:user@api.example.com:443}" "/tmp" {})))))

  (testing "handles multiple netrc patterns"
    (with-redefs [secrets/get-credential (fn [key-rc _]
                                           (case key-rc
                                             "api1.example.com" "password1"
                                             "api2.example.com" "password2"
                                             nil))]
      (is (= "password1 and password2"
             (interpolation/replace-dynamic-strings "${netrc:api1.example.com} and ${netrc:api2.example.com}" "/tmp" {})))))

  (testing "handles mixed env, file, classpath, and netrc patterns"
    (with-redefs [interpolation/get-env (fn [env-var]
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
             (interpolation/replace-dynamic-strings "${env:TEST_VAR} and ${file:/file.txt} and ${classpath:resource.txt} and ${netrc:api.example.com}" "/tmp" {})))))

  (testing "handles netrc pattern within longer strings"
    (with-redefs [secrets/get-credential (fn [key-rc _]
                                           (when (= key-rc "api.openai.com")
                                             "sk-abc123"))]
      (is (= "Bearer sk-abc123" (interpolation/replace-dynamic-strings "Bearer ${netrc:api.openai.com}" "/tmp" {})))))

  (testing "handles exception when reading netrc credential fails"
    (with-redefs [logger/warn (fn [& _] nil)
                  secrets/get-credential (fn [_] (throw (ex-info "Netrc error" {})))]
      (is (= "" (interpolation/replace-dynamic-strings "${netrc:api.example.com}" "/tmp" {})))
      (is (= "prefix  suffix" (interpolation/replace-dynamic-strings "prefix ${netrc:api.example.com} suffix" "/tmp" {})))))

  (testing "handles netrc pattern with special characters in key-rc"
    (with-redefs [secrets/get-credential (fn [key-rc _]
                                           (case key-rc
                                             "api-gateway.example-corp.com" "password1"
                                             "api_service.example.com" "password2"
                                             nil))]
      (is (= "password1" (interpolation/replace-dynamic-strings "${netrc:api-gateway.example-corp.com}" "/tmp" {})))
      (is (= "password2" (interpolation/replace-dynamic-strings "${netrc:api_service.example.com}" "/tmp" {}))))))

(deftest config-schema-test
  (testing "docs/config.json is a valid JSON schema"
    (let [schema (json/parse-string (slurp (io/file "docs" "config.json")))]
      (is (= "http://json-schema.org/draft-07/schema#" (get schema "$schema")))
      (is (= "https://eca.dev/config.json" (get schema "$id")))
      (is (= "ECA Configuration" (get schema "title")))
      (is (map? (get schema "properties")))
      (is (map? (get schema "definitions")))))

  (testing "update-global-config! includes $schema in written config"
    (let [temp-dir (fs/create-temp-dir)
          config-file (io/file (str temp-dir) "config.json")]
      (try
        (with-redefs [config/global-config-file (constantly config-file)]
          (config/update-global-config! {:defaultModel "anthropic/claude-sonnet-4-6"})
          (let [written-config (json/parse-string (slurp config-file))]
            (is (= "https://eca.dev/config.json" (get written-config "$schema")))
            (is (= "anthropic/claude-sonnet-4-6" (get written-config "defaultModel")))))
        (finally
          (fs/delete-tree temp-dir))))))

(deftest effective-model-variants-test
  (let [anthropic-variants {"low" {:output_config {:effort "low"} :thinking {:type "adaptive"}}
                            "medium" {:output_config {:effort "medium"} :thinking {:type "adaptive"}}
                            "high" {:output_config {:effort "high"} :thinking {:type "adaptive"}}
                            "max" {:output_config {:effort "max"} :thinking {:type "adaptive"}}}
        openai-variants {"none" {:reasoning {:effort "none" :summary "auto"}}
                         "low" {:reasoning {:effort "low" :summary "auto"}}
                         "medium" {:reasoning {:effort "medium" :summary "auto"}}
                         "high" {:reasoning {:effort "high" :summary "auto"}}
                         "xhigh" {:reasoning {:effort "xhigh" :summary "auto"}}}
        config {:variantsByModel {".*sonnet[-._]4[-._]6|opus[-._]4[-._][56]"
                                  {:variants anthropic-variants}
                                  ".*gpt[-._]5[-._]3[-._]codex|gpt[-._]5[-._]2(?!\\d)"
                                  {:variants openai-variants
                                   :excludeProviders ["github-copilot"]}}}]

    (testing "Returns built-in variants for matching anthropic model"
      (is (= anthropic-variants
             (config/effective-model-variants config "anthropic" "claude-sonnet-4-6" nil))))

    (testing "Returns built-in variants for opus models"
      (is (= anthropic-variants
             (config/effective-model-variants config "anthropic" "claude-opus-4-5" nil)))
      (is (= anthropic-variants
             (config/effective-model-variants config "anthropic" "claude-opus-4-6" nil))))

    (testing "Returns built-in variants for matching openai model"
      (is (= openai-variants
             (config/effective-model-variants config "openai" "gpt-5.2" nil)))
      (is (= openai-variants
             (config/effective-model-variants config "openai" "gpt-5.3-codex" nil))))

    (testing "Returns nil for non-matching model without user variants"
      (is (nil? (config/effective-model-variants config "openai" "gpt-4.1" nil)))
      (is (nil? (config/effective-model-variants config "ollama" "llama3" nil))))

    (testing "Custom provider with matching model gets built-in variants"
      (is (= anthropic-variants
             (config/effective-model-variants config "my-proxy" "claude-opus-4-6" nil))))

    (testing "excludeProviders prevents built-in variants for that provider"
      (is (nil? (config/effective-model-variants config "github-copilot" "gpt-5.2" nil))))

    (testing "User variants override built-in on name clash"
      (is (= (merge anthropic-variants {"high" {:custom "payload"}})
             (config/effective-model-variants config "anthropic" "claude-sonnet-4-6"
                                             {"high" {:custom "payload"}}))))

    (testing "User variant set to {} removes it from result"
      (is (= (dissoc anthropic-variants "high" "max")
             (config/effective-model-variants config "anthropic" "claude-sonnet-4-6"
                                             {"high" {} "max" {}}))))

    (testing "User-only variants pass through when no regex match"
      (is (= {"fast" {:speed "turbo"}}
             (config/effective-model-variants config "ollama" "llama3"
                                             {"fast" {:speed "turbo"}}))))

    (testing "User-only variant set to {} is removed"
      (is (nil? (config/effective-model-variants config "ollama" "llama3"
                                                 {"fast" {}}))))

    (testing "Matches model name variations with different separators"
      (is (= anthropic-variants
             (config/effective-model-variants config "custom" "sonnet.4.6" nil)))
      (is (= anthropic-variants
             (config/effective-model-variants config "custom" "opus_4_5" nil)))
      (is (= openai-variants
             (config/effective-model-variants config "openai" "gpt-5.2" nil))))

    (testing "Does not match partial model versions"
      (is (nil? (config/effective-model-variants config "openai" "gpt-5.23" nil))))

    (testing "Nil model-name returns only user variants"
      (is (nil? (config/effective-model-variants config "openai" nil nil)))
      (is (= {"fast" {:a 1}}
             (config/effective-model-variants config "openai" nil {"fast" {:a 1}}))))

    (testing "Missing variantsByModel key in config returns only user variants"
      (is (nil? (config/effective-model-variants {} "openai" "gpt-5.2" nil)))
      (is (= {"fast" {:a 1}}
             (config/effective-model-variants {} "openai" "gpt-5.2" {"fast" {:a 1}}))))

    (testing "Invalid regex pattern in variantsByModel is skipped gracefully"
      (let [bad-config {:variantsByModel {"[invalid" {:variants {"low" {:a 1}}}}}]
        (is (nil? (config/effective-model-variants bad-config "openai" "gpt-5.2" nil)))
        (is (= {"fast" {:a 1}}
               (config/effective-model-variants bad-config "openai" "gpt-5.2" {"fast" {:a 1}})))))))
