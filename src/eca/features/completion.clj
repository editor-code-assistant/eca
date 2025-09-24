(ns eca.features.completion
  (:require
   [clojure.string :as string]
   [eca.features.prompt :as f.prompt]
   [eca.llm-api :as llm-api]
   [eca.logger :as logger]
   [eca.shared :as shared]))

(defn complete [{:keys [uri doc-version position]} db* config]
  (let [full-model "openai/gpt-5-mini"
        [provider model] (string/split full-model #"/" 2)
        db @db*
        model-capabilities (get-in db [:models full-model])
        provider-auth (get-in db [:auth provider])
        filename (shared/uri->filename uri)
        {:keys [line character]} position
        input-code (slurp filename)
        instructions (f.prompt/inline-completion-prompt line character)
        _ (logger/info "--->" input-code)
        _ (logger/info "--->" instructions)
        {:keys [error-message result]} (llm-api/complete!
                                        {:provider provider
                                         :model model
                                         :config config
                                         :input-code input-code
                                         :instructions instructions
                                         :provider-auth provider-auth
                                         :model-capabilities model-capabilities})]
    (cond
      error-message
      {:error-message error-message}

      (not result)
      {:error-message ""}

      :else
      {:items [{:id "123"
                :text result

                :doc-version doc-version
                :range {:start {:line line :character character}
                        :end {:line line :character character}}}]})))

(comment
  (complete
   {:uri "file:///home/greg/dev/eca/src/eca/features/hello.clj"
    :doc-version 1
    :position {:line 4
               :character 2}}
   eca.db/db*
   (eca.config/all @eca.db/db*)))
