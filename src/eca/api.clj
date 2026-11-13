(ns eca.api
  "The module API: what modules call, built-in and external alike. External
   modules, interpreted with SCI, see only the `eca.api*` namespaces of ECA.

   Every function that needs one takes first the `ctx` ECA passed to the
   module's function (`:start`, a tool or command handler, an event
   handler...). `ctx` is opaque: what it carries depends on where the
   function runs. Within one module API version (`:api` 1) this namespace
   only grows."
  (:require
   [clojure.string :as string]
   [eca.config :as config]
   [eca.logger :as logger]
   [eca.modules :as modules]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn ^:private db [ctx]
  (or (some-> (:db* ctx) deref) (:db ctx)))

(defn module-id
  "The id of the module this `ctx` was given to, e.g. `:acme/jira`."
  [ctx]
  (:module-id ctx))

(defn config
  "ECA's config, with defaults applied: ECA's keys and every module's, e.g.
   `(:jiraUrl (config ctx))`. Keys are keywords, at the paths users write
   them, e.g. `(get-in (config ctx) [:toolCall :readFile :maxLines])`."
  [ctx]
  (or (:config ctx)
      (some-> (:db* ctx) deref config/all)))

(defn state
  "The module's state: the value its `:start` returned, or nil."
  [ctx]
  (modules/state (:module-id ctx)))

(defn workspaces
  "The workspace folders, as absolute paths."
  [ctx]
  (mapv (comp shared/uri->filename :uri) (:workspace-folders (db ctx))))

(defn log
  "Writes to ECA's log, on stderr, tagged with the module id. `level` is
   `:debug`, `:info`, `:warn` or `:error`; `args` are joined like `str` with
   spaces, and an exception among them is logged with its stack trace."
  [ctx level & args]
  (let [log-fn (case level
                 :error logger/error
                 :warn logger/warn
                 :debug logger/debug
                 logger/info)
        chat-id (:chat-id ctx)
        parent-chat-id (or (:parent-chat-id ctx)
                           (when chat-id (get-in (db ctx) [:chats chat-id :parent-chat-id])))]
    (logger/with-chat-context chat-id parent-chat-id
      (apply log-fn (str "[MODULE " (some-> (:module-id ctx) symbol) "]") args))
    nil))

(defn ^:private owner
  "The id of the module `ctx` belongs to; registrations need one."
  [ctx]
  (or (:module-id ctx)
      (throw (ex-info "This ctx belongs to no module" {}))))

(defn ^:private tools-changed!
  "Sends the clients the tools again, when ECA runs a session."
  [ctx]
  (when-let [db* (:db* ctx)]
    (when-let [messenger (:messenger ctx)]
      (try
        ((requiring-resolve 'eca.features.tools/refresh-module-tool-servers!) db* messenger (config ctx))
        (catch Exception e
          (logger/warn "[MODULES]" "Could not send the tools to the client:" e))))))

(defn ^:private check!
  "Throws when the entry named `entry-name` has problems, or its name doesn't
   match `name-re`."
  [what entry-name name-re problems]
  (let [problems (cond-> (vec problems)
                   (not (and (string? entry-name) (re-matches name-re entry-name)))
                   (conj (str "the name must be a string matching " name-re)))]
    (when (seq problems)
      (throw (ex-info (format "Invalid %s %s: %s" what (pr-str entry-name) (string/join "; " problems))
                      {:name entry-name :problems problems})))))

(defn register-tool!
  "Adds a tool while the module runs, like an entry of its `:tools`: named
   `<prefix>__<tool-name>`, removed when the module stops. Registering a name
   again replaces the tool."
  [ctx tool-name tool]
  (check! "tool" tool-name #"[A-Za-z0-9_-]+" (modules/tool-problems tool))
  (modules/register! (owner ctx) :tools tool-name tool)
  (tools-changed! ctx)
  nil)

(defn unregister-tool!
  "Removes a tool `register-tool!` added."
  [ctx tool-name]
  (modules/unregister! (owner ctx) :tools tool-name)
  (tools-changed! ctx)
  nil)

(defn register-command!
  "Adds a slash command while the module runs, like an entry of its
   `:commands`: `/<command-name>`, or `/<prefix>:<command-name>`. Removed
   when the module stops."
  [ctx command-name command]
  (check! "command" command-name #"[A-Za-z0-9_.-]+" (modules/command-problems command))
  (modules/register! (owner ctx) :commands command-name command)
  nil)

(defn unregister-command!
  "Removes a command `register-command!` added."
  [ctx command-name]
  (modules/unregister! (owner ctx) :commands command-name)
  nil)

(defn text
  "A tool result holding the text `s`."
  [s]
  {:error false
   :contents [{:type :text :text (str s)}]})

(defn error
  "A tool result that reports an error, with the text `s`."
  [s]
  {:error true
   :contents [{:type :text :text (str s)}]})
