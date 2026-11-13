(ns integration.modules-test
  "An external module, interpreted with SCI in the real server: loaded from
   modules.<id>.source, its tools sent to the client, its command run."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [integration.helper :refer [match-content]]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(eca/clean-after-test)

(def ^:private module-source
  "(ns acme.hello
     (:require [clojure.string :as str]
               [eca.api :as eca]))

   (def module
     {:id :acme/hello
      :description \"Says hello.\"
      :tools {\"greet\" {:description \"Greet someone.\"
                        :parameters {:type \"object\" :properties {\"name\" {:type \"string\"}}}
                        :handler (fn [_ctx {:strs [name]}] (str \"Hello \" name))}}
      :commands {\"hello\" {:description \"Say hello.\"
                           :arguments [{:name \"who\"}]
                           :handler (fn [ctx {:keys [who]}]
                                      (str \"Hello \" (str/upper-case who) \" from \" (eca/module-id ctx)))}}})")

(defn- module-server
  "The first tool/serverUpdated for a module, skipping ECA's own."
  []
  (some (fn [_]
          (let [server (eca/client-awaits-server-notification :tool/serverUpdated)]
            (when (= "module" (:type server)) server)))
        (range 5)))

(deftest external-module
  (let [path (str (fs/path eca/*eca-out-dir* "modules-test" "hello.clj"))]
    (fs/create-dirs (fs/parent path))
    (spit path module-source)
    (eca/start-process!)
    (eca/request! (fixture/initialize-request
                   {:initializationOptions (merge fixture/default-init-options
                                                  {:modules {"acme/hello" {:source path}}})
                    :capabilities {:codeAssistant {:chat {}}}}))
    (eca/notify! (fixture/initialized-notification))

    (testing "its tools reach the client on their own server"
      (is (match? {:type "module"
                   :name "hello"
                   :module "acme/hello"
                   :status "running"
                   :tools [{:name "greet" :description "Greet someone."}]}
                  (module-server))))

    (testing "its command is listed"
      (is (match? {:commands (m/embeds [{:name "hello" :description "Say hello." :arguments [{:name "who"}]}])}
                  (eca/request! (fixture/chat-query-commands-request {:query "hello"})))))

    (testing "and runs"
      (let [resp (eca/request! (fixture/chat-prompt-request {:message "/hello ana"}))
            chat-id (:chatId resp)]
        (is (match? {:chatId string? :status "prompting"} resp))
        (match-content chat-id "user" {:type "text" :text "/hello ana\n"})
        (match-content chat-id "system" {:type "progress" :state "running" :text "Loading config"})
        (match-content chat-id "system" {:type "text" :text (m/pred #(string/includes? % "Hello ANA from :acme/hello"))})
        (match-content chat-id "system" {:type "progress" :state "finished"})))))
