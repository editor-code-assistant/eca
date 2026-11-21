(ns integration.chat.memory-test
  "End-to-end tests for `:eca/memory`: initial index, quiet unchanged turn,
   live `<memory-changes>` on external edit, and disabled-by-default behavior."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [llm-mock.mocks :as llm.mocks]))

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

(defn- user-messages [body]
  (filterv #(= "user" (:role %)) (:input body)))

(defn- message-text [msg]
  (string/join "\n" (keep :text (:content msg))))

(deftest memory-on-stores-the-index-block-test
  (let [xdg (str (fs/create-temp-dir {:prefix "eca-memory-integration"}))
        global-dir (fs/path xdg "eca" "memory" "global")
        gotcha-file (str (fs/path global-dir "integration-gotcha.md"))]
    ;; Seed the global memory dir BEFORE the server starts; the store is
    ;; scanned on each user request from this XDG_DATA_HOME via *extra-env*.
    (fs/create-dirs global-dir)
    (spit gotcha-file
          "---\nname: Integration gotcha\ndescription: e2e seeded memory lives here\n---\n\nBody.\n")
    (doseq [i (range 4)]
      (spit (str (fs/path global-dir (str "extra-" i ".md")))
            (str "---\nname: Extra note " i "\ndescription: background topic " i "\n---\n\nBody " i ".\n")))
    (try
      (binding [eca/*extra-env* {"XDG_DATA_HOME" xdg}]
        (eca/start-process!)
        (llm.mocks/set-case! :simple-text-0)
        (eca/request! (fixture/initialize-request
                       {:initializationOptions
                        (assoc fixture/default-init-options :modules {"eca/memory" {:enabled true}})
                        :capabilities {:codeAssistant {:chat {}}}}))
        (eca/notify! (fixture/initialized-notification))

        (let [prompt-resp (eca/request! (fixture/chat-prompt-request
                                         {:model "openai/gpt-4.1"
                                          :message "Hello"}))
              chat-id (:chatId prompt-resp)]
          (is (string? chat-id))

          (drain-until-progress-finished! chat-id)

          (testing "the first LLM request puts guidance in instructions and the index block on the first user message"
            (let [body (llm.mocks/get-req-body :simple-text-0)
                  instructions (:instructions body)
                  users (user-messages body)
                  first-user (message-text (first users))]
              (is (string/includes? instructions "## Memory")
                  "the read guidance reaches provider instructions")
              (is (string/includes? instructions "## Memory write guidance")
                  "write guidance reaches provider instructions")
              (is (not (string/includes? instructions "<memory-index"))
                  "the index block belongs on the user message, not static instructions")
              (is (= 1 (count users)))
              (is (string/includes? first-user "Hello"))
              (is (= 1 (count (re-seq #"<memory-index" first-user)))
                  "exactly one full index block is sent on the first user message")
              (is (string/includes? first-user "Integration gotcha")
                  "seeded memory entry reaches the first user message")
              (is (string/includes? first-user "e2e seeded memory lives here")
                  "memory description reaches the first user message")))

          (testing "a second prompt keeps the index on the first user message and adds no block to the second"
            (eca/request! (fixture/chat-prompt-request
                           {:model "openai/gpt-4.1"
                            :message "Second"
                            :chat-id chat-id}))
            (drain-until-progress-finished! chat-id)
            (let [users (user-messages (llm.mocks/get-req-body :simple-text-0))
                  first-user (message-text (first users))
                  second-user (message-text (second users))]
              (is (= 2 (count users)))
              (is (= 1 (count (re-seq #"<memory-index" first-user)))
                  "only the stored block of the first turn stays on the first user message")
              (is (string/includes? second-user "Second")
                  "the second user message is present")
              (is (not (string/includes? second-user "<memory-index"))
                  "the second user message gets no duplicate index")
              (is (not (string/includes? second-user "<memory-changes"))
                  "no external change, so no change list on the second user message")))

          (testing "an external edit between turns delivers a change list on the third user message"
            (spit gotcha-file
                  "---\nname: Integration gotcha\ndescription: updated externally mid-chat\n---\n\nNew body.\n")
            (eca/request! (fixture/chat-prompt-request
                           {:model "openai/gpt-4.1"
                            :message "Third"
                            :chat-id chat-id}))
            (drain-until-progress-finished! chat-id)
            (let [users (user-messages (llm.mocks/get-req-body :simple-text-0))
                  third-user (message-text (nth users 2))]
              (is (= 3 (count users)))
              (is (string/includes? third-user "Third"))
              (is (= 1 (count (re-seq #"<memory-changes" third-user)))
                  "external change delivers a change list on the next turn")
              (is (string/includes? third-user "updated externally mid-chat"))
              (is (not (string/includes? third-user "Extra note 0"))
                  "unchanged notes are not repeated in the change list")))))
      (finally
        (eca/clean!)
        (fs/delete-tree xdg)))))

(deftest memory-off-by-default-injects-nothing-test
  (let [xdg (str (fs/create-temp-dir {:prefix "eca-memory-integration"}))]
    (try
      (binding [eca/*extra-env* {"XDG_DATA_HOME" xdg}]
        (eca/start-process!)
        (llm.mocks/set-case! :simple-text-0)
        (eca/request! (fixture/initialize-request)) ;; default init options: the module stays off
        (eca/notify! (fixture/initialized-notification))

        (let [prompt-resp (eca/request! (fixture/chat-prompt-request
                                         {:model "openai/gpt-4.1"
                                          :message "Hello"}))
              chat-id (:chatId prompt-resp)]
          (is (string? chat-id))
          (drain-until-progress-finished! chat-id)

          (let [body (llm.mocks/get-req-body :simple-text-0)
                instructions (:instructions body)
                users (user-messages body)
                first-user (message-text (first users))]
            (is (= 1 (count users)))
            (is (string/includes? first-user "Hello")
                "the request reached the mock, so the checks below look at it")
            (is (not (string/includes? instructions "## Memory"))
                "no memory guidance with the module off")
            (is (not (string/includes? instructions "Memory write guidance"))
                "no write guidance with the module off")
            (is (not (string/includes? first-user "<memory-index"))
                "no memory block on the user message with the module off")
            (is (not (fs/exists? (fs/path xdg "eca" "memory")))
                "the module off creates no memory dir"))))
      (finally
        (eca/clean!)
        (fs/delete-tree xdg)))))
