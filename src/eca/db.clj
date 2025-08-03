(ns eca.db)

(set! *warn-on-reflection* true)

(def ^:private one-million 1000000)

(defonce initial-db
  {:client-info {}
   :workspace-folders []
   :client-capabilities {}
   :chats {}
   :chat-behaviors ["agent" "chat"]
   :chat-default-behavior "agent"
   :models {"o4-mini" {:tools true
                       :web-search false
                       :reason? true
                       :input-token-cost (/ 1.10 one-million)
                       :output-token-cost (/ 4.40 one-million)}
            "o3" {:tools true
                  :web-search false
                  :reason? true
                  :input-token-cost (/ 2.0 one-million)
                  :output-token-cost (/ 8.0 one-million)}
            "gpt-4.1" {:tools true
                       :web-search true
                       :reason? false
                       :max-output-tokens 32000
                       :input-token-cost (/ 2.0 one-million)
                       :output-token-cost (/ 8.0 one-million)}
            "claude-sonnet-4-0" {:tools true
                                 :web-search true
                                 :max-output-tokens 8196
                                 :reason? true
                                 :reason-tokens 2048
                                 :input-token-cost (/ 3.0 one-million)
                                 :input-cache-creation-token-cost (/ 3.75 one-million)
                                 :input-cache-read-token-cost (/ 0.3 one-million)
                                 :output-token-cost (/ 15.0 one-million)}
            "claude-opus-4-0" {:tools true
                               :web-search true
                               :max-output-tokens 8196
                               :reason? true
                               :reason-tokens 2048
                               :input-token-cost (/ 15.0 one-million)
                               :input-cache-creation-token-cost (/ 18.75 one-million)
                               :input-cache-read-token-cost (/ 1.5 one-million)
                               :output-token-cost (/ 75.0 one-million)}
            "claude-3-5-haiku-latest" {:tools true
                                       :web-search true
                                       :reason? false
                                       :max-output-tokens 4096
                                       :input-token-cost (/ 0.8 one-million)
                                       :input-cache-creation-token-cost (/ 1.0 one-million)
                                       :input-cache-read-token-cost (/ 0.08 one-million)
                                       :output-token-cost (/ 4.0 one-million)}

            ;; TODO: finalize these!
            "gemini-2.5-pro" {:tools true
                              :web-search true
                              :reason? true
                              :max-output-tokens 32000
                              :input-token-cost (/ 1.0 one-million)
                              :output-token-cost (/ 4.0 one-million)}
            "gemini-2.5-flash" {:tools true
                                :web-search true
                                :reason? false
                                :max-output-tokens 32000
                                :input-token-cost (/ 1.0 one-million)
                                :output-token-cost (/ 4.0 one-million)}

            ;; + ollama local models + custom provider models
            }
   :mcp-clients {}})

(defonce db* (atom initial-db))
