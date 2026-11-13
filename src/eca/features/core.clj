(ns eca.features.core
  "The `:eca/core` module: what stays in core (protocol, persistence, config,
   the chat loop and the module runtime), as a module so its config keys,
   events and commands are listed like any other module's. It can't be
   disabled."
  (:require
   [eca.features.built-in-command :as built-in-command]
   [eca.modules.events :as events]))

(set! *warn-on-reflection* true)

(def module
  {:id :eca/core
   :description "Chats, config, models and the module runtime."
   :config-spec {:modules {:type :map
                           :doc "Per module id: enabled, source and priority."}
                 :disabledTools {:type :array :default []}
                 :toolCall {:outputTruncation {:type :map :default {:lines 2000 :sizeKb 50}}}}
   :defines {:events events/core-events}
   :commands (built-in-command/commands
              [["login" "Log into a provider (Ex: /login github-copilot)" [{:name "provider-id"}]]
               ["model" "Select model for current chat (Ex: /model anthropic/claude-sonnet-4-6)" [{:name "full-model"}]]
               ["costs" "Total costs of the current chat session." []]
               ["quota" "Subscription quota usage and reset times (Claude Pro/Max, ChatGPT, GitHub Copilot)." []]
               ["context" "Context window usage breakdown for the current chat." []]
               ["compact" "Summarize the chat so far cleaning previous chat history to reduce context."
                [{:name "additional-input"}]]
               ["fork" "Fork current chat into a new chat with the same history and settings." []]
               ["btw" "Ask a side question in a forked chat, keeping this chat history clean (Ex: /btw how does X work?)"
                [{:name "prompt" :required true}]]
               ["resume" "Resume the specified chat-id. Blank to list chats or 'latest'." [{:name "chat-id"}]]
               ["chats" "List chats from all workspaces to find where a chat lives (Ex: /chats, /chats <title-filter>)"
                [{:name "title-filter"}]]
               ["delete-chat" (str "Permanently delete the current chat or a chat-id from the /resume list, asking "
                                   "confirmation. (Ex: /delete-chat, /delete-chat 2)")
                [{:name "chat-id"}]]
               ["export" (str "Export the current chat to a file or directory to transfer/import it elsewhere. "
                              "Ex: /export /tmp/chat.edn or /export .")
                [{:name "filepath"}]]
               ["import" "Import a chat from a file as a new resumable chat. Ex: /import /tmp/chat.edn" [{:name "filepath"}]]
               ["remote" "Show remote server connection details." []]
               ["config" "Show ECA config for troubleshooting." []]
               ["doctor" "Check ECA details for troubleshooting." []]
               ["debug-chat" "Dump the current chat (obfuscated) to a file for debugging. Ex: /debug-chat /tmp/out.edn"
                [{:name "filepath"}]]
               ["prompt-show" "Prompt sent to LLM as system instructions." [{:name "optional-prompt"}]]
               ["sync-system-prompt" (str "Re-sync the system prompt (AGENTS.md, rules, skills) on the next message, "
                                          "invalidating the chat prompt cache.")
                []]
               ["modules" "List modules; enable, disable or reload one (Ex: /modules disable eca/mcp)."
                [{:name "action"} {:name "module-id"}]]])})
