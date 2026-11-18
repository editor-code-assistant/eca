(ns eca.oauth-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.oauth :as oauth]))

(deftest generate-pkce-test
  (testing "generates verifier and challenge"
    (let [{:keys [verifier challenge]} (oauth/generate-pkce)]
      (is (string? verifier))
      (is (string? challenge))
      (is (not= verifier challenge))
      (is (not (string/includes? challenge "="))
          "challenge should not contain padding"))))
