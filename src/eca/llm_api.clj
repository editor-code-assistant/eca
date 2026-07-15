(ns eca.llm-api
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.features.prompt :as f.prompt]
   [eca.llm-providers.anthropic :as llm-providers.anthropic]
   [eca.llm-providers.azure]
   [eca.llm-providers.bedrock :as llm-providers.bedrock]
   [eca.llm-providers.copilot]
   [eca.llm-providers.deepseek]
   [eca.llm-providers.errors :as llm-providers.errors]
   [eca.llm-providers.google]
   [eca.llm-providers.litellm]
   [eca.llm-providers.lmstudio]
   [eca.llm-providers.mistral]
   [eca.llm-providers.moonshot]
   [eca.llm-providers.ollama :as llm-providers.ollama]
   [eca.llm-providers.openai :as llm-providers.openai]
   [eca.llm-providers.openai-chat :as llm-providers.openai-chat]
   [eca.llm-providers.openrouter]
   [eca.llm-providers.z-ai]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]
   [eca.message-sanitize :as message-sanitize]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[LLM-API]")

(def no-available-model-error-msg "No available model found. Configure at least one provider model.")

(def ^:private copilot-responses-api-models
  #{"gpt-5.3-codex" "gpt-5.4" "gpt-5.4-mini" "gpt-5.5"})

(def ^:private default-max-retries 10)
(def ^:private premature-stop-max-retries 3)
(def ^:private default-base-delay-ms 2000)
(def ^:private default-backoff-multiplier 2)
(def ^:private max-delay-ms 60000)
(def ^:private rate-limit-wait-buffer-ms 1000)
(def ^:private cancel-check-interval-ms 100)

(defn ^:private retry-delay-ms
  "Computes exponential backoff delay with jitter for the given attempt (0-based).
   Capped at `max-delay-ms` to avoid excessively long waits."
  [attempt]
  (let [base (long (* default-base-delay-ms (Math/pow default-backoff-multiplier (long attempt))))
        capped (min base max-delay-ms)
        jitter (long (* capped (rand)))]
    (+ (quot capped 2) jitter)))

(defn ^:private sleep-with-cancel
  "Sleeps for `duration-ms`, checking `cancelled-fn?` every 100ms.
   Returns true if sleep completed, false if cancelled."
  [duration-ms cancelled-fn?]
  (loop [remaining duration-ms]
    (cond
      (cancelled-fn?)
      false

      (<= remaining 0)
      true

      :else
      (let [chunk (min remaining cancel-check-interval-ms)]
        (Thread/sleep (long chunk))
        (recur (long (- remaining chunk)))))))

(defn ^:private first-available-model
  "Returns deterministic first available model from DB."
  [db]
  (some->> (:models db) keys sort first))

(defn refine-file-context [path lines-range]
  (cond
    (not (fs/exists? path))
    (logger/warn logger-tag "File not found at" path)
    (not (fs/readable? path))
    (logger/warn logger-tag "Unable to read file at" path)
    :else
    (let [content (slurp path)]
      (if lines-range
        (let [lines (string/split-lines content)
              start (dec (:start lines-range))
              end (min (count lines) (:end lines-range))]
          (string/join "\n" (subvec lines start end)))
        content))))

(defn default-model
  "Returns the default LLM model checking this waterfall:
  - defaultModel set
  - Anthropic api key set
  - Openai api key set
  - Github copilot login done
  - Ollama first model if running
  - Anthropic default model.

  Returns nil when there are no available models."
  [db config]
  (let [[initial-decision model-candidate]
        (or (when-let [config-default-model (:defaultModel config)]
              [:config-default-model config-default-model])
            (when (llm-util/provider-api-key "anthropic" (get-in db [:auth "anthropic"]) config)
              [:api-key-found "anthropic/claude-sonnet-4-6"])
            (when (llm-util/provider-api-key "openai" (get-in db [:auth "openai"]) config)
              [:api-key-found "openai/gpt-5.2"])
            (when (get-in db [:auth "github-copilot" :api-key])
              [:api-key-found "github-copilot/gpt-5.5"])
            (when-let [ollama-model (first (filter #(string/starts-with? % config/ollama-model-prefix) (keys (:models db))))]
              [:ollama-running ollama-model])
            [:default "anthropic/claude-sonnet-4-6"])
        model (if (contains? (:models db) model-candidate)
                model-candidate
                (first-available-model db))
        decision (cond
                   (= model model-candidate) initial-decision
                   model :first-available-model
                   :else :no-available-model)]
    (logger/info logger-tag (format "Default LLM model '%s' decision '%s'" model decision))
    model))

(defn ^:private real-model-name [model model-capabilities]
  (or (:model-name model-capabilities) model))

(defn ^:private api->handler [api]
  (case (some-> api keyword)
    :openai-responses {:api     :openai-responses
                       :handler llm-providers.openai/create-response!}
    :anthropic {:api     :anthropic
                :handler llm-providers.anthropic/chat!}
    :openai-chat {:api     :openai-chat
                  :handler llm-providers.openai-chat/chat-completion!}
    :ollama {:api     :ollama
             :handler llm-providers.ollama/chat!}
    :bedrock {:api     :bedrock
              :handler llm-providers.bedrock/chat!}
    nil))

(defn provider->api-handler
  ([provider model config]
   (provider->api-handler provider model nil config))
  ([provider model model-capabilities config]
   (or (api->handler (:api model-capabilities))
       (cond
         (= "openai" provider) (api->handler :openai-responses)
         (= "anthropic" provider) (api->handler :anthropic)
         (= "github-copilot" provider) (api->handler (if (copilot-responses-api-models model)
                                                       :openai-responses
                                                       :openai-chat))
         (= "google" provider) (api->handler :openai-chat)
         (= "ollama" provider) (api->handler :ollama)
         :else (case (get-in config [:providers provider :api])
                 ("openai-responses" "openai") (api->handler :openai-responses)
                 "anthropic" (api->handler :anthropic)
                 "openai-chat" (api->handler :openai-chat)
                 "bedrock" (api->handler :bedrock)
                 nil)))))

(def ^:private reasoning-keys-by-api
  {:anthropic [:thinking]
   :bedrock [:thinking :output_config]
   :openai-responses [:reasoning]
   :openai-chat [:reasoning]
   :ollama [:think]})

(def ^:private roles-with-provider-specific-ids
  "Roles whose :content carries opaque provider-specific identifiers
   (Anthropic signatures, OpenAI Responses rs_*/encrypted_content, tool
   use ids like toolu_*/call_*) that the next provider may reject."
  #{"reason" "tool_call" "tool_call_output" "server_tool_use" "server_tool_result"})

(defn ^:private entry-incompatible-with-api?
  [target-api {:keys [role content]}]
  (boolean (and target-api
                (map? content)
                (:api content)
                (not= (:api content) target-api)
                (contains? roles-with-provider-specific-ids role))))

(defn sanitize-past-messages-for-api
  "Drops history entries whose opaque provider-specific ids would make the
   request invalid under `target-api`, then strips internal-only top-level
   message metadata before provider serialization. Returns a map:
     :messages      - sanitized past-messages vector
     :dropped-count - number of entries removed
     :dropped-apis  - set of origin apis whose entries were dropped
   Entries without a :api tag (legacy, untagged) are kept as-is so existing
   in-flight chats keep working. Issue #209."
  [target-api past-messages]
  (let [past (vec past-messages)
        incompatible? (partial entry-incompatible-with-api? target-api)
        kept (into [] (comp (remove incompatible?) (map message-sanitize/strip-internal-message-fields)) past)
        dropped (filterv incompatible? past)]
    {:messages kept
     :dropped-count (count dropped)
     :dropped-apis (into #{} (keep #(get-in % [:content :api])) dropped)}))

(defn ^:private extra-payload-considering-variant
  "Resolves the effective extra-payload by merging extraPayload with variant payload.
   Variant values take priority over extraPayload on clashing keys.
   When reason? is false, strips provider-specific reasoning keys from the result.
   Falls back to a \"default\" variant when no explicit variant is selected."
  [model-config variant {:keys [api]} reason?]
  (let [variant-payload (or (get-in model-config [:variants variant])
                            (when (nil? variant)
                              (get-in model-config [:variants "default"])))
        extra-payload (:extraPayload model-config)
        merged (if variant-payload
                 (shared/deep-merge extra-payload variant-payload)
                 extra-payload)]
    (if (and merged (not reason?))
      (let [keys-to-strip (get reasoning-keys-by-api api)]
        (apply dissoc merged keys-to-strip))
      merged)))

(defn ^:private prompt!
  [{:keys [provider model model-capabilities instructions user-messages config variant
           on-message-received on-error on-prepare-tool-call on-tools-called on-reason on-usage-updated
           on-server-web-search on-server-image-generation on-history-sanitized
           past-messages tools provider-auth sync? subagent? cancelled? prompt-cache-key]
    :or {on-error identity}}]
  (let [real-model (real-model-name model model-capabilities)
        tools (when (:tools model-capabilities) tools)
        reason? (:reason? model-capabilities)
        supports-image? (:image-input? model-capabilities)
        web-search (:web-search model-capabilities)
        mid-conversation-system? (:mid-conversation-system? model-capabilities)
        image-generation (:image-generation? model-capabilities)
        max-output-tokens (:max-output-tokens model-capabilities)
        provider-config (get-in config [:providers provider])
        model-config (get-in provider-config [:models model])
        model-config (update model-config :variants #(config/effective-model-variants config provider model model-capabilities %))
        {:keys [handler] :as api-handler} (provider->api-handler provider model model-capabilities config)
        {past-messages :messages
         sanitized-dropped-count :dropped-count
         sanitized-dropped-apis :dropped-apis} (sanitize-past-messages-for-api (:api api-handler) past-messages)
        user-messages (message-sanitize/sanitize-outbound-messages user-messages)
        _ (when (and on-history-sanitized (pos? sanitized-dropped-count))
            (try
              (on-history-sanitized {:dropped-count sanitized-dropped-count
                                     :dropped-apis sanitized-dropped-apis
                                     :target-api (:api api-handler)})
              (catch Exception e
                (logger/warn logger-tag "on-history-sanitized callback failed" {:exception (ex-message e)}))))
        extra-payload (extra-payload-considering-variant model-config variant api-handler reason?)
        extra-headers (merge (:extraHeaders provider-config)
                             (:extraHeaders model-config))
        reasoning-history (or (:reasoningHistory model-config) :all)
        [auth-type api-key] (llm-util/provider-api-key provider provider-auth config)
        api-url (llm-util/provider-api-url provider config)
        ;; Flatten {:static :dynamic} instructions map into a single string for non-Anthropic providers
        flat-instructions (if (map? instructions) (f.prompt/instructions->str instructions) instructions)
        anthropic-opts {:model real-model
                        :instructions instructions
                        :user-messages user-messages
                        :max-output-tokens max-output-tokens
                        :reason? reason?
                        :supports-image? supports-image?
                        :past-messages past-messages
                        :tools tools
                        :web-search web-search
                        :mid-conversation-system? mid-conversation-system?
                        :extra-payload extra-payload
                        :extra-headers extra-headers
                        :api-url api-url
                        :api-key api-key
                        :auth-type auth-type
                        :cancelled? cancelled?
                        :cache-retention (:cacheRetention provider-config)
                        :stream-idle-timeout-seconds (:streamIdleTimeoutSeconds config)}
        callbacks (when-not sync?
                    {:on-message-received on-message-received
                     :on-error on-error
                     :on-prepare-tool-call on-prepare-tool-call
                     :on-tools-called on-tools-called
                     :on-reason on-reason
                     :on-usage-updated on-usage-updated
                     :on-server-web-search on-server-web-search
                     :on-server-image-generation on-server-image-generation})]
    (try
      (when-not api-url (throw (ex-info (format "API url not found.\nMake sure you have provider '%s' configured properly." provider) {})))
      (cond
        (= "openai" provider)
        (handler
         {:model real-model
          :instructions flat-instructions
          :user-messages user-messages
          :max-output-tokens max-output-tokens
          :reason? reason?
          :supports-image? supports-image?
          :past-messages past-messages
          :tools tools
          :web-search web-search
          :image-generation image-generation
          :extra-payload (merge {:parallel_tool_calls true}
                                extra-payload)
          :extra-headers extra-headers
          :reasoning-history reasoning-history
          :api-url api-url
          :api-key api-key
          :auth-type auth-type
          :account-id (:account-id provider-auth)
          :prompt-cache-key prompt-cache-key}
         callbacks)

        (= "anthropic" provider)
        (handler anthropic-opts callbacks)

        (= "github-copilot" provider)
        (let [api-url (or (:api-url provider-auth) api-url)
              copilot-headers (fn [user-initiator? anthropic?]
                                (cond-> (merge {"openai-intent" "conversation-panel"
                                                "x-request-id" (str (random-uuid))
                                                "x-initiator" (if user-initiator? "user" "agent")
                                                "vscode-sessionid" ""
                                                "vscode-machineid" ""
                                                "Copilot-Vision-Request" "true"
                                                "copilot-integration-id" "vscode-chat"}
                                               (llm-util/copilot-ide-headers)
                                               extra-headers)
                                  ;; Copilot uses a GitHub bearer token, not Anthropic OAuth, so replace the
                                  ;; handler's OAuth beta with the Messages shim's required beta.
                                  anthropic? (assoc "anthropic-beta" "interleaved-thinking-2025-05-14")))
              user-initiator? (fn [body message-key]
                                (and (not subagent?)
                                     (= "user" (-> body message-key last :role))))
              base-opts {:model real-model
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
                         :prompt-cache-key prompt-cache-key}]
          (case (:api api-handler)
            :openai-responses
            (handler
             (assoc base-opts
                    :web-search web-search
                    :image-generation image-generation
                    :extra-payload (merge {:parallel_tool_calls true} extra-payload)
                    :extra-headers (fn [{:keys [body]}]
                                     (copilot-headers (user-initiator? body :input) false)))
             callbacks)

            :anthropic
            (handler
             (assoc anthropic-opts
                    :api-url api-url
                    :extra-headers (fn [{:keys [body]}]
                                     (copilot-headers (user-initiator? body :messages) true)))
             callbacks)

            (handler
             (assoc base-opts
                    :extra-payload (merge {:parallel_tool_calls true} extra-payload)
                    :extra-headers (fn [{:keys [body]}]
                                     (copilot-headers (user-initiator? body :messages) false)))
             callbacks)))

        (= "google" provider)
        (handler
         {:model real-model
          :instructions flat-instructions
          :user-messages user-messages
          :max-output-tokens max-output-tokens
          :reason? reason?
          :supports-image? supports-image?
          :past-messages past-messages
          :tools tools
          :think-tag-start "<thought>"
          :think-tag-end "</thought>"
          :reasoning-history reasoning-history
          :extra-payload (merge {:parallel_tool_calls false}
                                (when reason?
                                  {:extra_body {:google {:thinking_config {:include_thoughts true}}}})
                                extra-payload)
          :extra-headers extra-headers
          :api-url api-url
          :api-key api-key}
         callbacks)

        (= "ollama" provider)
        (handler
         {:api-url api-url
          :reason? (:reason? model-capabilities)
          :supports-image? supports-image?
          :model real-model
          :instructions flat-instructions
          :user-messages user-messages
          :past-messages past-messages
          :tools tools
          :extra-payload extra-payload
          :extra-headers extra-headers}
         callbacks)

        (and (or model-config
                 model-capabilities)
             handler)
        (let [url-relative-path (:completionUrlRelativePath provider-config)
              think-tag-start (:thinkTagStart provider-config)
              think-tag-end (:thinkTagEnd provider-config)
              http-client (:httpClient provider-config)]
          (handler
           {:model real-model
            :instructions flat-instructions
            :user-messages user-messages
            :max-output-tokens max-output-tokens
            :web-search web-search
            :image-generation image-generation
            :reason? reason?
            :supports-image? supports-image?
            :past-messages past-messages
            :tools tools
            :extra-payload extra-payload
            :extra-headers extra-headers
            :url-relative-path url-relative-path
            :think-tag-start think-tag-start
            :think-tag-end think-tag-end
            :reasoning-history reasoning-history
            :http-client http-client
            :api-url api-url
            :api-key api-key
            :cancelled? cancelled?
            :cache-retention (:cacheRetention provider-config)
            :stream-idle-timeout-seconds (:streamIdleTimeoutSeconds config)}
           callbacks))

        :else
        (on-error {:message (format "ECA Unsupported model %s for provider %s" real-model provider)}))
      (catch Exception e
        (on-error {:exception e})))))

(defn sync-or-async-prompt!
  [{:keys [provider model model-capabilities instructions user-messages config on-first-response-received
           on-message-received on-error on-prepare-tool-call on-tools-called on-reason on-usage-updated
           on-server-web-search on-server-image-generation on-history-sanitized
           past-messages tools provider-auth refresh-provider-auth-fn variant cancelled? on-retry subagent? prompt-cache-key]
    :or {on-first-response-received identity
         on-message-received identity
         on-error identity
         on-prepare-tool-call identity
         on-tools-called identity
         on-reason identity
         on-usage-updated identity
         on-server-web-search identity
         on-server-image-generation identity
         on-history-sanitized identity
         cancelled? (constantly false)}}]
  (let [first-response-received* (atom false)
        ;; Fire :on-history-sanitized at most once per sync-or-async-prompt! call:
        ;; prompt! is invoked multiple times on retries and we don't want to
        ;; spam the chat with repeated "history sanitized" notices.
        history-sanitized-emitted?* (atom false)
        on-history-sanitized-wrapper (fn [args]
                                       (when (compare-and-set! history-sanitized-emitted?* false true)
                                         (try
                                           (on-history-sanitized args)
                                           (catch Exception e
                                             (logger/warn logger-tag "on-history-sanitized callback failed" {:exception (ex-message e)})))))
        emit-first-message-fn (fn [& args]
                                (when (compare-and-set! first-response-received* false true)
                                  (apply on-first-response-received args)))
        on-message-received-wrapper (fn [& args]
                                      (apply emit-first-message-fn args)
                                      (apply on-message-received args))
        on-reason-wrapper (fn [& args]
                            (apply emit-first-message-fn args)
                            (apply on-reason args))
        on-prepare-tool-call-wrapper (fn [& args]
                                       (apply emit-first-message-fn args)
                                       (apply on-prepare-tool-call args))
        on-server-web-search-wrapper (fn [& args]
                                       (apply emit-first-message-fn args)
                                       (apply on-server-web-search args))
        on-server-image-generation-wrapper (fn [& args]
                                             (apply emit-first-message-fn args)
                                             (apply on-server-image-generation args))
        on-error-wrapper (fn [{:keys [exception] :as args}]
                           (when-not (:silent? (ex-data exception))
                             (logger/error args)
                             (on-error args)))
        provider-config (get-in config [:providers provider])
        retry-rules (:retryRules provider-config)
        ;; Renew before each prompt! call — token can expire during long tool calls or retries.
        fresh-provider-auth (fn []
                              (if refresh-provider-auth-fn
                                (try
                                  (or (refresh-provider-auth-fn) provider-auth)
                                  (catch Exception e
                                    (logger/warn logger-tag
                                                 "refresh-provider-auth-fn failed, falling back to captured auth"
                                                 {:exception (ex-message e)})
                                    provider-auth))
                                provider-auth))
        maybe-retry (fn [error-data attempt on-give-up retry-prompt-fn]
                      (let [{error-type :error/type
                             :as classified} (llm-providers.errors/classify-error error-data retry-rules)
                            max-retries (if (= :premature-stop error-type)
                                          premature-stop-max-retries
                                          default-max-retries)
                            rl-wait (when (= :rate-limited error-type)
                                      (llm-providers.errors/rate-limit-wait (:headers error-data)
                                                                            (System/currentTimeMillis)))
                            max-wait-ms (some-> (:rateLimitMaxWaitSeconds provider-config) long (* 1000))
                            wait-too-long? (boolean (and rl-wait
                                                         max-wait-ms
                                                         (> (long (:delay-ms rl-wait)) (long max-wait-ms))))]
                        (if (and (contains? #{:rate-limited :overloaded :retryable-custom :premature-stop} error-type)
                                 (< attempt max-retries)
                                 (not wait-too-long?)
                                 (not @first-response-received*)
                                 (not (cancelled?)))
                          (let [delay-ms (if rl-wait
                                           (+ (long (:delay-ms rl-wait)) rate-limit-wait-buffer-ms)
                                           (retry-delay-ms attempt))]
                            (logger/info logger-tag
                                         (format "Retryable error (attempt %d/%d), retrying in %ds%s"
                                                 (inc attempt) max-retries (quot delay-ms 1000)
                                                 (if rl-wait " (rate limit reset from headers)" ""))
                                         {:error-type error-type
                                          :status (:status error-data)})
                            (when on-retry
                              (try
                                (on-retry {:attempt (inc attempt)
                                           :max-retries max-retries
                                           :delay-ms delay-ms
                                           :resets-at (:resets-at rl-wait)
                                           :error-data error-data
                                           :classified classified})
                                (catch Exception e
                                  (logger/warn logger-tag "on-retry callback failed" {:exception e}))))
                            (if (sleep-with-cancel delay-ms cancelled?)
                              (retry-prompt-fn (inc attempt))
                              (on-give-up error-data)))
                          (on-give-up (cond-> error-data
                                        rl-wait (assoc :rate-limit-resets-at (:resets-at rl-wait)))))))
        model-config (get-in provider-config [:models model])
        model-config (update model-config :variants #(config/effective-model-variants config provider model model-capabilities %))
        api-handler (provider->api-handler provider model model-capabilities config)
        extra-payload (extra-payload-considering-variant model-config variant api-handler (:reason? model-capabilities))
        stream? (if (not (nil? (:stream extra-payload)))
                  (:stream extra-payload)
                  true)]
    (if (not stream?)
      (let [sync-prompt-with-retry*
            (fn sync-prompt-with-retry [attempt]
              (loop [result (prompt!
                             {:sync? true
                              :provider provider
                              :model model
                              :model-capabilities model-capabilities
                              :instructions instructions
                              :tools tools
                              :provider-auth (fresh-provider-auth)
                              :past-messages past-messages
                              :user-messages user-messages
                              :variant variant
                              :subagent? subagent?
                              :prompt-cache-key prompt-cache-key
                              :on-error on-error-wrapper
                              :on-history-sanitized on-history-sanitized-wrapper
                              :config config})]
                (let [{:keys [error output-text reason-text reasoning-content tools-to-call call-tools-fn reason-id usage]} result]
                  (if error
                    (maybe-retry error attempt on-error-wrapper sync-prompt-with-retry)
                    (do
                      (when reason-text
                        (on-reason-wrapper {:status :started :id reason-id})
                        (on-reason-wrapper {:status :thinking :id reason-id :text reason-text})
                        (on-reason-wrapper {:status :finished
                                            :id reason-id
                                            :delta-reasoning? (some? reasoning-content)}))
                      (on-message-received-wrapper {:type :text :text output-text})
                      (some-> usage (on-usage-updated))
                      (if-let [new-result (when (seq tools-to-call)
                                            (doseq [tool-to-call tools-to-call]
                                              (on-prepare-tool-call tool-to-call))
                                            (call-tools-fn on-tools-called))]
                        (recur new-result)
                        (on-message-received-wrapper {:type :finish :finish-reason "stop"})))))))]
        (sync-prompt-with-retry* 0))
      (let [async-prompt-with-retry*
            (fn async-prompt-with-retry [attempt]
              (prompt!
               {:sync? false
                :provider provider
                :model model
                :model-capabilities model-capabilities
                :instructions instructions
                :tools tools
                :provider-auth (fresh-provider-auth)
                :past-messages past-messages
                :user-messages user-messages
                :variant variant
                :subagent? subagent?
                :prompt-cache-key prompt-cache-key
                :cancelled? cancelled?
                :on-message-received on-message-received-wrapper
                :on-prepare-tool-call on-prepare-tool-call-wrapper
                :on-tools-called on-tools-called
                :on-usage-updated on-usage-updated
                :on-server-web-search on-server-web-search-wrapper
                :on-server-image-generation on-server-image-generation-wrapper
                :on-reason on-reason-wrapper
                :on-history-sanitized on-history-sanitized-wrapper
                :on-error (fn [error-data]
                            (if (:silent? (ex-data (:exception error-data)))
                              (on-error-wrapper error-data)
                              (maybe-retry error-data attempt on-error-wrapper async-prompt-with-retry)))
                :config config}))]
        (async-prompt-with-retry* 0)))))

(defn sync-prompt!
  [{:keys [provider model model-capabilities instructions
           prompt past-messages user-messages config tools provider-auth subagent?]}]
  (prompt!
   {:sync? true
    :provider provider
    :model model
    :model-capabilities model-capabilities
    :instructions instructions
    :tools tools
    :provider-auth provider-auth
    :past-messages past-messages
    :user-messages (or user-messages
                       [{:role "user" :content [{:type :text :text prompt}]}])
    :subagent? subagent?
    :config config
    :on-error (fn [error] {:error error})}))
