(ns eca.llm-providers.openai-codex-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eca.llm-providers.openai-codex :as openai-codex]))

(set! *warn-on-reflection* true)

(deftest turn-context-headers-test
  (let [context (openai-codex/new-turn-context "chat-123")
        client-version (second (re-find #"client_version=(\d+\.\d+\.\d+)"
                                        openai-codex/models-url))]
    (testing "session and thread routing stay stable for the chat"
      (let [headers (openai-codex/request-headers
                     {:account-id "account-1"
                      :turn-context context})]
        (is (= {"ChatGPT-Account-ID" "account-1"
                "Originator" "codex_cli_rs"
                "Session-ID" "chat-123"
                "Thread-ID" "chat-123"
                "x-client-request-id" "chat-123"}
               (dissoc headers "User-Agent")))
        (is (= (str "codex_cli_rs/" client-version)
               (get headers "User-Agent")))))

    (testing "the first turn state is replayed and later values cannot replace it"
      (openai-codex/capture-turn-state! context "state-1")
      (openai-codex/capture-turn-state! context "state-2")
      (is (= "state-1"
             (get (openai-codex/request-headers {:turn-context context})
                  "x-codex-turn-state"))))))

(deftest responses-lite-body-test
  (let [body (openai-codex/responses-lite-body
              {:model "gpt-5.6-sol"
               :instructions "Use the repository rules."
               :input [{:role "user" :content "Fix it"}]
               :tools [{:type "function" :name "eca__read_file"}
                       {:type "web_search"}
                       {:type "image_generation"}]
               :parallel_tool_calls true
               :reasoning {:effort "low" :summary "auto"}
               :stream true})]
    (is (nil? (:instructions body)))
    (is (nil? (:tools body)))
    (is (false? (:parallel_tool_calls body)))
    (is (= {:effort "low" :summary "auto" :context "all_turns"}
           (:reasoning body)))
    (is (= [{:type "additional_tools"
             :role "developer"
             :tools [{:type "function" :name "eca__read_file"}]}
            {:type "message"
             :role "developer"
             :content [{:type "input_text"
                        :text "Use the repository rules."}]}
            {:role "user" :content "Fix it"}]
           (:input body)))))

(deftest responses-lite-body-without-reasoning-test
  (testing "Lite always declares all-turn reasoning context"
    (is (= {:context "all_turns"}
           (:reasoning
            (openai-codex/responses-lite-body
             {:input [{:role "user" :content "Generate a title"}]}))))))

(deftest live-model-discovery-test
  (is (= {:discovered-codex-responses-lite? true
          :discovered-default-reasoning-effort "low"
          :discovered-supports-parallel-tool-calls? true
          :discovered-variants
          {"low" {:reasoning {:effort "low" :summary "auto"}}
           "max" {:reasoning {:effort "max" :summary "auto"}}}}
         (openai-codex/live-model-discovery
          {:use_responses_lite true
           :default_reasoning_level "low"
           :supported_reasoning_levels [{:effort "low"}
                                        {:effort "max"}
                                        {:effort "ultra"}]
           :supports_parallel_tool_calls true})))
  (testing "an explicit live false value overrides static Lite fallbacks"
    (is (false? (:discovered-codex-responses-lite?
                 (openai-codex/live-model-discovery
                  {:use_responses_lite false}))))))

(deftest responses-lite-fallback-test
  (testing "all current GPT-5.6 Codex models retain Lite metadata without /models"
    (doseq [model ["gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6-luna"]]
      (is (true? (:discovered-codex-responses-lite?
                  (openai-codex/responses-lite-fallback model))))))
  (testing "model matching is case-insensitive"
    (is (true? (:discovered-codex-responses-lite?
                (openai-codex/responses-lite-fallback "GPT-5.6-SOL"))))))
