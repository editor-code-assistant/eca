(ns eca.features.commands
  (:require
   [clojure.string :as string]
   [eca.features.context :as f.context]
   [eca.features.prompt :as f.prompt]
   [eca.logger :as logger]
   [eca.modules :as modules]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(def ^:private built-in-command-order
  "The order in which clients list the built-in commands."
  ["init" "login" "model" "agent" "skills" "skill-create" "costs" "quota" "context" "approvals"
   "forget-approvals" "compact" "fork" "btw" "resume" "chats" "delete-chat" "export" "import" "remote"
   "config" "doctor" "debug-chat" "repo-map-show" "rules" "prompt-show" "sync-system-prompt" "subagents"
   "plugins" "plugin-install" "plugin-update" "plugin-uninstall" "hooks" "modules"])

(defn ^:private command-entries
  "`[entry command-name command]` for each command of the active modules, in
   start order, then the ones modules register while they run. A module's
   `:commands` is a map, or (built-ins) a function of ctx, `:config` and
   `:db`, giving `[command-name command]` pairs in order."
  [db config]
  (concat (for [[entry commands] (modules/contributions config :commands)
                [command-name command] (if (modules/fn-like? commands)
                                         (commands (modules/module-ctx {:config config :db db} entry))
                                         commands)]
            [entry command-name command])
          (modules/registrations config :commands)))

(defn ^:private source-entry?
  "Whether a built-in module gives the command for one of the sources
   clients tell apart by its `:client-type`: `:mcpPrompt`, `:skill` or
   `:custom-prompt`."
  [[entry _command-name command]]
  (boolean (and (:built-in? entry) (:client-type command))))

(defn ^:private own-commands
  "The modules' own commands, in the order clients list them: the built-in
   ones in `built-in-command-order`, then the others in module start order,
   the ones modules register while they run last. Each is
   `{:name :full-id :entry :command}`. `:name` is the command name, or its
   full id `<prefix>:<name>` when several modules have that name; the first
   module keeps a full id."
  [entries]
  (let [order (zipmap built-in-command-order (range))
        commands (->> entries
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

(defn ^:private module-commands
  "The active modules' commands: `:own`, as `own-commands` lists them, and
   `:sources`, the ones built-in modules give for MCP prompts, skills and
   custom commands, each `{:name :entry :command}`, in order."
  [db config]
  (let [entries (command-entries db config)]
    {:own (own-commands (remove source-entry? entries))
     :sources (for [[entry command-name command :as e] entries
                    :when (source-entry? e)]
                {:name command-name :entry entry :command command})}))

(defn ^:private find-own-command
  "The command of `own` typed as `command-name`: its name or its full id."
  [command-name own]
  (some #(when (or (= command-name (:name %)) (= command-name (:full-id %))) %)
        own))

(defn ^:private find-source-command
  "The command of `sources` typed as `command-name`, given for `client-type`."
  [command-name client-type sources]
  (some #(when (and (= command-name (:name %)) (= client-type (:client-type (:command %)))) %)
        sources))

(defn ^:private find-module-command
  "The module's own command typed as `command-name`: its name or its full id."
  [command-name db config]
  (find-own-command command-name (:own (module-commands db config))))

(defn ^:private own-client-command
  "A module's own command as clients see it."
  [{:keys [name entry command]}]
  {:name name
   :type (if (:built-in? entry) :native :module)
   :description (:description command)
   :arguments (or (:arguments command) [])})

(defn find-command
  "The command `command-name` names, as `all-commands` lists it, also when
   typed by its full id (e.g. `eca:compact`); nil when no module has it."
  [command-name db config]
  (some-> (find-module-command command-name db config) own-client-command))

(defn ^:private source-client-command
  "A command a built-in module gives for a source, as clients see it: its
   keys but the handler, with its `:client-type` as `:type`."
  [{:keys [name command]}]
  (-> command
      (dissoc :handler :client-type)
      (assoc :name name :type (:client-type command))))

(defn all-commands
  "The commands clients list: MCP prompts, the modules' own commands, skills,
   then custom commands."
  [db config]
  (let [{:keys [own sources]} (module-commands db config)
        sources-of (fn [client-type]
                     (for [source sources
                           :when (= client-type (:client-type (:command source)))]
                       (source-client-command source)))]
    (concat (sources-of :mcpPrompt)
            (map own-client-command own)
            (sources-of :skill)
            (sources-of :custom-prompt))))

(defn substitute-args
  "`content` with the typed `args` in its placeholders: its `{{name}}` ones
   by position when it has any, else `$ARGS` and `$ARGUMENTS` (all of them),
   `$ARGn` and `$n`."
  [content args]
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

(defn ^:private file-context-arg [arg]
  (when (and (string/starts-with? arg "@")
             (< 1 (count arg)))
    (f.context/parse-context-reference (subs arg 1))))

(defn add-file-context
  "`prompt`, followed by the files the `@path` args name, when there are any."
  [prompt args db]
  (if-let [contexts-str (some-> (keep file-context-arg args)
                                (f.context/raw-contexts->refined db)
                                seq
                                (f.prompt/contexts-str nil nil))]
    [{:type :text :text prompt}
     {:type :text :text contexts-str}]
    prompt))

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
  "Runs the command typed as `/command args`: a module's own command, else a
   custom command, else a skill. With `client-type` `:mcpPrompt`, the MCP
   prompt `command`."
  ([command args chat-ctx]
   (handle-command! command args chat-ctx nil))
  ([command args {:keys [chat-id config db* message] :as chat-ctx} client-type]
   (let [{:keys [own sources]} (module-commands (some-> db* deref) config)]
     (if-let [{:keys [entry] module-command :command} (if (= :mcpPrompt client-type)
                                                        (find-source-command command :mcpPrompt sources)
                                                        (or (find-own-command command own)
                                                            (find-source-command command :custom-prompt sources)
                                                            (find-source-command command :skill sources)))]
       (let [ctx (modules/module-ctx chat-ctx entry)
             args (command-args module-command args message)
             handler (:handler module-command)]
         (command-result (if (:built-in? entry)
                           (handler ctx args)
                           (modules/invoke ctx entry (str "command " command) {} handler ctx args))
                         entry
                         chat-id))
       (let [text (str "Unknown command: " command)]
         ;; an MCP prompt may be gone since it was typed, its server stopping:
         ;; say so and end the prompt, as when the server can't give it
         (if (= :mcpPrompt client-type)
           {:type :system-text :text text}
           {:type :text :text text}))))))
