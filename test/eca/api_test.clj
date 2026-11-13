(ns eca.api-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.api :as eca]
   [eca.logger :as logger]
   [eca.modules :as modules]
   [eca.shared :as shared]
   [eca.test-helper :as h]))

(h/reset-components-before-test)

(deftest module-id-test
  (is (= :acme/x (eca/module-id {:module-id :acme/x})))
  (is (nil? (eca/module-id {}))))

(deftest config-test
  (testing "the ctx's config"
    (is (= {:jiraUrl "https://jira"} (eca/config {:config {:jiraUrl "https://jira"}}))))
  (testing "else ECA's config, from the db"
    (is (= "ask" (get-in (eca/config {:db* (h/db*)}) [:toolCall :approval :byDefault]))))
  (testing "nil without either, e.g. while config defaults are computed"
    (is (nil? (eca/config {:db {:workspace-folders []}})))))

(deftest state-test
  (with-redefs [modules/state (fn [id] (when (= :acme/x id) {:n 1}))]
    (is (= {:n 1} (eca/state {:module-id :acme/x})))
    (is (nil? (eca/state {:module-id :acme/y})))))

(deftest workspaces-test
  (swap! (h/db*) assoc :workspace-folders [{:uri (shared/filename->uri (h/file-path "/tmp/a"))}
                                           {:uri (shared/filename->uri (h/file-path "/tmp/b"))}])
  (is (= [(h/file-path "/tmp/a") (h/file-path "/tmp/b")] (eca/workspaces {:db* (h/db*)})))
  (is (= [(h/file-path "/tmp/a") (h/file-path "/tmp/b")] (eca/workspaces {:db (h/db)}))))

(deftest log-test
  (testing "logs with the module id, and returns nil"
    (let [logged (atom nil)]
      (with-redefs [logger/warn (fn [& args] (reset! logged args))]
        (is (nil? (eca/log {:module-id :acme/x} :warn "careful" 42))))
      (is (= ["[MODULE acme/x]" "careful" 42] @logged)))))

(deftest results-test
  (is (= {:error false :contents [{:type :text :text "done"}]} (eca/text "done")))
  (is (= {:error true :contents [{:type :text :text "boom"}]} (eca/error "boom"))))
