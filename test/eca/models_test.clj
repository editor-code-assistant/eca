(ns eca.models-test
  (:require [clojure.test :refer [deftest is testing]]
            [eca.llm-util :as llm-util]
            [eca.models :as models]
            [matcher-combinators.test :refer [match?]]))

(set! *warn-on-reflection* true)

(deftest fetch-compatible-models-test
  (testing "Successful model fetching from /models endpoint"
    (with-redefs [hato.client/get (constantly {:status 200
                                              :body {:data [{:id "gpt-4"}
                                                            {:id "gpt-4-turbo"}
                                                            {:id "gpt-3.5-turbo"}]}})]
      (is (match?
           {"gpt-4" {}
            "gpt-4-turbo" {}
            "gpt-3.5-turbo" {}}
           (#'models/fetch-compatible-models
            {:api-url "https://api.example.com"
             :api-key "sk-test"
             :provider "test-provider"})))))

  (testing "Returns nil when api-url is nil"
    (is (nil? (#'models/fetch-compatible-models
               {:api-url nil
                :api-key "sk-test"
                :provider "test"}))))

  (testing "Returns nil when models data is empty"
    (with-redefs [hato.client/get (constantly {:status 200 :body {:data []}})]
      (is (nil? (#'models/fetch-compatible-models
                 {:api-url "https://api.example.com"
                  :api-key "sk-test"
                  :provider "test"})))))

  (testing "Handles non-200 error gracefully"
    (with-redefs [hato.client/get (constantly {:status 500 :body {}})]
      (is (nil? (#'models/fetch-compatible-models
                 {:api-url "https://api.example.com"
                  :api-key "sk-test"
                  :provider "test"})))))

  (testing "Handles network exception gracefully"
    (with-redefs [hato.client/get (fn [_] (throw (ex-info "Network error" {})))]
      (is (nil? (#'models/fetch-compatible-models
                 {:api-url "https://api.example.com"
                  :api-key "sk-test"
                  :provider "test"})))))

  (testing "Filters out models without id"
    (with-redefs [hato.client/get (constantly {:status 200
                                              :body {:data [{:id "gpt-4"}
                                                            {:name "no-id-model"}]}})]
      (is (match?
           {"gpt-4" {}}
           (#'models/fetch-compatible-models
            {:api-url "https://api.example.com"
             :api-key "sk-test"
             :provider "test"}))))))

(deftest provider-with-fetch-models?-test
  (testing "Returns true when fetchModels is true"
    (is (true? (#'models/provider-with-fetch-models?
               {:api "openai-chat" :fetchModels true}))))

  (testing "Returns false when fetchModels is not set"
    (is (false? (#'models/provider-with-fetch-models?
                {:api "openai-chat"}))))

  (testing "Returns false when fetchModels is false"
    (is (false? (#'models/provider-with-fetch-models?
                {:api "openai-chat" :fetchModels false}))))

  (testing "Returns nil (falsy) when api is not set"
    (is (nil? (#'models/provider-with-fetch-models?
              {:fetchModels true})))))

(deftest merge-provider-models-test
  (testing "Static models override dynamic ones"
    (let [dynamic {"gpt-4" {}
                   "gpt-3.5" {}}
          static {"gpt-4" {:extraPayload {:temp 0.5}}}]
      (is (match?
           {"gpt-4" {:extraPayload {:temp 0.5}}
            "gpt-3.5" {}}
           (#'models/merge-provider-models static dynamic)))))

  (testing "Works with empty static models"
    (let [dynamic {"gpt-4" {} "gpt-3.5" {}}]
      (is (match?
           {"gpt-4" {} "gpt-3.5" {}}
           (#'models/merge-provider-models {} dynamic)))))

  (testing "Works with empty dynamic models"
    (let [static {"gpt-4" {:extraPayload {:temp 0.5}}}]
      (is (match?
           {"gpt-4" {:extraPayload {:temp 0.5}}}
           (#'models/merge-provider-models static {}))))))

(deftest build-model-capabilities-test
  (testing "Uses model-name from config when present"
    (let [all-models {"provider/gpt-4-turbo" {:tools true :reason? false :web-search false}}
          result (#'models/build-model-capabilities
                  all-models
                  "provider"
                  "gpt-4"
                  {:modelName "gpt-4-turbo"})]
      (is (match?
           ["provider/gpt-4" {:tools true :reason? false :web-search false :model-name "gpt-4-turbo"}]
           result))))

  (testing "Merges with known capabilities"
    (let [all-models {"provider/gpt-4" {:tools true :reason? true :web-search true}}
          result (#'models/build-model-capabilities
                  all-models
                  "provider"
                  "gpt-4"
                  {})]
      (is (match?
           ["provider/gpt-4" {:tools true :reason? true :web-search true :model-name "gpt-4"}]
           result))))

  (testing "Uses default capabilities for unknown models"
    (let [all-models {}
          result (#'models/build-model-capabilities
                  all-models
                  "unknown-provider"
                  "unknown-model"
                  {})]
      (is (match?
           ["unknown-provider/unknown-model"
            {:tools true :reason? true :web-search false :model-name "unknown-model"}]
           result)))))

(deftest fetch-dynamic-provider-models-test
  (testing "Fetches models for providers with fetchModels enabled"
    (with-redefs [hato.client/get (constantly {:status 200
                                              :body {:data [{:id "gpt-4"}]}})
                  llm-util/provider-api-url (constantly "https://api.example.com")
                  llm-util/provider-api-key (constantly [:auth/token "sk-test"])]
      (let [config {:providers {"provider1" {:api "openai-chat" :fetchModels true}
                                "provider2" {:api "openai-chat" :fetchModels false}}}
            db {:auth {"provider1" "auth-data"}}
            result (#'models/fetch-dynamic-provider-models config db)]
        (is (match?
             {"provider1" {"gpt-4" {}}}
             result)))))

  (testing "Skips providers without fetchModels"
    (with-redefs [hato.client/get (constantly {:status 200
                                              :body {:data [{:id "gpt-4"}]}})]
      (let [config {:providers {"provider1" {:api "openai-chat" :fetchModels false}
                                "provider2" {:api "openai-chat"}}}
            db {:auth {}}
            result (#'models/fetch-dynamic-provider-models config db)]
        (is (empty? result)))))

  (testing "Handles failed fetches gracefully"
    (with-redefs [hato.client/get (constantly {:status 500 :body {}})
                  llm-util/provider-api-url (constantly "https://api.example.com")
                  llm-util/provider-api-key (constantly [:auth/token "sk-test"])]
      (let [config {:providers {"provider1" {:api "openai-chat" :fetchModels true}}}
            db {:auth {}}
            result (#'models/fetch-dynamic-provider-models config db)]
        (is (empty? result))))))
