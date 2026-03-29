(ns eca.features.providers
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.db :as db]
   [eca.messenger :as messenger]
   [eca.models :as models]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

;; --- Provider metadata ---

(def ^:private provider-labels
  {"anthropic" "Anthropic"
   "openai" "OpenAI"
   "github-copilot" "GitHub Copilot"
   "google" "Google"
   "azure" "Azure"
   "deepseek" "DeepSeek"
   "openrouter" "OpenRouter"
   "z-ai" "Z-AI"
   "ollama" "Ollama"})

(def ^:private login-methods
  {"anthropic"      [{:key "max"     :label "Max (subscription via OAuth)"}
                     {:key "console" :label "Console (API key via OAuth)"}
                     {:key "manual"  :label "Manual (paste API key)"}]
   "openai"         [{:key "pro"    :label "Pro (subscription via OAuth)"}
                     {:key "manual" :label "Manual (paste API key)"}]
   "github-copilot" [{:key "device" :label "GitHub device flow"}]
   "google"         [{:key "api-key" :label "Enter API key"}]
   "azure"          [{:key "api-key" :label "Enter API key, URL & models"}]
   "deepseek"       [{:key "api-key" :label "Enter API key & models"}]
   "openrouter"     [{:key "api-key" :label "Enter API key & models"}]
   "z-ai"           [{:key "api-key" :label "Enter API key & models"}]})

(def ^:private provider-login-fields
  {"google"     [{:key "api-key" :label "API Key" :type "secret"}]
   "deepseek"   [{:key "api-key" :label "API Key" :type "secret"}
                 {:key "models"  :label "Model names (comma-separated)" :type "text"}]
   "openrouter" [{:key "api-key" :label "API Key" :type "secret"}
                 {:key "models"  :label "Model names (comma-separated)" :type "text"}]
   "z-ai"       [{:key "api-key" :label "API Key" :type "secret"}
                 {:key "models"  :label "Model names (comma-separated)" :type "text"}]
   "azure"      [{:key "api-key" :label "API Key" :type "secret"}
                 {:key "url"     :label "API URL" :type "text"}
                 {:key "models"  :label "Model names (comma-separated)" :type "text"}]})

(def ^:private provider-configs
  {"deepseek"   {:api "openai-chat" :url "https://api.deepseek.com"}
   "openrouter" {:api "openai-chat" :url "https://openrouter.ai/api/v1"}
   "z-ai"       {:api "openai-chat" :url "https://api.x.ai"}
   "azure"      {:api "openai-chat"}})

;; --- Auth resolution ---

(defn ^:private ->env-var-name [provider-name]
  (str (-> provider-name
           (string/replace "-" "_")
           string/upper-case)
       "_API_KEY"))

(defn ^:private resolve-auth-info
  "Determines auth status for a provider by checking each credential source
   in the same priority order as llm-util/provider-api-key."
  [provider-name provider-auth config]
  (let [config-key (not-empty (get-in config [:providers provider-name :key]))
        login-key (:api-key provider-auth)
        env-var-name (->env-var-name provider-name)
        env-key (config/get-env env-var-name)
        [source auth-type] (cond
                             config-key ["config" :auth/token]
                             login-key  ["login" (or (:type provider-auth) :auth/oauth)]
                             env-key    ["env" :auth/token]
                             :else      nil)]
    (if source
      (let [now (quot (System/currentTimeMillis) 1000)
            expires (:expires-at provider-auth)]
        (shared/assoc-some
         {:status (cond
                    (nil? expires)                  "authenticated"
                    (<= ^long expires now)          "expired"
                    (<= ^long expires (+ now 86400)) "expiring"
                    :else                           "authenticated")
          :type (if (= auth-type :auth/oauth) "oauth" "api-key")
          :source source}
         :mode (some-> (:mode provider-auth) name)
         :expires-at expires
         :env-var (when (= source "env") env-var-name)))
      {:status "unauthenticated"})))

