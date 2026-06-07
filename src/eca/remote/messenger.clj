(ns eca.remote.messenger
  "BroadcastMessenger wraps an inner IMessenger (typically ServerMessenger)
   and broadcasts events to all connected SSE clients."
  (:require
   [eca.messenger :as messenger]
   [eca.remote.sse :as sse]
   [eca.shared :as shared]))

(set! *warn-on-reflection* true)

(defn- ->camel [data]
  (shared/map->camel-cased-map data))

(defrecord BroadcastMessenger [inner sse-connections* pending-questions*]
  messenger/IMessenger

  (chat-content-received [_this data]
    (messenger/chat-content-received inner data)
    (sse/broadcast! sse-connections* "chat:content-received" (->camel data)))

  (chat-cleared [_this params]
    (messenger/chat-cleared inner params)
    (sse/broadcast! sse-connections* "chat:cleared" (->camel params)))

  (chat-status-changed [_this params]
    (messenger/chat-status-changed inner params)
    (sse/broadcast! sse-connections* "chat:status-changed" (->camel params)))

  (chat-deleted [_this params]
    (messenger/chat-deleted inner params)
    (sse/broadcast! sse-connections* "chat:deleted" (->camel params)))

  (chat-opened [_this params]
    (messenger/chat-opened inner params)
    (sse/broadcast! sse-connections* "chat:opened" (->camel params)))

  (rewrite-content-received [_this data]
    (messenger/rewrite-content-received inner data))

  (tool-server-updated [_this params]
    (messenger/tool-server-updated inner params)
    (sse/broadcast! sse-connections* "tool:server-updated" (->camel params)))

  (tool-server-removed [_this params]
    (messenger/tool-server-removed inner params)
    (sse/broadcast! sse-connections* "tool:server-removed" (->camel params)))

  (provider-updated [_this params]
    (messenger/provider-updated inner params)
    (sse/broadcast! sse-connections* "providers:updated" (->camel params)))

  (jobs-updated [_this params]
    (messenger/jobs-updated inner params)
    (sse/broadcast! sse-connections* "jobs:updated" (->camel params)))

  (config-updated [_this params]
    (messenger/config-updated inner params)
    (sse/broadcast! sse-connections* "config:updated" (->camel params)))

  (showMessage [_this msg]
    (messenger/showMessage inner msg)
    (sse/broadcast! sse-connections* "session:message" (->camel msg)))

  (progress [_this params]
    (messenger/progress inner params)
    (sse/broadcast! sse-connections* "session:progress" (->camel params)))

  (editor-diagnostics [_this uri]
    (messenger/editor-diagnostics inner uri))
  (ask-question [_this params]
    ;; No SSE clients: fall back to inner so JSON-RPC editor sessions work
    ;; unchanged. Otherwise reuse the caller-supplied :request-id (set by
    ;; ask_user to match the id in tool-call state) and route the answer via
    ;; SSE + /api/v1/answer.
    (if (empty? @sse-connections*)
      (messenger/ask-question inner params)
      (let [request-id (or (:request-id params) (str (random-uuid)))
            p (promise)
            wire-params (-> params (dissoc :request-id) (assoc :requestId request-id))]
        (swap! pending-questions* assoc request-id p)
        (sse/broadcast! sse-connections* "chat:ask-question" (->camel wire-params))
        p))))

(defn make-broadcast-messenger
  "Creates a BroadcastMessenger with a fresh pending-questions registry.
   Prefer this over `->BroadcastMessenger` so callers don't have to know
   about the internal registry atom."
  [inner sse-connections*]
  (->BroadcastMessenger inner sse-connections* (atom {})))

(defn answer-question!
  "Resolves a pending question (previously registered by `ask-question`) by
   request-id. Delivers `{:answer answer :cancelled (boolean cancelled)}` to
   the registered promise and removes the entry from the registry.
   Returns true if a pending question was found and delivered, nil otherwise
   (e.g. unknown or already-answered request-id).

   Uses `swap-vals!` so that claiming the entry is a single atomic op:
   under concurrent answer-question! calls for the same request-id only the
   caller that wins the swap observes the entry in `old` and delivers."
  [{:keys [pending-questions*]} request-id answer cancelled]
  (let [[old _new] (swap-vals! pending-questions* dissoc request-id)]
    (when-let [p (get old request-id)]
      (deliver p {:answer answer :cancelled (boolean cancelled)})
      true)))
