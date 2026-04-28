(ns eca.features.completion-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.db :as db]
   [eca.features.completion :as f.completion]
   [eca.features.login :as f.login]
   [eca.llm-api :as llm-api]
   [eca.test-helper :as h]))

(h/reset-components-before-test)

(deftest complete-renews-expired-auth-test
  (testing "Renews expired auth before requesting inline completion"
    (h/reset-components!)
    (h/config! {:env "test"
                :completion {:model "github-copilot/gpt-4o"
                             :systemPrompt "Complete the code."}})
    (swap! (h/db*) assoc
           :models {"github-copilot/gpt-4o" {:reason? true
                                              :tools true
                                              :web-search true}})
    (swap! (h/db*) assoc-in [:auth "github-copilot"] {:api-key "expired"
                                                        :expires-at 1})
    (let [login-ctx* (atom nil)
          api-opts* (atom nil)
          result (with-redefs [db/update-global-cache! (fn [& _] nil)
                               f.login/login-step
                               (fn [{:keys [db*] :as ctx}]
                                 (reset! login-ctx* ctx)
                                 (swap! db* assoc-in [:auth "github-copilot"] {:api-key "renewed"
                                                                                 :expires-at 9999999999}))
                               llm-api/sync-prompt!
                               (fn [opts]
                                 (reset! api-opts* opts)
                                 {:output-text "completion"})]
                   (f.completion/complete {:doc-text "abc"
                                           :doc-version 3
                                           :position {:line 1 :character 4}}
                                          (h/db*) (h/config) (h/messenger) (h/metrics)))]
      (is (= "github-copilot" (:provider @login-ctx*)))
      (is (= :login/renew-token (:step @login-ctx*)))
      (is (= "github-copilot" (:provider @api-opts*)))
      (is (= "gpt-4o" (:model @api-opts*)))
      (is (= {:api-key "renewed" :expires-at 9999999999} (:provider-auth @api-opts*)))
      (is (= {:items [{:text "completion"
                       :doc-version 3
                       :range {:start {:line 1 :character 4}
                               :end {:line 1 :character 4}}}]}
             result)))))
