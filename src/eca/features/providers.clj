(ns eca.features.providers
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.db :as db]
   [eca.features.quota :as f.quota]
   [eca.messenger :as messenger]
   [eca.models :as models]
   [eca.modules :as modules]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

;; --- Provider declarations, from the modules' :providers ---

(def ^:private declaration-keys
  "Keys of a provider declaration that aren't provider config."
  #{:label :login :models-fn})

(defn declarations
  "Provider id → declaration (`:label`, `:login` and the config keys a login
   saves), from the active modules' `:providers`. The first module wins."
  [config]
  (reduce (fn [acc [_entry providers]]
            (reduce-kv (fn [acc id declaration]
                         (if (contains? acc id) acc (assoc acc id declaration)))
                       acc
                       providers))
          {}
          (modules/contributions config :providers)))

(defn ^:private base-declaration
  "The declaration of `provider-name`, or of the provider it inherits from."
  [provider-name config]
  (let [declarations (declarations config)]
    (or (get declarations provider-name)
        (get declarations (config/provider-base provider-name config)))))

(defn login-provider-ids
  "The providers a module declares a login for."
  [config]
  (into #{} (keep (fn [[id declaration]] (when (:login declaration) id))) (declarations config)))

(def ^:private ask-field-defaults
  {:api-key {:key "api-key" :label "API Key" :type "secret"}
   :url {:key "url" :label "API URL" :type "text"}
   :models {:key "models" :label "Model names (comma-separated)" :type "text"}})

(defn ask-fields
  "The fields of a `:login {:ask [..]}` declaration, as `[field label]` pairs.
   A field is `:api-key`, `:url` or `:models`, or `[field label]`."
  [ask]
  (mapv (fn [field]
          (if (vector? field)
            [(first field) (second field)]
            [field (get-in ask-field-defaults [field :label])]))
        ask))

(defn ^:private ask-method-label [ask]
  (let [names (map {:api-key "API key" :url "URL" :models "models"} (map first (ask-fields ask)))]
    (str "Enter " (if (next names)
                    (str (string/join ", " (butlast names)) " & " (last names))
                    (first names)))))

(defn ^:private login-methods [provider-name config]
  (let [{:keys [ask methods]} (:login (base-declaration provider-name config))]
    (cond
      methods methods
      ask [{:key "api-key" :label (ask-method-label ask)}])))

(defn ^:private login-fields [provider-name config]
  (when-let [ask (get-in (base-declaration provider-name config) [:login :ask])]
    (mapv (fn [[field label]] (assoc (ask-field-defaults field) :label label))
          (ask-fields ask))))

(defn ask-declaration
  "The declaration of `provider-name`, or of the provider it inherits from,
   when it logs in by asking for fields: `:login {:ask [..]}`."
  [provider-name config]
  (let [declaration (base-declaration provider-name config)]
    (when (get-in declaration [:login :ask])
      declaration)))

(defn parse-models
  "Comma-separated model names → a provider `:models` map."
  [s]
  (into {}
        (comp (map string/trim) (remove string/blank?) (map (fn [model] [model {}])))
        (string/split (or s "") #",")))

(defn login-config
  "The provider config an `:ask` login saves: the declaration's config keys
   plus the answers."
  [declaration {:keys [api-key url models]}]
  (shared/assoc-some (apply dissoc declaration declaration-keys)
                     :key api-key
                     :url (some-> url string/trim not-empty)
                     :models (not-empty (parse-models models))))

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

(defn key-auth-override-warning
  "When a configured/env API key would take precedence over login auth for
   `provider-name`, returns a warning string to show the user during `/login`,
   else nil. Mirrors the precedence in `llm-util/provider-api-key`, where a
   configured `:key` (including the `${env:..._API_KEY}` defaults) and a bare
   `<PROVIDER>_API_KEY` env var both win over OAuth/login auth."
  [provider-name config]
  (let [config-key (not-empty (get-in config [:providers provider-name :key]))
        env-var-name (->env-var-name provider-name)
        env-key (config/get-env env-var-name)]
    (when (or config-key env-key)
      (format (str "⚠️ An API key is already configured for `%s` (e.g. the `%s` environment variable "
                   "or a `key` in your ECA config). Configured API keys take precedence over login auth, "
                   "so ECA may keep using the API key — which can incur paid API usage — even after you log in. "
                   "Unset `%s` (or remove the provider `key`) to use your login instead.")
              provider-name env-var-name env-var-name))))

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
                                    :web-search (:web-search caps false)
                                    :image-generation (:image-generation? caps false)}}
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
     :label (:label (get (declarations config) provider-name))
     :settings (config-settings provider-config provider-settings-exclude-keys)
     :login (when-let [methods (login-methods provider-name config)]
              {:methods methods})
     ;; Only subscription logins (OAuth) have a quota, see eca.features.quota.
     :quota (when (= "oauth" (:type auth-info))
              (get-in db [:provider-quotas provider-name])))))

