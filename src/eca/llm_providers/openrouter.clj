(ns eca.llm-providers.openrouter
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.login :as f.login]))

(defmethod f.login/login-step ["openrouter" :login/start] [{:keys [db* chat-id provider send-msg!]}]
  (swap! db* assoc-in [:chats chat-id :login-provider] provider)
  (swap! db* assoc-in [:auth provider] {:step :login/waiting-api-key})
  (send-msg! "Paste your API Key"))

(defmethod f.login/login-step ["openrouter" :login/waiting-api-key] [{:keys [input db* provider send-msg!]}]
  (swap! db* assoc-in [:auth provider] {:step :login/waiting-models
                                        :api-key input})
  (send-msg! "Inform one or more models (separated by `,`):"))

(defmethod f.login/login-step ["openrouter" :login/waiting-models] [{:keys [input db* provider send-msg!] :as ctx}]
  (let [api-key (get-in @db* [:auth provider :api-key])]
    (config/update-global-config! {:providers {"openrouter" {:api "openai-chat"
                                                             :url "https://openrouter.ai/api/v1"
                                                             :models (reduce
                                                                      (fn [models model-str]
                                                                        (assoc models (string/trim model-str) {}))
                                                                      {}
                                                                      (string/split input #","))
                                                             :key api-key}}}))
  (swap! db* update :auth dissoc provider)
  (send-msg! (format "API key and models saved to %s" (.getCanonicalPath (config/global-config-file))))
  (f.login/login-done! ctx))
