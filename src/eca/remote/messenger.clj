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

(defrecord BroadcastMessenger [inner sse-connections*]
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

  (rewrite-content-received [_this data]
    (messenger/rewrite-content-received inner data))

  (tool-server-updated [_this params]
    (messenger/tool-server-updated inner params)
    (sse/broadcast! sse-connections* "tool:server-updated" (->camel params)))

  (config-updated [_this params]
    (messenger/config-updated inner params)
    (sse/broadcast! sse-connections* "config:updated" (->camel params)))

  (showMessage [_this msg]
    (messenger/showMessage inner msg)
    (sse/broadcast! sse-connections* "session:message" (->camel msg)))

  (editor-diagnostics [_this uri]
    (messenger/editor-diagnostics inner uri)))
