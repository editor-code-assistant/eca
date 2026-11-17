(ns eca.message-sanitize-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.message-sanitize :as message-sanitize]))

(def ^:private module-context
  {:type :text :text "<additionalContext>\nNotes.\n</additionalContext>" :module :acme/notes :data {:v 1}})

(def ^:private hook-context
  {:type :text :text "<additionalContext>\nA hook's.\n</additionalContext>"})

(defn- tool-output [& contents]
  {:role "tool_call_output" :content {:id "t1" :output {:error false :contents (vec contents)}}})

(deftest strip-internal-message-fields-test
  (testing "a module's context keeps its text for the model, without :module and :data"
    (is (= {:role "user" :content [{:type :text :text "hi"} (dissoc module-context :module :data) hook-context]}
           (message-sanitize/strip-internal-message-fields
            {:role "user" :content [{:type :text :text "hi"} module-context hook-context]
             :created-at 1 :content-id "c1"}))))
  (testing "also in a tool call's output"
    (is (= (tool-output {:type :text :text "ok"} (dissoc module-context :module :data))
           (message-sanitize/strip-internal-message-fields (tool-output {:type :text :text "ok"} module-context)))))
  (testing "a string content stays as it is"
    (is (= {:role "user" :content "hi"}
           (message-sanitize/strip-internal-message-fields {:role "user" :content "hi" :created-at 1})))))

(deftest without-module-contexts-test
  (testing "the contexts modules added go, a hook's stays"
    (is (= {:role "user" :content [{:type :text :text "hi"} hook-context]}
           (message-sanitize/without-module-contexts
            {:role "user" :content [{:type :text :text "hi"} module-context hook-context]}))))
  (testing "also in a tool call's output"
    (is (= (tool-output {:type :text :text "ok"})
           (message-sanitize/without-module-contexts (tool-output {:type :text :text "ok"} module-context)))))
  (testing "a string content stays as it is"
    (is (= {:role "user" :content "hi"} (message-sanitize/without-module-contexts {:role "user" :content "hi"})))))
