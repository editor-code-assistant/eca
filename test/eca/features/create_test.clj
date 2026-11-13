(ns eca.features.create-test
  (:require
   [cheshire.core :as json]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [eca.config :as config]
   [eca.features.create :as f.create]
   [eca.features.tools :as f.tools]
   [eca.modules :as modules]
   [eca.test-helper :as h]
   [matcher-combinators.test :refer [match?]]))

(h/reset-components-before-test)

(defn- config
  "ECA's config without the user's files, with `extra` from the client."
  [& [extra]]
  (reset! config/initialization-config* (merge {:pureConfig true} extra))
  (config/clear-cache!)
  (config/all (h/db)))

(defn- tools-of [agent cfg]
  (f.tools/all-tools "chat-1" agent (h/db) cfg))

(defn- find-tool [agent cfg full-name]
  (some #(when (= full-name (:full-name %)) %) (tools-of agent cfg)))

(defn- call
  "Calls create tool `full-name` as the create agent's chat does: its text
   and whether it's an error."
  [cfg full-name args]
  (let [{:keys [error contents]} ((:handler (find-tool "create" cfg full-name))
                                  args
                                  {:db (h/db) :db* (h/db*) :config cfg :agent "create" :chat-id "chat-1"})]
    {:error error :text (:text (first contents))}))

(deftest agent-test
  (let [cfg (config)]
    (testing "clients list the create agent after code and plan"
      (is (= ["code" "plan" "create"] (config/primary-agent-names cfg))))
    (testing "it has its own prompt and the code agent's tools"
      (is (match? {:mode "primary"
                   :prompts {:chat #"^# ECA — Editor Code Assistant \(Create\)"}
                   :disabledTools ["preview_file_change"]}
                  (get-in cfg [:agent "create"]))))
    (testing "it goes away with eca/create, which it grows out of"
      (let [cfg (config {:modules {:eca/create {:enabled false}}})]
        (is (= ["code" "plan"] (config/primary-agent-names cfg)))
        (is (not (modules/active? cfg :eca/agent-create)))))))

(deftest tools-only-in-create-chats-test
  (let [cfg (config)
        create-tools #(->> (tools-of % cfg)
                           (filter (fn [tool] (= "create" (get-in tool [:server :name]))))
                           (map :full-name)
                           sort)]
    (is (= ["create__eval" "create__guide" "create__modules"] (create-tools "create")))
    (is (empty? (create-tools "code")))
    (is (empty? (create-tools "plan")))
    (testing "the create agent has the code agent's tools too"
      (is (some? (find-tool "create" cfg "eca__write_file")))
      (is (nil? (find-tool "create" cfg "eca__preview_file_change"))))))

(deftest approval-test
  (let [approval (fn [cfg full-name]
                   (let [all-tools (tools-of "create" cfg)]
                     (f.tools/approval all-tools (find-tool "create" cfg full-name) {} (h/db) cfg "create")))]
    (testing "reading the guide and the modules is allowed"
      (is (= :allow (approval (config) "create__guide")))
      (is (= :allow (approval (config) "create__modules"))))
    (testing "evaluating code always asks, even when the config allows every tool"
      (is (= :ask (approval (config) "create__eval")))
      (is (= :ask (approval (config {:toolCall {:approval {:byDefault "allow"}}}) "create__eval"))))))

(deftest guide-tool-test
  (let [cfg (config)]
    (testing "without a topic, it lists them"
      (is (match? {:error false :text #"(?s)^ECA's guide.*\n- modules: ECA modules"}
                  (call cfg "create__guide" {}))))
    (testing "a topic"
      (is (match? {:error false :text #"^# Modules\n"}
                  (call cfg "create__guide" {"topic" "modules"}))))
    (testing "one key of the config schema"
      (is (match? {:properties {:host {:default "127.0.0.1"} :port {:default 0}}}
                  (json/parse-string (:text (call cfg "create__guide" {"topic" "config-schema" "key" "repl"})) true)))
      (testing "through a map with free names"
        (is (match? {:type "string"}
                    (json/parse-string (:text (call cfg "create__guide" {"topic" "config-schema"
                                                                         "key" "mcpServers.my-server.command"}))
                                       true))))
      (testing "through a $ref"
        (is (match? {:properties {:byDefault map? :allow map?}}
                    (json/parse-string (:text (call cfg "create__guide" {"topic" "config-schema"
                                                                         "key" "toolCall.approval"}))
                                       true))))
      (testing "a definition"
        (is (match? {:properties {:command map?}}
                    (json/parse-string (:text (call cfg "create__guide" {"topic" "config-schema"
                                                                         "key" "definitions.mcpServer"}))
                                       true))))
      (testing "an unknown key lists the keys around it"
        (is (match? {:error true :text #"No config key toolCall.nope in the schema. Keys under toolCall: .*approval"}
                    (call cfg "create__guide" {"topic" "config-schema" "key" "toolCall.nope"})))))
    (testing "an unknown topic"
      (is (match? {:error true :text #"No topic nope. Topics: introduction, models"}
                  (call cfg "create__guide" {"topic" "nope"}))))))

(deftest modules-tool-test
  (modules/add-external! "/tmp/mods/broken.clj" nil {:kind :global :rank 3 :expected-id :acme/broken
                                                      :error "Boom (broken.clj:3:1)"})
  (let [cfg (config)]
    (testing "without an id, every module"
      (is (match? {:error false :text #"(?s)^## Modules \(\d+ active\).*`:eca/agent-create`"}
                  (call cfg "create__modules" {}))))
    (testing "one module, with why it isn't active"
      (is (match? {:error false :text #"(?s)`:acme/broken`.*failed: Failed to load: Boom \(broken.clj:3:1\)"}
                  (call cfg "create__modules" {"id" "acme/broken"}))))
    (testing "its recent errors"
      (let [entry (modules/active-entry cfg :eca/agent-create)]
        (try
          (modules/invoke {} entry "a test" {} #(throw (ex-info "Kaboom" {})))
          (catch Exception _)))
      (is (match? {:text #"(?s)errors, latest last:\n  - in a test: Kaboom"}
                  (call cfg "create__modules" {"id" ":eca/agent-create"}))))
    (testing "an unknown module"
      (is (match? {:error true :text #"No module acme/nope"}
                  (call cfg "create__modules" {"id" "acme/nope"}))))))

(deftest eval-tool-test
  (let [cfg (config)]
    (testing "the value and what the code printed"
      (is (= {:error false :text "=> 3"} (call cfg "create__eval" {"code" "(+ 1 2)"})))
      (is (= {:error false :text "=> :done\nOutput:\nhi\n"}
             (call cfg "create__eval" {"code" "(println \"hi\") :done"}))))
    (testing "errors, with where they happened"
      (is (= {:error true :text "Error: Divide by zero (line 1, column 1)"}
             (call cfg "create__eval" {"code" "(/ 1 0)"}))))
    (testing "it tests a module with eca.api.test"
      (is (= {:error false :text "=> \"pong\""}
             (call cfg "create__eval"
                   {"code" (str "(require '[eca.api.test :as t])"
                                "(t/run-tool {:id :acme/t"
                                "             :tools {\"ping\" {:description \"Ping.\" :parameters {}"
                                "                               :handler (fn [_ _] \"pong\")}}}"
                                "            \"ping\" {})")}))))
    (testing "no code"
      (is (match? {:error true} (call cfg "create__eval" {"code" " "}))))))

(deftest eval-code-timeout-test
  (is (match? {:error "Timed out after 1 seconds"}
              (f.create/eval-code "(deref (promise))" 1000))))

(deftest eval-value-cut-test
  (testing "long values are cut"
    (is (string/ends-with? (:value (f.create/eval-code "(apply str (repeat 30000 \"x\"))" 10000))
                           "… (cut)"))))
