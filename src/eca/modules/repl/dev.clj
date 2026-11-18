(ns eca.modules.repl.dev
  "What the nREPL of `:eca/repl` offers besides `eca.api`, as the `eca.repl`
   namespace: a ctx to call `eca.api` with, the modules, installing one from
   its map and reloading or removing one, read-only views of ECA's state,
   and tracing a chat's module events. It works only in an evaluation of
   that nREPL, so modules can't depend on it. Changes take effect at once,
   for this session."
  (:require
   [eca.config :as config]
   [eca.features.tools :as f.tools]
   [eca.logger :as logger]
   [eca.modules :as modules]
   [eca.modules.events :as events]
   [eca.modules.loader :as loader]
   [sci.core :as sci])
  (:import
   [java.io Writer]))

(set! *warn-on-reflection* true)

(def ^:private ^:dynamic *connection*
  "The nREPL client evaluating code, which `:eca/repl` binds for each
   evaluation: `{:send! (fn [writer line]) :traced (atom {chat-id tracer})}`.
   `send!` queues a line for the client's own thread, so a chat never waits
   on its socket."
  nil)

(defn ^:private connection []
  (or *connection*
      (throw (ex-info "eca.repl works only in the nREPL of the eca/repl module" {}))))

(defn ^:private session
  "The session's components and current config."
  []
  (connection)
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
        (f.tools/refresh-module-tool-servers! db* messenger config)
        (catch Exception e
          (logger/warn "[REPL]" "Could not send the tools to the client:" e))))))

(defn ctx
  "A ctx on the running ECA, to call `eca.api` with, e.g.
   `(eca/notify! (ctx) \"Hi\")`. Options: `:module`, a module id, to act as
   that module (its state and chat state); `:chat-id`, to act in that chat
   (`eca/chat`, `eca/send-prompt!`...)."
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
   :running? :failures :errors}`."
  []
  (let [[_ config] (session)]
    (mapv #(select-keys % [:id :status :reason :source :running? :failures :errors])
          (modules/statuses config))))

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
  "Removes module `id`. Another module, e.g. one from a file, only turns off
   for this session: remove its file, or its `modules.<id>.source`, to remove
   it for good. Returns nil."
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
    (loader/reload! id config @(:db* components))
    (modules/restart! components (second (session)) id)
    (apply-changes!)
    (status-of (second (session)) id)))

;; --- ECA's state, read-only

(defn db
  "ECA's whole state, as a snapshot: chats, models, MCP clients, auth..."
  []
  @(:db* (first (session))))

(defn chat-raw
  "Chat `chat-id` as ECA keeps it, as a snapshot: its messages, tool calls,
   usage... nil when there's no such chat."
  [chat-id]
  (get-in (db) [:chats chat-id]))

(defn config-raw
  "The config from every source over the active modules' defaults, before
   modules change it with `:config/resolve`; `eca.api/config` gives the
   result. Read again on each call."
  []
  (config/merged-layers {:workspace-folders (:workspace-folders (db))}))

;; --- Tracing

(def ^:private chat-keys
  "Keys every chat event has, left out of the trace lines."
  #{:chat-id :agent :model :variant :parent-chat-id})

(def ^:private max-trace-line-length 2000)

(defn ^:private changes
  "What the handlers did to `event`: the answer of a handle event, else the
   keys whose values changed."
  [kind event result]
  (if (= :handle kind)
    result
    (when (map? result)
      (not-empty (into {}
                       (keep (fn [[k v]]
                               (when (not= v (get event k))
                                 [(get {::events/ended-by :ended-by ::events/by :by} k k) v])))
                       result)))))

(defn ^:private trace-line [chat-id event-id kind event result]
  (let [line (binding [*print-length* 10
                       *print-level* 4]
               (str "[trace " chat-id "] " event-id " " (pr-str (apply dissoc event chat-keys))
                    (let [c (changes kind event result)]
                      (cond
                        (some? c) (str " => " (pr-str c))
                        (= :handle kind) " => no answer"
                        :else nil))))]
    (if (> (count line) max-trace-line-length)
      (str (subs line 0 max-trace-line-length) "…")
      line)))

(defn trace!
  "Prints each module event of chat `chat-id`, and what its handlers changed,
   to this REPL, until `untrace!` or the REPL disconnects. Events nobody
   handles may not be sent, e.g. `:llm/before-request`; lines are dropped
   when the REPL can't keep up."
  [chat-id]
  (session)
  (let [{:keys [send! traced]} (connection)
        ^Writer out @sci/out
        tracer (fn [event-id kind event result]
                 (send! out (str (trace-line chat-id event-id kind event result) "\n")))]
    (events/trace! chat-id tracer)
    (swap! traced assoc chat-id tracer)
    nil))

(defn untrace!
  "Stops tracing chat `chat-id`, or every chat."
  ([] (connection) (events/untrace-all!))
  ([chat-id] (connection) (events/trace! chat-id nil)))
