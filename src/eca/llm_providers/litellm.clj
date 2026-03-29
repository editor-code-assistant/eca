(ns eca.llm-providers.litellm
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.login :as f.login]))

(defmethod f.login/login-step ["litellm" :login/start] [{:keys [db* chat-id provider send-msg!]}]
  (swap! db* assoc-in [:chats chat-id :login-provider] provider)
  (swap! db* assoc-in [:auth provider] {:step :login/waiting-api-key})
  (send-msg! "Paste your API Key"))

(defmethod f.login/login-step ["litellm" :login/waiting-api-key] [{:keys [input db* provider send-msg!]}]
  (swap! db* assoc-in [:auth provider] {:step :login/waiting-url
                                        :api-key input})
  (send-msg! "Inform your LiteLLM API URL (e.g. https://litellm.my-company.com):"))

(defmethod f.login/login-step ["litellm" :login/waiting-url] [{:keys [input db* provider send-msg!]}]
  (swap! db* assoc-in [:auth provider :url] (string/trim input))
  (swap! db* assoc-in [:auth provider :step] :login/waiting-models)
  (send-msg! "Inform one or more models (separated by `,`):"))

(defmethod f.login/login-step ["litellm" :login/waiting-models] [{:keys [input db* provider send-msg!] :as ctx}]
  (let [{:keys [api-key url]} (get-in @db* [:auth provider])]
    (config/update-global-config! {:providers {"litellm" {:api "openai-responses"
                                                          :url url
                                                          :models (reduce
                                                                   (fn [models model-str]
                                                                     (assoc models (string/trim model-str) {}))
                                                                   {}
                                                                   (string/split input #","))
                                                          :key api-key}}}))
  (swap! db* assoc-in [:auth provider] {:step :login/done :type :auth/token})
  (send-msg! (format "API key and models saved to %s" (.getCanonicalPath (config/global-config-file))))
  (f.login/login-done! ctx))
