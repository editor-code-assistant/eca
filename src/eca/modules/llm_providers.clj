(ns eca.modules.llm-providers
  "The `:eca/llm-providers` module: logging into providers, from `/login` in a
   chat or from the client's providers panel (`providers/*`), and their
   subscription quotas (`/quota`, `providers/updated`). Requests don't need
   it: core renews login tokens itself (`eca.llm-api.auth`)."
  (:require
   [eca.llm-api.auth :as llm-api.auth]
   [eca.logger :as logger]
   [eca.messenger :as messenger]
   [eca.metrics :as metrics]
   [eca.modules.llm-providers.login :as m.llm-providers.login]
   [eca.modules.llm-providers.providers :as m.llm-providers.providers]
   [eca.modules.llm-providers.quota :as m.llm-providers.quota]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

;; --- Protocol methods

(defn ^:private answering-expected-errors
  "Calls `f`, answering an error it expects (an ExceptionInfo with an
   `:error-response`) as `{:error error-response}`."
  [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (if-let [error-response (:error-response (ex-data e))]
        {:error error-response}
        (throw e)))))

(defn ^:private providers-list [{:keys [db* config metrics]} _params]
  (metrics/task metrics :eca/providers-list
    (m.llm-providers.providers/providers-list db* config)))

(defn ^:private providers-login [{:keys [db* config messenger metrics]} params]
  (metrics/task metrics :eca/providers-login
    (answering-expected-errors
     #(m.llm-providers.providers/provider-login (:provider params) (:method params) db* config messenger metrics))))

(defn ^:private providers-login-input [{:keys [db* config messenger metrics]} params]
  (metrics/task metrics :eca/providers-login-input
    (answering-expected-errors
     #(m.llm-providers.providers/provider-login-input (:provider params) (:data params) db* config messenger metrics))))

(defn ^:private providers-logout [{:keys [db* config messenger metrics]} params]
  (metrics/task metrics :eca/providers-logout
    (m.llm-providers.providers/provider-logout (:provider params) db* config messenger metrics)))

;; --- A chat logging in

(defn ^:private login-chat-prompt
  "A prompt of a chat in the `:login` status: the login's next step."
  [{:keys [db* messenger config metrics]} params]
  (m.llm-providers.login/handle-step params db* messenger config metrics))

;; --- Commands

(defn ^:private login-command
  [{:keys [chat-id db* config messenger metrics]} {args :argv}]
  (messenger/chat-content-received
   messenger
   {:chat-id chat-id
    :role "system"
    :content {:type :text
              :text (shared/multi-str
                     "Login started, type 'cancel' anytime to exit login."
                     ""
                     "Note: Login should be used to authenticate via oauth (subs) on some providers or auto configure ECA config with a provider API key.")}})
  (m.llm-providers.login/handle-step {:message (or (first args) "")
                                      :chat-id chat-id}
                                     db*
                                     messenger
                                     config
                                     metrics)
  {:type :new-chat-status
   :status :login})

(defn ^:private quota-command
  [{:keys [chat-id db* config messenger] :as chat-ctx} _args]
  (let [db @db*
        providers (m.llm-providers.quota/subscription-providers db config)]
    (doseq [provider providers]
      (llm-api.auth/maybe-renew-auth-token!
       {:provider provider
        :on-error (fn [msg]
                    (logger/warn "[commands]" (format "Could not renew '%s' auth token before fetching quota: %s" provider msg)))}
       chat-ctx)
      (m.llm-providers.providers/refresh-quota! provider db* config messenger :force? true))
    {:type :chat-messages
     :chats {chat-id {:messages [{:role "system"
                                  :content [{:type :text
                                             :text (m.llm-providers.quota/quota-text @db* providers)}]}]}}}))

;; --- Events

(defn ^:private response-headers
  "`:llm/response-headers`, for a subscription login: its quota, from the
   response's headers."
  [{:keys [db* config messenger]} {:keys [provider headers]}]
  ;; Display-only, so never let it break the request.
  (try
    (when (m.llm-providers.quota/update-from-headers! db* provider headers config)
      (m.llm-providers.providers/notify-provider-updated! provider db* config messenger))
    (catch Exception e
      (logger/warn "[CHAT]" "Failed to update provider quota" e)))
  nil)

(defn ^:private models-synced
  "`:models/synced`: the subscription quotas show before the first request;
   the sync renewed the tokens first."
  [{:keys [db* config messenger]} _event]
  (doseq [provider (m.llm-providers.quota/subscription-providers @db* config)]
    (m.llm-providers.providers/refresh-quota! provider db* config messenger))
  nil)

(def module
  {:id :eca/llm-providers
   :description "Provider logins (/login, the providers panel) and subscription quotas."
   :rpc {"providers/list" {:kind :request :handler #'providers-list}
         "providers/login" {:kind :request :handler #'providers-login}
         "providers/loginInput" {:kind :request :handler #'providers-login-input}
         "providers/logout" {:kind :request :handler #'providers-logout}}
   :chat-statuses {:login #'login-chat-prompt}
   :commands {"login" {:description "Log into a provider (Ex: /login github-copilot)"
                       :arguments [{:name "provider-id"}]
                       :handler #'login-command}
              "quota" {:description "Subscription quota usage and reset times (Claude Pro/Max, ChatGPT, GitHub Copilot)."
                       :arguments []
                       :handler #'quota-command}}
   :event-handlers {:llm/response-headers {:handler #'response-headers}
                    :models/synced {:handler #'models-synced}}})
