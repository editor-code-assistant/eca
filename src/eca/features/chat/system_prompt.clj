(ns eca.features.chat.system-prompt
  "The chat's system prompt between turns: the cache signature that says
   when it changed, and the notices about it."
  (:require
   [clojure.string :as string]
   [eca.cache :as cache]
   [eca.digest :as digest]
   [eca.features.chat.lifecycle :as f.chat.lifecycle]
   [eca.features.prompt :as f.prompt]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn prompt-cache-key
  "Builds a provider-agnostic prompt cache key.
   OpenAI's Responses API sends it as `prompt_cache_key`; other providers
   currently ignore it. Scoping by agent prevents cache hits across
   agent switches within the same user session."
  [agent]
  (str (System/getProperty "user.name") "@ECA"
       (when (not-empty agent) (str "/" agent))))

(defn static-prompt-cache-signature
  "Per-component SHA-256 cache identity for static prompt reuse.
   Returns a map of category -> hash so mid-chat instruction changes
   can be attributed to what changed (prompt, contexts, rules, skills, tools,
   and modules: the static sections those don't follow, see
   `f.prompt/uncategorized-static-sections`). The modules category is only
   there when such sections are, so the signature of a chat with the
   built-in sections stays as it was."
  [refined-contexts static-rules path-scoped-rules skills repo-map* agent config chat-id all-tools db]
  (let [static-contexts (vec (filter f.prompt/static-prompt-context? refined-contexts))
        sha (comp digest/sha-256-hex pr-str)
        uncategorized (f.prompt/uncategorized-static-sections refined-contexts static-rules path-scoped-rules skills
                                                              repo-map* agent config chat-id all-tools db)]
    (cond-> {:prompt (sha {:agent agent
                           :chat-prompt-template (f.prompt/eca-chat-prompt agent config chat-id db)
                           :environment {:os-name (str (System/getProperty "os.name") " " (System/getProperty "os.version"))
                                         :shell (or (System/getenv "SHELL") (System/getenv "ComSpec"))
                                         :user-name (System/getProperty "user.name")
                                         :home-dir (cache/user-home)}
                           :is-subagent (boolean (get-in db [:chats chat-id :subagent]))
                           :startup-context (get-in db [:chats chat-id :startup-context])})
             :contexts (sha {:workspace-roots (mapv (comp shared/uri->filename :uri) (:workspace-folders db))
                             :static-contexts static-contexts})
             :rules (sha {:static-rules (mapv #(select-keys % [:id :name :scope :content]) static-rules)
                          :path-scoped-rules (mapv #(select-keys % [:id :name :scope :workspace-root :paths :enforce]) path-scoped-rules)})
             :skills (sha (mapv #(select-keys % [:name :description]) skills))
             ;; Deferred tools are tracked separately because they render a catalog into
             ;; the static prompt, unlike normal tools whose schemas are sent per turn.
             :tools (sha {:names (sort (map :full-name all-tools))
                          :deferrable (sort (map :full-name (filter :deferrable all-tools)))})}
      uncategorized (assoc :modules (sha uncategorized)))))

(defn changed-system-prompt-categories
  "Names of system prompt categories that changed vs the cached signature;
   a category on one side only (modules) counts as changed.
   Empty when the old signature has an unknown (legacy) shape."
  [prompt-cache static-signature agent full-model]
  (let [old-sig (:static-signature prompt-cache)]
    (cond-> []
      (not= (:agent prompt-cache) agent) (conj "agent")
      (not= (:model prompt-cache) full-model) (conj "model")
      (map? old-sig) (into (keep (fn [k]
                                   (when (not= (get static-signature k) (get old-sig k))
                                     (name k))))
                           (distinct (concat (keys static-signature) (keys old-sig)))))))

(defn send-system-prompt-changed-notice!
  [chat-ctx categories suffix]
  (f.chat.lifecycle/send-content!
   chat-ctx :system
   {:type :text
    :text (str "\nSystem prompt changed"
               (when (seq categories)
                 (str " (" (string/join ", " categories) ")"))
               suffix)}))

(defn pinned-system-prompt-changed-text
  "Notice for a pinned chat whose system prompt inputs drifted.
   Tools are special: the tool schemas sent to the LLM are recomputed every
   turn, so tool changes (e.g. a MCP server connected/disconnected) already
   apply to the current chat, only the system prompt text referencing them
   is kept."
  [categories]
  (let [tools-changed? (boolean (some #{"tools"} categories))
        tools-only? (= ["tools"] categories)]
    (cond
      tools-only?
      "Tools changed and already apply to this chat, keeping current chat system prompt text.\n"

      tools-changed?
      (str "System prompt changed (" (string/join ", " categories)
           "), keeping current chat system prompt, changes will apply to new chats (tools already apply). Use /sync-system-prompt to apply now.\n")

      :else
      (str "System prompt changed"
           (when (seq categories)
             (str " (" (string/join ", " categories) ")"))
           ", keeping current chat system prompt, changes will apply to new chats. Use /sync-system-prompt to apply now.\n"))))
