(ns eca.features.tools-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.tools :as f.tools]
   [eca.features.tools.filesystem :as f.tools.filesystem]
   [eca.test-helper :as h]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(deftest all-tools-test
  (testing "Include mcp tools"
    (is (match?
         (m/embeds [{:name "eval"
                     :description "eval code"
                     :parameters {"type" "object"
                                  :properties {"code" {:type "string"}}}
                     :origin :mcp}])
         (f.tools/all-tools {:mcp-clients {:clojureMCP
                                           {:tools [{:name "eval"
                                                     :description "eval code"
                                                     :parameters {"type" "object"
                                                                  :properties {"code" {:type "string"}}}}]}}}
                            {}))))
  (testing "Include enabled native tools"
    (is (match?
         (m/embeds [{:name "eca_directory_tree"
                     :description string?
                     :parameters some?
                     :origin :native}])
         (f.tools/all-tools {} {:nativeTools {:filesystem {:enabled true}}}))))
  (testing "Do not include disabled native tools"
    (is (match?
         (m/embeds [(m/mismatch {:name "eca_directory_tree"})])
         (f.tools/all-tools {} {:nativeTools {:filesystem {:enabled false}}}))))
  (testing "Replace special vars description"
    (is (match?
         (m/embeds [{:name "eca_directory_tree"
                     :description (format "Only in %s" (h/file-path "/path/to/project/foo"))
                     :parameters some?
                     :origin :native}])
         (with-redefs [f.tools.filesystem/definitions {"eca_directory_tree" {:description "Only in $workspaceRoots"
                                                                             :parameters {}}}]
           (f.tools/all-tools {:workspace-folders [{:name "foo" :uri (h/file-uri "file:///path/to/project/foo")}]}
                              {:nativeTools {:filesystem {:enabled true}}}))))))
