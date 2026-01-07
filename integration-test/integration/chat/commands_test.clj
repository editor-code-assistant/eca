(ns integration.chat.commands-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [integration.helper :refer [match-content] :as h]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(eca/clean-after-test)

(deftest query-commands
  (eca/start-process!)

  (eca/request! (fixture/initialize-request))
  (eca/notify! (fixture/initialized-notification))

  (testing "We query all available commands"
    (let [resp (eca/request! (fixture/chat-query-commands-request
                              {:query ""}))]
      (is (match?
           {:chatId nil
            :commands [{:name "init" :arguments []}
                       {:name "login" :arguments [{:name "provider-id"}]}
                       {:name "skills" :arguments []}
                       {:name "costs" :arguments []}
                       {:name "compact" :arguments [{:name "additional-input"}]}
                       {:name "resume" :arguments [{:name "chat-id"}]}
                       {:name "config" :arguments []}
                       {:name "doctor" :arguments []}
                       {:name "repo-map-show" :arguments []}
                       {:name "prompt-show" :arguments [{:name "optional-prompt"}]}]}
           resp))))

  (testing "We query specific commands"
    (let [resp (eca/request! (fixture/chat-query-commands-request
                              {:query "co"}))]
      (is (match?
           {:chatId nil
            :commands [{:name "login" :arguments [{:name "provider-id"}]}
                       {:name "costs" :arguments []}
                       {:name "compact" :arguments [{:name "additional-input"}]}
                       {:name "config" :arguments []}]}
           resp))))

  (testing "We send a built-in command"
    (let [resp (eca/request! (fixture/chat-prompt-request
                              {:message "/prompt-show"}))
          chat-id (:chatId resp)]
      (is (match?
           {:chatId string?
            :model string?
            :status "prompting"}
           resp))

      (match-content chat-id "user" {:type "text" :text "/prompt-show\n"})
      (match-content chat-id "system" {:type "text" :text (m/pred #(string/includes? % "You are ECA"))})
      (match-content chat-id "system" {:type "progress" :state "finished"}))))

(deftest mcp-prompts
  (eca/start-process!)

  (eca/request! (fixture/initialize-request
                 {:initializationOptions (merge fixture/default-init-options
                                                {:mcpServers {"mcp-server-sample"
                                                              (if h/windows?
                                                                {:command "cmd.exe"
                                                                 :args ["/c" (str "cd /d " h/mcp-server-sample-path " && clojure -M:server")]}
                                                                {:command "bash"
                                                                 :args ["-c" (str "cd " h/mcp-server-sample-path " && clojure -M:server")]})}})}))
  (eca/notify! (fixture/initialized-notification))
  (testing "ECA tools"
    (is (match? {:type "native"}
                (eca/client-awaits-server-notification :tool/serverUpdated))))
  (testing "Mcp starting"
    (is (match? {:type "mcp"
                 :name "mcpServerSample"}
                (eca/client-awaits-server-notification :tool/serverUpdated))))
  (testing "Mcp started"
    (is (match? {:type "mcp"
                 :name "mcpServerSample"}
                (eca/client-awaits-server-notification :tool/serverUpdated))))

  (testing "MCP prompts available when querying commands"
    (let [resp (eca/request! (fixture/chat-query-commands-request
                              {:query ""}))]
      (is (match?
           {:chatId nil
            :commands (m/embeds
                       [{:name "mcpServerSample:my-prompt" :arguments [{:name "some-arg-1"}]}])}
           resp)))))
