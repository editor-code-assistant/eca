(ns eca.features.tools.mcp.clojure-mcp-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.tools :as f.tools]
   [matcher-combinators.test :refer [match?]]))

(def ^:private example-diff
  (string/join "\n" ["--- original.txt"
                     "+++ revised.txt"
                     "@@ -1,1 +1,2 @@"
                     "-a"
                     "+b"
                     "+c"]))

(deftest tool-call-details-after-invocation-clojure-mcp-clojure-edit-test
  (testing "Tool call details for the Clojure MCP clojure_edit tool"
    (is (match? {:type :fileChange
                 :path "/home/alice/my-org/my-proj/project.clj"
                 :linesAdded 2
                 :linesRemoved 1
                 :diff example-diff}
                (f.tools/tool-call-details-after-invocation
                 :clojure_edit
                 {"file_path" "/home/alice/my-org/my-proj/project.clj"
                  "form_identifier" "a"
                  "form_type" "atom"
                  "operation" "replace"
                  "content" "b\nc"}
                 nil
                 {:error false :contents [{:type :text :text example-diff}]}
                 nil)))))

(deftest tool-call-details-after-invocation-clojure-mcp-clojure-edit-replace-sexp-test
  (testing "Tool call details for the Clojure MCP clojure_edit_replace_sexp tool"
    (is (match? {:type :fileChange
                 :path "/home/alice/my-org/my-proj/project.clj"
                 :linesAdded 2
                 :linesRemoved 1
                 :diff example-diff}
                (f.tools/tool-call-details-after-invocation
                 :clojure_edit_replace_sexp
                 {"file_path" "/home/alice/my-org/my-proj/project.clj"
                  "match_form" "a"
                  "new_form" "b\nc"
                  "replace_all" false}
                 nil
                 {:error false :contents [{:type :text :text example-diff}]}
                 nil)))))

(deftest tool-call-details-after-invocation-clojure-mcp-file-edit-test
  (testing "Tool call details for the Clojure MCP file_edit tool"
    (is (match? {:type :fileChange
                 :path "/home/alice/my-org/my-proj/project.clj"
                 :linesAdded 2
                 :linesRemoved 1
                 :diff example-diff}
                (f.tools/tool-call-details-after-invocation
                 :file_edit
                 {"file_path" "/home/alice/my-org/my-proj/project.clj"
                  "old_string" "a"
                  "new_string" "b\nc"}
                 nil
                 {:error false :contents [{:type :text :text example-diff}]}
                 nil)))))

(deftest tool-call-details-after-invocation-clojure-mcp-file-write-test
  (testing "Tool call details for the Clojure MCP file_write tool"
    (is (match? {:type :fileChange
                 :path "/home/alice/my-org/my-proj/project.clj"
                 :linesAdded 2
                 :linesRemoved 1
                 :diff example-diff}
                (f.tools/tool-call-details-after-invocation
                 :file_write
                 {"file_path" "/home/alice/my-org/my-proj/project.clj"
                  "content" "my-content"}
                 nil
                 {:error false
                  :contents [{:type :text
                              :text (string/join "\n"
                                                 ["Clojure file updated: /home/alice/my-org/my-proj/project.clj"
                                                  "Changes:" example-diff])}]}
                 nil)))))
