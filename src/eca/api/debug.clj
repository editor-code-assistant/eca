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

(def ^:private ^:dynamic *repl-connection*
  "The nREPL client evaluating code, which `:eca/repl` binds for each one:
   `{:send! (fn [writer line]) :traced (atom {chat-id tracer})}`. `send!`
   queues a line for the client's own thread, so a chat never waits on its
   socket."
  nil)

(defn trace!
  "Prints each module event of chat `chat-id`, and what its handlers changed,
   to this REPL, until `untrace!` or the REPL disconnects. Only in the nREPL
   of `:eca/repl`. Events nobody handles may not be sent, e.g.
   `:llm/before-request`; lines are dropped when the REPL can't keep up."
  [chat-id]
  (db*)
  (let [{:keys [send! traced]} (or *repl-connection*
                                   (throw (ex-info "trace! works only in the nREPL of the eca/repl module" {})))
        ^Writer out @sci/out
        tracer (fn [event-id kind event result]
                 (send! out (str (trace-line chat-id event-id kind event result) "\n")))]
    (events/trace! chat-id tracer)
    (swap! traced assoc chat-id tracer)
    nil))

(defn untrace!
  "Stops tracing chat `chat-id`, or every chat."
  ([] (events/untrace-all!))
  ([chat-id] (events/trace! chat-id nil)))
