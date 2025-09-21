(ns eca.llm-providers.azure
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.login :as f.login]))

(defmethod f.login/login-step ["azure" :login/start] [{:keys [db* chat-id provider send-msg!]}]
  (swap! db* assoc-in [:chats chat-id :login-provider] provider)
  (swap! db* assoc-in [:auth provider] {:step :login/waiting-api-key})
  (send-msg! "Paste your API Key"))

(defmethod f.login/login-step ["azure" :login/waiting-api-key] [{:keys [input db* provider send-msg!]}]
  (swap! db* assoc-in [:auth provider] {:step :login/waiting-api-url
                                        :api-key input})
  (send-msg! "Inform the API URL (ex: 'https://your-resource-name.openai.azure.com'):"))

(defmethod f.login/login-step ["azure" :login/waiting-api-url] [{:keys [input db* provider send-msg!]}]
  (swap! db* update-in [:auth provider] merge {:step :login/waiting-models
                                               :url input})
  (send-msg! "Inform one or more models (separated by `,`):"))

(defmethod f.login/login-step ["azure" :login/waiting-models] [{:keys [input db* provider send-msg!] :as ctx}]
  (let [{:keys [api-url api-key]} (get-in @db* [:auth provider])]
    (config/update-global-config! {:providers {"azure" {:api "openai-responses"
                                                        :url api-url
                                                        :completionUrlRelativePath "/openai/responses?api-version=2025-04-01-preview"
                                                        :models (reduce
                                                                 (fn [models model-str]
                                                                   (assoc models (string/trim model-str) {}))
                                                                 {}
                                                                 (string/split input #","))
                                                        :key api-key}}}))
  (swap! db* update :auth dissoc provider)
  (send-msg! (format "API key, url and models saved to %s" (.getCanonicalPath (config/global-config-file))))
  (f.login/login-done! ctx))