(def ^:private provider-settings-exclude-keys
  #{:key :keyRc :requiresAuth? :requiresAuth :models})

(def ^:private model-settings-exclude-keys
  #{})

(defn ^:private config-settings
  "Extracts non-nil, non-excluded config entries as a settings map."
  [config-map exclude-keys]
  (not-empty
   (into {}
         (comp (remove (fn [[k _]] (contains? exclude-keys k)))
               (filter (fn [[_ v]] (some? v)))
               (remove (fn [[_ v]] (and (coll? v) (empty? v)))))
         config-map)))

(defn ^:private provider-models
  "Returns models for a specific provider from the global model map."
  [all-models provider-name model-configs]
  (let [prefix (str provider-name "/")]
    (->> all-models
         (filter (fn [[k _]] (string/starts-with? k prefix)))
         (mapv (fn [[full-id caps]]
                 (let [model-id (subs full-id (count prefix))
                       model-config (get model-configs model-id {})]
                   (shared/assoc-some
                    {:id model-id
                     :capabilities {:reason (:reason? caps false)
                                    :vision (:image-input? caps false)
                                    :tools (:tools caps false)
                                    :web-search (:web-search caps false)}}
                    :cost (when (or (:input-token-cost caps) (:output-token-cost caps))
                            {:input (:input-token-cost caps)
                             :output (:output-token-cost caps)})
                    :settings (config-settings model-config model-settings-exclude-keys))))))))

;; --- Provider status ---

(defn build-provider-status
  "Builds the full status map for a single provider.
   Takes the deref'd db value for a consistent snapshot."
  [provider-name db config]
  (let [provider-auth (get-in db [:auth provider-name] {})
        all-models (:models db)
        provider-config (get-in config [:providers provider-name])
        configured? (some? provider-config)
        requires-auth? (get provider-config :requiresAuth? (some? (:api provider-config)))
        is-local? (and configured? (not requires-auth?))
        model-configs (or (:models provider-config) {})
        models (provider-models all-models provider-name model-configs)
        auth-info (if is-local?
                    (if (seq models)
                      {:status "local"}
                      {:status "not-running"})
                    (resolve-auth-info provider-name provider-auth config))]
    (shared/assoc-some
     {:id provider-name
      :configured configured?
      :auth auth-info
      :models models}
     :label (get provider-labels provider-name)
     :settings (config-settings provider-config provider-settings-exclude-keys)
     :login (when-let [methods (get login-methods provider-name)]
              {:methods methods}))))

(defn notify-provider-updated!
  "Sends a providers/updated notification to the client with the current
   status of a single provider."
  [provider-name db* config messenger]
  (messenger/provider-updated messenger
                              (build-provider-status provider-name @db* config)))

(defn sync-and-notify!
  "Common post-login/logout action: persist auth cache, re-sync models,
   and send both config/updated and providers/updated notifications."
  [provider-name db* messenger metrics]
  (db/update-global-cache! @db* metrics)
  (let [config (config/all @db*)]
    (models/sync-models! db* config
                         (fn [new-models]
                           (messenger/config-updated messenger {:chat {:models (sort (keys new-models))}})
                           (notify-provider-updated! provider-name db* config messenger)))))

;; --- Login dispatch ---

(defmulti start-login!
  "Starts a provider-specific async login flow (OAuth, device flow, etc.).
   Returns an action descriptor for the client.
   Implementations live in provider namespaces."
  (fn [provider-name method _db* _config _messenger _metrics] [provider-name method]))

(defmethod start-login! :default [provider-name method _ _ _ _]
  (throw (ex-info "Unsupported login method"
                  {:error-response {:message (str "Unsupported login method '" method "' for provider '" provider-name "'")}})))

(defmulti complete-oauth-code!
  "Exchanges an OAuth authorization code for tokens.
   Called when client submits a code after browser-based auth.
   Implementations live in provider namespaces."
  (fn [provider-name _data _db* _messenger _metrics] provider-name))

(defmethod complete-oauth-code! :default [provider-name _ _ _ _]
  (throw (ex-info "Provider does not support OAuth code exchange"
                  {:error-response {:message (str "Provider '" provider-name "' does not support code-based login")}})))

;; --- Handlers ---

(defn providers-list
  "Returns all known providers with their current status."
  [db* config]
  (let [db @db*
        auth-providers (set (keys (:auth db)))
        config-providers (set (keys (:providers config)))
        all-provider-names (sort (into config-providers auth-providers))]
    {:providers (mapv #(build-provider-status % db config) all-provider-names)}))

(defn provider-login
  "Initiates a login flow for a provider.
   Two-round-trip: first call without method returns choose-method,
   second call with method returns an action descriptor."
  [provider-name method db* config messenger metrics]
  (let [methods (get login-methods provider-name)]
    (when-not methods
      (throw (ex-info "Unknown provider" {:error-response {:message (str "Unknown provider: " provider-name)}})))
    (cond
      ;; No method given, multiple available -> choose
      (and (nil? method) (> (count methods) 1))
      {:action "choose-method"
       :methods methods}

      ;; No method given, single available -> use it directly
      (and (nil? method) (= (count methods) 1))
      (recur provider-name (:key (first methods)) db* config messenger metrics)

      ;; Manual / API key methods -> return input fields
      (or (= method "manual") (= method "api-key"))
      (let [fields (or (get provider-login-fields provider-name)
                       [{:key "api-key" :label "API Key" :type "secret"}])]
        {:action "input" :fields fields})

      ;; OAuth and device flow are provider-specific (implemented below)
      :else
      (start-login! provider-name method db* config messenger metrics))))

(defn provider-login-input
  "Processes login input submitted by the client."
  [provider-name data db* _config messenger metrics]
  (if (:code data)
    ;; OAuth code exchange (e.g., Anthropic after browser auth)
    (complete-oauth-code! provider-name data db* messenger metrics)
    ;; API key input
    (let [api-key (get data :api-key)
          models-str (get data :models)
          url (get data :url)
          provider-cfg (get provider-configs provider-name)
          models-map (when (not-empty models-str)
                       (into {} (map (fn [m] [(string/trim m) {}])
                                     (string/split models-str #","))))]
      (if (get provider-login-fields provider-name)
        ;; Providers that save to config (google, deepseek, openrouter, z-ai, azure)
        (let [config-update (shared/assoc-some
                             (merge {:key api-key} provider-cfg)
                             :url (or url (:url provider-cfg))
                             :models models-map)]
          (config/update-global-config! {:providers {provider-name config-update}})
          (swap! db* assoc-in [:auth provider-name] {:step :login/done :type :auth/token}))
        ;; Manual key for OAuth providers (anthropic, openai)
        (swap! db* assoc-in [:auth provider-name] {:step :login/done
                                                    :type :auth/token
                                                    :api-key api-key
                                                    :mode :manual}))
      (sync-and-notify! provider-name db* messenger metrics)
      {:action "done"})))

(defn provider-logout
  "Clears auth for a provider and re-syncs models."
  [provider-name db* _config messenger metrics]
  (swap! db* assoc-in [:auth provider-name] {})
  (sync-and-notify! provider-name db* messenger metrics)
  {})
