(ns eca.llm-api
  (:require
   [babashka.fs :as fs]
   [clojure.string :as string]
   [eca.config :as config]
   [eca.llm-providers.anthropic :as llm-providers.anthropic]
   [eca.llm-providers.azure]
   [eca.llm-providers.copilot]
   [eca.llm-providers.deepseek]
   [eca.llm-providers.google]
   [eca.llm-providers.ollama :as llm-providers.ollama]
   [eca.llm-providers.openai :as llm-providers.openai]
   [eca.llm-providers.openai-chat :as llm-providers.openai-chat]
   [eca.llm-providers.openrouter]
   [eca.llm-providers.z-ai]
   [eca.llm-util :as llm-util]
   [eca.logger :as logger]))

(set! *warn-on-reflection* true)

(def ^:private logger-tag "[LLM-API]")

(def no-available-model-error-msg "No available model found. Configure at least one provider model.")

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
              [:api-key-found "anthropic/claude-sonnet-4.5"])
            (when (llm-util/provider-api-key "openai" (get-in db [:auth "openai"]) config)
              [:api-key-found "openai/gpt-5.2"])
            (when (get-in db [:auth "github-copilot" :api-key])
              [:api-key-found "github-copilot/gpt-5.2"])
            (when-let [ollama-model (first (filter #(string/starts-with? % config/ollama-model-prefix) (keys (:models db))))]
              [:ollama-running ollama-model])
            [:default "anthropic/claude-sonnet-4.5"])
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

(defn provider->api-handler [provider config]
  (cond
    (= "openai" provider) {:api :openai-responses
                           :handler llm-providers.openai/create-response!}
    (= "anthropic" provider) {:api :anthropic
                              :handler llm-providers.anthropic/chat!}
    (= "github-copilot" provider) {:api :openai-chat
                                   :handler llm-providers.openai-chat/chat-completion!}
    (= "google" provider) {:api :openai-chat
                           :handler llm-providers.openai-chat/chat-completion!}
    (= "ollama" provider) {:api :ollama
                           :handler llm-providers.ollama/chat!}
    :else (case (get-in config [:providers provider :api])
            ("openai-responses" "openai") {:api :openai-responses
                                           :handler llm-providers.openai/create-response!}
            "anthropic" {:api :anthropic
                         :handler llm-providers.anthropic/chat!}
            "openai-chat" {:api :openai-chat
                           :handler llm-providers.openai-chat/chat-completion!}
            nil)))

(defn ^:private prompt!
  [{:keys [provider model model-capabilities instructions user-messages config
           on-message-received on-error on-prepare-tool-call on-tools-called on-reason on-usage-updated
           past-messages tools provider-auth sync?]
    :or {on-error identity}}]
  (let [real-model (real-model-name model model-capabilities)
        tools (when (:tools model-capabilities) tools)
        reason? (:reason? model-capabilities)
        supports-image? (:image-input? model-capabilities)
        web-search (:web-search model-capabilities)
        max-output-tokens (:max-output-tokens model-capabilities)
        provider-config (get-in config [:providers provider])
        model-config (get-in provider-config [:models model])
        extra-payload (:extraPayload model-config)
        extra-headers (:extraHeaders model-config)
        reasoning-history (or (:reasoningHistory model-config) :all)
        [auth-type api-key] (llm-util/provider-api-key provider provider-auth config)
        api-url (llm-util/provider-api-url provider config)
        {:keys [handler]} (provider->api-handler provider config)
        callbacks (when-not sync?
                    {:on-message-received on-message-received
                     :on-error on-error
                     :on-prepare-tool-call on-prepare-tool-call
                     :on-tools-called on-tools-called
                     :on-reason on-reason
                     :on-usage-updated on-usage-updated})]
    (try
      (when-not api-url (throw (ex-info (format "API url not found.\nMake sure you have provider '%s' configured properly." provider) {})))
      (cond
        (= "openai" provider)
        (handler
         {:model real-model
          :instructions instructions
          :user-messages user-messages
          :max-output-tokens max-output-tokens
          :reason? reason?
          :supports-image? supports-image?
          :past-messages past-messages
          :tools tools
          :web-search web-search
          :extra-payload (merge {:parallel_tool_calls true}
                                extra-payload)
          :extra-headers extra-headers
          :reasoning-history reasoning-history
          :api-url api-url
          :api-key api-key
          :auth-type auth-type
          :account-id (:account-id provider-auth)}
         callbacks)

        (= "anthropic" provider)
        (handler
         {:model real-model
          :instructions instructions
          :user-messages user-messages
          :max-output-tokens max-output-tokens
          :reason? reason?
          :supports-image? supports-image?
          :past-messages past-messages
          :tools tools
          :web-search web-search
          :extra-payload extra-payload
          :extra-headers extra-headers
          :api-url api-url
          :api-key api-key
          :auth-type auth-type}
         callbacks)

        (= "github-copilot" provider)
        (handler
         {:model real-model
          :instructions instructions
          :user-messages user-messages
          :max-output-tokens max-output-tokens
          :reason? reason?
          :supports-image? supports-image?
          :past-messages past-messages
          :tools tools
          :extra-payload (merge {:parallel_tool_calls true}
                                extra-payload)
          :reasoning-history reasoning-history
          :api-url api-url
          :api-key api-key
          :extra-headers (fn [{:keys [body]}]
                           (let [user-initiator? (= "user" (-> body :messages last :role))]
                             (merge {"openai-intent" "conversation-panel"
                                     "x-request-id" (str (random-uuid))
                                     "x-initiator" (if user-initiator? "user" "agent")
                                     "vscode-sessionid" ""
                                     "vscode-machineid" ""
                                     "Copilot-Vision-Request" "true"
                                     "copilot-integration-id" "vscode-chat"}
                                    extra-headers)))}
         callbacks)

        (= "google" provider)
        (handler
         {:model real-model
          :instructions instructions
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
          :instructions instructions
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
            :instructions instructions
            :user-messages user-messages
            :max-output-tokens max-output-tokens
            :web-search web-search
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
            :api-key api-key}
           callbacks))

        :else
        (on-error {:message (format "ECA Unsupported model %s for provider %s" real-model provider)}))
      (catch Exception e
        (on-error {:exception e})))))

(defn sync-or-async-prompt!
  [{:keys [provider model model-capabilities instructions user-messages config on-first-response-received
           on-message-received on-error on-prepare-tool-call on-tools-called on-reason on-usage-updated
           past-messages tools provider-auth]
    :or {on-first-response-received identity
         on-message-received identity
         on-error identity
         on-prepare-tool-call identity
         on-tools-called identity
         on-reason identity
         on-usage-updated identity}}]
  (let [first-response-received* (atom false)
        emit-first-message-fn (fn [& args]
                                (when-not @first-response-received*
                                  (reset! first-response-received* true)
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
        on-error-wrapper (fn [{:keys [exception] :as args}]
                           (when-not (:silent? (ex-data exception))
                             (logger/error args)
                             (on-error args)))
        provider-config (get-in config [:providers provider])
        model-config (get-in provider-config [:models model])
        extra-payload (:extraPayload model-config)
        stream? (if (not (nil? (:stream extra-payload)))
                  (:stream extra-payload)
                  true)]
    (if (not stream?)
      (loop [result (prompt!
                     {:sync? true
                      :provider provider
                      :model model
                      :model-capabilities model-capabilities
                      :instructions instructions
                      :tools tools
                      :provider-auth provider-auth
                      :past-messages past-messages
                      :user-messages user-messages
                      :on-error on-error-wrapper
                      :config config})]
        (let [{:keys [error output-text reason-text reasoning-content tools-to-call call-tools-fn reason-id usage]} result]
          (if error
            (on-error-wrapper error)
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
                (on-message-received-wrapper {:type :finish :finish-reason "stop"}))))))
      (prompt!
       {:sync? false
        :provider provider
        :model model
        :model-capabilities model-capabilities
        :instructions instructions
        :tools tools
        :provider-auth provider-auth
        :past-messages past-messages
        :user-messages user-messages
        :on-message-received on-message-received-wrapper
        :on-prepare-tool-call on-prepare-tool-call-wrapper
        :on-tools-called on-tools-called
        :on-usage-updated on-usage-updated
        :on-reason on-reason-wrapper
        :on-error on-error-wrapper
        :config config}))))

(defn sync-prompt!
  [{:keys [provider model model-capabilities instructions
           prompt past-messages user-messages config tools provider-auth]}]
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
    :config config
    :on-error (fn [error] {:error error})}))
