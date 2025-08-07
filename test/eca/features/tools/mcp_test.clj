(ns eca.features.tools.mcp-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.tools.mcp :as mcp]))

(deftest all-tools-test
  (testing "empty db"
    (is (= []
           (mcp/all-tools {}))))

  (testing "db with no mcp-clients"
    (is (= []
           (mcp/all-tools {:some-other-key "value"}))))

  (testing "db with empty mcp-clients"
    (is (= []
           (mcp/all-tools {:mcp-clients {}}))))

  (testing "db with mcp-clients but no tools"
    (is (= []
           (mcp/all-tools {:mcp-clients {"server1" {}}}))))

  (testing "db with single server with tools"
    (let [tools [{:name "tool1" :description "desc1"}
                 {:name "tool2" :description "desc2"}]]
      (is (= tools
             (mcp/all-tools {:mcp-clients {"server1" {:tools tools}}})))))

  (testing "db with multiple servers with tools"
    (let [tools1 [{:name "tool1" :description "desc1"}]
          tools2 [{:name "tool2" :description "desc2"}
                  {:name "tool3" :description "desc3"}]]
      (is (= (concat tools1 tools2)
             (mcp/all-tools {:mcp-clients {"server1" {:tools tools1}
                                           "server2" {:tools tools2}}}))))))

(deftest all-prompts-test
  (testing "empty db"
    (is (= []
           (mcp/all-prompts {}))))

  (testing "db with no mcp-clients"
    (is (= []
           (mcp/all-prompts {:some-other-key "value"}))))

  (testing "db with empty mcp-clients"
    (is (= []
           (mcp/all-prompts {:mcp-clients {}}))))

  (testing "db with mcp-clients but no prompts"
    (is (= []
           (mcp/all-prompts {:mcp-clients {"server1" {}}}))))

  (testing "db with single server with prompts"
    (let [prompts [{:name "prompt1" :description "desc1"}
                   {:name "prompt2" :description "desc2"}]
          expected (mapv #(assoc % :server "server1") prompts)]
      (is (= expected
             (mcp/all-prompts {:mcp-clients {"server1" {:prompts prompts}}})))))

  (testing "db with multiple servers with prompts"
    (let [prompts1 [{:name "prompt1" :description "desc1"}]
          prompts2 [{:name "prompt2" :description "desc2"}
                    {:name "prompt3" :description "desc3"}]
          expected (concat
                    (mapv #(assoc % :server "server1") prompts1)
                    (mapv #(assoc % :server "server2") prompts2))]
      (is (= expected
             (mcp/all-prompts {:mcp-clients {"server1" {:prompts prompts1}
                                             "server2" {:prompts prompts2}}}))))))

(deftest all-resources-test
  (testing "empty db"
    (is (= []
           (mcp/all-resources {}))))

  (testing "db with no mcp-clients"
    (is (= []
           (mcp/all-resources {:some-other-key "value"}))))

  (testing "db with empty mcp-clients"
    (is (= []
           (mcp/all-resources {:mcp-clients {}}))))

  (testing "db with mcp-clients but no resources"
    (is (= []
           (mcp/all-resources {:mcp-clients {"server1" {}}}))))

  (testing "db with single server with resources"
    (let [resources [{:uri "file://test1" :name "resource1"}
                     {:uri "file://test2" :name "resource2"}]
          expected (mapv #(assoc % :server "server1") resources)]
      (is (= expected
             (mcp/all-resources {:mcp-clients {"server1" {:resources resources}}})))))

  (testing "db with multiple servers with resources"
    (let [resources1 [{:uri "file://test1" :name "resource1"}]
          resources2 [{:uri "file://test2" :name "resource2"}
                      {:uri "file://test3" :name "resource3"}]
          expected (concat
                    (mapv #(assoc % :server "server1") resources1)
                    (mapv #(assoc % :server "server2") resources2))]
      (is (= expected
             (mcp/all-resources {:mcp-clients {"server1" {:resources resources1}
                                               "server2" {:resources resources2}}}))))))

(deftest shutdown!-test
  (testing "shutdown with no clients"
    (let [db* (atom {})]
      (mcp/shutdown! db*)
      (is (= {:mcp-clients {}} @db*))))

  (testing "shutdown with empty mcp-clients"
    (let [db* (atom {:mcp-clients {}})]
      (mcp/shutdown! db*)
      (is (= {:mcp-clients {}} @db*))))

  (testing "shutdown preserves other db data"
    (let [db* (atom {:mcp-clients {}
                     :workspace-folders []
                     :other-key "value"})]
      (mcp/shutdown! db*)
      (is (= {:mcp-clients {}
              :workspace-folders []
              :other-key "value"} @db*)))))
