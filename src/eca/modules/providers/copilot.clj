(ns eca.modules.providers.copilot
  "The `:eca/copilot` module. GitHub Copilot models, with the account's own
   model list and quota."
  (:require
   [cheshire.core :as json]
   [clojure.string :as string]
   [eca.client-http :as client]
   [eca.config :as config]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.models :as models]
   [eca.modules.llm-providers.login :as m.llm-providers.login]
   [eca.modules.llm-providers.providers :as m.llm-providers.providers]
   [eca.modules.llm-providers.quota :as m.llm-providers.quota]
   [eca.shared :as shared :refer [assoc-some join-api-url multi-str]]
   [hato.client :as http])
  (:import
   [java.net URLDecoder]
   [java.nio.charset StandardCharsets]))

(set! *warn-on-reflection* true)

(def ^:private default-client-id "Iv1.b507a08c87ecfe98")

(def ^:private token-renew-max-attempts 2)

(def ^:private token-renew-retry-delay-ms 250)

(def ^:private token-renew-retry-statuses #{500 502 503 504})

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

(defn ^:private ide-headers
  "GitHub Copilot's API authenticates IDE requests by a recognized editor
   identity. A non-editor value (e.g. `eca/...`) is rejected with
   `400 missing/unknown Editor-Version header for IDE auth`, so we present the
   VS Code Copilot client identity its `/models` and chat endpoints expect."
  []
  {"editor-version" "vscode/1.107.0"
   "editor-plugin-version" "copilot-chat/0.35.0"
   "copilot-integration-id" "vscode-chat"})

(defn ^:private policy-api-headers [api-key]
  (merge {"Authorization" (str "Bearer " api-key)
          "Content-Type" "application/json"}
         (ide-headers)))

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

(defn ^:private enrich-error
  [{:keys [error-data model]}]
  (enrich-model-not-supported-error error-data model))

(defn ^:private recoverable-error?
  [{:keys [error-data db]}]
  (boolean
   (and (model-not-supported-error? error-data)
        (get-in db [:client-capabilities :code-assistant :chat-capabilities :ask-question]))))

(defn ^:private recover-error!
  [{:keys [provider model db messenger chat-id]}]
  (let [auth (get-in db [:auth provider])]
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
        request-options {:headers (merge (auth-headers)
                                         {"authorization" (str "token " access-token)})
                         :throw-exceptions? false
                         :http-client (client/merge-with-global-http-client {})
                         :as :json}]
    (loop [attempt 1]
      (let [{:keys [status body]} (http/get token-url request-options)]
        (cond
          (:token body)
          (cond-> {:api-key (:token body)
                   :expires-at (:expires_at body)}
            (get-in body [:endpoints :api]) (assoc :api-url (get-in body [:endpoints :api])))

          (and (token-renew-retry-statuses status)
               (< attempt token-renew-max-attempts))
          (do
            (Thread/sleep (long token-renew-retry-delay-ms))
            (recur (inc attempt)))

          :else
          (throw (ex-info (format "Error on copilot login: %s" body)
                          {:status status
                           :body body})))))))

