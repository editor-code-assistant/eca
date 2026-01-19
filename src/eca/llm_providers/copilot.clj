(ns eca.llm-providers.copilot
  (:require
   [cheshire.core :as json]
   [eca.client-http :as client]
   [eca.config :as config]
   [eca.features.login :as f.login]
   [hato.client :as http]))

(def ^:private client-id "Iv1.b507a08c87ecfe98")

(defn ^:private auth-headers []
  {"Content-Type" "application/json"
   "Accept" "application/json"
   "editor-plugin-version" "eca/*"
   "editor-version" (str "eca/" (config/eca-version))})

(def ^:private oauth-login-device-url
  "https://github.com/login/device/code")

(defn ^:private oauth-url []
  (let [{:keys [body]} (http/post
                        oauth-login-device-url
                        {:headers (auth-headers)
                         :body (json/generate-string {:client_id client-id
                                                      :scope "read:user"})
                         :http-client (client/merge-with-global-http-client {})
                         :as :json})]
    {:user-code (:user_code body)
     :device-code (:device_code body)
     :url (:verification_uri body)}))

(def ^:private oauth-login-access-token-url
  "https://github.com/login/oauth/access_token")

(defn ^:private oauth-access-token [device-code]
  (let [{:keys [status body]} (http/post
                               oauth-login-access-token-url
                               {:headers (auth-headers)
                                :body (json/generate-string {:client_id client-id
                                                             :device_code device-code
                                                             :grant_type "urn:ietf:params:oauth:grant-type:device_code"})
                                :throw-exceptions? false
                                :http-client (client/merge-with-global-http-client {})
                                :as :json})]
    (if (= 200 status)
      (:access_token body)
      (throw (ex-info (format "Github auth failed: %s" (pr-str body))
                      {:status status
                       :body body})))))

(def ^:private oauth-copilot-token-url
  "https://api.github.com/copilot_internal/v2/token")

(defn ^:private oauth-renew-token [access-token]
  (let [{:keys [status body]} (http/get
                               oauth-copilot-token-url
                               {:headers (merge (auth-headers)
                                                {"authorization" (str "token " access-token)})
                                :throw-exceptions? false
                                :http-client (client/merge-with-global-http-client {})
                                :as :json})]
    (if-let [token (:token body)]
      {:api-key token
       :expires-at (:expires_at body)}
      (throw (ex-info (format "Error on copilot login: %s" body)
                      {:status status
                       :body body})))))

(defmethod f.login/login-step ["github-copilot" :login/start] [{:keys [db* chat-id provider send-msg!]}]
  (let [{:keys [user-code device-code url]} (oauth-url)]
    (swap! db* assoc-in [:chats chat-id :login-provider] provider)
    (swap! db* assoc-in [:auth provider] {:step :login/waiting-user-confirmation
                                          :device-code device-code})
    (send-msg! (format "Open your browser at:\n\n%s\n\nAuthenticate using the code: `%s`\nThen type anything in the chat and send it to continue the authentication."
                       url
                       user-code))))

(defmethod f.login/login-step ["github-copilot" :login/waiting-user-confirmation] [{:keys [db* provider send-msg!] :as ctx}]
  (let [access-token (oauth-access-token (get-in @db* [:auth provider :device-code]))
        {:keys [api-key expires-at]} (oauth-renew-token access-token)]
    (swap! db* update-in [:auth provider] merge {:step :login/done
                                                 :access-token access-token
                                                 :api-key api-key
                                                 :expires-at expires-at})

    (f.login/login-done! ctx)
    (send-msg! "\nMake sure to enable the model you want use at: https://github.com/settings/copilot/features")))

(defmethod f.login/login-step ["github-copilot" :login/renew-token] [{:keys [db* provider] :as ctx}]
  (let [access-token (get-in @db* [:auth provider :access-token])
        {:keys [api-key expires-at]} (oauth-renew-token access-token)]
    (swap! db* update-in [:auth provider] merge {:api-key api-key
                                                 :expires-at expires-at})
    (f.login/login-done! ctx :silent? true)))
