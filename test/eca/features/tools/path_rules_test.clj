(ns eca.features.tools.path-rules-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.features.rules :as f.rules]
   [eca.features.tools.path-rules :as f.tools.path-rules]
   [eca.shared :as shared]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(def ^:private fetch-rule-tool {:full-name "eca__fetch_rule"})

(def ^:private chat-id "chat-1")

(defn ^:private rule-match
  ([id] (rule-match id {}))
  ([id rule-extra]
   {:rule (merge {:id id :name (str "Rule " id)} rule-extra)
    :match {:match? true :matched-pattern "src/**"}}))

(defn ^:private base-db [& [extra]]
  (merge {:workspace-folders [{:uri (h/file-uri "file:///foo/bar") :name "foo"}]
          :chats {chat-id {:model "openai/gpt-5"}}}
         extra))

(deftest fetch-rule-available?-test
  (testing "true when eca__fetch_rule is in all-tools"
    (is (true? (f.tools.path-rules/fetch-rule-available?
                [{:full-name "eca__read_file"} fetch-rule-tool]))))
  (testing "false when not present"
    (is (false? (f.tools.path-rules/fetch-rule-available? [{:full-name "eca__read_file"}]))))
  (testing "false for empty or nil all-tools"
    (is (false? (f.tools.path-rules/fetch-rule-available? [])))
    (is (false? (f.tools.path-rules/fetch-rule-available? nil)))))

