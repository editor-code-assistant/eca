(ns eca.features.hooks-test
  (:require
   [babashka.process :as p]
   [clojure.test :refer [deftest is testing]]
   [eca.features.hooks :as f.hooks]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(defn set-action-payload [a*]
  (fn [p]
    (reset! a* p)))

(deftest trigger-if-matches!-test
  (testing "prePrompt"
    (h/reset-components!)
    (h/config! {:hooks {"my-hook" {:type "prePrompt"
                                   :actions [{:type "shell"
                                              :shell "echo hey"}]}}})
    (let [on-before-action* (atom nil)
          on-after-action* (atom nil)]
      (with-redefs [p/sh (constantly {:exit 0 :out "hey" :err nil})]
        (f.hooks/trigger-if-matches!
         :prePrompt
         {:foo "1"}
         {:on-before-action (set-action-payload on-before-action*)
          :on-after-action (set-action-payload on-after-action*)}
         (h/db)
         (h/config)))
      (is (match?
           {:id string?
            :name "my-hook"}
           @on-before-action*))
      (is (match?
           {:id string?
            :name "my-hook"
            :status 0
            :output "hey"
            :error nil}
           @on-after-action*))))
  (testing "preToolCall does not matches"
    (h/reset-components!)
    (h/config! {:hooks {"my-hook" {:type "preToolCall"
                                   :matcher "my-mcp__my.*"
                                   :actions [{:type "shell"
                                              :shell "echo hey"}]}}})
    (let [on-before-action* (atom nil)
          on-after-action* (atom nil)]
      (with-redefs [p/sh (constantly {:exit 2 :out nil :err "stop!"})]
        (f.hooks/trigger-if-matches!
         :preToolCall
         {:server "my-other-mcp"
          :tool-name "my-tool"}
         {:on-before-action (set-action-payload on-before-action*)
          :on-after-action (set-action-payload on-after-action*)}
         (h/db)
         (h/config)))
      (is (match?
           nil
           @on-before-action*))
      (is (match?
           nil
           @on-after-action*))))
  (testing "preToolCall matches"
    (h/reset-components!)
    (h/config! {:hooks {"my-hook" {:type "preToolCall"
                                   :matcher "my-mcp__my.*"
                                   :actions [{:type "shell"
                                              :shell "echo hey"}]}}})
    (let [on-before-action* (atom nil)
          on-after-action* (atom nil)]
      (with-redefs [p/sh (constantly {:exit 2 :out nil :err "stop!"})]
        (f.hooks/trigger-if-matches!
         :preToolCall
         {:server "my-mcp"
          :tool-name "my-tool"}
         {:on-before-action (set-action-payload on-before-action*)
          :on-after-action (set-action-payload on-after-action*)}
         (h/db)
         (h/config)))
      (is (match?
           {:id string?
            :name "my-hook"}
           @on-before-action*))
      (is (match?
           {:id string?
            :name "my-hook"
            :status 2
            :output nil
            :error "stop!"}
           @on-after-action*)))))
