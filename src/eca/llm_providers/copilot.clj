(ns eca.llm-providers.copilot
  (:require
   [cheshire.core :as json]
   [eca.client-http :as client]
   [eca.config :as config]
   [eca.features.login :as f.login]
   [eca.features.providers :as f.providers]
   [eca.logger :as logger]
   [eca.shared :refer [multi-str]]
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

;; --- Settings-based login (providers/login flow) ---

(defmethod f.providers/start-login! ["github-copilot" "device"] [_ _ db* _config messenger metrics]
  (let [{:keys [user-code device-code url]} (oauth-url)]
    (swap! db* assoc-in [:auth "github-copilot"] {:step :login/waiting-user-confirmation
                                                   :device-code device-code})
    (future
      (loop [attempts 0]
        (Thread/sleep 5000)
        (when (and (< attempts 60)
                   (= :login/waiting-user-confirmation
                      (get-in @db* [:auth "github-copilot" :step])))
          (let [result (try
                         (let [access-token (oauth-access-token device-code)
                               {:keys [api-key expires-at]} (oauth-renew-token access-token)]
                           (swap! db* update-in [:auth "github-copilot"] merge
                                  {:step :login/done
                                   :access-token access-token
                                   :api-key api-key
                                   :expires-at expires-at})
                           (f.providers/sync-and-notify! "github-copilot" db* messenger metrics)
                           :done)
                         (catch Exception e
                           (logger/debug "[COPILOT]" "Device poll attempt" attempts ":" (ex-message e))
                           :retry))]
            (when (= :retry result)
              (recur (inc attempts)))))))
    {:action "device-code"
     :url url
     :code user-code
     :message "Enter this code at the URL above. Make sure Copilot is enabled at https://github.com/settings/copilot/features"}))

;; --- Chat-based login (legacy /login command) ---

(defmethod f.login/login-step ["github-copilot" :login/start] [{:keys [db* chat-id provider send-msg!]}]
  (let [{:keys [user-code device-code url]} (oauth-url)]
    (swap! db* assoc-in [:chats chat-id :login-provider] provider)
    (swap! db* assoc-in [:auth provider] {:step :login/waiting-user-confirmation
                                          :device-code device-code})
    (send-msg! (multi-str
                "First, make sure you have Copilot enabled in you Github account: https://github.com/settings/copilot/features"
                (format "Then, open your browser at:\n\n%s\n\nAuthenticate using the code: `%s`\nThen type anything in the chat and send it to continue the authentication."
                        url
                        user-code)))))

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
    (f.login/login-done! ctx :silent? true :skip-models-sync? true)))
