(ns eca.features.tools-modules-test
  "Built-in tools come from the active modules' `:tools`."
  (:require
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing]]
   [eca.features.tools :as f.tools]
   [eca.modules :as modules]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(defn- tool-names [config]
  (set (map :full-name (f.tools/all-tools "chat-1" "code" (h/db) config))))

(deftest built-in-tools-test
  (testing "every built-in tool comes from a module"
    (is (set/subset? #{"eca__read_file" "eca__shell_command" "eca__bg_job" "eca__git" "eca__task"
                       "eca__compact_chat" "eca__spawn_agent" "eca__skill"}
                     (tool-names {}))))
  (testing "disabling a module removes its tools"
    (let [names (tool-names {:modules {:eca/git {:enabled false} :eca/shell {:enabled false}}})]
      (is (not (contains? names "eca__git")))
      (is (not (contains? names "eca__shell_command")))
      (is (not (contains? names "eca__bg_job")))
      (is (contains? names "eca__read_file")))))

(deftest module-tool-test
  (let [calls* (atom [])
        test-module {:id :eca/test-tools
                     :tools {"echo" {:description "Echo the text."
                                     :parameters {:type "object"
                                                  :properties {"text" {:type "string"}}
                                                  :required ["text"]}
                                     :summary-fn (fn [_ctx args] (str "Echoing " (get args "text")))
                                     :require-approval-fn (fn [_ctx args] (= "ask" (get args "text")))
                                     :handler (fn [ctx args]
                                                (swap! calls* conj [(:module-id ctx) (:chat-id ctx) args])
                                                {:error false
                                                 :contents [{:type :text :text (get args "text")}]})}}}]
    (with-redefs [modules/built-in-modules (constantly (conj (h/built-in-modules-with :eca/approval) test-module))]
      (let [db* (h/db*)
            tools (f.tools/all-tools "chat-1" "code" @db* {})
            echo (some #(when (= "eca__echo" (:full-name %)) %) tools)]
        (testing "a module tool is listed like a built-in one"
          (is (match? {:name "echo" :full-name "eca__echo" :origin :native :server {:name "eca"}
                       :description "Echo the text."}
                      echo)))
        (testing "its functions get ctx first, with the module in it"
          (is (= "Echoing hi" (f.tools/tool-call-summary tools "eca__echo" {"text" "hi"} {} @db*)))
          (is (= :ask (f.tools/approval tools echo {"text" "ask"} @db* {} "code")))
          (is (match? {:error false :contents [{:type :text :text "hi"}]}
                      (f.tools/call-tool! "eca__echo" {"text" "hi"} "chat-1" "call-1" "code" db* {}
                                          (h/messenger) (h/metrics) identity identity {})))
          (is (= [[:eca/test-tools "chat-1" {"text" "hi"}]] @calls*)))))))

(deftest built-in-tools-error-test
  (testing "a built-in :tools function that throws fails the tool list, as before modules"
    (with-redefs [modules/built-in-modules (constantly (conj (h/built-in-modules-with)
                                                            {:id :eca/test-broken-tools
                                                             :tools (fn [_ctx] (throw (ex-info "Bad agent config" {})))}))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Bad agent config" (tool-names {}))))))

(deftest custom-tools-replace-built-in-test
  (testing "a custom tool named like a built-in one replaces it, as before modules"
    (let [config {:customTools {"read_file" {:description "My own read_file."
                                             :command "cat {{path}}"
                                             :schema {:properties {"path" {:type "string"}}
                                                      :required ["path"]}}}}
          read-file (some #(when (= "eca__read_file" (:full-name %)) %)
                          (f.tools/all-tools "chat-1" "code" (h/db) config))]
      (is (= "My own read_file." (:description read-file))))))
