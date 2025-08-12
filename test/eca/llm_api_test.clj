(ns eca.llm-api-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.llm-api :as llm-api]
   [eca.test-helper :as h]))

(h/reset-components-before-test)

(deftest default-model-test
  (testing "Custom provider default-model? present"
    (with-redefs [config/get-env (constantly nil)]
      (let [db {:models {"my-model" {:custom-provider? true :default-model? true}}}
            config {}]
        (is (= "my-model" (llm-api/default-model db config))))))

  (testing "Ollama running model present"
    (with-redefs [config/get-env (constantly nil)]
      (let [db {:models {"ollama/foo" {:tools true}
                         "gpt-4.1" {:tools true}
                         "other-model" {:tools true}}}
            config {}]
        (is (= "ollama/foo" (llm-api/default-model db config))))))

  (testing "Anthropic API key present in config"
    (with-redefs [config/get-env (constantly nil)]
      (let [db {:models {}}
            config {:anthropicApiKey "something"}]
        (is (= "claude-sonnet-4-20250514" (llm-api/default-model db config))))))

  (testing "Anthropic API key present in ENV"
    (with-redefs [config/get-env (fn [k] (when (= k "ANTHROPIC_API_KEY") "env-anthropic"))]
      (let [db {:models {}}
            config {}]
        (is (= "claude-sonnet-4-20250514" (llm-api/default-model db config))))))

  (testing "OpenAI API key present in config"
    (with-redefs [config/get-env (constantly nil)]
      (let [db {:models {}}
            config {:openaiApiKey "yes!"}]
        (is (= "gpt-5" (llm-api/default-model db config))))))

  (testing "OpenAI API key present in ENV"
    (with-redefs [config/get-env (fn [k] (when (= k "OPENAI_API_KEY") "env-openai"))]
      (let [db {:models {}}
            config {}]
        (is (= "gpt-5" (llm-api/default-model db config))))))

  (testing "Fallback default (no keys anywhere)"
    (with-redefs [config/get-env (constantly nil)]
      (let [db {:models {}}
            config {}]
        (is (= "claude-sonnet-4-20250514" (llm-api/default-model db config)))))))
