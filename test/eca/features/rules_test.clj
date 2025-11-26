(ns eca.features.rules-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.features.rules :as f.rules]
   [eca.test-helper :as h]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(deftest all-test
  (testing "absolute config rule"
    (with-redefs [clojure.core/slurp (constantly "MY_RULE_CONTENT")
                  fs/absolute? (constantly true)
                  fs/exists? #(= (h/file-path "/path/to/my-rule.md") %)
                  fs/canonicalize identity
                  fs/file-name (constantly "cool-name")]
      (let [config {:rules [{:path (h/file-path "/path/to/my-rule.md")}]}]
        (is (match?
             (m/embeds [{:type :user-config
                         :path (h/file-path "/path/to/my-rule.md")
                         :name "cool-name"
                         :content "MY_RULE_CONTENT"}])
             (f.rules/all config []))))))

  (testing "relative config rule"
    (with-redefs [fs/absolute? (constantly false)
                  fs/exists? #(contains? #{(h/file-path "/my/project/.eca/rules")
                                           (h/file-path "/my/project/.foo/cool-rule.md")} (str %))
                  fs/glob (constantly [])
                  fs/canonicalize identity
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "MY_RULE_CONTENT")]
      (let [config {:rules [{:path ".foo/cool-rule.md"}]}
            roots [{:uri (h/file-uri "file:///my/project")}]]
        (is (match?
             (m/embeds [{:type :user-config
                         :path (h/file-path "/my/project/.foo/cool-rule.md")
                         :name "cool-name"
                         :content "MY_RULE_CONTENT"}])
             (f.rules/all config roots))))))

  (testing "local file rules"
    (with-redefs [fs/exists? #(= (h/file-path "/my/project/.eca/rules") (str %))
                  fs/glob (constantly [(fs/path "cool.md")])
                  fs/canonicalize identity
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "MY_RULE_CONTENT")]
      (let [roots [{:uri (h/file-uri "file:///my/project")}]]
        (is (match?
             (m/embeds [{:type :user-local-file
                         :path "cool.md"
                         :name "cool-name"
                         :content "MY_RULE_CONTENT"}])
             (f.rules/all {} roots))))))
  (testing "global file rules"
    (with-redefs [config/get-env (constantly (h/file-path "/home/someuser/.config"))
                  fs/exists? #(= (h/file-path "/home/someuser/.config/eca/rules") (str %))
                  fs/glob (constantly [(fs/path "cool.md")])
                  fs/canonicalize identity
                  fs/file-name (constantly "cool-name")
                  clojure.core/slurp (constantly "MY_RULE_CONTENT")]
      (let [roots [{:uri (h/file-uri "file:///my/project")}]]
        (is (match?
             (m/embeds [{:type :user-global-file
                         :path "cool.md"
                         :name "cool-name"
                         :content "MY_RULE_CONTENT"}])
             (f.rules/all {} roots)))))))
