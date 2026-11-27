(ns eca.modules.providers.openai
  "The `:eca/openai` module. GPT models from OpenAI: a ChatGPT subscription
   or a pasted key. And the `:eca/api-openai-responses` module. The OpenAI
   Responses API."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [eca.client-http :as client]
   [eca.config :as config]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.message-sanitize :as message-sanitize]
   [eca.models :as models]
   [eca.modules.providers.variants :as m.variants]
   [eca.oauth :as oauth]
   [eca.shared :refer [assoc-some join-api-url multi-str]]
   [hato.client :as http]
   [ring.util.codec :as ring.util]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[OPENAI]")

(def ^:private responses-path "/v1/responses")
(def ^:private codex-models-timeout-ms 10000)

;; OpenAI OAuth requests go through the ChatGPT Codex backend, whose context
;; windows differ from the direct OpenAI API catalog (models.dev). Used as a
;; fallback when the Codex /models endpoint is unreachable so e.g. gpt-5.5
;; reports 272k instead of the direct API's 1.05M window.
(def ^:private codex-context-fallback
  {"gpt-5.1-codex-max" 272000
   "gpt-5.1-codex-mini" 272000
   "gpt-5.3-codex-spark" 128000
   "gpt-5.3-codex" 272000
   "gpt-5.2-codex" 272000
   "gpt-5.4-mini" 272000
   "gpt-5.5" 272000
   "gpt-5.4" 272000
   "gpt-5.2" 272000
   "gpt-5.6-sol" 272000
   "gpt-5.6-terra" 272000
   "gpt-5.6-luna" 272000
   "gpt-6-astra" 272000
   "gpt-6.2-astra" 272000
   "gpt-6.2-sol" 500000
   "gpt-5" 272000})

;; ChatGPT subscription requests go through the Codex backend, which requires
;; the Codex CLI request identity and supports a Responses Lite payload shape
;; for some models. All Codex specifics live in the `codex-` fns below.

;; Keep >= the newest model's `minimal_client_version`; the live gate is stricter than the catalog, so pin a recent released Codex version.
(def ^:private codex-compatibility-version "0.170.0")

(def ^:private codex-responses-url "https://chatgpt.com/backend-api/codex/responses")

(def ^:private codex-models-url
  (str "https://chatgpt.com/backend-api/codex/models?client_version="
       codex-compatibility-version))

(defn ^:private codex-request?
  "Codex requests are exclusive to the built-in openai provider (or providers
   inheriting it, `provider` being the provider base) authenticated via OAuth
   (ChatGPT subscription). Custom Responses API providers never hit the Codex
   backend, whatever their auth."
  [provider auth-type]
  (and (= "openai" provider)
       (= :auth/oauth auth-type)))

(defn ^:private new-codex-turn-context
  "Creates state shared by every request and retry in one user turn."
  []
  (let [turn-id (str (random-uuid))]
    {:session-id turn-id
     :thread-id turn-id
     :turn-state* (atom nil)}))

(defn ^:private codex-request-headers
  [{:keys [account-id turn-context responses-lite?]}]
  (let [{:keys [session-id thread-id turn-state*]} turn-context]
    (assoc-some
     ;; The backend reports a mismatched Originator/User-Agent pair as
     ;; server_is_overloaded, so keep Codex's default identity paired.
     {"Originator" "codex_cli_rs"
      "User-Agent" (str "codex_cli_rs/" codex-compatibility-version)}
     "ChatGPT-Account-ID" account-id
     "Session-ID" session-id
     "Thread-ID" thread-id
     "x-client-request-id" thread-id
     "x-codex-turn-state" (some-> turn-state* deref)
     "x-openai-internal-codex-responses-lite" (when responses-lite? "true"))))

(defn ^:private capture-codex-turn-state!
  "Stores the first routing state returned for a turn, matching Codex's
   first-write-wins behavior."
  [turn-context turn-state]
  (when-let [turn-state* (:turn-state* turn-context)]
    (when-not (string/blank? turn-state)
      (compare-and-set! turn-state* nil turn-state))))

