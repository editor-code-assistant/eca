(ns eca.llm-providers.lmstudio
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.login :as f.login]))

(defmethod f.login/login-step ["lmstudio" :login/start] [{:keys [db* chat-id provider send-msg!]}]
  (swap! db* assoc-in [:chats chat-id :login-provider] provider)
  (swap! db* assoc-in [:auth provider] {:step :login/waiting-models})
  (send-msg! "Inform one or more models (separated by `,`):"))

(defmethod f.login/login-step ["lmstudio" :login/waiting-models] [{:keys [input db* provider send-msg!] :as ctx}]
  (config/update-global-config! {:providers {"lmstudio" {:api "openai-chat"
                                                         :url "http://localhost:1234"
                                                         :completionUrlRelativePath "/v1/chat/completions"
                                                         :httpClient {:version "http-1.1"}
                                                         :models (reduce
                                                                  (fn [models model-str]
                                                                    (assoc models (string/trim model-str) {}))
                                                                  {}
                                                                  (string/split input #","))}}})
  (swap! db* assoc-in [:auth provider] {:step :login/done :type :auth/token})
  (send-msg! (format "Models saved to %s" (.getCanonicalPath (config/global-config-file))))
  (f.login/login-done! ctx))