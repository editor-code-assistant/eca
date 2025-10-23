(ns eca.llm-providers.google
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.login :as f.login]))

(defmethod f.login/login-step ["google" :login/start] [{:keys [db* chat-id provider send-msg!]}]
  (swap! db* assoc-in [:chats chat-id :login-provider] provider)
  (swap! db* assoc-in [:auth provider] {:step :login/waiting-api-key})
  (send-msg! "Paste your google API Key"))

(defmethod f.login/login-step ["google" :login/waiting-api-key] [{:keys [db* input provider send-msg!] :as ctx}]
  (if (not (string/blank? input))
    (do
      (config/update-global-config! {:providers {"google" {:key input}}})
      (swap! db* update :auth dissoc provider)
      (send-msg! (format "API key saved to %s" (.getCanonicalPath (config/global-config-file))))
      (f.login/login-done! ctx))
    (send-msg! (format "Invalid API key '%s'" input))))
