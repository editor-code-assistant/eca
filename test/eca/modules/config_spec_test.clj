(ns eca.modules.config-spec-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.modules.config-spec :as config-spec]
   [matcher-combinators.test :refer [match?]]))

(def jira-spec
  {:jiraUrl {:type :string :doc "Jira base URL"}
   :jiraToken {:type :string :secret? true :default "${env:JIRA_TOKEN}"}})

(def filesystem-spec
  {:toolCall {:readFile {:maxLines {:type :number :default 2000}}}})

(deftest leaves-test
  (testing "a map with :type is a key, any other map a level"
    (is (= {[:jiraUrl] {:type :string :doc "Jira base URL"}
            [:jiraToken] {:type :string :secret? true :default "${env:JIRA_TOKEN}"}}
           (config-spec/leaves jira-spec)))
    (is (= {[:toolCall :readFile :maxLines] {:type :number :default 2000}}
           (config-spec/leaves filesystem-spec))))
  (testing "no spec"
    (is (= {} (config-spec/leaves nil)))))

(deftest defaults-test
  (is (= {:jiraToken "${env:JIRA_TOKEN}"} (config-spec/defaults jira-spec)))
  (is (= {:toolCall {:readFile {:maxLines 2000}}} (config-spec/defaults filesystem-spec)))
  (is (= {:mcpServers {}} (config-spec/defaults {:mcpServers {:type :map :default {}}}))))

(deftest problems-test
  (testing "valid trees"
    (is (= [] (config-spec/problems jira-spec)))
    (is (= [] (config-spec/problems filesystem-spec)))
    (is (= [] (config-spec/problems {:unii {:mode {:type :enum :values ["a" "b"] :default "a"}}})))
    (is (= [] (config-spec/problems nil))))
  (testing "invalid trees"
    (is (match? [#"x: :type must be one of"] (config-spec/problems {:x {:type :int}})))
    (is (match? [#"mode: an :enum needs a non-empty :values"] (config-spec/problems {:mode {:type :enum}})))
    (is (match? [#"x: :default 1 doesn't match :type :string"] (config-spec/problems {:x {:type :string :default 1}})))
    (is (match? [#"x: unknown spec keys :required"] (config-spec/problems {:x {:type :string :required true}})))
    (is (match? [#"a.b: an empty level"] (config-spec/problems {:a {:b {}}})))
    (is (match? [#"a: expected a key spec"] (config-spec/problems {:a 1})))
    (is (match? [#"config keys must be keywords"] (config-spec/problems {"a" {:type :string}})))
    (is (match? [#"must be a tree"] (config-spec/problems {:type :string})))
    (testing "a key named type can't be a level"
      (is (match? [#"foo: :type must be one of"] (config-spec/problems {:foo {:type {:bar {:type :string}}}}))))))

(deftest conflicts-test
  (let [declared {[:toolCall :readFile :maxLines] {:spec {:type :number :default 2000} :module :eca/filesystem}
                  [:mcpServers] {:spec {:type :map :default {}} :module :eca/mcp}}]
    (testing "the same key with the same spec is fine, docs aside"
      (is (= [] (config-spec/conflicts declared {:toolCall {:readFile {:maxLines {:type :number :default 2000
                                                                                   :doc "Other doc"}}}}))))
    (testing "the same key with another spec"
      (is (match? [#"toolCall.readFile.maxLines: :eca/filesystem declares it with another spec"]
                  (config-spec/conflicts declared {:toolCall {:readFile {:maxLines {:type :string}}}}))))
    (testing "a key where another module has a level"
      (is (match? [#"toolCall.readFile: other modules declare keys inside it"]
                  (config-spec/conflicts declared {:toolCall {:readFile {:type :map}}}))))
    (testing "a level where another module has a key"
      (is (match? [#"mcpServers.foo: :eca/mcp declares mcpServers as a key"]
                  (config-spec/conflicts declared {:mcpServers {:foo {:type :string}}}))))
    (testing "new keys"
      (is (= [] (config-spec/conflicts declared jira-spec))))))

(deftest type-problems-test
  (let [declared {[:jiraUrl] {:spec {:type :string}}
                  [:toolCall :readFile :maxLines] {:spec {:type :number}}
                  [:unii :mode] {:spec {:type :enum :values ["a" "b"]}}}]
    (is (= [] (config-spec/type-problems declared {:jiraUrl "x" :toolCall {:readFile {:maxLines 3}}})))
    (is (= [] (config-spec/type-problems declared {})))
    (is (match? [{:path [:jiraUrl] :type :string}
                 {:path [:toolCall :readFile :maxLines] :type :number}
                 {:path [:unii :mode] :type :enum}]
                (config-spec/type-problems declared {:jiraUrl 1
                                                     :toolCall {:readFile {:maxLines "3"}}
                                                     :unii {:mode "c"}})))))

(deftest unknown-root-keys-test
  (is (= #{:jiraUrll}
         (config-spec/unknown-root-keys {[:jiraUrl] {:spec {:type :string}}
                                         [:toolCall :readFile :maxLines] {:spec {:type :number}}}
                                        {:jiraUrll "x" :toolCall {}}))))

(deftest masked-test
  (is (= {:jiraUrl "x" :jiraToken "****"}
         (config-spec/masked {[:jiraUrl] {:spec {:type :string}}
                              [:jiraToken] {:spec {:type :string :secret? true}}}
                             {:jiraUrl "x" :jiraToken "secret"}))))

(deftest path-str-test
  (is (= "toolCall.readFile.maxLines" (config-spec/path-str [:toolCall :readFile :maxLines])))
  (is (= "modules.eca/mcp" (config-spec/path-str [:modules :eca/mcp]))))
