(ns integration.chat.memory-test
  "End-to-end coverage of the file-based memory feature: with
   `memory.enabled: true` the always-on index and write guidance actually
   reach the LLM provider request (through real config plumbing, real
   workspace/mail dirs and the mock LLM server), and the
   `memory/indexLoaded` notification is emitted; with memory disabled (the
   default) nothing memory-related is injected at all."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [llm-mock.mocks :as llm.mocks]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(eca/clean-after-test)

(defn- drain-until-progress-finished!
  "Drain chat/contentReceived notifications for `chat-id` until the finishing
  progress event, so the prompt has fully settled."
  [chat-id]
  (loop []
    (let [n (eca/client-awaits-server-notification :chat/contentReceived)]
      (when-not (and (= chat-id (:chatId n))
                     (= "system" (:role n))
                     (= "progress" (get-in n [:content :type]))
                     (= "finished" (get-in n [:content :state])))
        (recur)))))

(deftest memory-enabled-injects-index-test
  (let [xdg (str (fs/create-temp-dir {:prefix "eca-memory-integration"}))]
    ;; Seed the global memory dir BEFORE the server starts; the index is
    ;; scanned per-turn from this XDG_CONFIG_HOME via *extra-env*.
    (let [global-dir (fs/path xdg "eca" "memory" "global")]
      (fs/create-dirs global-dir)
      (spit (str (fs/path global-dir "integration-gotcha.md"))
            "---\nname: Integration gotcha\ndescription: e2e seeded memory lives here\n---\n\nBody.\n"))
    (binding [eca/*extra-env* {"XDG_CONFIG_HOME" xdg}]
      (eca/start-process!)
      (llm.mocks/set-case! :simple-text-0)
      (eca/request! (fixture/initialize-request
                     {:initializationOptions
                      (assoc fixture/default-init-options :memory {:enabled true})
                      :capabilities {:codeAssistant {:chat {}}}}))
      (eca/notify! (fixture/initialized-notification))

      (let [prompt-resp (eca/request! (fixture/chat-prompt-request
                                       {:model "openai/gpt-4.1"
                                        :message "Hello"}))
            chat-id (:chatId prompt-resp)]
        (is (string? chat-id))

        (testing "memory/indexLoaded notification carries the seeded memory"
          (let [n (eca/client-awaits-server-notification :memory/indexLoaded)]
            (is (match? {:chatId chat-id
                         :count 1
                         :totalCount 1
                         :items (m/embeds [{:name "Integration gotcha"}])}
                        n))))

        (drain-until-progress-finished! chat-id)

        (testing "the LLM request actually carries index + guidance"
          (let [body (llm.mocks/get-req-body :simple-text-0)
                body-str (pr-str body)]
            (is (string/includes? body-str "## Memory")
                "index block reaches the provider request")
            (is (string/includes? body-str "Integration gotcha")
                "seeded memory entry reaches the provider request")
            (is (string/includes? body-str "e2e seeded memory lives here")
                "memory description reaches the provider request")
            (is (string/includes? body-str "## Memory write guidance")
                "write guidance reaches the provider request")))))))

(deftest memory-disabled-by-default-injects-nothing-test
  (let [xdg (str (fs/create-temp-dir {:prefix "eca-memory-integration"}))]
    (binding [eca/*extra-env* {"XDG_CONFIG_HOME" xdg}]
      (eca/start-process!)
      (llm.mocks/set-case! :simple-text-0)
      (eca/request! (fixture/initialize-request)) ;; default init options: no :memory config
      (eca/notify! (fixture/initialized-notification))

      (let [prompt-resp (eca/request! (fixture/chat-prompt-request
                                       {:model "openai/gpt-4.1"
                                        :message "Hello"}))
            chat-id (:chatId prompt-resp)]
        (is (string? chat-id))
        (drain-until-progress-finished! chat-id)

        (let [body-str (pr-str (llm.mocks/get-req-body :simple-text-0))]
          (is (not (string/includes? body-str "## Memory"))
              "no memory block with the feature off")
          (is (not (string/includes? body-str "Memory write guidance"))
              "no write guidance with the feature off"))))))
