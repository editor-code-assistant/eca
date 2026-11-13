(ns eca.api.debug
  "Read-only views of ECA's internals, for a REPL on a running ECA (the
   `:eca/repl` module). They work only while `:eca/repl` runs, so modules
   can't depend on them."
  (:require
   [eca.config :as config]
   [eca.modules :as modules]
   [eca.modules.events :as events]
   [sci.core :as sci])
  (:import
   [java.io Writer]))

(set! *warn-on-reflection* true)

(defn ^:private db* []
  (when-not (modules/running? :eca/repl)
    (throw (ex-info "eca.api.debug works only while the eca/repl module runs" {})))
  (or (:db* (modules/components))
      (throw (ex-info "ECA's session hasn't started" {}))))

(defn db
  "ECA's whole state, as a snapshot: chats, models, MCP clients, auth..."
  []
  @(db*))

(defn chat-raw
  "Chat `chat-id` as ECA keeps it, as a snapshot: its messages, tool calls,
   usage... nil when there's no such chat."
  [chat-id]
  (get-in @(db*) [:chats chat-id]))

(defn config-raw
  "The config from every source over the active modules' defaults, before
   modules change it with `:config/resolve`; `eca.api/config` gives the
   result. Read again on each call."
  []
  (config/merged-layers {:workspace-folders (:workspace-folders @(db*))}))

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
                                 [(if (= ::events/ended-by k) :ended-by k) v])))
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
   to the output of the REPL that called this, until `untrace!`. Events
   nobody handles may not be sent, e.g. `:llm/before-request`."
  [chat-id]
  (db*)
  (let [^Writer out @sci/out]
    (events/trace! chat-id
                   (fn [event-id kind event result]
                     (try
                       (locking out
                         (.write out (str (trace-line chat-id event-id kind event result) "\n"))
                         (.flush out))
                       (catch Exception _
                         ;; the REPL went away
                         (events/trace! chat-id nil)))))
    nil))

(defn untrace!
  "Stops tracing chat `chat-id`, or every chat."
  ([] (events/untrace-all!))
  ([chat-id] (events/trace! chat-id nil)))
