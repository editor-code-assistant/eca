(ns integration.chat.invalid-image-test
  "Covers recovery from providers rejecting image content: an MCP tool returns
   a tiny image, the (mocked) provider rejects any request carrying it with a
   400 (mimicking xAI's 512 total-pixels minimum), and ECA must retry without
   images instead of poisoning the chat forever."
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [llm-mock.mocks :as llm.mocks]
   [matcher-combinators.core :as mc]
   [matcher-combinators.test :refer [match?]]
   [mcp-mock.server :as mcp-mock]))

(eca/clean-after-test)

(def ^:private init-options
  (merge fixture/default-init-options
         {:mcpServers {"test-mcp" {:url (str "http://localhost:" mcp-mock/port "/mcp")}}}))

(defn ^:private await-content
  "Consumes chat/contentReceived notifications until one matches role+content,
   failing the test after 40 non-matching notifications."
  [chat-id role content]
  (loop [remaining 40]
    (if (zero? remaining)
      (is false (str "No contentReceived matched role=" role " content=" content))
      (let [actual (eca/client-awaits-server-notification :chat/contentReceived)]
        (when-not (mc/indicates-match?
                   (mc/match {:chatId chat-id :role role :content content} actual))
          (recur (dec remaining)))))))

(deftest invalid-image-recovery
  (eca/start-process!)
  (mcp-mock/reset-requests!)
  (eca/request! (fixture/initialize-request {:initializationOptions init-options}))
  (eca/notify! (fixture/initialized-notification))

  ;; Wait for MCP server to be ready: native, mcp starting, mcp running
  (eca/client-awaits-server-notification :tool/serverUpdated)
  (eca/client-awaits-server-notification :tool/serverUpdated)
  (eca/client-awaits-server-notification :tool/serverUpdated)

  (testing "provider rejecting a tool-result image triggers a retry without images"
    (llm.mocks/set-case! :invalid-image-0)
    (let [resp (eca/request! (fixture/chat-prompt-request
                              {:model "anthropic/claude-sonnet-4-6"
                               :message "Call the tiny-image tool"}))
          chat-id (:chatId resp)]

      (is (match? {:chatId string?
                   :model "anthropic/claude-sonnet-4-6"
                   :status "prompting"}
                  resp))

      ;; MCP tool runs and returns text + image contents (the image is split
      ;; into its own content notification).
      (await-content chat-id "assistant" {:type "toolCalled"
                                          :origin "mcp"
                                          :name "tiny-image"
                                          :error nil
                                          :outputs [{:type "text" :text "Evaluation result rendered as image:"}]})
      (await-content chat-id "assistant" {:type "image"
                                          :mediaType "image/png"
                                          :base64 mcp-mock/tiny-png-base64})

      ;; The continuation request carries the image -> mocked 400 -> recovery.
      (await-content chat-id "system" {:type "text"
                                       :text #(string/includes? % "rejected an image")})

      ;; Retry (without images) succeeds and the turn completes.
      (await-content chat-id "assistant" {:type "text" :text "Recovered without the image"})
      (await-content chat-id "system" {:type "progress" :state "finished"})

      (testing "MCP mock received exactly one tools/call"
        (is (= 1 (count (mcp-mock/get-requests-by-method "tools/call")))))

      (testing "retry request replaced the image with a placeholder"
        (let [retry-body (llm.mocks/get-req-body :invalid-image-0)
              as-str (pr-str retry-body)]
          (is (not (string/includes? as-str "\"image\""))
              "no image content may be replayed after the provider rejected it")
          (is (string/includes? as-str "[image removed: rejected by the LLM provider]")
              "the tool result keeps a placeholder where the image was"))))))
