(ns eca.features.tools.mcp.clojure-mcp-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.tools :as f.tools]
   [eca.features.tools.mcp :as f.mcp]
   [matcher-combinators.test :refer [match?]]))

(def ^:private example-diff
  (string/join "\n" ["--- original.txt"
                     "+++ revised.txt"
                     "@@ -1,1 +1,2 @@"
                     "-a"
                     "+b"
                     "+c"]))

(deftest tool-call-details-before-invocation-clojure-mcp-dry-run-test
  (testing "the dry run previewing the change is called with the chat id"
    (let [call* (atom nil)]
      (with-redefs [f.mcp/call-tool! (fn [server-name name args ctx]
                                       (reset! call* {:server-name server-name :name name
                                                      :args args :ctx ctx})
                                       {:error false :contents [{:type :text :text "new content"}]})]
        (is (match? {:type :fileChange
                     :path "/home/alice/my-org/my-proj/new.clj"
                     :linesAdded 1
                     :linesRemoved 0}
                    (f.tools/tool-call-details-before-invocation
                     "file_write"
                     {"file_path" "/home/alice/my-org/my-proj/new.clj" "content" "new content"}
                     {:name "clojure-mcp" :version "0.2.0"}
                     {} {} "chat-1" true "call-1"))))
      (is (match? {:server-name "clojure-mcp"
                   :name "file_write"
                   :args {"dry_run" "new-source"}
                   :ctx {:chat-id "chat-1"}}
                  @call*)))))

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
