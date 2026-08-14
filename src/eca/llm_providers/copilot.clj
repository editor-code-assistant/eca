(ns eca.llm-providers.copilot
  (:require
   [cheshire.core :as json]
   [clojure.string :as string]
   [eca.client-http :as client]
   [eca.config :as config]
   [eca.features.login :as f.login]
   [eca.features.providers :as f.providers]
   [eca.llm-providers.errors :as llm-providers.errors]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
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

(defn model-not-supported-error?
  "True when a Copilot completion request failed with 400 model_not_supported:
   the model id is unknown or the account's per-model policy is not enabled."
  [{:keys [status body]}]
  (and (= 400 status)
       (string/includes? (if (string? body) body (pr-str body))
                         "model_not_supported")))

(defn enrich-model-not-supported-error
  "Rewrites Copilot's cryptic 400 model_not_supported error with actionable
   guidance: the /models catalog lists every model, but requests fail until
   the account's per-model policy is enabled (via a client consent prompt;
   there is no per-model toggle on the GitHub settings page anymore)."
  [error-data model]
  (if (model-not-supported-error? error-data)
    (assoc error-data :message
           (format (multi-str
                    "GitHub Copilot rejected model '%s': it is not supported or not enabled for your account."
                    "To enable it, use an ECA client that supports questions (ECA asks and enables it on retry) or enable it once via VS Code."
                    "On Business/Enterprise plans an org admin must enable the model in the org's Copilot policies.")
                   model))
    error-data))

(defn ^:private policy-api-headers [api-key]
  (merge {"Authorization" (str "Bearer " api-key)
          "Content-Type" "application/json"}
         (llm-util/copilot-ide-headers)))

(defn fetch-model-policy
  "Live-fetches the Copilot /models catalog and returns the policy entry for
   `model` as {:listed? bool :policy {:state .. :terms ..}}, or nil when the
   catalog cannot be fetched."
  [{:keys [api-url api-key]} model]
  (try
    (let [{:keys [status body]} (http/get (str api-url "/models")
                                          {:headers (policy-api-headers api-key)
                                           :throw-exceptions? false
                                           :as :json
                                           :http-client (client/merge-with-global-http-client {})})]
      (if (= 200 status)
        (let [entry (first (filter #(= model (:id %)) (:data body)))]
          {:listed? (some? entry)
           :policy (:policy entry)})
        (do
          (logger/warn "[COPILOT]" "Failed to fetch model policy, status:" status)
          nil)))
    (catch Exception e
      (logger/warn "[COPILOT]" "Failed to fetch model policy:" (ex-message e))
      nil)))

(defn enable-model-policy!
  "Enables the account's per-model policy for `model`, accepting the model
   terms; the same call editor consent dialogs make. Returns {:enabled? true}
   or {:enabled? false ...}."
  [{:keys [api-url api-key]} model]
  (try
    (let [{:keys [status body]} (http/post (str api-url "/models/" model "/policy")
                                           {:headers (policy-api-headers api-key)
                                            :body (json/generate-string {:state "enabled"})
                                            :throw-exceptions? false
                                            :as :json
                                            :http-client (client/merge-with-global-http-client {})})]
      (if (and (number? status) (<= 200 status 299))
        {:enabled? true}
        {:enabled? false :status status :body body}))
    (catch Exception e
      {:enabled? false :error (ex-message e)})))

(def ^:private enable-model-answer-label "Enable model")

(def ^:private ask-to-enable-timeout-ms (* 5 60 1000))

(defn ask-to-enable-model!
  "Interactive consent to enable a Copilot per-model policy, mirroring the
   editors' consent dialog: live-checks the policy state, asks the user via
   chat/askQuestion and enables the policy on acceptance.
   Returns {:result :enabled|:declined|:cancelled|:no-policy|:enable-failed}."
  [{:keys [messenger chat-id auth model]}]
  (let [{:keys [listed? policy] :as policy-check} (fetch-model-policy auth model)]
    (if (or (nil? policy-check)
            (not listed?)
            (nil? policy)
            (= "enabled" (:state policy)))
      {:result :no-policy :listed? listed? :policy policy}
      (let [question (multi-str
                      (format "GitHub Copilot model '%s' is not enabled for your account." model)
                      ""
                      (str (:terms policy))
                      ""
                      "Enable it?")
            response (deref (messenger/ask-question
                             messenger
                             {:chatId chat-id
                              :question question
                              :allowFreeform false
                              :request-id (str (random-uuid))
                              :options [{:label enable-model-answer-label
                                         :description "Accept these terms and enable the model for your account"}
                                        {:label "Cancel"
                                         :description "Keep the model disabled"}]})
                            ask-to-enable-timeout-ms
                            {:cancelled true})]
        (cond
          (:cancelled response)
          {:result :cancelled}

          (= enable-model-answer-label (:answer response))
          (let [{:keys [enabled?] :as enable-result} (enable-model-policy! auth model)
                ;; The policy endpoint answers 200 but silently ignores models
                ;; not included in the account's plan (e.g. premium models on
                ;; Copilot Free), so verify the policy actually flipped.
                verified-policy (when enabled? (fetch-model-policy auth model))]
            (cond
              (not enabled?)
              (assoc enable-result :result :enable-failed)

              (or (nil? verified-policy)
                  (= "enabled" (get-in verified-policy [:policy :state])))
              {:result :enabled}

              :else
              {:result :enable-rejected :policy (:policy verified-policy)}))

          :else
          {:result :declined})))))

(defmethod llm-providers.errors/enrich-provider-error "github-copilot"
  [{:keys [error-data model]}]
  (enrich-model-not-supported-error error-data model))

(defmethod llm-providers.errors/recoverable-error? "github-copilot"
  [{:keys [error-data db]}]
  (boolean
   (and (model-not-supported-error? error-data)
        (get-in db [:client-capabilities :code-assistant :chat-capabilities :ask-question]))))

(defmethod llm-providers.errors/recover-error! "github-copilot"
  [{:keys [model db messenger chat-id]}]
  (let [auth (get-in db [:auth "github-copilot"])]
    (messenger/chat-content-received messenger
                                     {:chat-id chat-id
                                      :role :system
                                      :content {:type :progress :state :running :text "Waiting model enable answer"}})
    (let [{:keys [result] :as outcome} (ask-to-enable-model! {:messenger messenger
                                                              :chat-id chat-id
                                                              :auth auth
                                                              :model model})]
      (case result
        :enabled
        {:retry? true
         :notice (format "\nModel '%s' enabled for your account, retrying...\n" model)
         :retry-user-message "The model was just enabled for this account. Continue with the original request."}

        (:declined :cancelled)
        {:retry? false :notice (format "\nModel '%s' was not enabled." model)}

        :enable-rejected
        {:retry? false
         :notice (format (str "\nGitHub accepted the request but did not enable model '%s':"
                              " it is likely not included in your Copilot plan."
                              " Select another model.")
                         model)}

        :enable-failed
        {:retry? false
         :notice (format "\nFailed to enable model '%s': %s"
                         model
                         (pr-str (select-keys outcome [:status :body :error])))}

        ;; :no-policy or unknown: fall back to the enriched terminal error message
        {:retry? false :notice nil}))))

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
