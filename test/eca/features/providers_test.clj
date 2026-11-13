(ns eca.features.providers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.features.providers :as f.providers]
   [eca.models :as models]
   [eca.modules :as modules]
   [eca.test-helper :as h]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(defn- status-of [providers id]
  (some #(when (= id (:id %)) %) (:providers providers)))

(deftest declarations-test
  (testing "labels and logins come from the provider modules"
    (reset! config/initialization-config* {:pureConfig true})
    (config/clear-cache!)
    (let [providers (with-redefs [config/get-env (constantly nil)]
                      (f.providers/providers-list (h/db*) (config/all (h/db))))]
      (is (match? {:id "anthropic" :label "Anthropic"
                   :login {:methods [{:key "max"} {:key "console"} {:key "manual"}]}}
                  (status-of providers "anthropic")))
      (is (match? {:id "deepseek" :label "DeepSeek" :configured false
                   :login {:methods [{:key "api-key" :label "Enter API key & models"}]}}
                  (status-of providers "deepseek")))
      (is (match? {:label "Enter API key, URL & models"} (-> (status-of providers "azure") :login :methods first)))
      (is (match? {:label "Enter models"} (-> (status-of providers "lmstudio") :login :methods first)))
      (is (match? {:label "Enter API key"} (-> (status-of providers "google") :login :methods first)))
      (testing "Bedrock is listed: it has a login"
        (is (match? {:id "bedrock" :label "AWS Bedrock"} (status-of providers "bedrock"))))
      (testing "Ollama has a label but no login"
        (is (match? {:id "ollama" :label "Ollama" :login m/absent} (status-of providers "ollama"))))))
  (testing "a disabled provider module is not listed, unless configured"
    (with-redefs [modules/built-in-modules (constantly (h/built-in-modules-with :eca/anthropic))]
      (is (= ["anthropic"] (mapv :id (:providers (f.providers/providers-list (h/db*) {}))))))
    (with-redefs [modules/built-in-modules (constantly (h/built-in-modules-with :eca/anthropic))]
      (is (= ["anthropic" "deepseek"]
             (mapv :id (:providers (f.providers/providers-list (h/db*) {:providers {"deepseek" {:api "openai-chat"}}}))))))))

(deftest provider-login-test
  (testing "an :ask login returns its fields"
    (is (= {:action "input"
            :fields [{:key "api-key" :label "API Key" :type "secret"}
                     {:key "url" :label "API URL (e.g. https://litellm.my-company.com)" :type "text"}
                     {:key "models" :label "Model names (comma-separated)" :type "text"}]}
           (f.providers/provider-login "litellm" nil (h/db*) {} (h/messenger) (h/metrics)))))
  (testing "several methods: the client chooses one"
    (is (match? {:action "choose-method" :methods [{:key "pro"} {:key "manual"}]}
                (f.providers/provider-login "openai" nil (h/db*) {} (h/messenger) (h/metrics)))))
  (testing "a manual key for an OAuth provider"
    (is (= {:action "input" :fields [{:key "api-key" :label "API Key" :type "secret"}]}
           (f.providers/provider-login "anthropic" "manual" (h/db*) {} (h/messenger) (h/metrics)))))
  (testing "a provider no module declares"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown provider"
                          (f.providers/provider-login "acme" nil (h/db*) {} (h/messenger) (h/metrics))))))

(deftest provider-login-input-test
  (let [saved* (atom [])]
    (with-redefs [config/update-global-config! #(swap! saved* conj %)
                  models/sync-models! (fn [& _] nil)
                  f.providers/reset-quota! (fn [& _] nil)]
      (testing "the declaration's config keys plus the answers"
        (f.providers/provider-login-input "deepseek" {:api-key "sk" :models "deepseek-chat, deepseek-reasoner"}
                                          (h/db*) {} (h/messenger) (h/metrics))
        (is (= {:providers {"deepseek" {:api "openai-chat"
                                        :url "https://api.deepseek.com"
                                        :key "sk"
                                        :models {"deepseek-chat" {} "deepseek-reasoner" {}}}}}
               (last @saved*)))
        (is (= {:step :login/done :type :auth/token} (get-in (h/db) [:auth "deepseek"]))))
      (testing "Azure saves what its chat login saves"
        (f.providers/provider-login-input "azure" {:api-key "k" :url "https://acme.openai.azure.com" :models "gpt-5"}
                                          (h/db*) {} (h/messenger) (h/metrics))
        (is (= {:providers {"azure" {:api "openai-responses"
                                     :completionUrlRelativePath "/openai/responses?api-version=2025-04-01-preview"
                                     :key "k"
                                     :url "https://acme.openai.azure.com"
                                     :models {"gpt-5" {}}}}}
               (last @saved*))))
      (testing "a default provider entry: only the key"
        (f.providers/provider-login-input "google" {:api-key "AIza"} (h/db*) {} (h/messenger) (h/metrics))
        (is (= {:providers {"google" {:key "AIza"}}} (last @saved*))))
      (testing "a manual key for an OAuth provider goes to the auth state, not the config"
        (reset! saved* [])
        (f.providers/provider-login-input "anthropic" {:api-key "sk-ant"} (h/db*) {} (h/messenger) (h/metrics))
        (is (= [] @saved*))
        (is (= {:step :login/done :type :auth/token :api-key "sk-ant" :mode :manual}
               (get-in (h/db) [:auth "anthropic"])))))))

(deftest login-config-test
  (is (= {:api "bedrock" :key "t" :url "https://x" :models {"a" {} "b" {}}}
         (f.providers/login-config {:label "AWS Bedrock" :api "bedrock" :login {:ask [:api-key]}}
                                   {:api-key "t" :url " https://x " :models "a,, b"}))))
