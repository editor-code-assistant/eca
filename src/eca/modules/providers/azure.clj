(ns eca.modules.providers.azure)

(set! *warn-on-reflection* true)

(def module
  {:id :eca/azure
   :description "Azure OpenAI, at the user's resource URL."
   :requires #{:eca/api-openai-responses}
   :providers {"azure" {:label "Azure"
                        :api "openai-responses"
                        :completionUrlRelativePath "/openai/responses?api-version=2025-04-01-preview"
                        :login {:ask [:api-key
                                      [:url "API URL (e.g. https://your-resource-name.openai.azure.com)"]
                                      :models]}}}})
