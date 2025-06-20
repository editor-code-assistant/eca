(ns eca.db)

(defonce initial-db
  {:client-info {}
   :workspace-folders []
   :client-capabilities {}
   :chats []
   :chat-behaviors ["agent" "chat"]
   :chat-default-behavior "agent"
   :models ["o4-mini"
            "gpt-4.1"
            "claude-sonnet-4-0"
            "claude-opus-4-0"
            "claude-3-5-haiku-latest"
            "gemini-2.5-pro"
            "gemini-2.5-flash"
            "gemini-2.5-flash-lite-preview-06-17"] ;; + ollama local models
   :default-model "o4-mini" ;; unless a ollama model is running.
   })

(defonce db* (atom initial-db))
