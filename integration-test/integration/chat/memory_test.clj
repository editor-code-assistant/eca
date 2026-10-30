(ns integration.chat.memory-test
  "End-to-end coverage of the file-based memory feature: with
   `memory.enabled: true` a full memory index block is stored in the first user
   message, reaches the LLM provider request (through real config plumbing, real
   workspace/memory dirs and the mock LLM server), and the
   `memory/indexLoaded` notification is emitted; a second prompt reuses the
   stored block without adding one; with memory disabled (the default) nothing
   memory-related is sent at all."
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

(deftest memory-enabled-stores-the-index-block-test
  (let [xdg (str (fs/create-temp-dir {:prefix "eca-memory-integration"}))]
    ;; Seed the global memory dir BEFORE the server starts; the store is
    ;; scanned on each normal prompt from this XDG_DATA_HOME via *extra-env*.
    (let [global-dir (fs/path xdg "eca" "memory" "global")]
      (fs/create-dirs global-dir)
      (spit (str (fs/path global-dir "integration-gotcha.md"))
            "---\nname: Integration gotcha\ndescription: e2e seeded memory lives here\n---\n\nBody.\n"))
    (binding [eca/*extra-env* {"XDG_CONFIG_HOME" xdg "XDG_DATA_HOME" xdg}]
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

        (testing "the first LLM request carries the stored index block and the guidance"
          (let [body-str (pr-str (llm.mocks/get-req-body :simple-text-0))]
            (is (= 1 (count (re-seq #"<memory-index" body-str)))
                "exactly one full index block is sent")
            (is (string/includes? body-str "Integration gotcha")
                "seeded memory entry reaches the provider request")
            (is (string/includes? body-str "e2e seeded memory lives here")
                "memory description reaches the provider request")
            (is (string/includes? body-str "## Memory")
                "the read guidance reaches the provider request")
            (is (string/includes? body-str "## Memory write guidance")
                "write guidance reaches the provider request")))

        (testing "a second prompt reuses the stored block and adds none"
          (eca/request! (fixture/chat-prompt-request
                         {:model "openai/gpt-4.1"
                          :message "Second"
                          :chat-id chat-id}))
          (drain-until-progress-finished! chat-id)
          (let [body-str (pr-str (llm.mocks/get-req-body :simple-text-0))]
            (is (not (string/includes? body-str "<memory-changes"))
                "no external change, so no change list")
            (is (= 1 (count (re-seq #"<memory-index" body-str)))
                "only the stored block of the first turn is present")))))))

(deftest memory-disabled-by-default-injects-nothing-test
  (let [xdg (str (fs/create-temp-dir {:prefix "eca-memory-integration"}))]
    (binding [eca/*extra-env* {"XDG_CONFIG_HOME" xdg "XDG_DATA_HOME" xdg}]
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
