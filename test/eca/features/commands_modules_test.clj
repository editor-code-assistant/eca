(ns eca.features.commands-modules-test
  "Commands come from the active modules' `:commands`."
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.features.commands :as f.commands]
   [eca.modules :as modules]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(defn- command-names [config]
  (mapv :name (f.commands/all-commands (h/db) config)))

(deftest built-in-commands-test
  (testing "built-in commands keep their order and type"
    (is (= ["init" "login" "model" "agent" "skills" "skill-create" "costs" "quota" "context" "approvals"]
           (take 10 (command-names {}))))
    (is (every? #(= :native (:type %)) (take 34 (f.commands/all-commands (h/db) {})))))
  (testing "disabling a module removes its commands"
    (let [names (set (command-names {:modules {:eca/plugins {:enabled false}
                                               :eca/manage {:enabled false}}}))]
      (is (not (contains? names "plugins")))
      (is (not (contains? names "plugin-install")))
      (is (not (contains? names "init")))
      (is (contains? names "login")))))

(def ^:private test-module
  {:id :eca/test-commands
   :commands {"greet" {:description "Say hi."
                       :arguments [{:name "who"}]
                       :handler (fn [ctx {:keys [who raw]}]
                                  (str "Hi " who " from " (:module-id ctx) " (" raw ")"))}
              "ask" {:description "Ask the model."
                     :handler (fn [_ctx {:keys [raw]}] {:prompt (str "Answer: " raw)})}
              "quiet" {:description "Does it all itself."
                       :handler (fn [_ctx _args] nil)}}})

(deftest module-command-test
  (with-redefs [modules/built-in-modules (constantly (conj (h/built-in-modules-with) test-module))]
    (let [ctx {:chat-id "c1" :db* (h/db*) :config {} :messenger (h/messenger)}]
      (testing "listed after the built-in ones"
        (is (match? [{:name "greet" :type :native :description "Say hi." :arguments [{:name "who"}]}
                     {:name "ask"} {:name "quiet"}]
                    (filter #(#{"greet" "ask" "quiet"} (:name %)) (f.commands/all-commands (h/db) {})))))
      (testing "a string is shown as a system message; args are by name, plus :raw"
        (is (= {:type :chat-messages
                :chats {"c1" {:messages [{:role "system"
                                          :content [{:type :text :text "Hi ana from :eca/test-commands (ana now)"}]}]}}}
               (f.commands/handle-command! "greet" ["ana" "now"] (assoc ctx :message "/greet ana now")))))
      (testing "{:prompt ..} is sent as the user's message"
        (is (= {:type :send-prompt :prompt "Answer: why?"}
               (f.commands/handle-command! "ask" ["why?"] (assoc ctx :message "/ask why?")))))
      (testing "nil: nothing more to do"
        (is (= {:type :new-chat-status :status :idle}
               (f.commands/handle-command! "quiet" [] ctx))))
      (testing "the full id works too"
        (is (match? {:name "greet"} (f.commands/find-command "eca:greet" (h/db) {})))
        (is (match? {:type :chat-messages} (f.commands/handle-command! "eca:greet" ["bo"] ctx)))))))

(deftest name-clash-test
  (testing "a name two modules share is listed by full id"
    (with-redefs [modules/built-in-modules
                  (constantly (conj (h/built-in-modules-with)
                                    test-module
                                    {:id :eca/other :prefix "other"
                                     :commands {"greet" {:handler (fn [_ _] "other")}}}))]
      (let [names (set (command-names {}))]
        (is (contains? names "eca:greet"))
        (is (contains? names "other:greet"))
        (is (not (contains? names "greet"))))
      (is (= "other" (get-in (f.commands/handle-command! "other:greet" [] {:chat-id "c1" :config {}})
                             [:chats "c1" :messages 0 :content 0 :text]))))))

(def ^:private sources-module
  "A built-in module whose `:commands` function gives its own commands and
   ones for sources, as `:eca/skills`, `:eca/custom-commands` and `:eca/mcp`
   do."
  {:id :eca/test-sources
   :commands (fn [_ctx]
               [["srv:summarize" {:client-type :mcpPrompt
                                  :description "An MCP prompt."
                                  :arguments [{:name "topic"}]
                                  :handler (fn [_ctx {:keys [argv]}]
                                             {:type :send-messages
                                              :messages [{:role "user" :content (str "About " (first argv))}]})}]
                ["review" {:client-type :skill :description "A skill." :handler (fn [_ _] {:prompt "skill"})}]
                ["review" {:client-type :custom-prompt :description "A custom command." :handler (fn [_ _] {:prompt "custom"})}]
                ["tidy" {:client-type :skill :description "Another skill." :handler (fn [_ _] {:prompt "tidy skill"})}]
                ["tidy" {:description "Its own command." :handler (fn [_ _] "own tidy")}]])})

(deftest command-sources-test
  (with-redefs [modules/built-in-modules (constantly (conj (h/built-in-modules-with) sources-module))]
    (let [config {:pureConfig true}
          ctx {:chat-id "c1" :db* (h/db*) :config config}
          commands (f.commands/all-commands (h/db) config)]
      (testing "MCP prompts first, then the modules' own commands, skills and custom commands, typed by their :client-type"
        (is (match? {:name "srv:summarize" :type :mcpPrompt :description "An MCP prompt." :arguments [{:name "topic"}]}
                    (first commands)))
        (is (match? [{:name "tidy" :type :native :description "Its own command."}
                     {:name "review" :type :skill :description "A skill."}
                     {:name "tidy" :type :skill}
                     {:name "review" :type :custom-prompt :description "A custom command."}]
                    (filter #(#{"tidy" "review"} (:name %)) commands)))
        (is (not-any? #(or (contains? % :handler) (contains? % :client-type)) commands)))
      (testing "a module's own command runs before a custom command, which runs before a skill"
        (is (= "own tidy" (get-in (f.commands/handle-command! "tidy" [] ctx) [:chats "c1" :messages 0 :content 0 :text])))
        (is (= {:type :send-prompt :prompt "custom"} (f.commands/handle-command! "review" [] ctx))))
      (testing "an MCP prompt runs only as one, and its typed result passes"
        (is (= {:type :send-messages :messages [{:role "user" :content "About tests"}]}
               (f.commands/handle-command! "srv:summarize" ["tests"] ctx :mcpPrompt)))
        (is (= {:type :text :text "Unknown command: srv:summarize"}
               (f.commands/handle-command! "srv:summarize" ["tests"] ctx)))
        (testing "gone since it was typed, it's shown as unknown"
          (is (= {:type :system-text :text "Unknown command: srv:gone"}
                 (f.commands/handle-command! "srv:gone" [] ctx :mcpPrompt)))))
      (testing "only a module's own commands have a full id"
        (is (match? {:name "tidy" :type :native} (f.commands/find-command "eca:tidy" (h/db) config)))
        (is (nil? (f.commands/find-command "eca:review" (h/db) config)))))))

(deftest external-client-type-test
  (testing "an external module's :client-type is ignored: the command is its own"
    (modules/add-external! "/tmp/mods/ext.clj"
                           {:id :acme/ext
                            :commands {"ext" {:client-type :skill :handler (fn [_ _] "ext")}}}
                           {:kind :global :rank 3})
    (try
      (is (match? [{:name "ext" :type :module}]
                  (filter #(= "ext" (:name %)) (f.commands/all-commands (h/db) {:pureConfig true}))))
      (finally
        (modules/reset-registry!)))))
