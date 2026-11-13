(ns eca.api
  "The module API: what modules call, built-in and external alike. External
   modules, interpreted with SCI, see only the `eca.api*` namespaces of ECA.

   Every function that needs one takes first the `ctx` ECA passed to the
   module's function (`:start`, a tool or command handler, an event
   handler...). `ctx` is opaque: what it carries depends on where the
   function runs. Within one module API version (`:api` 1) this namespace
   only grows."
  (:require
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
