(ns eca.llm-providers.lmstudio)

(set! *warn-on-reflection* true)

(def module
  {:id :eca/lmstudio
   :description "Local models through LM Studio."
   :requires #{:eca/api-openai-chat}
   :providers {"lmstudio" {:label "LM Studio"
                           :api "openai-chat"
                           :url "http://localhost:1234"
                           :completionUrlRelativePath "/v1/chat/completions"
                           :httpClient {:version "http-1.1"}
                           :login {:ask [:models]}}}})        ; local: no key
