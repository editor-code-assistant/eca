(ns eca.features.tools.tool-search-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.tools.tool-search :as f.tools.tool-search]
   [eca.features.tools.util :as tools.util]))

(def ^:private deferred-tools
  [{:full-name "github__create_pull_request"
    :description "Create a pull request on GitHub"
    :parameters {:type "object" :properties {"title" {:type "string"}}}
    :deferrable true
    :deferred true}
   {:full-name "postgres__run_query"
    :description "Run a SQL query against the database"
    :parameters {:type "object" :properties {"sql" {:type "string"}}}
    :deferrable true
    :deferred true}])

(def ^:private all-tools
  (conj deferred-tools
        {:full-name "eca__read_file" :description "Read a file" :parameters {}}))

(defn ^:private search [db* arguments]
  ((:handler (get f.tools.tool-search/definitions "search_tools"))
   arguments
   {:db* db* :chat-id "chat-1" :all-tools all-tools}))

(deftest search-tools-test
  (testing "matching tools are returned with their schema and activated for the chat"
    (let [db* (atom {})
          result (search db* {"query" "pull request"})
          text (-> result :contents first :text)]
      (is (false? (:error result)))
      (is (string/includes? text "github__create_pull_request"))
      (is (string/includes? text "\"title\""))
      (is (not (string/includes? text "postgres__run_query")))
      (is (= #{"github__create_pull_request"}
             (tools.util/activated-deferred-tools @db* "chat-1")))))

  (testing "matches on tool name too"
    (let [db* (atom {})]
      (search db* {"query" "run_query"})
      (is (= #{"postgres__run_query"}
             (tools.util/activated-deferred-tools @db* "chat-1")))))

  (testing "activations accumulate across searches"
    (let [db* (atom {})]
      (search db* {"query" "pull request"})
      (search db* {"query" "sql database"})
      (is (= #{"github__create_pull_request" "postgres__run_query"}
             (tools.util/activated-deferred-tools @db* "chat-1")))))

  (testing "blank query lists every deferred tool"
    (let [db* (atom {})
          text (-> (search db* {}) :contents first :text)]
      (is (string/includes? text "github__create_pull_request"))
      (is (string/includes? text "postgres__run_query"))))

  (testing "max_results limits how many tools are loaded"
    (let [db* (atom {})]
      (search db* {"max_results" 1})
      (is (= 1 (count (tools.util/activated-deferred-tools @db* "chat-1"))))))

  (testing "non deferred tools are never returned"
    (let [db* (atom {})
          text (-> (search db* {"query" "read file"}) :contents first :text)]
      (is (not (string/includes? text "eca__read_file")))))

  (testing "no match lists the available deferred tools without activating any"
    (let [db* (atom {})
          result (search db* {"query" "kubernetes"})
          text (-> result :contents first :text)]
      (is (false? (:error result)))
      (is (string/includes? text "Available deferred tools"))
      (is (empty? (tools.util/activated-deferred-tools @db* "chat-1")))))

  (testing "errors when there is nothing deferred"
    (let [db* (atom {})
          result ((:handler (get f.tools.tool-search/definitions "search_tools"))
                  {"query" "anything"}
                  {:db* db* :chat-id "chat-1" :all-tools [{:full-name "eca__read_file"}]})]
      (is (true? (:error result))))))
