(ns integration.chat.editor-lsp-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [integration.eca :as eca]
   [integration.fixture :as fixture]
   [integration.helper :as h]
   [llm-mock.mocks :as llm.mocks]
   [matcher-combinators.core :as mc]
   [matcher-combinators.matchers :as m]
   [matcher-combinators.test :refer [match?]]))

(eca/clean-after-test)

(defn ^:private await-content-matching
  "Consumes chat/contentReceived notifications until one matches, returning it."
  [chat-id role content]
  (loop [tries 0]
    (if (< tries 50)
      (let [actual (eca/client-awaits-server-notification :chat/contentReceived)]
        (if (mc/indicates-match? (mc/match {:chatId chat-id :role role :content content} actual))
          actual
          (recur (inc tries))))
      (throw (ex-info "Timeout waiting for matching content" {:content content})))))

(deftest editor-definition-and-references
  (eca/start-process!)

  (eca/request! (fixture/initialize-request
                 {:initializationOptions fixture/default-init-options
                  :capabilities {:codeAssistant {:chat {}
                                                 :editor {:definition true
                                                          :references true}}}}))
  (eca/notify! (fixture/initialized-notification))
  (let [file1-path (h/project-path->canon-path "resources/file1.md")
        file1-uri (h/file->uri file1-path)]
    (testing "definition found via the editor's language server"
      (eca/mock-response :editor/getDefinition
                         {:status "success"
                          :locations [{:uri file1-uri
                                       :range {:start {:line 1 :character 1}
                                               :end {:line 1 :character 10}}}]})
      (llm.mocks/set-case! :editor-lsp-0)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:model "openai/gpt-5-mini"
                                 :message "Where is Something defined?"}))
            chat-id (:chatId resp)]
        (is (match? {:chatId (m/pred string?) :status "prompting"} resp))
        (is (match?
             {:type "toolCalled"
              :origin "native"
              :name "editor_definition"
              :summary "LSP definition: Something"
              :error false
              :outputs [{:type "text"
                         :text (str file1-path ":1:1: Something here")}]}
             (:content (await-content-matching chat-id "assistant" {:type "toolCalled"
                                                                    :name "editor_definition"}))))
        (testing "the server sent the resolved 1-based position to the client"
          (is (match?
               {:uri (m/pred #(string/ends-with? % "file1.md"))
                :position {:line 1 :character 1}}
               (eca/client-awaits-server-request :editor/getDefinition))))
        (testing "capability-gated tools were offered to the LLM"
          (is (match?
               {:tools (m/embeds [{:name "eca__editor_definition"}
                                  {:name "eca__editor_references"}])}
               (llm.mocks/get-req-body :editor-lsp-0))))))
    (testing "references failing in the editor gives actionable error to the LLM"
      (eca/mock-response :editor/getReferences
                         {:status "error"
                          :message "lsp busy"})
      (llm.mocks/set-case! :editor-lsp-1)
      (let [resp (eca/request! (fixture/chat-prompt-request
                                {:model "openai/gpt-5-mini"
                                 :message "Who uses Something?"}))
            chat-id (:chatId resp)]
        (is (match?
             {:type "toolCalled"
              :origin "native"
              :name "editor_references"
              :error true
              :outputs [{:type "text"
                         :text "Editor failed to find references: lsp busy. Use eca__grep as fallback."}]}
             (:content (await-content-matching chat-id "assistant" {:type "toolCalled"
                                                                    :name "editor_references"}))))
        (is (match?
             {:uri (m/pred #(string/ends-with? % "file1.md"))
              :position {:line 1 :character 1}
              :includeDeclaration false}
             (eca/client-awaits-server-request :editor/getReferences)))))))
