(ns eca.llm-api.auth
  "Keeps the providers' login tokens fresh: renews an OAuth token about to
   expire with its provider's `:login/renew-token` step (its declaration's
   `:login :steps`), so requests work whether or not `/login` is available."
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.db.global-cache :as db.global-cache]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[LOGIN]")

(defn ->env-var-name
  "The env var with `provider-name`'s API key: `<PROVIDER>_API_KEY`."
  [provider-name]
  (str (-> provider-name
           (string/replace "-" "_")
           string/upper-case)
       "_API_KEY"))

(defn api-key-overrides-login?
  "Whether a configured or env API key takes precedence over login auth for
   `provider-name`. Mirrors the precedence in `llm-util/provider-api-key`, where
   a configured `:key` (including the `${env:..._API_KEY}` defaults) and a bare
   `<PROVIDER>_API_KEY` env var both win over OAuth/login auth."
  [provider-name config]
  (boolean (or (not-empty (get-in config [:providers provider-name :key]))
               (config/get-env (->env-var-name provider-name)))))

(defn ^:private renew-token!
  "Runs the `:login/renew-token` step of the provider `provider` follows (see
   `config/provider-base`)."
  [{:keys [provider config] :as ctx}]
  (if-let [renew (get-in (llm-util/provider-declaration config (config/provider-base provider config))
                         [:login :steps :login/renew-token])]
    (renew ctx)
    (throw (ex-info "Error: Unknown login step" {:provider provider}))))

(defn ^:private renew-auth!
  [provider
   {:keys [db* messenger config metrics]}
   {:keys [on-error]}]
  (try
    ;; Serialize across ECA processes that share `~/.cache/eca/db.transit.json`.
    ;; OAuth refresh tokens are single-use, so two concurrent processes
    ;; both POSTing with the same token would have one win and the other
    ;; receive `invalid_grant`. Holding the cache lock + re-reading disk
    ;; lets the loser adopt the winner's rotated tokens instead. #462
    (db.global-cache/with-global-cache-lock
      (db.global-cache/sync-auth-from-cache! db* provider metrics)
      (let [expires-at (get-in @db* [:auth provider :expires-at])
            now-plus-60 (+ 60 (quot (System/currentTimeMillis) 1000))]
        (if (and expires-at (> (long expires-at) now-plus-60))
          (logger/info logger-tag
                       (format "Skipping %s renew; peer process already refreshed." provider))
          (do
            (renew-token!
             {:provider provider
              :metrics metrics
              :messenger messenger
              :config config
              :step :login/renew-token
              :db* db*})
            (db.global-cache/update-global-auth-cache! @db* provider metrics)))))
    (catch Exception e
      (on-error (.getMessage e)))))

(defn maybe-renew-auth-token! [{:keys [provider on-renewing on-error]} ctx]
  (when-not (api-key-overrides-login? provider (:config ctx))
    (when-let [expires-at (get-in @(:db* ctx) [:auth provider :expires-at])]
      ;; Renew 60s before expiration to avoid race between check and request
      (when (<= (long expires-at) (+ 60 (quot (System/currentTimeMillis) 1000)))
        (when on-renewing
          (on-renewing))
        (renew-auth! provider ctx
                     {:on-error on-error})))))

(defn renew-expiring-auth-tokens!
  "Best-effort proactive renewal of any provider auth tokens that are at/near
   expiry. Used by non-interactive paths like model-catalog sync, which would
   otherwise call provider endpoints with a stale short-lived token (e.g.
   Copilot's session token) and get a 401. `ctx` needs {:db* :messenger :config
   :metrics}."
  [{:keys [db*] :as ctx}]
  (doseq [provider (keys (:auth @db*))]
    (maybe-renew-auth-token!
     {:provider provider
      :on-error (fn [msg]
                  (logger/warn logger-tag
                               (format "Could not renew '%s' auth token before sync: %s" provider msg)))}
     ctx)))