(defn ^:private parse-form [s]
  (into {}
        (keep (fn [pair]
                (let [[k v] (string/split pair #"=" 2)
                      ^String v (or v "")]
                  (when (seq k)
                    [k (URLDecoder/decode v StandardCharsets/UTF_8)]))))
        (string/split s #"&")))

(defn ^:private copilot-label [quota-id]
  (case quota-id
    ("premium_interactions" "premium_models") "Premium"
    "chat" "Chat"
    "completions" "Completions"
    (string/capitalize (string/replace quota-id #"[_-]+" " "))))

(defn ^:private copilot-window [quota-id {:keys [entitlement remaining-percent unlimited? reset]}]
  (when (and remaining-percent
             (not unlimited?)
             (not (contains? #{-1.0 0.0} entitlement)))
    (assoc-some
     {:id quota-id
      :label (copilot-label quota-id)
      :used-percent (m.llm-providers.quota/round1 (max 0.0 (- 100.0 remaining-percent)))}
     :resets-at (m.llm-providers.quota/->epoch-seconds reset))))

(defn ^:private prefer-premium-models
  "Newer Copilot backends report `premium_models` alongside the legacy
   `premium_interactions`; keep only the former, like VS Code does."
  [windows]
  (if (some #(= "premium_models" (:id %)) windows)
    (remove #(= "premium_interactions" (:id %)) windows)
    windows))

(defn ^:private quota-windows
  "GitHub Copilot quotas from `x-quota-snapshot-<quota-id>` headers, whose
   values are url-encoded like `ent=300&rem=95.3&rst=2026-09-01T00:00:00Z`
   (`rem` is the remaining percentage, `ent=-1` means unlimited). The
   declaration's `:quota :headers`."
  [headers]
  (prefer-premium-models
   (keep (fn [[k v]]
           (when-let [[_ quota-id] (re-matches #"x-quota-snapshot-(.+)" k)]
             (let [{:strs [ent rem rst]} (parse-form v)]
               (copilot-window quota-id {:entitlement (m.llm-providers.quota/parse-number ent)
                                         :remaining-percent (m.llm-providers.quota/parse-number rem)
                                         :reset rst}))))
         headers)))

(defn ^:private usage->snapshot
  "Snapshot from GitHub's `copilot_internal/user` response `quota_snapshots`."
  [{:keys [quota_snapshots quota_reset_date_utc quota_reset_date]}]
  (let [windows (prefer-premium-models
                 (keep (fn [[quota-id {:keys [entitlement percent_remaining unlimited quota_reset_at]}]]
                         (copilot-window (name quota-id)
                                         {:entitlement (m.llm-providers.quota/parse-number entitlement)
                                          :remaining-percent (m.llm-providers.quota/parse-number percent_remaining)
                                          :unlimited? (true? unlimited)
                                          :reset (or quota_reset_at quota_reset_date_utc quota_reset_date)}))
                       quota_snapshots))]
    (m.llm-providers.quota/snapshot windows)))

(defn ^:private fetch-quota
  "`:quota :fetch`: the account's Copilot usage."
  [{:keys [provider provider-auth config]}]
  (let [url (str (github-api-base-url (get-in config [:providers provider])) "/copilot_internal/user")
        {:keys [status body]} (http/get url
                                        {:headers (merge (auth-headers)
                                                         {"authorization" (str "token " (:access-token provider-auth))})
                                         :throw-exceptions? false
                                         :as :json
                                         :http-client (client/merge-with-global-http-client {})
                                         :timeout 10000})]
    (if (= 200 status)
      (usage->snapshot body)
      (throw (ex-info (format "Copilot user endpoint returned status %s" status) {:status status})))))

;; --- Settings-based login (providers/login flow) ---

(defn ^:private poll-device-authorization!
  "Polls GitHub's device-flow token endpoint in background until the user
   authorizes in the browser, the login state changes (e.g. cancelled), or
   `max-attempts` is reached. Calls `on-token` with the token data on success
   or `on-timeout` when attempts are exhausted."
  [provider provider-settings db* {:keys [interval-ms max-attempts on-token on-timeout]
                                   :or {interval-ms 5000
                                        max-attempts 180}}]
  (future
    (loop [attempts 0]
      (Thread/sleep (long interval-ms))
      (when (= :login/waiting-user-confirmation
               (get-in @db* [:auth provider :step]))
        (if (< attempts max-attempts)
          (let [token-data (try
                             (when-let [access-token (oauth-access-token
                                                      provider-settings
                                                      (get-in @db* [:auth provider :device-code]))]
                               (assoc (oauth-renew-token provider-settings access-token)
                                      :access-token access-token))
                             (catch Exception e
                               (logger/debug "[COPILOT]" "Device poll attempt" attempts ":" (ex-message e))
                               nil))]
            (if token-data
              (try
                (on-token token-data)
                (catch Exception e
                  (logger/warn "[COPILOT]" "Failed to complete device login:" (ex-message e))))
              (recur (inc attempts))))
          (on-timeout))))))

(defn ^:private start-device-login!
  "The `device` login method's `:start`: GitHub's device flow."
  [provider db* config messenger metrics]
  (let [provider-settings (get-in config [:providers provider])
        {:keys [user-code device-code url]} (oauth-url provider-settings)]
    (swap! db* assoc-in [:auth provider] {:step :login/waiting-user-confirmation
                                          :device-code device-code})
    (poll-device-authorization!
     provider
     provider-settings
     db*
     {:max-attempts 60
      :on-token (fn [token-data]
                  (swap! db* update-in [:auth provider] merge
                         (assoc token-data :step :login/done))
                  (m.llm-providers.providers/sync-and-notify! provider db* messenger metrics))
      :on-timeout (fn [])})
    {:action "device-code"
     :url url
     :code user-code
     :message (format "Enter this code at the URL above. Make sure Copilot is enabled at %s/settings/copilot/features"
                      (github-base-url provider-settings))}))

;; --- Chat-based login (legacy /login command) ---

(defn ^:private complete-chat-device-login!
  "Finishes the chat-based device login once, guarding against the polling
   future and a typed chat message completing it twice."
  [{:keys [db* provider config send-msg!] :as ctx} token-data]
  (when (= :login/waiting-user-confirmation (get-in @db* [:auth provider :step]))
    (swap! db* update-in [:auth provider] merge (assoc token-data :step :login/done))
    (m.llm-providers.login/login-done! ctx)
    (let [github-url (github-base-url (get-in config [:providers provider]))]
      (send-msg! (format "\nMake sure to enable the models you want to use at: [%s/settings/copilot/features](%s/settings/copilot/features)"
                         github-url
                         github-url)))))

(defn ^:private start-step [{:keys [db* chat-id provider config send-msg!] :as ctx}]
  (let [provider-settings (get-in config [:providers provider])
        {:keys [user-code device-code url]} (oauth-url provider-settings)
        github-url (github-base-url provider-settings)]
    (swap! db* assoc-in [:chats chat-id :login-provider] provider)
    (swap! db* assoc-in [:auth provider] {:step :login/waiting-user-confirmation
                                          :device-code device-code})
    (send-msg! (multi-str
                (format "First, make sure you have Copilot enabled in your GitHub account: [%s/settings/copilot/features](%s/settings/copilot/features)"
                        github-url
                        github-url)
                ""
                (format "Then, open your browser at [%s](%s) and authenticate using the code:" url url)
                ""
                (format "`%s`" user-code)
                ""
                "ECA will complete the login automatically after you authorize, type 'cancel' anytime to abort."))
    (poll-device-authorization!
     provider
     provider-settings
     db*
     {:on-token (fn [token-data] (complete-chat-device-login! ctx token-data))
      :on-timeout (fn [] (send-msg! "GitHub authorization timed out. Type 'cancel' and start the login again."))})))

(defn ^:private waiting-user-confirmation-step [{:keys [db* provider config send-msg!] :as ctx}]
  (let [provider-settings (get-in config [:providers provider])
        token-data (try
                     (when-let [access-token (oauth-access-token provider-settings (get-in @db* [:auth provider :device-code]))]
                       (assoc (oauth-renew-token provider-settings access-token)
                              :access-token access-token))
                     (catch Exception _ nil))]
    (if token-data
      (complete-chat-device-login! ctx token-data)
      (send-msg! "Waiting for the browser authorization to complete, type 'cancel' to abort."))))

(defn ^:private renew-token-step [{:keys [db* provider config]}]
  (let [provider-settings (get-in config [:providers provider])
        access-token (get-in @db* [:auth provider :access-token])
        token-data (oauth-renew-token provider-settings access-token)]
    (swap! db* update-in [:auth provider] merge token-data)))

(defn ^:private model-api [supported-endpoints]
  (let [endpoints (set supported-endpoints)]
    (cond
      (contains? endpoints "/v1/messages") :anthropic
      (contains? endpoints "/responses") :openai-responses
      (contains? endpoints "/chat/completions") :openai-chat)))

(defn ^:private reasoning-variants [model-id api supports]
  (let [efforts (some->> (:reasoning_effort supports)
                         (filter string?)
                         seq)
        adaptive? (true? (:adaptive_thinking supports))
        min-budget (:min_thinking_budget supports)
        max-budget (:max_thinking_budget supports)]
    (case api
      :openai-responses
      (when efforts
        (into {}
              (map (fn [effort]
                     [effort {:reasoning {:effort effort :summary "auto"}}]))
              efforts))

      :openai-chat
      (when efforts
        (into {}
              (map (fn [effort]
                     [effort {:reasoning_effort effort}]))
              efforts))

      :anthropic
      (let [adaptive-thinking (cond-> {:type "adaptive"}
                                ;; Opus 4.7 defaults adaptive thinking display to "omitted", which
                                ;; produces empty thinking blocks. Request summaries explicitly.
                                (re-find #"(?i)opus[-._]4[-._]7(?:$|[-._])" model-id)
                                (assoc :display "summarized"))]
        (cond
          ;; Adaptive-thinking models (Opus 4.7+/Sonnet 5+) reject thinking.type
          ;; "enabled", so a "default" variant keeps no-variant requests on
          ;; adaptive instead of the enabled fallback in the provider (#528).
          (and efforts adaptive?)
          (into {"default" {:thinking adaptive-thinking}}
                (map (fn [effort]
                       [effort {:thinking adaptive-thinking
                                :output_config {:effort effort}}]))
                efforts)

          adaptive?
          {"default" {:thinking adaptive-thinking}}

          ;; Some Copilot Messages models (for example Claude Opus/Sonnet 4.5) advertise
          ;; budget bounds instead of named reasoning efforts. Project that range into
          ;; the existing variant picker without exposing raw token budgets in the UI.
          (and (number? max-budget) (pos? max-budget))
          (let [min-value (max 1024 (long (if (number? min-budget) min-budget 1024)))
                max-value (dec (long max-budget))
                high-value (max min-value (quot (long max-budget) 2))]
            (when (<= min-value max-value)
              {"high" {:thinking {:type "enabled"
                                  :budget_tokens high-value}}
               "max" {:thinking {:type "enabled"
                                 :budget_tokens max-value}}}))

          :else nil))

      nil)))

(defn ^:private model-hidden?
  "True for catalog entries VS Code's picker hides (#601): flagged
   `model_picker_enabled: false` (embeddings, internal agents, the Auto router
   entry) or with a `disabled` policy, which GitHub accepts but never enables
   for plans that don't include the model. `unconfigured` policies are not
   hidden: the model works once the user accepts its terms via the consent
   flow. Entries without the flag or policy are not hidden."
  [{:keys [model_picker_enabled policy]}]
  (or (false? model_picker_enabled)
      (= "disabled" (:state policy))))

(defn ^:private parse-model-entry
  "Parses a Copilot /models entry into [id discovered-config]. The picker
   category (versatile/powerful/lightweight) is kept as provider data to
   choose a default model."
  [{:keys [id supported_endpoints capabilities model_picker_category]}]
  (when (and (string? id)
             (not (string/blank? id)))
    (let [supports (:supports capabilities)
          api (model-api supported_endpoints)
          variants (reasoning-variants id api supports)
          reason? (or (true? (:adaptive_thinking supports))
                      (seq (:reasoning_effort supports))
                      (number? (:max_thinking_budget supports))
                      (number? (:min_thinking_budget supports)))]
      [id (assoc-some {}
                      :discovered-api api
                      :discovered-reason? (when reason? true)
                      :discovered-variants (not-empty variants)
                      :discovered-provider-data (when (shared/not-blank model_picker_category)
                                                  {:picker-category model_picker_category}))])))

(defn ^:private configured-model-ids
  "Model ids referenced by a provider's static `models` config: the keys plus
   `modelName` aliases, without a `<provider>/` prefix."
  [provider static-models]
  (into (set (keys static-models))
        (keep (fn [[_ {:keys [modelName]}]]
                (when (string? modelName)
                  (if (string/includes? modelName "/")
                    (let [[alias-provider model] (shared/full-model->provider+model modelName)]
                      (when (= provider alias-provider) model))
                    modelName))))
        static-models))

(defn ^:private parse-models
  "Parses the Copilot catalog entries. The catalog returns every model the API
   knows regardless of purpose or plan; entries hidden from the picker (see
   `model-hidden?`) are dropped unless the user lists them under the
   provider's `models` config, keeping their discovered API and reasoning
   variants so they aren't routed by model name. When the catalog has entries
   but none is usable for the account (Copilot Free/Student only get auto model
   selection, which third-party clients can't use), returns an empty map
   rather than nil so callers don't fall back to models.dev and list models
   the account can't use."
  [provider models-data static-models]
  (let [configured? (configured-model-ids provider static-models)
        models (into {}
                     (keep (fn [entry]
                             (when-let [[id _ :as parsed] (parse-model-entry entry)]
                               (when (or (not (model-hidden? entry))
                                         (configured? id))
                                 parsed))))
                     models-data)]
    (when (and (seq models-data) (empty? models))
      ;; the tag of the models sync's other logs
      (logger/warn "[MODELS]"
                   (format "Provider '%s': /models returned %d models but none is available for this account's plan"
                           provider (count models-data))))
    (when (seq models-data)
      models)))

(defn ^:private models-override
  "`:models-override-fn`: the account's models, from Copilot's /models
   catalog. It needs an IDE's headers (see `ide-headers`), so the generic
   /models fetch isn't tried: `llm-util/no-native-models` when the catalog
   can't be fetched or is empty, for models.dev's."
  [{:keys [provider api-url api-key extra-headers static-models]}]
  (or (when api-url
        (models/fetch-models-endpoint
         provider
         (join-api-url api-url "/models")
         (client/merge-llm-headers
          (merge (assoc-some (merge {"Content-Type" "application/json"} (ide-headers))
                             "Authorization" (when api-key (str "Bearer " api-key)))
                 extra-headers))
         #(parse-models provider % static-models)))
      llm-util/no-native-models))

(def ^:private responses-api-min-gpt-version [5 3])

(defn ^:private responses-api-model?
  "Name-based routing for GitHub Copilot models whose API wasn't discovered
   from the /models catalog (fetch failed, `fetchModels` disabled, model not
   listed). Copilot serves OpenAI models from gpt-5.3-codex/gpt-5.4 on only
   through the Responses API; older ones (gpt-4.1, gpt-5, gpt-5.1, gpt-5.2)
   and non-OpenAI models take Chat Completions."
  [model]
  (boolean
   (when (string? model)
     (when-let [[_ major minor] (re-find #"(?i)^gpt-(\d+)(?:\.(\d+))?(?:$|[-._])" model)]
       (<= 0 (compare [(parse-long major) (parse-long (or minor "0"))]
                      responses-api-min-gpt-version))))))

(defn ^:private api-by-model-name
  "The API a model takes when its catalog entry didn't say (the
   declaration's `:api-fn`)."
  [{:keys [model]}]
  (if (responses-api-model? model) :openai-responses :openai-chat))

(def ^:private picker-category-rank
  {"versatile" 0 "powerful" 1 "lightweight" 2})

(defn ^:private default-model
  "With a Copilot login, one of the models discovered for the account (models
   hidden from the picker or outside the plan never reach db), preferring the
   catalog's `versatile` picker category, then `powerful`, then
   `lightweight`, then uncategorized ones; alphabetical within a category so
   the choice is deterministic (the declaration's `:default-model`). Nil when
   the account has no usable model."
  [{:keys [db]}]
  (when (get-in db [:auth "github-copilot" :api-key])
    (->> (:models db)
         (filter (fn [[full-model _]] (string/starts-with? full-model "github-copilot/")))
         (sort-by (fn [[full-model capabilities]]
                    [(get picker-category-rank
                          (get-in capabilities [:provider-data :picker-category])
                          (count picker-category-rank))
                     full-model]))
         ffirst)))

(defn ^:private request-opts
  "A request's options (the declaration's `:request-opts`), for the API the
   model takes: the URL the login gave, and the headers of an IDE's chat
   panel, telling who started the request."
  [_opts {:keys [model flat-instructions user-messages max-output-tokens reason? supports-image? past-messages
                 tools web-search image-generation extra-payload extra-headers reasoning-history api-url api-key
                 provider-auth model-capabilities api subagent? prompt-cache-key cancelled?
                 stream-idle-timeout-seconds anthropic-opts]}]
  (when (nil? (:api model-capabilities))
    ;; the tag of the request's other logs
    (logger/info "[LLM-API]"
                 (format "Copilot model '%s' has no API discovered from /models catalog, routing to %s by model name"
                         model api)))
  (let [api-url (or (:api-url provider-auth) api-url)
        copilot-headers (fn [user-initiator? anthropic?]
                          (cond-> (merge {"openai-intent" "conversation-panel"
                                          "x-request-id" (str (random-uuid))
                                          "x-initiator" (if user-initiator? "user" "agent")
                                          "vscode-sessionid" ""
                                          "vscode-machineid" ""
                                          "Copilot-Vision-Request" "true"
                                          "copilot-integration-id" "vscode-chat"}
                                         (ide-headers)
                                         extra-headers)
                            ;; Copilot uses a GitHub bearer token, not Anthropic OAuth, so replace the
                            ;; handler's OAuth beta with the Messages shim's required beta.
                            anthropic? (assoc "anthropic-beta" "interleaved-thinking-2025-05-14")))
        user-initiator? (fn [body message-key]
                          (and (not subagent?)
                               (= "user" (-> body message-key last :role))))
        base-opts {:model model
                   :instructions flat-instructions
                   :user-messages user-messages
                   :max-output-tokens max-output-tokens
                   :reason? reason?
                   :supports-image? supports-image?
                   :past-messages past-messages
                   :tools tools
                   :reasoning-history reasoning-history
                   :api-url api-url
                   :api-key api-key
                   :prompt-cache-key prompt-cache-key
                   :cancelled? cancelled?
                   :stream-idle-timeout-seconds stream-idle-timeout-seconds}]
    (case api
      :openai-responses
      (assoc base-opts
             :web-search web-search
             :image-generation image-generation
             :extra-payload (merge {:parallel_tool_calls true} extra-payload)
             :extra-headers (fn [{:keys [body]}]
                              (copilot-headers (user-initiator? body :input) false)))

      :anthropic
      (assoc anthropic-opts
             :api-url api-url
             :extra-headers (fn [{:keys [body]}]
                              (copilot-headers (user-initiator? body :messages) true)))

      (assoc base-opts
             :extra-payload (merge {:parallel_tool_calls true} extra-payload)
             :extra-headers (fn [{:keys [body]}]
                              (copilot-headers (user-initiator? body :messages) false))))))

(def module
  {:id :eca/copilot
   :description "GitHub Copilot models, with the account's own model list and quota."
   ;; picks the Responses or the Chat Completions API by model
   :requires #{:eca/api-openai-responses :eca/api-openai-chat}
   :providers {"github-copilot" {:label "GitHub Copilot"
                                 :login {:methods [{:key "device" :label "GitHub device flow" :start #'start-device-login!}]
                                         ;; the chat's /login
                                         :steps {:login/start #'start-step
                                                 :login/waiting-user-confirmation #'waiting-user-confirmation-step
                                                 :login/renew-token #'renew-token-step}}
                                 :quota {:fetch #'fetch-quota :headers #'quota-windows}
                                 :request-opts #'request-opts
                                 :api-fn #'api-by-model-name
                                 :default-model {:rank 3 :fn #'default-model}
                                 :models-override-fn #'models-override
                                 :errors {:enrich #'enrich-error
                                          :recoverable? #'recoverable-error?
                                          :recover! #'recover-error!}}}
   :config-defaults {:providers {"github-copilot" {:api "openai-chat"
                                                   :url "${env:GITHUB_COPILOT_API_URL:https://api.githubcopilot.com}"
                                                   :key nil ;; not supported, requires login auth
                                                   :requiresAuth? true
                                                   ;; models come from the account's /models catalog after login;
                                                   ;; a static entry here would bypass its plan filtering.
                                                   :models {}}}}})