(deftest record-and-validated-rule?-test
  (testing "rule not validated on empty db"
    (is (false? (f.tools.path-rules/validated-rule? {} chat-id "rule-1"))))
  (testing "validated after recording"
    (let [db* (atom {})]
      (f.tools.path-rules/record-validated-rule! db* chat-id {:id "rule-1"} {:matched-pattern "src/**"})
      (is (true? (f.tools.path-rules/validated-rule? @db* chat-id "rule-1")))
      (testing "other rule ids still not validated"
        (is (false? (f.tools.path-rules/validated-rule? @db* chat-id "rule-2"))))
      (testing "other chats not affected"
        (is (false? (f.tools.path-rules/validated-rule? @db* "chat-2" "rule-1"))))
      (testing "recording is idempotent (set semantics)"
        (f.tools.path-rules/record-validated-rule! db* chat-id {:id "rule-1"} nil)
        (is (= #{"rule-1"} (get-in @db* [:chats chat-id :validated-path-rules])))))))

(deftest enforce-on-modify?-test
  (testing "nil enforce defaults to modify (backwards compatible)"
    (is (true? (f.tools.path-rules/enforce-on-modify? {}))))
  (testing "explicit modify"
    (is (true? (f.tools.path-rules/enforce-on-modify? {:enforce ["modify"]}))))
  (testing "modify together with read"
    (is (true? (f.tools.path-rules/enforce-on-modify? {:enforce ["read" "modify"]}))))
  (testing "read-only rule is not enforced on modify"
    (is (not (f.tools.path-rules/enforce-on-modify? {:enforce ["read"]}))))
  (testing "empty enforce vector disables modify enforcement (differs from nil)"
    (is (not (f.tools.path-rules/enforce-on-modify? {:enforce []})))))

(deftest enforce-on-read?-test
  (testing "nil enforce is not enforced on read"
    (is (not (f.tools.path-rules/enforce-on-read? {}))))
  (testing "explicit read"
    (is (true? (f.tools.path-rules/enforce-on-read? {:enforce ["read"]}))))
  (testing "read together with modify"
    (is (true? (f.tools.path-rules/enforce-on-read? {:enforce ["modify" "read"]}))))
  (testing "modify-only rule is not enforced on read"
    (is (not (f.tools.path-rules/enforce-on-read? {:enforce ["modify"]})))))

(deftest applicable-path-scoped-rules-test
  (testing "nil when fetch_rule tool is not available"
    (is (nil? (f.tools.path-rules/applicable-path-scoped-rules
               {} (base-db) chat-id "code" [{:full-name "eca__read_file"}] (h/file-path "/foo/bar/src/a.clj")))))
  (testing "nil when target-path is blank or nil"
    (is (nil? (f.tools.path-rules/applicable-path-scoped-rules
               {} (base-db) chat-id "code" [fetch-rule-tool] "")))
    (is (nil? (f.tools.path-rules/applicable-path-scoped-rules
               {} (base-db) chat-id "code" [fetch-rule-tool] nil))))
  (testing "delegates to f.rules/matching-path-scoped-rules with db-derived args"
    (let [called-args* (atom nil)
          matches [(rule-match "rule-1")]
          db (base-db)
          target-path (h/file-path "/foo/bar/src/a.clj")]
      (with-redefs [f.rules/matching-path-scoped-rules
                    (fn [config roots agent full-model path]
                      (reset! called-args* {:config config :roots roots :agent agent
                                            :full-model full-model :path path})
                      matches)]
        (is (= matches (f.tools.path-rules/applicable-path-scoped-rules
                        {:some "config"} db chat-id "code" [fetch-rule-tool] target-path)))
        (is (match? {:config {:some "config"}
                     :roots (:workspace-folders db)
                     :agent "code"
                     :full-model "openai/gpt-5"
                     :path target-path}
                    @called-args*))))))

(deftest missing-path-scoped-rules-for-modify-test
  (let [target-path (h/file-path "/foo/bar/src/a.clj")]
    (testing "nil when target-path is nil"
      (is (nil? (f.tools.path-rules/missing-path-scoped-rules-for-modify
                 {} (base-db) chat-id "code" [fetch-rule-tool] nil))))
    (with-redefs [shared/normalize-path (fn [p] p)]
      (testing "nil when no rules match"
        (with-redefs [f.rules/matching-path-scoped-rules (constantly [])]
          (is (nil? (f.tools.path-rules/missing-path-scoped-rules-for-modify
                     {} (base-db) chat-id "code" [fetch-rule-tool] target-path)))))
      (testing "keeps default and modify rules, drops read-only rules"
        (let [default-rule (rule-match "rule-default")
              modify-rule (rule-match "rule-modify" {:enforce ["modify"]})
              read-rule (rule-match "rule-read" {:enforce ["read"]})]
          (with-redefs [f.rules/matching-path-scoped-rules (constantly [default-rule modify-rule read-rule])]
            (is (match? [{:rule {:id "rule-default"}}
                         {:rule {:id "rule-modify"}}]
                        (f.tools.path-rules/missing-path-scoped-rules-for-modify
                         {} (base-db) chat-id "code" [fetch-rule-tool] target-path))))))
      (testing "already validated rules are removed"
        (with-redefs [f.rules/matching-path-scoped-rules (constantly [(rule-match "rule-1") (rule-match "rule-2")])]
          (let [db (base-db {:chats {chat-id {:model "openai/gpt-5"
                                              :validated-path-rules #{"rule-1"}}}})]
            (is (match? [{:rule {:id "rule-2"}}]
                        (f.tools.path-rules/missing-path-scoped-rules-for-modify
                         {} db chat-id "code" [fetch-rule-tool] target-path))))))
      (testing "nil when all matching rules are validated"
        (with-redefs [f.rules/matching-path-scoped-rules (constantly [(rule-match "rule-1")])]
          (let [db (base-db {:chats {chat-id {:model "openai/gpt-5"
                                              :validated-path-rules #{"rule-1"}}}})]
            (is (nil? (f.tools.path-rules/missing-path-scoped-rules-for-modify
                       {} db chat-id "code" [fetch-rule-tool] target-path)))))))))

(deftest missing-path-scoped-rules-for-read-test
  (let [target-path (h/file-path "/foo/bar/src/a.clj")]
    (with-redefs [shared/normalize-path (fn [p] p)]
      (testing "only read-enforced rules are returned"
        (let [default-rule (rule-match "rule-default")
              read-rule (rule-match "rule-read" {:enforce ["read"]})
              both-rule (rule-match "rule-both" {:enforce ["read" "modify"]})]
          (with-redefs [f.rules/matching-path-scoped-rules (constantly [default-rule read-rule both-rule])]
            (is (match? [{:rule {:id "rule-read"}}
                         {:rule {:id "rule-both"}}]
                        (f.tools.path-rules/missing-path-scoped-rules-for-read
                         {} (base-db) chat-id "code" [fetch-rule-tool] target-path))))))
      (testing "nil when only default/modify rules match"
        (with-redefs [f.rules/matching-path-scoped-rules (constantly [(rule-match "rule-default")
                                                                      (rule-match "rule-modify" {:enforce ["modify"]})])]
          (is (nil? (f.tools.path-rules/missing-path-scoped-rules-for-read
                     {} (base-db) chat-id "code" [fetch-rule-tool] target-path))))))))

(deftest missing-path-scoped-rules-error-test
  (testing "builds an error content listing each missing rule"
    (let [target-path (h/file-path "/foo/bar/src/a.clj")
          missing-rules [{:rule {:id "rule-1" :name "Clojure style"}
                          :match {:matched-pattern "src/**/*.clj"}}
                         {:rule {:id "rule-2" :name "Docs rule"}
                          :match {:matched-pattern "src/**"}}]
          result (f.tools.path-rules/missing-path-scoped-rules-error target-path missing-rules "modifying")
          text (-> result :contents first :text)]
      (is (match? {:error true
                   :contents [{:type :text}]}
                  result))
      (is (string/includes? text (str "Path-scoped rules must be fetched before modifying '" target-path "'.")))
      (is (string/includes? text "- Clojure style\n  id: rule-1"))
      (is (string/includes? text "matched-pattern: src/**/*.clj"))
      (is (string/includes? text "- Docs rule\n  id: rule-2"))
      (is (string/includes? text "matched-pattern: src/**"))
      (is (string/includes? text "call `fetch_rule` with this exact `id` and `path`"))))
  (testing "action is interpolated"
    (let [result (f.tools.path-rules/missing-path-scoped-rules-error
                  (h/file-path "/foo/a.md") [(rule-match "rule-1")] "reading")]
      (is (string/includes? (-> result :contents first :text) "before reading")))))

(deftest require-fetched-path-scoped-rules-test
  (let [target-path (h/file-path "/foo/bar/src/a.clj")
        ctx {:config {}
             :db (base-db)
             :chat-id chat-id
             :agent "code"
             :all-tools [fetch-rule-tool]}]
    (with-redefs [shared/normalize-path (fn [p] p)]
      (testing "error when a modify-enforced rule was not fetched"
        (with-redefs [f.rules/matching-path-scoped-rules (constantly [(rule-match "rule-1")])]
          (let [result (f.tools.path-rules/require-fetched-path-scoped-rules target-path ctx)]
            (is (match? {:error true} result))
            (is (string/includes? (-> result :contents first :text) "before modifying"))
            (is (string/includes? (-> result :contents first :text) "id: rule-1")))))
      (testing "nil when no rules are missing"
        (with-redefs [f.rules/matching-path-scoped-rules (constantly [])]
          (is (nil? (f.tools.path-rules/require-fetched-path-scoped-rules target-path ctx)))))
      (testing "nil when matching rule is read-only"
        (with-redefs [f.rules/matching-path-scoped-rules (constantly [(rule-match "rule-1" {:enforce ["read"]})])]
          (is (nil? (f.tools.path-rules/require-fetched-path-scoped-rules target-path ctx))))))))

(deftest require-fetched-path-scoped-rules-for-read-test
  (let [target-path (h/file-path "/foo/bar/src/a.clj")
        ctx {:config {}
             :db (base-db)
             :chat-id chat-id
             :agent "code"
             :all-tools [fetch-rule-tool]}]
    (with-redefs [shared/normalize-path (fn [p] p)]
      (testing "error when a read-enforced rule was not fetched"
        (with-redefs [f.rules/matching-path-scoped-rules (constantly [(rule-match "rule-1" {:enforce ["read"]})])]
          (let [result (f.tools.path-rules/require-fetched-path-scoped-rules-for-read target-path ctx)]
            (is (match? {:error true} result))
            (is (string/includes? (-> result :contents first :text) "before reading")))))
      (testing "nil when matching rule is modify-only (default)"
        (with-redefs [f.rules/matching-path-scoped-rules (constantly [(rule-match "rule-1")])]
          (is (nil? (f.tools.path-rules/require-fetched-path-scoped-rules-for-read target-path ctx)))))
      (testing "nil when fetch_rule tool unavailable"
        (with-redefs [f.rules/matching-path-scoped-rules (constantly [(rule-match "rule-1" {:enforce ["read"]})])]
          (is (nil? (f.tools.path-rules/require-fetched-path-scoped-rules-for-read
                     target-path (assoc ctx :all-tools [])))))))))
