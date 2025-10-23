(ns mcp-server-sample.mcp
  (:require
   [mcp-server-sample.util :refer [log]])
  (:import
   [io.modelcontextprotocol.server McpServerFeatures$SyncPromptSpecification]
   [io.modelcontextprotocol.spec
    McpSchema$GetPromptResult
    McpSchema$Prompt
    McpSchema$PromptArgument
    McpSchema$PromptMessage
    McpSchema$Role
    McpSchema$TextContent]
   [java.util.function BiFunction]))

(defn create-prompt-specification
  [{:keys [name description arguments handler]}]
  (let [prompt-args (mapv (fn [{:keys [name description required]}]
                            (McpSchema$PromptArgument. name description required))
                          arguments)
        prompt (McpSchema$Prompt. name
                                  description
                                  prompt-args)
        handler (reify BiFunction
                  (apply [_this _exchange args]
                    (let [args-map (if (and args (.arguments args))
                                     (into {} (.arguments args))
                                     {})
                          result (try
                                   (let [content-text (handler args-map)
                                         text-content (McpSchema$TextContent. content-text)
                                         message (McpSchema$PromptMessage. McpSchema$Role/USER text-content)
                                         messages [message]]
                                     (McpSchema$GetPromptResult. nil messages))
                                   (catch Exception e
                                     (McpSchema$GetPromptResult. (.getMessage e) [])))]
                      (log (str "Prompt called: " name " with arguments " (pr-str args-map)))

                      result)))]
    (McpServerFeatures$SyncPromptSpecification. prompt handler)))