(def ^:private codex-reasoning-efforts
  #{"none" "low" "medium" "high" "xhigh" "max"})

(defn ^:private codex-normalize-reasoning-efforts [levels]
  (->> levels
       (keep #(if (map? %) (:effort %) %))
       (map #(if (= "ultra" %) "max" %))
       (filter codex-reasoning-efforts)
       distinct
       vec
       not-empty))

(defn ^:private codex-reasoning-variants [efforts]
  (when efforts
    (into {}
          (map (fn [effort]
                 [effort {:reasoning {:effort effort :summary "auto"}}]))
          efforts)))

(def ^:private codex-responses-lite-fallbacks
  {"gpt-5.6-sol" {:default-reasoning-effort "low"
                  :supported-reasoning-efforts ["low" "medium" "high" "xhigh" "max"]}
   "gpt-5.6-terra" {:default-reasoning-effort "medium"
                    :supported-reasoning-efforts ["low" "medium" "high" "xhigh" "max"]}
   "gpt-5.6-luna" {:default-reasoning-effort "medium"
                   :supported-reasoning-efforts ["low" "medium" "high" "xhigh" "max"]}
   "gpt-6-astra" {:default-reasoning-effort "low"
                  :supported-reasoning-efforts ["low" "medium" "high" "xhigh" "max"]}
   "gpt-6.2-astra" {:default-reasoning-effort "low"
                    :supported-reasoning-efforts ["low" "medium" "high" "xhigh" "max"]}
   "gpt-6.2-sol" {:default-reasoning-effort "low"
                  :supported-reasoning-efforts ["low" "medium" "high" "xhigh" "max"]}})

(defn ^:private codex-model-fallback-discovery
  "Static discovery data for known Responses Lite models, used when the live
   Codex /models endpoint is unreachable or hasn't run."
  [model]
  (when-let [{:keys [default-reasoning-effort supported-reasoning-efforts]}
             (get codex-responses-lite-fallbacks (string/lower-case (str model)))]
    {:discovered-provider-data {:responses-lite? true
                                :default-reasoning-effort default-reasoning-effort}
     :discovered-variants (codex-reasoning-variants supported-reasoning-efforts)}))

(defn ^:private codex-live-model-discovery
  "Maps a Codex /models entry into provider-specific discovery metadata."
  [{:keys [use_responses_lite default_reasoning_level
           supported_reasoning_levels supports_parallel_tool_calls] :as model}]
  (let [efforts (codex-normalize-reasoning-efforts supported_reasoning_levels)
        provider-data (assoc-some
                       (cond-> {}
                         (contains? model :use_responses_lite)
                         (assoc :responses-lite? (true? use_responses_lite))

                         ;; Lite requires parallel_tool_calls=false. Preserve the
                         ;; explicit live pair before merging fallback metadata.
                         (and (true? use_responses_lite)
                              (true? supports_parallel_tool_calls))
                         (assoc :parallel-tool-calls-without-lite? true))
                       :default-reasoning-effort default_reasoning_level
                       :parallel-tool-calls? supports_parallel_tool_calls)]
    (assoc-some {}
                :discovered-provider-data (not-empty provider-data)
                :discovered-variants (codex-reasoning-variants efforts))))

(defn ^:private function-tool? [tool]
  (= "function" (:type tool)))

(defn ^:private codex-responses-lite-body
  "Projects a regular Responses request into the Codex Responses Lite shape."
  [body]
  (let [instructions (:instructions body)
        tools (filterv function-tool? (:tools body))
        input (cond-> [{:type "additional_tools"
                        :role "developer"
                        :tools tools}]
                (not (string/blank? instructions))
                (conj {:type "message"
                       :role "developer"
                       :content [{:type "input_text"
                                  :text instructions}]})

                true
                (into (:input body)))]
    (-> body
        (dissoc :instructions :tools)
        (assoc :input input
               :parallel_tool_calls false
               :reasoning (assoc (or (:reasoning body) {})
                                 :context "all_turns")))))

(defn ^:private codex-body-projection
  "Selects the effective Codex wire shape once for an entire turn."
  [codex? provider-data body]
  (cond
    (not codex?) :ordinary
    (and (true? (:parallel-tool-calls-without-lite? provider-data))
         (true? (:parallel_tool_calls body))
         (some function-tool? (:tools body))) :codex-parallel
    (true? (:responses-lite? provider-data)) :codex-lite
    :else :ordinary))

(defn ^:private pos-num [n]
  (when (and (number? n) (pos? n)) n))

(defn ^:private deep-merge-config [a b]
  (if (and (map? a) (map? b))
    (merge-with deep-merge-config a b)
    b))

(defn ^:private merge-codex-model-config [fallback live]
  (cond-> (deep-merge-config fallback live)
    (contains? live :discovered-variants)
    (assoc :discovered-variants (:discovered-variants live))))

(defn ^:private codex-context-fallback-for [model]
  (let [model-name (string/lower-case (str model))]
    (some (fn [[slug context-limit]]
            (when (string/includes? model-name slug)
              context-limit))
          (sort-by (comp - count key) codex-context-fallback))))

(defn ^:private codex-model-config
  "Builds a model-config in user-override shape ({:limit {:context ..}}) so the
   generic catalog code applies these limits through its existing override path."
  [context-limit output-limit]
  (let [limit (assoc-some {}
                          :context (pos-num context-limit)
                          :output (pos-num output-limit))]
    (assoc-some {} :limit (not-empty limit))))

(defn ^:private codex-live-model-entry [model]
  (let [slug (:slug model)
        context-limit (:context_window model)
        output-limit (or (:max_output_tokens model) (:max_completion_tokens model))]
    (when (and (string? slug) (not (string/blank? slug)))
      [slug (deep-merge-config
             (codex-model-config context-limit output-limit)
             (codex-live-model-discovery model))])))

(defn ^:private codex-fallback-models [static-models]
  (merge
   (into {}
         (map (fn [[model context-limit]]
                [model (deep-merge-config
                        (codex-model-config context-limit nil)
                        (or (codex-model-fallback-discovery model) {}))]))
         codex-context-fallback)
   (into {}
         (keep (fn [[model model-config]]
                 (when-let [context-limit (or (codex-context-fallback-for model)
                                              (codex-context-fallback-for (:modelName model-config)))]
                   [model (codex-model-config context-limit nil)])))
         static-models)))

(defn ^:private fetch-oauth-models
  "Resolves OpenAI OAuth (ChatGPT/Codex) model limits from the Codex /models
   endpoint, falling back to known Codex caps on any failure. Returns a map of
   model-id -> model-config in user-override shape."
  ([api-key static-models]
   (fetch-oauth-models api-key nil static-models))
  ([api-key account-id static-models]
   (let [fallback-models (codex-fallback-models static-models)]
     (try
       (if-not api-key
         fallback-models
         (let [{:keys [status body]} (http/get codex-models-url
                                               {:headers (merge
                                                          {"Authorization" (str "Bearer " api-key)}
                                                          (codex-request-headers
                                                           {:account-id account-id}))
                                                :throw-exceptions? false
                                                :as :json
                                                :http-client (client/merge-with-global-http-client {})
                                                :timeout codex-models-timeout-ms})]
           (if (not= 200 status)
             (do
               (logger/warn logger-tag (format "Codex /models endpoint returned status %s" status))
               fallback-models)
             (let [live-models (not-empty (into {}
                                                (keep codex-live-model-entry)
                                                (:models body)))]
               (or (not-empty (merge-with merge-codex-model-config fallback-models live-models))
                   fallback-models)))))
       (catch Exception e
         (logger/warn logger-tag (format "Failed to fetch Codex /models endpoint: %s" e))
         fallback-models)))))

(defn ^:private models-override
  "`:models-override-fn`: with a ChatGPT login (OAuth), the Codex models."
  [{:keys [auth-type api-key account-id static-models]}]
  (when (= :auth/oauth auth-type)
    (fetch-oauth-models api-key account-id static-models)))

(defn ^:private jwt-payload->account-id
  "Extract account ID from JWT payload, checking multiple locations like opencode does."
  [payload]
  (or (get payload "chatgpt_account_id")
      (get-in payload ["https://api.openai.com/auth" "chatgpt_account_id"])
      (get-in payload ["organizations" 0 "id"])))

(defn ^:private jwt-token->account-id
  "Extract account ID from a JWT token string.
  Returns nil when the token is not a valid JWT."
  [token]
  (try
    (when (string? token)
      (let [[_ base64] (string/split token #"\.")
            payload (some-> base64
                            oauth/<-base64
                            json/parse-string)]
        (jwt-payload->account-id payload)))
    (catch Exception _)))

(def ^:private codex-usage-url "https://chatgpt.com/backend-api/wham/usage")

(defn ^:private empty-codex-slot?
  "Whether a ChatGPT window slot carries no data: unused and without a
   duration, like the secondary slot of plans without a 5h limit, which report
   their weekly window in the primary slot. The Codex CLI skips those too."
  [used-percent minutes]
  (and (zero? used-percent)
       (not (pos? (or minutes 0)))))

(defn ^:private quota-windows
  "ChatGPT subscription windows from `x-codex-{primary,secondary}-*` headers.
   Other limit families use their own `x-<family>-` prefix. The declaration's
   `:quota :headers`."
  [headers]
  (keep (fn [[k v]]
          (when-let [[_ family slot] (re-matches #"x-([a-z0-9-]+)-(primary|secondary)-used-percent" k)]
            (when-let [used-percent (llm-util/parse-number v)]
              (let [prefix (str "x-" family "-" slot "-")
                    minutes (some-> (get headers (str prefix "window-minutes")) llm-util/parse-number long)
                    resets-at (or (some-> (get headers (str prefix "reset-at")) llm-util/parse-number long)
                                  (some->> (get headers (str prefix "reset-after-seconds"))
                                           llm-util/parse-number
                                           long
                                           (+ (llm-util/now-seconds))))
                    default-family? (= "codex" family)
                    label (or (llm-util/minutes->label minutes) slot)]
                (when-not (empty-codex-slot? used-percent minutes)
                  (assoc-some
                   {:id (if default-family? slot (str family "-" slot))
                    :label (if default-family? label (str family " " label))
                    :used-percent (llm-util/round1 used-percent)}
                   :resets-at resets-at
                   :window-minutes minutes))))))
        headers))

(defn ^:private usage->windows
  "Quota windows from ChatGPT's `backend-api/wham/usage` response, keeping
   the `primary`/`secondary` window ids used by the Codex response headers."
  [body]
  (keep (fn [[slot k]]
          (when-let [{:keys [used_percent limit_window_seconds reset_at reset_after_seconds]}
                     (get-in body [:rate_limit k])]
            (when-let [used-percent (llm-util/parse-number used_percent)]
              (let [minutes (some-> limit_window_seconds llm-util/parse-number long (quot 60))]
                (when-not (empty-codex-slot? used-percent minutes)
                  (assoc-some
                   {:id slot
                    :label (or (llm-util/minutes->label minutes) slot)
                    :used-percent (llm-util/round1 used-percent)}
                   :resets-at (or (llm-util/->epoch-seconds reset_at)
                                  (some->> reset_after_seconds llm-util/parse-number long (+ (llm-util/now-seconds))))
                   :window-minutes minutes))))))
        [["primary" :primary_window] ["secondary" :secondary_window]]))

(defn ^:private fetch-quota
  "`:quota :fetch`: the ChatGPT subscription's usage windows."
  [{:keys [provider-auth]}]
  (let [api-key (:api-key provider-auth)
        {:keys [status body]} (http/get codex-usage-url
                                        {:headers (merge
                                                   {"Authorization" (str "Bearer " api-key)}
                                                   (codex-request-headers
                                                    {:account-id (or (:account-id provider-auth)
                                                                     (jwt-token->account-id api-key))}))
                                         :throw-exceptions? false
                                         :as :json
                                         :http-client (client/merge-with-global-http-client {})
                                         :timeout codex-models-timeout-ms})]
    (if (= 200 status)
      (usage->windows body)
      (throw (ex-info (format "ChatGPT usage endpoint returned status %s" status) {:status status})))))

(defn ^:private response-body->result [body]
  {:output-text (reduce
                 #(str %1 (:text %2))
                 ""
                 (:content (last (:output body))))})

(defn ^:private non-blank-str [value]
  (let [value (if (coll? value) (first value) value)
        value (some-> value str string/trim)]
    (when-not (string/blank? value)
      value)))

(defn ^:private response-header [headers header-name]
  (some (fn [[k value]]
          (let [k (if (keyword? k) (name k) (str k))]
            (when (= header-name (string/lower-case k))
              (non-blank-str value))))
        headers))

(def ^:private transient-message-pattern
  #"(?i)an error occurred while processing your request\.\s+you can retry your request\b")

(defn ^:private error-api-type
  "What a Responses API error is, from its structured `code` or `type`
   (authoritative, also when unknown), else from its generic transient
   message; nil to let ECA classify it."
  [{:keys [code type message]}]
  (cond
    (some #{"server_error" "server_is_overloaded"} [code type]) :overloaded
    (some #{"usage_limit_reached" "insufficient_quota" "billing_hard_limit_reached"} [code type]) :quota-exhausted
    (some #{"rate_limit_exceeded"} [code type]) :rate-limited
    (or (some? code) (some? type)) :unknown
    (and (string? message) (re-find transient-message-pattern message)) :overloaded
    :else nil))

(defn ^:private with-error-api-type
  "`error-data` with the API's classification (`:error/api-type`), which
   ECA takes after the user's `retryRules`."
  [error-data]
  (assoc-some error-data :error/api-type (error-api-type error-data)))

(defn ^:private response-failed->error-data [data response-headers]
  (let [response (:response data)
        error (:error response)
        request-id (some non-blank-str
                         [(:request-id error)
                          (:request_id error)
                          (:request-id response)
                          (:request_id response)
                          (response-header response-headers "x-request-id")])]
    (with-error-api-type
      (assoc-some (or error {})
                  :message (or (:message error) "OpenAI response failed")
                  :error/source :openai-responses
                  :response-id (:id response)
                  :request-id request-id
                  :headers response-headers))))

(defn ^:private response-incomplete->error-data [data response-headers]
  (let [response (:response data)
        reason (get-in response [:incomplete_details :reason])
        request-id (some non-blank-str
                         [(:request-id response)
                          (:request_id response)
                          (response-header response-headers "x-request-id")])]
    (assoc-some {:message (if reason
                           (format "OpenAI response incomplete: %s" reason)
                           "OpenAI response incomplete")
                 :error/type :premature-stop
                 :error/source :openai-responses
                 :headers response-headers}
                :response-id (:id response)
                :request-id request-id
                :incomplete-reason reason)))

(defn ^:private stream-error->error-data [data response-headers]
  (let [error (or (:error data) data)
        error (if (= "error" (:type error))
                (dissoc error :type)
                error)
        request-id (some non-blank-str
                         [(:request-id error)
                          (:request_id error)
                          (response-header response-headers "x-request-id")])]
    (with-error-api-type
      (assoc-some error
                  :message (or (:message error) "OpenAI response stream failed")
                  :error/source :openai-responses
                  :request-id request-id
                  :headers response-headers))))

(defn ^:private base-responses-request!
  [{:keys [rid body api-url codex? url-relative-path api-key account-id
           turn-context responses-lite? on-error on-stream on-response-headers http-client extra-headers
           cancelled? stream-idle-timeout-seconds]}]
  (let [stream? (and on-stream (not= false (:stream body)))
        url (if codex?
              codex-responses-url
              (join-api-url api-url (or url-relative-path responses-path)))
        ;; Use persisted account-id first, fall back to extracting from JWT
        resolved-account-id (or account-id (jwt-token->account-id api-key))
        extra-headers (if (fn? extra-headers)
                        (extra-headers {:body body})
                        extra-headers)
        headers (client/merge-llm-headers
                 (merge
                  {"Authorization" (str "Bearer " api-key)
                   "Content-Type" "application/json"}
                  (when codex?
                    (codex-request-headers
                     {:account-id resolved-account-id
                      :turn-context turn-context
                      :responses-lite? responses-lite?}))
                  extra-headers))
        on-error (or on-error
                     (fn [error-data]
                       (llm-util/log-response logger-tag rid "response-error" body)
                       {:error error-data}))]
    (llm-util/log-request logger-tag rid url body headers)
    (try
      (let [{:keys [status body] resp-headers :headers} (http/post
                                                         url
                                                         {:headers headers
                                                          :body (json/generate-string body)
                                                          :throw-exceptions? false
                                                          :http-client (client/merge-with-global-http-client http-client)
                                                          :as (if stream? :stream :json)})]
        (when on-response-headers
          (on-response-headers resp-headers))
        (when (and codex? (= 200 status))
          (capture-codex-turn-state!
           turn-context
           (response-header resp-headers "x-codex-turn-state")))
        (if (not= 200 status)
          (let [body-str (if stream? (slurp body) body)]
            (logger/warn logger-tag "Unexpected response status: %s body: %s" status body-str)
            (on-error {:message (format "OpenAI response status: %s body: %s" status body-str)
                       :status status
                       :body body-str
                       :headers resp-headers}))
          (if stream?
            (let [{:keys [touch-fn set-reading-fn stop-fn reason*]}
                  (llm-util/start-stream-watchdog! body cancelled?
                                                   (when stream-idle-timeout-seconds
                                                     {:idle-timeout-ms (* 1000 stream-idle-timeout-seconds)}))]
              (try
                (let [stream-error
                      (with-open [rdr (io/reader body)]
                        (loop [events (seq (llm-util/event-data-seq rdr))]
                          (if-let [[event data] (first events)]
                            (do
                              (set-reading-fn false)
                              (touch-fn)
                              (llm-util/log-response logger-tag rid event data)
                              (cond
                                (= "response.failed" event)
                                (response-failed->error-data data resp-headers)

                                (= "response.incomplete" event)
                                (response-incomplete->error-data data resp-headers)

                                (= "error" event)
                                (stream-error->error-data data resp-headers)

                                (= "response.completed" event)
                                (do
                                  (on-stream event data resp-headers)
                                  nil)

                                :else
                                (do
                                  (on-stream event data resp-headers)
                                  (set-reading-fn true)
                                  (recur (next events)))))
                            (case @reason*
                              :cancelled
                              (throw (ex-info "Stream cancelled" {:silent? true}))

                              :idle-timeout
                              (llm-util/idle-timeout-error stream-idle-timeout-seconds nil)

                              (assoc-some
                               {:message "Stream disconnected before completion: stream closed before response.completed"
                                :error/type :premature-stop
                                :error/source :openai-responses
                                :headers resp-headers}
                               :request-id (response-header resp-headers "x-request-id"))))))]
                  (when stream-error
                    (on-error stream-error)))
                (catch java.io.IOException e
                  (let [reason @reason*]
                    (cond
                      (= :cancelled reason)
                      (throw (ex-info "Stream cancelled" {:silent? true}))

                      (= :idle-timeout reason)
                      (on-error (llm-util/idle-timeout-error stream-idle-timeout-seconds e))

                      :else
                      (throw e))))
                (finally
                  (stop-fn))))
            (do
              (llm-util/log-response logger-tag rid "response" body)
              (if (= "failed" (:status body))
                (on-error (response-failed->error-data {:response body} resp-headers))
                (response-body->result body))))))
      (catch Exception e
        (on-error {:exception e
                   :message (if (ex-data e)
                              (format "Internal error: %s" (or (ex-message e) (.getName (class e))))
                              (llm-util/connection-error-message e))})))))

(def ^:private responses-replay-blocking-events
  #{"response.output_text.delta"
    "response.output_text.annotation.added"
    "response.reasoning_summary_text.delta"
    "response.reasoning_summary_text.done"
    "response.output_item.added"
    "response.output_item.done"
    "response.completed"})

(defn ^:private request-with-retry!
  "Runs one exact Responses API request with request-scoped retries.
   The shared retry controller owns policy and backoff; this wrapper only tracks
   whether the current HTTP attempt emitted output that makes replay unsafe."
  [{:keys [on-error on-stream retry-request] :as request-opts}]
  (letfn [(request! [attempt]
            (let [replay-safe?* (atom true)]
              (base-responses-request!
               (assoc request-opts
                      :on-error (fn [error-data]
                                  (if retry-request
                                    (retry-request {:error-data error-data
                                                    :attempt attempt
                                                    :replay-safe? @replay-safe?*
                                                    :on-give-up on-error
                                                    :retry-fn request!})
                                    (on-error error-data)))
                      :on-stream (fn [event data & args]
                                   (when (contains? responses-replay-blocking-events event)
                                     (reset! replay-safe?* false))
                                   (apply on-stream event data args))))))]
    (request! 0)))

(defn ^:private normalize-messages [messages supports-image?]
  ;; Each history entry maps to one or more provider messages. Switched from
  ;; `keep` to `mapcat` so a single history role can emit multiple messages
  ;; (e.g. `tool_call_output` carrying image content emits a
  ;; `function_call_output` plus a synthetic user-role `input_image`).
  (mapcat (fn [{:keys [role content] :as msg}]
            ;; Defense-in-depth against #209: skip entries whose :content :api
            ;; was tagged by another provider. The OpenAI Responses API rejects
            ;; foreign reasoning ids/encrypted_content and foreign tool_use ids;
            ;; the central sanitizer in eca.llm-api drops these first, this
            ;; guard protects direct callers that bypass it.
            (let [foreign-api? (let [origin (:api content)]
                                 (and origin (not= :openai-responses origin)))]
              (case role
                "tool_call" (when-not foreign-api?
                              [{:type "function_call"
                                :name (:full-name content)
                                :call_id (:id content)
                                :arguments (json/generate-string (or (:arguments content) {}))}])

                "tool_call_output"
                (when-not foreign-api?
                  (let [contents (-> content :output :contents)
                        image-contents (when supports-image?
                                         (seq (filter #(= :image (:type %)) contents)))]
                    (cond-> [{:type "function_call_output"
                              :call_id (:id content)
                              :output (llm-util/stringfy-tool-result content)}]
                      ;; When the tool returned image content and the model
                      ;; supports image input, follow the function_call_output
                      ;; with a synthetic user-role input_image. The Responses
                      ;; API `function_call_output.output` is text-only, so a
                      ;; separate user message is the documented way to feed
                      ;; the bytes back to the model (mirrors the
                      ;; image_generation_call replay branch below).
                      image-contents
                      (conj {:role "user"
                             :content (mapv (fn [img]
                                              {:type "input_image"
                                               :image_url (format "data:%s;base64,%s"
                                                                  (or (:media-type img) "image/png")
                                                                  (:base64 img))})
                                            image-contents)}))))

                ;; Replay prior generations as user-role input_image: assistant-role
                ;; input_image is rejected, and the standalone image_generation_call
                ;; shape requires :store true (the id 404s otherwise).
                "image_generation_call" (when (and supports-image? (:base64 content))
                                          [{:role "user"
                                            :content [{:type "input_image"
                                                       :image_url (format "data:%s;base64,%s"
                                                                          (or (:media-type content) "image/png")
                                                                          (:base64 content))}]}])
                "reason" (when-not foreign-api?
                           [{:type "reasoning"
                             :id (:id content)
                             :summary (if (string/blank? (:text content))
                                        []
                                        [{:type "summary_text"
                                          :text (:text content)}])
                             :encrypted_content (:external-id content)}])
                "server_tool_use" []
                "server_tool_result" []
                [(-> msg
                     (update :content (fn [c]
                                        (if (string? c)
                                          c
                                          (keep #(case (name (:type %))

                                                   "text"
                                                   (assoc % :type (if (= "user" role)
                                                                    "input_text"
                                                                    "output_text"))

                                                   "image"
                                                   (when supports-image?
                                                     {:type      "input_image"
                                                      :image_url (format "data:%s;base64,%s"
                                                                         (:media-type %)
                                                                         (:base64 %))})

                                                   %)
                                                c)))))])))
          messages))

(defn ^:private ->tools [tools web-search image-generation]
  (cond->
   (mapv (fn [tool]
           {:type "function"
            :name (:full-name tool)
            :description (:description tool)
            :parameters (:parameters tool)
            :strict false})
         tools)
    web-search (conj {:type "web_search"})
    image-generation (conj {:type "image_generation" :output_format "png"})))

(defn create-response! [{:keys [model user-messages instructions reason? supports-image? api-key api-url url-relative-path
                                max-output-tokens past-messages tools web-search image-generation extra-payload extra-headers
                                provider auth-type provider-data account-id http-client prompt-cache-key cancelled?
                                stream-idle-timeout-seconds]}
                        {:keys [on-message-received on-error on-prepare-tool-call on-tools-called on-reason on-usage-updated
                                on-server-web-search on-server-image-generation on-response-headers retry-request] :as callbacks}]
  (let [codex? (codex-request? provider auth-type)
        provider-data (when codex?
                        (merge (:discovered-provider-data (codex-model-fallback-discovery model))
                               provider-data))
        default-reasoning-effort (:default-reasoning-effort provider-data)
        turn-context (when codex? (new-codex-turn-context))
        input (concat (normalize-messages past-messages supports-image?)
                      (normalize-messages user-messages supports-image?))
        tools (->tools tools web-search image-generation)
        base-body (cond-> (merge
                           (assoc-some
                            {:model model
                             :input input
                             :prompt_cache_key (or prompt-cache-key
                                                   (str (System/getProperty "user.name") "@ECA"))
                             :instructions instructions
                             :tools tools
                             :include (when reason?
                                        ["reasoning.encrypted_content"])
                             :store false
                             :reasoning (when reason?
                                          {:effort (or default-reasoning-effort "medium")
                                           :summary "auto"})
                             :stream true}
                            :max_output_tokens (when-not codex? max-output-tokens)
                            :tool_choice (when codex? "auto")
                            :parallel_tool_calls (:parallel_tool_calls extra-payload))
                           extra-payload)
                    ;; Codex /models can flag a model as not supporting parallel
                    ;; tool calls; sending true to it fails the request.
                    (and codex? (false? (:parallel-tool-calls? provider-data)))
                    (assoc :parallel_tool_calls false))
        body-projection (codex-body-projection codex? provider-data base-body)
        responses-lite? (= :codex-lite body-projection)
        prepare-body (fn [body]
                       (case body-projection
                         :codex-lite (codex-responses-lite-body body)
                         :codex-parallel (-> body
                                             (update :tools #(filterv (fn [tool]
                                                                       (or (function-tool? tool)
                                                                           (#{"web_search" "image_generation"} (:type tool)))) %))
                                             (update :reasoning #(assoc (or % {})
                                                                       :context "all_turns")))
                         body))
        body (prepare-body base-body)
        tool-call-by-item-id* (atom {})
        reasoning-item-id* (atom nil)
        sync-result* (when-not callbacks (atom nil))
        on-stream-fn
        (if callbacks
          (fn handle-stream [event data & _]
            (case event
              ;; text
              "response.output_text.delta"
              (on-message-received {:type :text
                                    :text (:delta data)})
              ;; tools
              "response.function_call_arguments.delta" (when-let [call (get @tool-call-by-item-id* (:item_id data))]
                                                         (on-prepare-tool-call {:id (:id call)
                                                                                :full-name (:full-name call)
                                                                                :arguments-text (:delta data)}))

              "response.output_item.done"
              (case (:type (:item data))
                "reasoning" (do (reset! reasoning-item-id* nil)
                                (on-reason {:status :finished
                                            :id (-> data :item :id)
                                            :external-id (-> data :item :encrypted_content)}))
                "function_call" (let [done-item-id (-> data :item :id)
                                      done-call-id (-> data :item :call_id)
                                      args (-> data :item :arguments)]
                                  (swap! tool-call-by-item-id*
                                         (fn [m]
                                           (if-let [existing-key (or (when (contains? m done-item-id) done-item-id)
                                                                     (->> m
                                                                          (some (fn [[k v]]
                                                                                  (when (= done-call-id (:id v)) k)))))]
                                             (assoc-in m [existing-key :arguments] args)
                                             (assoc m done-item-id {:arguments args})))))
                "web_search_call" (on-server-web-search {:status :finished
                                                         :id (-> data :item :id)
                                                         :output nil})
                "image_generation_call" (let [base64 (-> data :item :result)
                                              id (-> data :item :id)]
                                          (when (and base64 on-message-received)
                                            ;; :id retained for forward-compat with :store true mode (currently unused on the wire).
                                            (on-message-received (cond-> {:type :image
                                                                          :media-type "image/png"
                                                                          :base64 base64}
                                                                   id (assoc :id id))))
                                          (on-server-image-generation {:status :finished
                                                                       :id id
                                                                       :output nil}))
                nil)

              ;; URL mentioned
              "response.output_text.annotation.added"
              (case (-> data :annotation :type)
                "url_citation" (on-message-received
                                {:type :url
                                 :title (-> data :annotation :title)
                                 :url (-> data :annotation :url)})
                nil)

              ;; reasoning / tools
              "response.reasoning_summary_text.delta"
              (on-reason {:status :thinking
                          :id (or @reasoning-item-id* (:item_id data))
                          :text (:delta data)})

              "response.reasoning_summary_text.done"
              (on-reason {:status :thinking
                          :id (or @reasoning-item-id* (:item_id data))
                          :text "\n"})

              "response.output_item.added"
              (case (-> data :item :type)
                "reasoning" (let [id (-> data :item :id)]
                              (reset! reasoning-item-id* id)
                              (on-reason {:status :started :id id}))
                "function_call" (let [call-id (-> data :item :call_id)
                                      item-id (-> data :item :id)
                                      function-name (-> data :item :name)
                                      function-args (-> data :item :arguments)]
                                  (swap! tool-call-by-item-id* assoc item-id {:full-name function-name :id call-id})
                                  (on-prepare-tool-call {:id call-id
                                                         :full-name function-name
                                                         :arguments-text function-args}))
                "web_search_call" (on-server-web-search {:status :started
                                                         :id (-> data :item :id)
                                                         :name "web_search"
                                                         :input nil})
                "image_generation_call" (on-server-image-generation {:status :started
                                                                     :id (-> data :item :id)
                                                                     :name "image_generation"
                                                                     :input nil})
                nil)

              ;; done
              "response.completed"
              (let [response (:response data)
                    tool-calls (or (seq (keep (fn [{:keys [id call_id name arguments] :as output}]
                                                (when (= "function_call" (:type output))
                                                  (when-not (some #(= call_id (:id %)) (vals @tool-call-by-item-id*))
                                                    (swap! tool-call-by-item-id* assoc id {:full-name name :id call_id})
                                                    (on-prepare-tool-call {:id call_id
                                                                           :full-name name
                                                                           :arguments-text arguments}))
                                                  (merge {:id call_id
                                                          :item-id id
                                                          :full-name name}
                                                         (llm-util/parse-tool-call-arguments arguments))))
                                              (:output response)))
                                   ;; Fallback: some models stream tool calls via events
                                   ;; but return empty :output in response.completed
                                   (seq (keep (fn [[item-id {:keys [full-name id arguments]}]]
                                                (when arguments
                                                  (merge {:id id
                                                          :item-id item-id
                                                          :full-name full-name}
                                                         (llm-util/parse-tool-call-arguments arguments))))
                                              @tool-call-by-item-id*)))]
                (on-usage-updated (let [input-cache-read-tokens (-> response :usage :input_tokens_details :cached_tokens)]
                                    {:input-tokens (if input-cache-read-tokens
                                                     (- (-> response :usage :input_tokens) input-cache-read-tokens)
                                                     (-> response :usage :input_tokens))
                                     :output-tokens (-> response :usage :output_tokens)
                                     :input-cache-read-tokens input-cache-read-tokens}))
                (if (seq tool-calls)
                  (when-let [{:keys [new-messages tools fresh-api-key provider-auth]} (on-tools-called tool-calls)]
                    (let [new-messages (message-sanitize/sanitize-outbound-messages new-messages)]
                      (reset! tool-call-by-item-id* {})
                      (request-with-retry!
                       {:rid (llm-util/gen-rid)
                        :body (prepare-body
                               (assoc base-body
                                      :input (normalize-messages new-messages supports-image?)
                                      :tools (->tools tools web-search image-generation)))
                        :api-url api-url
                        :url-relative-path url-relative-path
                        :api-key (or fresh-api-key api-key)
                        :account-id (or (:account-id provider-auth) account-id)
                        :http-client http-client
                        :extra-headers extra-headers
                        :codex? codex?
                        :turn-context turn-context
                        :responses-lite? responses-lite?
                        :cancelled? cancelled?
                        :stream-idle-timeout-seconds stream-idle-timeout-seconds
                        :on-error on-error
                        :on-response-headers on-response-headers
                        :on-stream handle-stream
                        :retry-request retry-request})))
                  (on-message-received {:type :finish
                                        :finish-reason (-> data :response :status)})))
              nil))
          ;; Sync mode: collect text deltas into result atom
          (let [sb (StringBuilder.)]
            (fn handle-sync-stream [event data & _]
              (case event
                "response.output_text.delta"
                (.append sb ^String (:delta data))
                "response.completed"
                (reset! sync-result* {:output-text (.toString sb)})
                nil))))
        result (base-responses-request!
                {:rid (llm-util/gen-rid)
                 :body body
                 :api-url api-url
                 :url-relative-path url-relative-path
                 :api-key api-key
                 :account-id account-id
                 :http-client http-client
                 :extra-headers extra-headers
                 :codex? codex?
                 :turn-context turn-context
                 :responses-lite? responses-lite?
                 :cancelled? cancelled?
                 :stream-idle-timeout-seconds stream-idle-timeout-seconds
                 :on-error on-error
                 :on-response-headers on-response-headers
                 :on-stream on-stream-fn})]
    (if callbacks
      result
      (or @sync-result* result))))

(def ^:private client-id "app_EMoamEEZ73f0CkXaXp7hrann")

(defn ^:private oauth-url [server-url]
  (let [url "https://auth.openai.com/oauth/authorize"
        {:keys [challenge verifier]} (oauth/generate-pkce)
        state (oauth/generate-state)]
    {:verifier verifier
     :state state
     :url (str url "?" (ring.util/form-encode {:client_id client-id
                                               :response_type "code"
                                               :redirect_uri server-url
                                               :scope "openid profile email offline_access"
                                               :id_token_add_organizations "true"
                                               :prompt "login"
                                               :codex_cli_simplified_flow "true"
                                               :code_challenge challenge
                                               :code_challenge_method "S256"
                                               :state state}))}))

(def ^:private oauth-token-url
  "https://auth.openai.com/oauth/token")

(defn ^:private extract-account-id
  "Extract account ID from token response, trying id_token first, then access_token."
  [{:keys [id_token access_token]}]
  (or (jwt-token->account-id id_token)
      (jwt-token->account-id access_token)))

(defn ^:private token-error-reason
  "OpenAI's reason for refusing a token request, from its error body: JSON
   text, as only successful responses get parsed, in either OpenAI's
   `{error: {message}}` or the OAuth `{error, error_description}` shape."
  [body]
  (let [body (if (string? body)
               (try (json/parse-string body true) (catch Exception _ body))
               body)]
    (or (when (map? body)
          (or (get-in body [:error :message])
              (:error_description body)
              (when (string? (:error body)) (:error body))))
        (pr-str body))))

(defn ^:private oauth-authorize [server-url code verifier]
  (let [{:keys [status body]} (http/post
                               oauth-token-url
                               {:headers {"Content-Type" "application/json"}
                                :body (json/generate-string
                                       {:grant_type "authorization_code"
                                        :client_id client-id
                                        :code code
                                        :code_verifier verifier
                                        :redirect_uri server-url})
                                :http-client (client/merge-with-global-http-client {})
                                :throw-exceptions? false
                                :as :json})]
    (if (= 200 status)
      {:refresh-token (:refresh_token body)
       :access-token (:access_token body)
       :account-id (extract-account-id body)
       :expires-at (+ (quot (System/currentTimeMillis) 1000) (:expires_in body))}
      (throw (ex-info (format "OpenAI token exchange failed (%s): %s" status (token-error-reason body))
                      {:status status
                       :body body})))))

(defn ^:private oauth-refresh [refresh-token]
  (let [{:keys [status body]} (http/post
                               oauth-token-url
                               {:headers {"Content-Type" "application/json"}
                                :body (json/generate-string
                                       {:grant_type "refresh_token"
                                        :refresh_token refresh-token
                                        :client_id client-id})
                                :throw-exceptions? false
                                :http-client (client/merge-with-global-http-client {})
                                :as :json})]
    (if (= 200 status)
      {:refresh-token (:refresh_token body)
       :access-token (:access_token body)
       :expires-at (+ (quot (System/currentTimeMillis) 1000) (:expires_in body))}
      (throw (ex-info (format "OpenAI refresh token failed: %s" (pr-str body))
                      {:status status
                       :body body})))))

(def ^:private pro-callback-port
  "The only port OpenAI accepts in the redirect of the `pro` OAuth login."
  1455)

(def ^:private pro-callback-url
  (str "http://localhost:" pro-callback-port "/auth/callback"))

(defn ^:private in-background!
  "Runs `f` off the callback server's request thread, logging its failure."
  [f]
  (future
    (try
      (f)
      (catch Exception e
        (logger/error logger-tag "Finishing the OAuth login failed:" (ex-message e))))))

(defn ^:private start-pro-callback-server!
  "Serves the browser redirect of a `pro` (ChatGPT subscription) OAuth login
   started with `verifier` and `state`. The code is exchanged before the
   browser gets its page, so the page tells whether the login worked:
   `on-login` gets the tokens, `on-failure` the reason. A redirect of an
   earlier attempt is refused without stopping the server, so the latest
   attempt can still finish. Throws when the port can't be listened on."
  [{:keys [verifier state on-login on-failure]}]
  (let [port pro-callback-port
        server* (promise)
        stop! #(future
                 (Thread/sleep 2000) ;; let the browser render the page
                 (oauth/stop-oauth-server! port @server*))]
    (deliver server*
             (:server
              (oauth/start-oauth-server!
               {:port port
                :sync-success? true
                :on-success (fn [{:keys [code] :as callback}]
                              (when (not= state (:state callback))
                                (throw (ex-info "This browser tab belongs to an earlier login attempt: finish the login in the tab ECA opened last, or start it again." {})))
                              (try
                                (on-login (oauth-authorize pro-callback-url code verifier))
                                (catch Exception e
                                  (logger/error logger-tag "OAuth completion failed:" (ex-message e))
                                  (on-failure (ex-message e))
                                  (throw e))
                                (finally
                                  (stop!))))
                :on-error (fn [error]
                            (logger/error logger-tag "OAuth error:" error)
                            (on-failure error)
                            (stop!))})))))

;; --- Settings-based login (providers/login flow) ---

(defn ^:private start-pro-login!
  "The `pro` login method's `:start`: OAuth, for a ChatGPT subscription."
  [provider db* _config done!]
  (let [{:keys [verifier state url]} (oauth-url pro-callback-url)]
    (start-pro-callback-server!
     {:verifier verifier
      :state state
      :on-login (fn [{:keys [access-token refresh-token account-id expires-at]}]
                  (swap! db* update-in [:auth provider] merge
                         {:step :login/done
                          :type :auth/oauth
                          :mode :pro
                          :refresh-token refresh-token
                          :api-key access-token
                          :account-id account-id
                          :expires-at expires-at})
                  (in-background! done!))
      :on-failure (constantly nil)})
    {:action "authorize"
     :url url
     :message "Complete authentication in your browser. ECA will finish the login automatically."}))

;; --- Chat-based login (legacy /login command) ---

(defn ^:private start-step [{:keys [db* chat-id provider send-msg! ask!]}]
  (swap! db* assoc-in [:chats chat-id :login-provider] provider)
  (swap! db* assoc-in [:auth provider] {:step :login/waiting-login-method})
  (if ask!
    (ask! {:question "Select the OpenAI login method:"
           :options [{:label "pro" :description "GPT Plus/Pro (subscription)"}
                     {:label "manual" :description "Manually enter API Key"}]})
    (send-msg! (multi-str "Now, inform the login method:"
                          ""
                          "- pro: GPT Plus/Pro (subscription)"
                          "- manual: Manually enter API Key"))))

(defn ^:private waiting-login-method-step [{:keys [db* input provider send-msg! login-done!]}]
  (case input
    "pro"
    (let [{:keys [verifier state url]} (oauth-url pro-callback-url)]
      (start-pro-callback-server!
       {:verifier verifier
        :state state
        :on-login (fn [{:keys [access-token refresh-token account-id expires-at]}]
                    (swap! db* update-in [:auth provider] merge {:step :login/done
                                                                 :type :auth/oauth
                                                                 :refresh-token refresh-token
                                                                 :api-key access-token
                                                                 :account-id account-id
                                                                 :expires-at expires-at})
                    (in-background! (fn []
                                      (send-msg! "")
                                      (login-done!))))
        :on-failure (fn [error]
                      (send-msg! (str "Error authenticating via oauth: " error)))})
      (send-msg! (format "Open your browser at: [OpenAI login](%s)\n\nAuthenticate at OpenAI, then ECA will finish the login automatically." url)))
    "manual"
    (do
      (swap! db* assoc-in [:auth provider] {:step :login/waiting-api-key
                                            :mode :manual})
      (send-msg! "Paste your API Key"))
    (send-msg! (format "Unknown login method '%s'. Inform one of the options: pro, manual" input))))

(defn ^:private waiting-api-key-step [{:keys [input db* provider send-msg! login-done!]}]
  (if (string/starts-with? input "sk-")
    (do (config/update-global-config! {:providers {provider {:key input}}})
        (swap! db* assoc-in [:auth provider] {:step :login/done :type :auth/token})
        (send-msg! (str "API key saved in " (.getCanonicalPath (config/global-config-file))))
        (login-done!))
    (send-msg! "Invalid API key, it should start with 'sk-'")))

(defn ^:private renew-token-step [{:keys [db* provider]}]
  (let [current-auth (get-in @db* [:auth provider])
        existing-account-id (:account-id current-auth)
        {:keys [refresh-token access-token expires-at]} (oauth-refresh (:refresh-token current-auth))
        ;; Try to extract new account-id from refreshed tokens, fallback to existing
        new-account-id (or (jwt-token->account-id access-token) existing-account-id)]
    (swap! db* update-in [:auth provider] merge {:step :login/done
                                                 :type :auth/oauth
                                                 :refresh-token refresh-token
                                                 :api-key access-token
                                                 :account-id new-account-id
                                                 :expires-at expires-at})))

(defn ^:private list-models
  "The models of `/v1/models` (the API's `:list-models`)."
  [_ctx {:keys [provider api-url api-key extra-headers]}]
  (models/fetch-models-endpoint provider
                                (join-api-url api-url "/v1/models")
                                (models/native-models-headers api-key extra-headers)
                                (fn [models-data]
                                  (not-empty (into {} (keep models/parse-native-model-entry models-data))))))

(def api-module
  {:id :eca/api-openai-responses
   :description "The OpenAI Responses API."
   :apis {"openai-responses" {:handler (fn [_ctx request callbacks] (create-response! request callbacks))
                              ;; a provider's `api: "openai"` is this API
                              :aliases ["openai"]
                              :reasoning-keys [:reasoning]
                              :list-models #'list-models}}
   :config-defaults {:variantsByModel (merge m.variants/openai-responses m.variants/any-api)}})

(defn ^:private request-opts
  "A request's options (the declaration's `:request-opts`): the Responses
   API's, with parallel tool calls on and what Codex logins need (account,
   auth type, cache key)."
  [_opts {:keys [model flat-instructions user-messages max-output-tokens reason? supports-image? past-messages
                 tools web-search image-generation extra-payload extra-headers reasoning-history api-url api-key
                 provider-base auth-type model-capabilities provider-auth prompt-cache-key cancelled?
                 stream-idle-timeout-seconds]}]
  {:model model
   :instructions flat-instructions
   :user-messages user-messages
   :max-output-tokens max-output-tokens
   :reason? reason?
   :supports-image? supports-image?
   :past-messages past-messages
   :tools tools
   :web-search web-search
   :image-generation image-generation
   :extra-payload (merge {:parallel_tool_calls true} extra-payload)
   :extra-headers extra-headers
   :reasoning-history reasoning-history
   :api-url api-url
   :api-key api-key
   :provider provider-base
   :auth-type auth-type
   :provider-data (:provider-data model-capabilities)
   :account-id (:account-id provider-auth)
   :prompt-cache-key prompt-cache-key
   :cancelled? cancelled?
   :stream-idle-timeout-seconds stream-idle-timeout-seconds})

(defn ^:private default-model
  "With an OpenAI key or a ChatGPT login, GPT-6.2 Sol, OpenAI's everyday model
   (the declaration's `:default-model`)."
  [{:keys [db config]}]
  (when (llm-util/provider-api-key "openai" (get-in db [:auth "openai"]) config)
    "openai/gpt-6.2-sol"))

(defn ^:private response-field [m field]
  (when (map? m)
    (or (get m field)
        (get m (keyword field)))))

(defn ^:private usage-limit-reset-ms
  "`:errors :retry-after-ms`: a ChatGPT login's usage-limit reset, from the
   429's JSON body (`error.resets_at`, epoch seconds)."
  [{:keys [error-data]}]
  (let [body (:body error-data)
        body (cond
               (map? body) body
               (string? body) (try
                                (json/parse-string body)
                                (catch Exception _ nil))
               :else nil)
        error (response-field body "error")
        error-type (response-field error "type")
        reset-at (response-field error "resets_at")]
    (when (and (= "usage_limit_reached" error-type)
               (integer? reset-at))
      (* (long reset-at) 1000))))

(def ^:private image-generation-models
  "Mainline OpenAI chat models that support the built-in `image_generation`
   tool on the Responses API (the declaration's `:capabilities`). Sourced from
   OpenAI's image-generation tool guide (explicit list: gpt-4.1, gpt-4.1-mini,
   gpt-4.1-nano, gpt-4o-mini, gpt-5, gpt-5.5, o3) plus the published guide
   rule: \"gpt-5 and newer models should support the image generation tool\".
   Codex variants are deliberately excluded — OpenAI restricts them to
   function calling, structured outputs, streaming, and prompt caching."
  #{"gpt-4.1"
    "gpt-4.1-mini"
    "gpt-4.1-nano"
    "gpt-4o-mini"
    "gpt-5"
    "gpt-5-mini"
    "gpt-5-nano"
    "gpt-5.1"
    "gpt-5.2"
    "gpt-5.4"
    "gpt-5.5"
    "gpt-5.6-luna"
    "gpt-5.6-terra"
    "gpt-5.6-sol"
    "gpt-6-astra"
    "gpt-6.2-astra"
    "gpt-6.2-sol"
    "o3"})

(def ^:private web-search-models
  "OpenAI models that support the built-in web-search tool (the declaration's
   `:capabilities`), sourced from the web-search tool guide; its explicit
   denylist names only `gpt-4.1-nano` and `gpt-5 with minimal reasoning` (the
   latter is a runtime config, not a model id, so `gpt-5` itself stays in the
   set). Codex variants are deliberately excluded."
  #{"gpt-4.1"
    "gpt-4.1-mini"
    "gpt-4o"
    "gpt-4o-mini"
    "gpt-5"
    "gpt-5-mini"
    "gpt-5-nano"
    "gpt-5.1"
    "gpt-5.2"
    "gpt-5.4"
    "gpt-5.5"
    "gpt-5.6-luna"
    "gpt-5.6-terra"
    "gpt-5.6-sol"
    "gpt-6-astra"
    "gpt-6.1-sol"
    "gpt-6-sol"
    "gpt-6-luna"
    "gpt-6.2-astra"
    "gpt-6.2-sol"
    "o3"
    "o3-mini"
    "o4-mini"})

(def module
  {:id :eca/openai
   :description "GPT models from OpenAI: a ChatGPT subscription or a pasted key."
   :requires #{:eca/api-openai-responses}
   :providers {"openai" {:label "OpenAI"
                         :login {:methods [{:key "pro" :label "Pro (subscription via OAuth)" :start #'start-pro-login!}
                                           {:key "manual" :label "Manual (paste API key)"}]
                                 ;; the chat's /login
                                 :steps {:login/start #'start-step
                                         :login/waiting-login-method #'waiting-login-method-step
                                         :login/waiting-api-key #'waiting-api-key-step
                                         :login/renew-token #'renew-token-step}}
                         :quota {:fetch #'fetch-quota :headers #'quota-windows}
                         :request-opts #'request-opts
                         :api-fn (constantly :openai-responses)
                         :default-model {:rank 2 :fn #'default-model}
                         :models-override-fn #'models-override
                         :capabilities {:web-search web-search-models
                                        :image-generation? image-generation-models}
                         :errors {:retry-after-ms #'usage-limit-reset-ms}}}
   :config-defaults {:providers {"openai" {:api "openai-responses"
                                           :url "${env:OPENAI_API_URL:https://api.openai.com}"
                                           :key "${env:OPENAI_API_KEY}"
                                           :requiresAuth? true
                                           :models {"gpt-4.1" {}
                                                    "gpt-5" {}
                                                    "gpt-5-mini" {}
                                                    "gpt-5.2" {}
                                                    "gpt-5.3-codex" {}
                                                    "gpt-6.2-sol" {}}}}}})
