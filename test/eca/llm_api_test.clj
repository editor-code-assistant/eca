(ns eca.llm-api-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.client-test-helpers :refer [with-client-proxied *http-client-captures*]]
   [eca.config :as config]
   [eca.llm-api :as llm-api]
   [eca.secrets :as secrets]
   [eca.test-helper :as h]))

(h/reset-components-before-test)

(deftest default-model-test
  (testing "Custom provider defaultModel present"
    (with-redefs [config/get-env (constantly nil)
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {"my-provider/my-model" {}}}
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
      (let [db {:models {"anthropic/claude-sonnet-4.5" {}}}
            config {:providers {"anthropic" {:key "something"}}}]
        (is (= "anthropic/claude-sonnet-4.5" (llm-api/default-model db config))))))

  (testing "Anthropic API key present in ENV"
    (with-redefs [config/get-env (fn [k] (when (= k "ANTHROPIC_API_KEY") "env-anthropic"))
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {"anthropic/claude-sonnet-4.5" {}}}
            config {:providers {"anthropic" {:keyEnv "ANTHROPIC_API_KEY"}}}]
        (is (= "anthropic/claude-sonnet-4.5" (llm-api/default-model db config))))))

  (testing "OpenAI API key present in config"
    (with-redefs [config/get-env (constantly nil)
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {"openai/gpt-5.2" {}}}
            config {:providers {"openai" {:key "yes!"}}}]
        (is (= "openai/gpt-5.2" (llm-api/default-model db config))))))

  (testing "OpenAI API key present in ENV"
    (with-redefs [config/get-env (fn [k] (when (= k "OPENAI_API_KEY") "env-openai"))
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {"openai/gpt-5.2" {}}}
            config {:providers {"anthropic" {:key nil :keyEnv nil :keyRc nil}
                                "openai" {:keyEnv "OPENAI_API_KEY"}}}]
        (is (= "openai/gpt-5.2" (llm-api/default-model db config))))))

  (testing "Fallback default (no keys anywhere)"
    (with-redefs [config/get-env (constantly nil)
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {"anthropic/claude-sonnet-4.5" {}
                         "openai/gpt-5.2" {}}}
            config {}]
        (is (= "anthropic/claude-sonnet-4.5" (llm-api/default-model db config))))))

  (testing "Returns nil when no models are available"
    (with-redefs [config/get-env (constantly nil)
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {}}
            config {}]
        (is (nil? (llm-api/default-model db config))))))

  (testing "Missing configured defaultModel falls back to deterministic first available model"
    (with-redefs [config/get-env (constantly nil)
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {"z-model" {}
                         "a-model" {}}
                :auth {}}
            config {:defaultModel "missing-model"}]
        (is (= "a-model" (llm-api/default-model db config))))))

  (testing "When key-based default is unavailable, falls back to deterministic first available model"
    (with-redefs [config/get-env (constantly nil)
                  secrets/credential-file-paths (constantly [])]
      (let [db {:models {"openai/gpt-4.1" {}
                         "custom/zeta" {}}
                :auth {}}
            config {:providers {"anthropic" {:key "something"}}}]
        (is (= "custom/zeta" (llm-api/default-model db config)))))))

(deftest prompt-test
  (testing "Custom OpenAI provider behavior and proper passing of httpClient options to the Hato client"
    (let [req* (atom nil)]
      (with-client-proxied {}

        (fn handler [req]
          (reset! req* req)
          {:status 200
           :body {:usage {:prompt_tokens 5 :completion_tokens 2}
                  :choices [{:message {:content "hi"
                                       :reasoning_content "think more"}}]}})

        (let [response (#'eca.llm-api/prompt!
                        {:config {:providers {"lmstudio"
                                              {:api "openai-chat",
                                               :url "http://localhost:1234",
                                               :completionUrlRelativePath "/v1/chat/completions",
                                               :httpClient {:version :http-1.1},
                                               :models {"ibm/granite-4-h-tiny" {}}}}}

                         :provider "lmstudio"
                         :model "ibm/granite-4-h-tiny"

                         :model-capabilities {:tools false,
                                              :reason? false,
                                              :web-search false,
                                              :model-name "ibm/granite-4-h-tiny"}
                         :sync? true})]
          (is (= {:method "POST",
                  :uri "/v1/chat/completions"}
                 (select-keys @req* [:method :uri])))
          ;; Verify that a single Hato HTTP client request occurred and used HTTP/1.1
          (is (= [{:version :http-1.1}] (map #(dissoc % :proxy) @*http-client-captures*)))
          (is (= {:usage {:input-tokens 5, :output-tokens 2, :input-cache-read-tokens nil},
                  :tools-to-call (),
                  :reason-text "think more",
                  :reasoning-content "think more",
                  :output-text "hi"}
                 (select-keys response [:usage :tools-to-call :reason-text :reasoning-content :output-text])) response)))))

  (testing "Custom provider allows dynamically discovered models even when not present in provider :models config"
    (let [req* (atom nil)]
      (with-client-proxied {}

        (fn handler [req]
          (reset! req* req)
          {:status 200
           :body {:usage {:prompt_tokens 5 :completion_tokens 2}
                  :choices [{:message {:content "hi"}}]}})

        (let [response (#'eca.llm-api/prompt!
                        {:config {:providers {"synthetic"
                                              {:api "openai-chat"
                                               :url "http://localhost:1234"
                                               :completionUrlRelativePath "/v1/chat/completions"
                                               :httpClient {:version :http-1.1}
                                               :models {}}}}

                         :provider "synthetic"
                         :model "qwen-3-235b-instruct"

                         :model-capabilities {:tools false
                                              :reason? false
                                              :web-search false
                                              :model-name "hf:Qwen/Qwen3-235B-A22B-Instruct-2507"}
                         :sync? true})]
          (is (= {:method "POST"
                  :uri "/v1/chat/completions"}
                 (select-keys @req* [:method :uri])))
          (is (= "hi" (:output-text response))))))))
