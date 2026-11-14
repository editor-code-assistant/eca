(ns eca.api.dev
  "For developing modules from a REPL on a running ECA: install a module
   from its map, reload one from its file, remove one, see them all, and get
   a ctx to call `eca.api` with. Changes take effect at once, for this
   session."
  (:require
   [eca.config :as config]
   [eca.logger :as logger]
   [eca.modules :as modules]))

(set! *warn-on-reflection* true)

(defn ^:private session
  "The session's components and current config."
  []
  (let [{:keys [db*] :as components} (modules/components)]
    (when-not db*
      (throw (ex-info "ECA's session hasn't started" {})))
    [components (config/all @db*)]))

(defn ^:private apply-changes!
  "Starts and stops modules to match, and sends the clients the tools."
  []
  (let [[{:keys [db* messenger] :as components}] (session)
        config (config/all @db*)]
    (modules/reconcile-if-started! components config)
    (when messenger
      (try
        ((requiring-resolve 'eca.features.tools/refresh-module-tool-servers!) db* messenger config)
        (catch Exception e
          (logger/warn "[MODULES]" "Could not send the tools to the client:" e))))))

(defn ctx
  "A ctx on the running ECA, to call `eca.api` from a REPL, e.g.
   `(eca/notify! (ctx) \"Hi\")`. Options: `:module`, a module id, to act as
   that module (its state, chat state and registrations); `:chat-id`, to act
   in that chat (`eca/chat`, `eca/send-prompt!`...)."
  ([] (ctx {}))
  ([{:keys [module chat-id]}]
   (let [[{:keys [db*] :as components}] (session)]
     (when (and (some? module) (not (qualified-keyword? module)))
       (throw (ex-info ":module must be a module id, a qualified keyword like :acme/jira" {:module module})))
     (when (and (some? chat-id) (not (get-in @db* [:chats chat-id])))
       (throw (ex-info (str "There's no chat " (pr-str chat-id)) {:chat-id chat-id})))
     (cond-> (select-keys components [:db* :messenger :metrics])
       module (assoc :module-id module)
       chat-id (assoc :chat-id chat-id)))))

(defn ^:private repl-source [id]
  (str "repl:" (symbol id)))

(defn ^:private status-of [config id]
  (when-let [entry (some #(when (= id (:id %)) %) (modules/statuses config))]
    (select-keys entry [:id :status :reason :source :running? :failures :errors])))

(defn modules
  "Every module, built-in and external: `{:id :status :reason :source
   :running? :failures :errors}`, with the `:tools` and `:commands` it
   registered while running."
  []
  (let [[_ config] (session)
        registered (fn [kind id]
                     (vec (for [[entry reg-name _] (modules/registrations config kind)
                                :when (= id (:id entry))]
                            reg-name)))]
    (vec (for [entry (modules/statuses config)]
           (-> entry
               (select-keys [:id :status :reason :source :running? :failures :errors])
               (assoc :tools (registered :tools (:id entry))
                      :commands (registered :commands (:id entry))))))))

(defn install!
  "Adds `module`, a module map, to the running ECA and starts it, as if it
   was loaded from a file; it wins over a file module with the same id.
   Installing an id again replaces the module. Returns its status."
  [module]
  (when-not (and (map? module) (qualified-keyword? (:id module)))
    (throw (ex-info "A module is a map with a qualified keyword :id" {})))
  (session)
  (modules/add-external! (repl-source (:id module)) module {:kind :repl :rank -1})
  (apply-changes!)
  (status-of (second (session)) (:id module)))

(defn uninstall!
  "Removes module `id`. A module from a file only turns off for this
   session: remove its file, or its `modules.<id>.source`, to remove it for
   good. Returns nil."
  [id]
  (session)
  (if (contains? (modules/external-modules) (repl-source id))
    (modules/remove-external! (repl-source id))
    (modules/set-enabled! id false))
  (apply-changes!)
  nil)

(defn reload!
  "Loads module `id` again from its file and restarts it; a module `install!`
   added restarts. Returns its status."
  [id]
  (let [[components config] (session)]
    ((requiring-resolve 'eca.modules.loader/reload!) id config @(:db* components))
    (modules/restart! components (second (session)) id)
    (apply-changes!)
    (status-of (second (session)) id)))

(defn sci-ctx
  "The SCI context external modules run in, for a built-in module's REPL
   (`:eca/repl`); nil for any other module."
  [ctx]
  (let [config (or (:config ctx) (some-> (:db* ctx) deref config/all))]
    (when (and (map? config)
               (:built-in? (modules/active-entry config (:module-id ctx))))
      ((requiring-resolve 'eca.modules.loader/sci-context)))))
