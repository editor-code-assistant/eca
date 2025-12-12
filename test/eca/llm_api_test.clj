(ns eca.llm-api-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.llm-api :as llm-api]
   [eca.secrets :as secrets]
   [eca.test-helper :as h]))

(h/reset-components-before-test)

(deftest default-model-test
  (testing "Custom provider defaultModel present"
    (with-redefs [config/get-env (constantly nil)
                  secrets/credential-file-paths (constantly [])]
      (let [db {}
            config {:defaultModel "my-provider/my-model"}]
        (is (= "my-provider/my-model" (llm-api/default-model db config))))))

  (testing "Ollama running model present"
    (with-redefs [config/get-env (constantly nil)
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {"ollama/foo" {:tools true}
                         "gpt-4.1" {:tools true}
                         "other-model" {:tools true}}}
            config {}]
        (is (= "ollama/foo" (llm-api/default-model db config))))))

  (testing "Anthropic API key present in config"
    (with-redefs [config/get-env (constantly nil)
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {}}
            config {:providers {"anthropic" {:key "something"}}}]
        (is (= "anthropic/claude-sonnet-4.5" (llm-api/default-model db config))))))

  (testing "Anthropic API key present in ENV"
    (with-redefs [config/get-env (fn [k] (when (= k "ANTHROPIC_API_KEY") "env-anthropic"))
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {}}
            config {:providers {"anthropic" {:keyEnv "ANTHROPIC_API_KEY"}}}]
        (is (= "anthropic/claude-sonnet-4.5" (llm-api/default-model db config))))))

  (testing "OpenAI API key present in config"
    (with-redefs [config/get-env (constantly nil)
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {}}
            config {:providers {"openai" {:key "yes!"}}}]
        (is (= "openai/gpt-5.2" (llm-api/default-model db config))))))

  (testing "OpenAI API key present in ENV"
    (with-redefs [config/get-env (fn [k] (when (= k "OPENAI_API_KEY") "env-openai"))
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {}}
            config {:providers {"anthropic" {:key nil :keyEnv nil :keyRc nil}
                                "openai" {:keyEnv "OPENAI_API_KEY"}}}]
        (is (= "openai/gpt-5.2" (llm-api/default-model db config))))))

  (testing "Fallback default (no keys anywhere)"
    (with-redefs [config/get-env (constantly nil)
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {}}
            config {}]
        (is (= "anthropic/claude-sonnet-4.5" (llm-api/default-model db config)))))))
