(ns eca.messenger
  "An interface for sending messages to a 'client',
   whether it's a editor or a no-op for test.")

(set! *warn-on-reflection* true)

(defprotocol IMessenger
  (chat-content-received [this data])
  (rewrite-content-received [this data])
  (tool-server-updated [this params])
  (config-updated [this params])
  (showMessage [this msg])
  (editor-diagnostics [this uri]))
