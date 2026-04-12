(ns eca.messenger
  "An interface for sending messages to a 'client',
   whether it's a editor or a no-op for test.")

(set! *warn-on-reflection* true)

(defprotocol IMessenger
  (chat-content-received [this data])
  (chat-cleared [this params])
  (chat-status-changed [this params])
  (chat-deleted [this params])
  (chat-opened [this params])
  (rewrite-content-received [this data])
  (tool-server-updated [this params])
  (config-updated [this params])
  (provider-updated [this params])
  (jobs-updated [this params])
  (showMessage [this msg])
  (progress [this params])
  (editor-diagnostics [this uri]))