(defn notify-provider-updated!
  "Sends a providers/updated notification to the client with the current
   status of a single provider."
  [provider-name db* config messenger]
  (messenger/provider-updated messenger
                              (build-provider-status provider-name @db* config)))

(defn refresh-quota!
  "Best-effort fetch of the subscription quota of `provider-name` from its
   usage endpoint, notifying the client when it changed."
  [provider-name db* config messenger & {:keys [force?]}]
  (when (f.quota/refresh! provider-name {:db* db* :config config :force? force?})
    (notify-provider-updated! provider-name db* config messenger)))

(defn reset-quota!
  "Drops the quota of `provider-name` after its login changed, fetching the
   new one in background."
  [provider-name db* config messenger]
  (swap! db* update :provider-quotas dissoc provider-name)
  (swap! db* update :provider-quota-fetched-at dissoc provider-name)
  (shared/future* config
    (refresh-quota! provider-name db* config messenger)))

(defn sync-and-notify!
  "Common post-login/logout action: persist auth cache, re-sync models,
   and send both config/updated and providers/updated notifications."
  [provider-name db* messenger metrics]
  (db/update-global-auth-cache! @db* provider-name metrics)
  (let [config (config/all @db*)]
    (reset-quota! provider-name db* config messenger)
    (models/sync-models! db* config
                         (fn [new-models]
                           (messenger/config-updated messenger {:chat {:models (sort (keys new-models))}})
                           (notify-provider-updated! provider-name db* config messenger)))))

;; --- Login dispatch ---

(defmulti start-login!
  "Starts a provider-specific async login flow (OAuth, device flow, etc.).
   Returns an action descriptor for the client.
   Implementations live in provider namespaces and dispatch on the provider's
   base (see `config/provider-base`), storing auth under `provider-name`."
  (fn [provider-name method _db* config _messenger _metrics]
    [(config/provider-base provider-name config) method]))

(defmethod start-login! :default [provider-name method _ _ _ _]
  (throw (ex-info "Unsupported login method"
                  {:error-response {:message (str "Unsupported login method '" method "' for provider '" provider-name "'")}})))

(defmulti complete-oauth-code!
  "Exchanges an OAuth authorization code for tokens.
   Called when client submits a code after browser-based auth.
   Implementations live in provider namespaces and dispatch on the provider's
   base (see `config/provider-base`), storing auth under `provider-name`."
  (fn [provider-name _data _db* config _messenger _metrics]
    (config/provider-base provider-name config)))

(defmethod complete-oauth-code! :default [provider-name _ _ _ _ _]
  (throw (ex-info "Provider does not support OAuth code exchange"
                  {:error-response {:message (str "Provider '" provider-name "' does not support code-based login")}})))

;; --- Handlers ---

(defn providers-list
  "Returns all known providers with their current status."
  [db* config]
  (let [db @db*
        loginable (login-provider-ids config)
        ;; providers with login state: logged in, or of a provider that logs in
        auth-providers (filter #(or (seq (get-in db [:auth %]))
                                    (contains? loginable (config/provider-base % config)))
                               (keys (:auth db)))
        config-providers (set (keys (:providers config)))
        all-provider-names (sort (-> config-providers (into loginable) (into auth-providers)))]
    {:providers (mapv #(build-provider-status % db config) all-provider-names)}))

(defn provider-login
  "Initiates a login flow for a provider.
   Two-round-trip: first call without method returns choose-method,
   second call with method returns an action descriptor."
  [provider-name method db* config messenger metrics]
  (let [methods (login-methods provider-name config)]
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
      (let [fields (or (login-fields provider-name config)
                       [{:key "api-key" :label "API Key" :type "secret"}])]
        {:action "input" :fields fields})

      ;; OAuth and device flow are provider-specific (implemented below)
      :else
      (start-login! provider-name method db* config messenger metrics))))

(defn provider-login-input
  "Processes login input submitted by the client."
  [provider-name data db* config messenger metrics]
  (if (:code data)
    ;; OAuth code exchange (e.g., Anthropic after browser auth)
    (complete-oauth-code! provider-name data db* config messenger metrics)
    ;; API key input
    (let [api-key (get data :api-key)]
      (if-let [declaration (ask-declaration provider-name config)]
        ;; Providers that log in by asking for fields save them to config
        (do
          (config/update-global-config!
           {:providers {provider-name (login-config declaration {:api-key api-key
                                                                 :url (get data :url)
                                                                 :models (get data :models)})}})
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
