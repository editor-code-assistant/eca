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

(set! *warn-on-reflection* true)

(def ^:private default-client-id "Iv1.b507a08c87ecfe98")

(defn ^:private github-base-url [provider-settings]
  (or (get-in provider-settings [:auth :url])
      "https://github.com"))

(defn ^:private github-api-base-url [provider-settings]
  (let [base (github-base-url provider-settings)]
    (if (= base "https://github.com")
      "https://api.github.com"
      (str base "/api/v3"))))

(defn ^:private copilot-client-id [provider-settings]
  (or (get-in provider-settings [:auth :clientId])
      default-client-id))

(defn ^:private auth-headers []
  {"Content-Type" "application/json"
   "Accept" "application/json"
   "editor-plugin-version" "eca/*"
   "editor-version" (str "eca/" (config/eca-version))})

(defn ^:private oauth-url [provider-settings]
  (let [device-url (str (github-base-url provider-settings) "/login/device/code")
        {:keys [body]} (http/post
                        device-url
                        {:headers (auth-headers)
                         :body (json/generate-string {:client_id (copilot-client-id provider-settings)
                                                      :scope "read:user"})
                         :http-client (client/merge-with-global-http-client {})
                         :as :json})]
    {:user-code (:user_code body)
     :device-code (:device_code body)
     :url (:verification_uri body)}))

(defn ^:private oauth-access-token [provider-settings device-code]
  (let [access-token-url (str (github-base-url provider-settings) "/login/oauth/access_token")
        {:keys [status body]} (http/post
                               access-token-url
                               {:headers (auth-headers)
                                :body (json/generate-string {:client_id (copilot-client-id provider-settings)
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

(defn ^:private oauth-renew-token [provider-settings access-token]
  (let [token-url (str (github-api-base-url provider-settings) "/copilot_internal/v2/token")
        {:keys [status body]} (http/get
                               token-url
                               {:headers (merge (auth-headers)
                                                {"authorization" (str "token " access-token)})
                                :throw-exceptions? false
                                :http-client (client/merge-with-global-http-client {})
                                :as :json})]
    (if-let [token (:token body)]
      (cond-> {:api-key token
               :expires-at (:expires_at body)}
        (get-in body [:endpoints :api]) (assoc :api-url (get-in body [:endpoints :api])))
      (throw (ex-info (format "Error on copilot login: %s" body)
                      {:status status
                       :body body})))))

;; --- Settings-based login (providers/login flow) ---

(defmethod f.providers/start-login! ["github-copilot" "device"] [_ _ db* config messenger metrics]
  (let [provider-settings (get-in config [:providers "github-copilot"])
        {:keys [user-code device-code url]} (oauth-url provider-settings)]
    (swap! db* assoc-in [:auth "github-copilot"] {:step :login/waiting-user-confirmation
                                                   :device-code device-code})
    (future
      (loop [attempts 0]
        (Thread/sleep 5000)
        (when (and (< attempts 60)
                   (= :login/waiting-user-confirmation
                      (get-in @db* [:auth "github-copilot" :step])))
          (let [result (try
                         (let [access-token (oauth-access-token provider-settings device-code)
                               token-data (oauth-renew-token provider-settings access-token)]
                           (swap! db* update-in [:auth "github-copilot"] merge
                                  (assoc token-data
                                         :step :login/done
                                         :access-token access-token))
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
     :message (format "Enter this code at the URL above. Make sure Copilot is enabled at %s/settings/copilot/features"
                      (github-base-url provider-settings))}))

;; --- Chat-based login (legacy /login command) ---

(defmethod f.login/login-step ["github-copilot" :login/start] [{:keys [db* chat-id provider config send-msg!]}]
  (let [provider-settings (get-in config [:providers provider])
        {:keys [user-code device-code url]} (oauth-url provider-settings)
        github-url (github-base-url provider-settings)]
    (swap! db* assoc-in [:chats chat-id :login-provider] provider)
    (swap! db* assoc-in [:auth provider] {:step :login/waiting-user-confirmation
                                          :device-code device-code})
    (send-msg! (multi-str
                (format "First, make sure you have Copilot enabled in your Github account: %s/settings/copilot/features" github-url)
                (format "Then, open your browser at:\n\n%s\n\nAuthenticate using the code: `%s`\nThen type anything in the chat and send it to continue the authentication."
                        url
                        user-code)))))

(defmethod f.login/login-step ["github-copilot" :login/waiting-user-confirmation] [{:keys [db* provider config send-msg!] :as ctx}]
  (let [provider-settings (get-in config [:providers provider])
        access-token (oauth-access-token provider-settings (get-in @db* [:auth provider :device-code]))
        token-data (oauth-renew-token provider-settings access-token)]
    (swap! db* update-in [:auth provider] merge
           (assoc token-data :step :login/done :access-token access-token))
    (f.login/login-done! ctx)
    (send-msg! (format "\nMake sure to enable the model you want to use at: %s/settings/copilot/features"
                       (github-base-url provider-settings)))))

(defmethod f.login/login-step ["github-copilot" :login/renew-token] [{:keys [db* provider config] :as ctx}]
  (let [provider-settings (get-in config [:providers provider])
        access-token (get-in @db* [:auth provider :access-token])
        token-data (oauth-renew-token provider-settings access-token)]
    (swap! db* update-in [:auth provider] merge token-data)
    (f.login/login-done! ctx :silent? true :skip-models-sync? true)))
