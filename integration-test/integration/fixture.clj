(ns integration.fixture
  (:require
   [clojure.java.io :as io]
   [integration.helper :as h]
   [llm-mock.server :as llm-mock.server]))

(def base-llm-mock-url
  (str "http://localhost:" llm-mock.server/port))

(def default-providers
  {"openai" {:url (str base-llm-mock-url "/openai")
             :key "${env:FOO:foo-key}"}
   "anthropic" {:url (str base-llm-mock-url "/anthropic")
                :key "${env:FOO:foo-key}"}
   "github-copilot" {:url (str base-llm-mock-url "/github-copilot")
                     :key "${env:FOO:foo-key}"}
   "google" {:url (str base-llm-mock-url "/google")
             :key "${env:FOO:foo-key}"}})

(def default-init-options {:pureConfig true
                           :env "test"
                           :toolCall {:approval {:byDefault "allow"}}
                           :providers default-providers})

(defn initialize-request
  ([]
   (initialize-request {:initializationOptions default-init-options
                        :capabilities {:codeAssistant {:chat {}}}}))
  ([params]
   (initialize-request params [{:name "sample-test"
                                :uri (h/file->uri (io/file h/default-root-project-path))}]))
  ([params workspace-folders]
   [:initialize
    (merge (if workspace-folders {:workspace-folders workspace-folders} {})
           params)]))

(defn initialized-notification []
  [:initialized {}])

(defn shutdown-request
  []
  [:shutdown {}])

(defn exit-notification []
  [:exit {}])

(defn chat-prompt-request [params]
  [:chat/prompt params])

(defn chat-query-commands-request [params]
  [:chat/queryCommands params])

(defn rewrite-prompt-request [params]
  [:rewrite/prompt params])
