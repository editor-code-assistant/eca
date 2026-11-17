(ns eca.db
  "The in-memory state: its shape, the initial db and `db*`, and helpers on
   the chat tree and its remembered approvals. Saving and loading it:
   `eca.db.*`.")

(set! *warn-on-reflection* true)

(def ^:private _db-spec
  "Used for documentation only"
  {:client-info {:name :string
                 :version :string}
   :workspace-folders [{:name :string :uri :string}]
   :client-capabilities {:code-assistant {:editor {:diagnostics :boolean
                                                   :definition :boolean
                                                   :references :boolean}
                                          :chat-capabilities {:ask-question :boolean
                                                              :incremental-updates {:history-page-size :number}}}}
   :config-hash :string
   :providers-config-hash :string
   :last-config-notified ::any-map
   :stopping :boolean
   ;; chat ids deleted in this session; excluded from workspace cache writes so
   ;; the merge-on-write never resurrects them from a shared cache file.
   :deleted-chat-ids #{:string}
   ;; chats only their owner may prompt (spawn_agent for subagents); the owner
   ;; passes its :token as :owner-token, :workers counts unwinding prompt workers.
   :managed-chats {"<chat-id>" {:token ::object :workers :number :interrupted? :boolean}}
   :models {"<model-name>" {:web-search :boolean
                            :tools :boolean
                            :reason? :boolean
                            :image-input? :boolean
                            :max-output-tokens :number
                            :model-name :string ;; real model name used for requests
                            :limit {:context :number :output :number}
                            :input-token-cost (or :number nil)
                            :output-token-cost (or :number nil)
                            :input-cache-read-token-cost (or :number nil)
                            :input-cache-creation-token-cost (or :number nil)}}
   :mcp-clients {"<client-id>" {:client :McpSyncClient
                                :status (or :requires-auth :starting :running :failed :stopping :stopped)
                                :version :string
                                :tools [{:name :string
                                         :description :string
                                         :parameters ::any-map}]
                                :prompts [{:name :string
                                           :description :string
                                           :arguments [{:name :string
                                                        :description :string
                                                        :required :boolean}]}]
                                :resources [{:uri :string
                                             :name :string
                                             :description :string
                                             :mime-type :string}]}}
   ;; In-memory chats: entries loaded from the chats index start "index-only"
   ;; (marked :index-only?, no :messages, plus precomputed :message-count/
   ;; :user-message-count/:flags) until hydrate-chat! loads their per-chat
   ;; cache file. Live chats never carry the marker: their memory state is
   ;; authoritative and never overwritten from disk.
   :chats {"<chat-id>" {:id :string
                        :title (or :string nil)
                        :title-custom? :boolean ;; user manually renamed the chat
                        :status (or :idle :running :stopping :login)
                        :created-at :number
                        :updated-at :number
                        :login-provider :string
                        :model :string ;; last full model id used for this chat, e.g. "anthropic/claude-sonnet-4-6"
                        :last-api :keyword
                        :trust :boolean
                        :kind (or :inline nil) ;; nil for regular chats
                        :prompt-id :uuid
                        :user-prompt-count :number
                        :subagent :boolean
                        :parent-chat-id :string
                        :startup-context :string
                        :prompt-cache ::any-map
                        ;; each module's state in the chat (eca.api/swap-chat-state!),
                        ;; plain data saved with the chat and copied by forks
                        :modules {:<module-id> ::any}
                        ;; added by modules for the next request (eca.api/add-context!)
                        :module-contexts [{:module :keyword :content :string :data ::any}]
                        ;; a compaction a module asked for (eca.api/request-compaction!), run
                        ;; before the next LLM request; none after one was blocked in a prompt
                        :compaction-request {:module :keyword :trigger :keyword :instructions :string}
                        :compaction-blocked-prompt-id :uuid
                        ;; Context-area files already delivered in the
                        ;; user message history: context key -> content sha-256. #683
                        :sent-attached-contexts {"<context-key>" :string}
                        :messages [{:role (or "user" "assistant" "tool_call" "tool_call_output" "reason" "compact_marker" "flag" "server_tool_use" "server_tool_result")
                                    :content (or :string [::any-map]) ;; string for simple text, map/vector for structured content
                                    :content-id :string
                                    :created-at :number}]
                        :task {:next-id :number
                               :active-summary (or :string nil)
                               :tasks [{:id :number
                                        :subject :string
                                        :description :string
                                        :status (or :pending :in-progress :done)
                                        :priority (or :high :medium :low)
                                        :blocked-by #{:number}}]}
                        ;; "approve & remember" approvals, in memory only (never
                        ;; persisted). Only root chats carry them: subagents use
                        ;; their root chat's, see `remembered-approvals`.
                        :remembered-approvals {"<server__tool>" {:remember-to-approve? :boolean
                                                                 :remembered-command-keys #{:string}}}
                        ;; Reasoning blocks currently shown as one block in the
                        ;; client, in memory only (never persisted), see
                        ;; `eca.features.chat.lifecycle/send-content!`.
                        :reason-group {:id :string
                                       :total-time-ms :number
                                       :open {"<reason-id>" :number}
                                       :text? :boolean
                                       :last-text-block-id :string}
                        :tool-calls {"<tool-call-id>"
                                     {:status (or :initial :preparing :check-approval :waiting-approval
                                                  :execution-approved :executing :rejected :cleanup
                                                  :completed :stopping)

                                      :name :string
                                      :full-name :string
                                      :server :string
                                      :origin (or :native :mcp)
                                      :arguments ::any-map
                                      :decision-reason {:code :keyword :text :string}
                                      :approved?* :promise
                                      :future-cleanup-complete?* :promise
                                      :start-time :long
                                      :future :future
                                      :resources ::any-map
                                      :rollback-changes [{:path :string
                                                          :content (or :string nil)}]}}}}
   :auth {"<provider-name>" {:step (or :login/start :login/waiting-login-method
                                       :login/waiting-provider-code :login/waiting-api-key
                                       :login/waiting-user-confirmation :login/done :login/renew-token)
                             :type (or :auth/token :auth/oauth nil)
                             :mode (or :manual :console :max nil)
                             :api-key :string
                             :access-token :string
                             :refresh-token :string
                             :expires-at :long
                             :verifier :string
                             :device-code :string}}
   :mcp-auth {"<mcp-server-name>" {:type :auth/oauth
                                   :access-token :string
                                   :refresh-token :string
                                   :expires-at :long}}
   ;; Subscription quota per provider, in memory only (never persisted),
   ;; see `eca.features.quota`.
   :provider-quotas {"<provider-name>" {:updated-at :long
                                        :windows [{:id :string
                                                   :label :string
                                                   :used-percent :number
                                                   :resets-at :long
                                                   :window-minutes :long}]}}})

(defn parent-chat-id [db chat-id]
  (get-in db [:chats chat-id :parent-chat-id]))

(defn resolve-trust
  "Resolve the effective trust for `chat-id`: the chat's own `:trust` when set,
   otherwise the nearest ancestor's `:trust` by walking up `:parent-chat-id`.
   Returns nil when no chat in the chain has trust set. This lets trust toggled
   on a parent chat reach already-running subagents (#504)."
  [db chat-id]
  (loop [id chat-id
         seen #{}]
    (when (and id (not (contains? seen id)))
      (let [chat (get-in db [:chats id])]
        (if (some? (:trust chat))
          (:trust chat)
          (recur (:parent-chat-id chat) (conj seen id)))))))

(defn root-chat-id
  "Resolve the top-level chat of `chat-id` by walking up `:parent-chat-id`
   while the parent chat exists, so a subagent resolves to the chat that
   spawned it. Returns `chat-id` itself for top-level chats."
  [db chat-id]
  (loop [id chat-id
         seen #{chat-id}]
    (let [parent-id (get-in db [:chats id :parent-chat-id])]
      (if (and parent-id
               (not (contains? seen parent-id))
               (contains? (:chats db) parent-id))
        (recur parent-id (conj seen parent-id))
        id))))

(defn remembered-approvals
  "Tool calls approved with \"approve & remember\" in `chat-id`'s chat session,
   keyed by full tool name. They live in the root chat (see `root-chat-id`), so
   subagents share the approvals of the chat that spawned them. #639"
  [db chat-id]
  (get-in db [:chats (root-chat-id db chat-id) :remembered-approvals]))

(defn update-remembered-approvals
  "Returns `db` with `f` applied to the remembered approvals of `chat-id`'s chat
   session (see `remembered-approvals`), dropping the key when they end up
   empty. No-op when the chat does not exist, so a late approval never seeds a
   phantom chat entry."
  [db chat-id f & args]
  (let [root-id (root-chat-id db chat-id)]
    (if (contains? (:chats db) root-id)
      (let [approvals (apply f (get-in db [:chats root-id :remembered-approvals]) args)]
        (if (seq approvals)
          (assoc-in db [:chats root-id :remembered-approvals] approvals)
          (update-in db [:chats root-id] dissoc :remembered-approvals)))
      db)))

(defonce initial-db
  {:client-info {}
   :workspace-folders []
   :client-capabilities {}
   :config-hash nil
   :providers-config-hash nil
   :last-config-notified {}
   :stopping false
   :models {}
   :mcp-clients {}
   ;; Chat ids deleted in this session (not cached), see _db-spec.
   :deleted-chat-ids #{}
   ;; Chats only their owner may prompt (not cached), see _db-spec.
   :managed-chats {}

   ;; cacheable; bump `chats-version` when changing :chats shape, `version`
   ;; when changing :auth/:mcp-auth shape
   :chats {}
   :auth {"anthropic" {}
          "azure" {}
          "deepseek" {}
          "github-copilot" {}
          "google" {}
          "litellm" {}
          "lmstudio" {}
          "mistral" {}
          "moonshot" {}
          "openai" {}
          "openrouter" {}
          "z-ai" {}}
   :mcp-auth {}})

(defonce db* (atom initial-db))
