(ns eca.remote.messenger
  "BroadcastMessenger wraps an inner IMessenger (typically ServerMessenger)
   and broadcasts events to all connected SSE clients."
  (:require
   [eca.messenger :as messenger]
   [eca.remote.sse :as sse]))

(set! *warn-on-reflection* true)

(defrecord BroadcastMessenger [inner sse-connections*]
  messenger/IMessenger

  (chat-content-received [_this data]
    (messenger/chat-content-received inner data)
    (sse/broadcast! sse-connections* "chat:content-received" data))

  (chat-cleared [_this params]
    (messenger/chat-cleared inner params)
    (sse/broadcast! sse-connections* "chat:cleared" params))

  (chat-status-changed [_this params]
    (messenger/chat-status-changed inner params)
    (sse/broadcast! sse-connections* "chat:status-changed" params))

  (chat-deleted [_this params]
    (messenger/chat-deleted inner params)
    (sse/broadcast! sse-connections* "chat:deleted" params))

  (rewrite-content-received [_this data]
    (messenger/rewrite-content-received inner data))

  (tool-server-updated [_this params]
    (messenger/tool-server-updated inner params)
    (sse/broadcast! sse-connections* "tool:server-updated" params))

  (config-updated [_this params]
    (messenger/config-updated inner params)
    (sse/broadcast! sse-connections* "config:updated" params))

  (showMessage [_this msg]
    (messenger/showMessage inner msg)
    (sse/broadcast! sse-connections* "session:message" msg))

  (editor-diagnostics [_this uri]
    (messenger/editor-diagnostics inner uri)))
