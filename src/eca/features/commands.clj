(ns eca.features.commands
  (:require
   [clojure.string :as string]
   [eca.features.context :as f.context]
   [eca.features.prompt :as f.prompt]
   [eca.logger :as logger]
   [eca.modules :as modules]
   [eca.modules.custom-commands :as m.custom-commands]
   [eca.modules.mcp :as m.mcp]
   [eca.modules.skills :as m.skills]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private built-in-command-order
  "The order in which clients list the built-in commands."
  ["init" "login" "model" "agent" "skills" "skill-create" "costs" "quota" "context" "approvals"
   "forget-approvals" "compact" "fork" "btw" "resume" "chats" "delete-chat" "export" "import" "remote"
   "config" "doctor" "debug-chat" "repo-map-show" "rules" "prompt-show" "sync-system-prompt" "subagents"
   "plugins" "plugin-install" "plugin-update" "plugin-uninstall" "hooks" "modules"])

(defn ^:private module-commands
  "The active modules' commands, in the order clients list them: the built-in
   ones in `built-in-command-order`, then the others in module start order,
   the ones modules register while they run last. Each is
   `{:name :full-id :entry :command}`. `:name` is the command name, or its
   full id `<prefix>:<name>` when several modules have that name; the first
   module keeps a full id."
  [config]
  (let [order (zipmap built-in-command-order (range))
        commands (->> (concat (for [[entry module-commands] (modules/contributions config :commands)
                                    [command-name command] module-commands]
                                [entry command-name command])
                              (modules/registrations config :commands))
                      (map (fn [[entry command-name command]]
                             {:name command-name
                              :full-id (str (modules/prefix entry) ":" command-name)
                              :entry entry
                              :command command}))
                      (reduce (fn [{:keys [seen] :as acc} {:keys [full-id entry] :as command}]
                                (if (contains? seen full-id)
                                  (do (logger/warn "[commands]" (format "Module %s has a command %s, like another module; ignoring it"
                                                                      (:id entry) full-id))
                                      acc)
                                  (-> acc
                                      (update :seen conj full-id)
                                      (update :commands conj command))))
                              {:seen #{} :commands []})
                      :commands)
        name-counts (frequencies (map :name commands))]
    (->> commands
         (map #(cond-> % (> (name-counts (:name %)) 1) (assoc :name (:full-id %))))
         (sort-by #(get order (:name %) Integer/MAX_VALUE)))))

(defn ^:private find-module-command
  "The module command typed as `command-name`: its name or its full id."
  [command-name config]
  (some #(when (or (= command-name (:name %)) (= command-name (:full-id %))) %)
        (module-commands config)))

(defn find-command
  "The command `command-name` names, as `all-commands` lists it, also when
   typed by its full id (e.g. `eca:compact`); nil when no module has it."
  [command-name config]
  (when-let [{:keys [name entry command]} (find-module-command command-name config)]
    {:name name
     :type (if (:built-in? entry) :native :module)
     :description (:description command)
     :arguments (or (:arguments command) [])}))

(defn all-commands [db config]
  (let [mcp-prompts (->> (when (modules/active? config :eca/mcp) (m.mcp/all-prompts db))
                         (mapv #(-> %
                                    (assoc :name (str (:server %) ":" (:name %))
                                           :type :mcpPrompt)
                                    (dissoc :server))))
        eca-commands (map (fn [{:keys [name entry command]}]
                            {:name name
                             :type (if (:built-in? entry) :native :module)
                             :description (:description command)
                             :arguments (or (:arguments command) [])})
                          (module-commands config))
        custom-cmds (when (modules/active? config :eca/custom-commands)
                      (map (fn [custom]
                             {:name (:name custom)
                              :type :custom-prompt
                              :description (or (:description custom) (:path custom))
                              :arguments (:arguments custom)})
                           (m.custom-commands/all config (:workspace-folders db))))
        skills-cmds (when (modules/active? config :eca/skills)
                      (->> (m.skills/all config (:workspace-folders db))
                           (mapv (fn [skill]
                                   {:name (:name skill)
                                    :type :skill
                                    :description (:description skill)
                                    :arguments (:arguments skill)}))))]
    (concat mcp-prompts
            eca-commands
            skills-cmds
            custom-cmds)))

(defn ^:private substitute-args [content args]
  (let [named (shared/named-arg-names content)]
    (if (seq named)
      (shared/safe-selmer-render content
                                 (zipmap (map keyword named) args)
                                 "custom-command")
      (let [args-joined (string/join " " args)
            content-with-args (-> content
                                  (string/replace "$ARGS" args-joined)
                                  (string/replace "$ARGUMENTS" args-joined))]
        (reduce (fn [c [i arg]]
                  (-> c
                      (string/replace (str "$ARG" (inc i)) arg)
                      (string/replace (str "$" (inc i)) arg)))
                content-with-args
                (map-indexed vector args))))))

(defn ^:private get-custom-command [command args custom-cmds]
  (when-let [raw-content (:content (first (filter #(= command (:name %))
                                                  custom-cmds)))]
    (substitute-args raw-content args)))

(defn ^:private file-context-arg [arg]
  (when (and (string/starts-with? arg "@")
             (< 1 (count arg)))
    (f.context/parse-context-reference (subs arg 1))))

(defn ^:private add-file-context [prompt args db]
  (if-let [contexts-str (some-> (keep file-context-arg args)
                                (f.context/raw-contexts->refined db)
                                seq
                                (f.prompt/contexts-str nil nil))]
    [{:type :text :text prompt}
     {:type :text :text contexts-str}]
    prompt))

(defn ^:private custom-or-skill-command
  "A custom command or a skill typed as a command."
  [command args {:keys [db* config]}]
  (let [db @db*
        custom-cmds (when (modules/active? config :eca/custom-commands)
                      (m.custom-commands/all config (:workspace-folders db)))
        skills (when (modules/active? config :eca/skills)
                 (m.skills/all config (:workspace-folders db)))]
    (if-let [custom-command-prompt (get-custom-command command args custom-cmds)]
      {:type :send-prompt
       :prompt (add-file-context custom-command-prompt args db)}
      (if-let [skill (first (filter #(= command (:name %)) skills))]
        {:type :send-prompt
         :prompt (cond
                   ;; a module's skill may build its body when loaded, via
                   ;; the `eca__skill` tool; the slash form just nudges the
                   ;; LLM to load it.
                   (modules/fn-like? (:body skill))
                   (str "Load skill: " (:name skill))

                   (seq args)
                   (add-file-context (substitute-args (:body skill) args) args db)

                   :else
                   (str "Load skill: " (:name skill)))}
        {:type :text
         :text (str "Unknown command: " command)}))))

(defn ^:private command-args
  "A module command's `args`: its declared arguments by name, `:raw`, the text
   after the command, and `:argv`, the typed arguments."
  [{:keys [arguments]} argv message]
  (merge (zipmap (map (comp keyword :name) arguments) argv)
         {:argv (vec argv)
          :raw (or (some-> message (string/replace-first #"^\s*/\S+\s*" "") string/trim)
                   (string/join " " argv))}))

(defn ^:private command-result
  "A module command's result as chat.clj handles it. A string is shown as a
   system message, `{:prompt ..}` sent as the user's message and nil does
   nothing more; built-in commands may return typed results."
  [result entry chat-id]
  (cond
    (string? result) {:type :chat-messages
                      :chats {chat-id {:messages [{:role "system" :content [{:type :text :text result}]}]}}}
    (and (:built-in? entry) (map? result) (:type result)) result
    (and (map? result) (contains? result :prompt)) {:type :send-prompt :prompt (:prompt result)}
    :else {:type :new-chat-status :status :idle}))

(defn handle-command!
  "Runs the command typed as `/command args`: a module's command, else a
   custom command or a skill."
  [command args {:keys [chat-id config message] :as chat-ctx}]
  (if-let [{:keys [entry] module-command :command} (find-module-command command config)]
    (let [ctx (modules/module-ctx chat-ctx entry)
          args (command-args module-command args message)
          handler (:handler module-command)]
      (command-result (if (:built-in? entry)
                        (handler ctx args)
                        (modules/invoke ctx entry (str "command " command) {} handler ctx args))
                      entry
                      chat-id))
    (custom-or-skill-command command args chat-ctx)))
