---
description: "ECA modules: every ECA feature is a built-in module. Turn modules on or off, configure them, or write your own in Clojure to extend ECA."
---

# Modules

All ECA features are built-in modules: tools, agents, MCP, providers, hooks and more. Modules are how you extend ECA and configure how a feature works.

You can turn any module off, and write your own in Clojure. ECA runs them with [SCI](https://github.com/babashka/sci), a Clojure interpreter, so there's nothing to build or install.

!!! tip

    Ask ECA to write a module for you: its built-in `eca-manage` skill tells it how.

??? info "All built-in modules"

    Each module links to its code.

    | Module | What it does |
    |--------|--------------|
    | **Core** | |
    | [`eca/core`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/core.clj) | Chats, config, models and the module runtime. It can't be turned off. |
    | **Tools** | |
    | [`eca/filesystem`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/tools/filesystem.clj) | Read, write, edit, move and search files. |
    | [`eca/shell`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/tools/shell.clj) | Run shell commands and background jobs. |
    | [`eca/git`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/tools/git.clj) | Run git commands. |
    | [`eca/editor`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/tools/editor.clj) | Diagnostics, definitions and references from the editor. |
    | [`eca/ask-user`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/tools/ask_user.clj) | Let the model ask you a question. |
    | [`eca/task`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/tools/task.clj) | A task list the model keeps for each chat. |
    | **Agents** | |
    | [`eca/agents`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/agents.clj) | Agents from config and Markdown files, and choosing one for each chat. |
    | [`eca/subagents`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/subagents.clj) | Let an agent hand a task to a subagent and get its answer. |
    | [`eca/agent-code`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/agents/agent_code.clj) | The code agent, for coding tasks. The default agent. |
    | [`eca/agent-plan`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/agents/agent_plan.clj) | The plan agent: plans changes without making them. |
    | [`eca/subagent-explorer`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/subagents/subagent_explorer.clj) | The explorer subagent: finds and reads code without changing it. |
    | [`eca/subagent-general`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/subagents/subagent_general.clj) | The general subagent: research and multi-step tasks. |
    | **Skills, rules and MCP** | |
    | [`eca/skills`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/skills.clj) | Skills: `SKILL.md` folders the model loads when it needs them. |
    | [`eca/rules`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/rules.clj) | Rules: global, project and path-scoped. |
    | [`eca/agents-md`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/agents_md.clj) | AGENTS.md files: the workspaces', their parents' and the global one, in every chat. |
    | [`eca/mcp`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/mcp.clj) | MCP servers: their tools, prompts, resources and instructions. |
    | [`eca/tool-search`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/tools/tool_search.clj) | Hides MCP tools behind `search_tools` when there are too many. |
    | **Your extensions** | |
    | [`eca/custom-tools`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/tools/custom_tools.clj) | Tools from the `customTools` config: shell command templates. |
    | [`eca/custom-commands`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/custom_commands.clj) | Commands from Markdown files and the `commands` config. |
    | [`eca/hooks`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/hooks.clj) | Your shell hooks. |
    | [`eca/plugins`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/plugins.clj) | Plugin marketplaces: install, update and remove bundles. |
    | **Chat** | |
    | [`eca/approval`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/approval.clj) | Tool approval from the `toolCall.approval` rules. |
    | [`eca/chat-management`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/chat_management.clj) | Fork chats, ask side questions, and resume, list, delete, export and import chats. |
    | [`eca/chat-title`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/chat_title.clj) | The chat model titles each chat, and titles it again at its third request. |
    | [`eca/compact-as-summary`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/compact_as_summary.clj) | When the context fills up, the chat model summarizes the chat. |
    | [`eca/manage`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/manage.clj) | Set up, debug and change ECA: `/init`, `/skill-create`, and the `eca-info` and `eca-manage` skills. |
    | **Editor features** | |
    | [`eca/completion`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/completion.clj) | Inline code completion, as you type. |
    | [`eca/rewrite`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/rewrite.clj) | Rewrite the selected code with the model. |
    | **LLM APIs** | |
    | [`eca/api-anthropic`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/anthropic.clj) | The Anthropic Messages API. |
    | [`eca/api-openai-responses`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/openai.clj) | The OpenAI Responses API. |
    | [`eca/api-openai-chat`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/openai_chat.clj) | The OpenAI Chat Completions API, which OpenAI-compatible providers speak. |
    | [`eca/api-ollama`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/ollama.clj) | The Ollama chat API. |
    | [`eca/api-bedrock`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/bedrock.clj) | The AWS Bedrock Converse API. |
    | **Providers** | |
    | [`eca/llm-providers`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/llm_providers.clj) | Logging into providers, with `/login` or the providers panel, and their subscription quotas (`/quota`). Requests don't need it. |
    | [`eca/anthropic`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/anthropic.clj) | Claude models from Anthropic: a Max subscription, a Console key or a pasted key. |
    | [`eca/azure`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/azure.clj) | Azure OpenAI, at your resource URL. |
    | [`eca/bedrock`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/bedrock.clj) | Models on AWS Bedrock, with a bearer token. |
    | [`eca/copilot`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/copilot.clj) | GitHub Copilot models, with your account's model list and quota. |
    | [`eca/deepseek`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/deepseek.clj) | DeepSeek models. |
    | [`eca/google`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/google.clj) | Gemini models, through Google's OpenAI-compatible API. |
    | [`eca/litellm`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/litellm.clj) | A LiteLLM proxy, at your URL. |
    | [`eca/lmstudio`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/lmstudio.clj) | Local models through LM Studio. |
    | [`eca/mistral`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/mistral.clj) | Mistral models. |
    | [`eca/moonshot`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/moonshot.clj) | Moonshot's Kimi models. |
    | [`eca/ollama`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/ollama.clj) | Local models through Ollama. |
    | [`eca/openai`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/openai.clj) | GPT models from OpenAI: a ChatGPT subscription or a pasted key. |
    | [`eca/openrouter`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/openrouter.clj) | Models from many vendors, through OpenRouter. |
    | [`eca/z-ai`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/z_ai.clj) | Z.ai's GLM models, through its Anthropic-compatible API. |
    | **Off by default** | |
    | [`eca/remote`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/remote.clj) | The [remote](remote.md) server, for web clients like web.eca.dev. `remote.enabled` turns it on. |
    | [`eca/otel`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/otel.clj) | Exports ECA's [metrics](metrics.md) with OpenTelemetry. An `otlp` config turns it on. |
    | [`eca/repl`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/repl.clj) | An nREPL inside ECA. See [A REPL on ECA](#a-repl-on-eca). |

## Managing modules

`/modules` lists all modules, whether they're active and what they add.

```
/modules disable eca/mcp
/modules enable eca/mcp
/modules reload acme/jira
```

These changes last for the session. To keep them, set them in your config:

```javascript title="~/.config/eca/config.json"
{
  "modules": {
    "eca/mcp": {"enabled": false},
    "acme/jira": {"source": "~/dev/eca-jira/jira.clj"}
  }
}
```

- `enabled` turns a module on or off. Modules that require it turn off with it.
- `source` loads an external module from a file or folder.
- `priority` orders the module's event handlers, higher first. It's `0` by default, and `-100` for built-in modules. A handler's own `:priority` wins over it.

## Writing a module

A module is a Clojure namespace that defines a `module` map. Save this file and ECA loads it in a few seconds, then again every time you save it:

```clojure title="~/.config/eca/modules/hello.clj"
(ns me.hello)

(def module
  {:id :me/hello
   :description "Greets people."
   :tools {"greet" {:description "Greet someone by name."
                    :parameters {:type "object"
                                 :properties {"name" {:type "string"}}
                                 :required ["name"]}
                    :handler (fn [_ctx {:strs [name]}]
                               (str "Hello, " name "!"))}}
   :commands {"hello" {:description "Say hello."
                       :handler (fn [_ctx _args] "Hello from my module!")}}})
```

The model now has a `hello__greet` tool, and you have a `/hello` command. If the file has an error, `/modules` shows it, with its line.

ECA looks for modules in these places. When two define the same module id, the first one wins:

1. `eca server --module <path>`, for one session.
2. `modules.<id>.source` in your config.
3. `.eca/modules/` in each workspace.
4. `~/.config/eca/modules/`.
5. The `modules/` folder of installed [plugins](plugins.md).

A module can also be a folder with a `module.clj`. Its other namespaces load from the same folder, like `acme.jira.client` from `acme/jira/client.clj`.

!!! note "Trust"

    Modules run with ECA's permissions. A workspace's modules load without asking, the same way its hooks run.

### Config

A module declares the config keys it uses in `:config-spec`. Users set them at the root of the config, like ECA's own keys:

```clojure title="~/.config/eca/modules/jira.clj"
(ns acme.jira
  (:require [eca.api :as eca]))

(defn- fetch-issue [ctx key]
  (let [{:keys [jiraUrl jiraToken]} (eca/config ctx)]
    (:body (eca/http {:url (str jiraUrl "/rest/api/3/issue/" key)
                      :oauth-token jiraToken
                      :as :json}))))

(def module
  {:id :acme/jira
   :description "Jira issues."
   :config-spec {:jiraUrl {:type :string :doc "Jira base URL, e.g. https://acme.atlassian.net"}
                 :jiraToken {:type :string :secret? true :default "${env:JIRA_TOKEN}"}}
   :tools {"issue" {:description "Fetch a Jira issue: summary, description, status and comments."
                    :parameters {:type "object" :properties {"key" {:type "string"}} :required ["key"]}
                    :handler (fn [ctx {:strs [key]}] (pr-str (fetch-issue ctx key)))}}
   :commands {"jira" {:description "Start working on a Jira issue."
                      :arguments [{:name "key" :required true}]
                      :handler (fn [_ctx {:keys [key]}]
                                 {:prompt (str "Read Jira issue " key " with jira__issue, then implement it.")})}}})
```

```javascript title="~/.config/eca/config.json"
{
  "jiraUrl": "https://acme.atlassian.net"
}
```

- Types: `:string`, `:number`, `:boolean`, `:enum` (with `:values`), `:map` and `:array`.
- A map without `:type` groups keys: `{:autoVerify {:onEdit {:type :string}}}`.
- `:secret? true` masks the value when ECA shows the config.
- Entry names of a `:map` key stay strings, as in `mcpServers`.

ECA warns about config keys that neither it nor a loaded module knows, at startup and in `/doctor`.

### Tools

`:tools` maps each tool's name to its definition, or is a function `(fn [ctx])` that gives that map, for tools that depend on the chat: its ctx has `:config`, `:db`, `:chat-id` and `:agent`.

| Key | |
|-----|--|
| `:description` | Tells the model what the tool does: a string, or `(fn [ctx])`. |
| `:parameters` | The tool's arguments, as a JSON Schema. |
| `:handler` | `(fn [ctx args])`, with string keys in `args`. Returns a string, or `(eca/error s)` for an error. |
| `:enabled-fn` | Optional `(fn [ctx])`. The model gets the tool only when it returns true. |
| `:require-approval-fn` | Optional `(fn [ctx args])`. When it returns true, ECA asks before running the call, even if your config allows the tool. |
| `:summary-fn` | Optional `(fn [ctx args])`. The short text editors show for the call. |
| `:approval-keys-fn` | Optional `(fn [args])`. What "approve and remember" remembers, like each command of a shell call: `{:keys #{..} :complete? true}`. |
| `:normalize-arguments-fn` | Optional `(fn [args])`. The arguments as approval, hooks and the handler get them. |
| `:details-before-fn` | Optional `(fn [ctx args])`. Details editors show for the call before it runs, as the protocol's tool call details, like a file change's diff. |
| `:details-after-fn` | Optional `(fn [ctx args details result])`. The details once it ran; by default the details from before, else its JSON outputs. |
| `:replaces` | Optional. A built-in tool it takes over, like `"eca__read_file"`. |

A tool's full name is `<prefix>__<name>`: `jira__issue` above. The prefix is the name in the module's `:id`, unless the module sets `:prefix`; pick a distinctive one, since when two modules have a tool with the same full name, the first one keeps it. Use the full name in approval rules and `disabledTools`, or just `jira` for all the module's tools. Like any tool, ECA asks before running it unless your config allows it.

### Tool servers

A module whose tools come and go, as ECA's MCP support's do, serves them from tool servers instead of `:tools`:

```clojure
:tool-servers {:list (fn [_ctx]
                       [{:name "docs" :status "running"
                         :tools [{:name "lookup" :description "Look up the docs."
                                  :parameters {:type "object" :properties {"q" {:type "string"}}}}]}])
               :call (fn [_ctx _server _tool args _opts]
                       (str "Found " (get args "q")))}
```

| Key | |
|-----|--|
| `:list` | `(fn [ctx])`. The module's servers now: each with `:name`, `:status` and `:tools` (`:name`, `:description`, `:parameters`), maybe `:version`. ECA asks often, so keep it fast. A server or a tool without a `:name` is left out, with a warning. |
| `:call` | `(fn [ctx server tool args opts])`, with string keys in `args` and the calling chat's `:chat-id` in `opts`. Returns what a tool's handler returns; `(eca/progress! ctx text)` shows its progress. |
| `:await-refresh` | Optional `(fn [ctx timeout-ms])`. Blocks while a server updates its tools, `timeout-ms` at most. ECA waits after a batch of tool calls, so the model sees the new tools. |

A server's tools are named `<server>__<tool>`, like MCP tools, and work like them in approval rules, `disabledTools` and tool search. When two modules have a server with the same name, the first one to start keeps it. Clients get each server as one of the module's tool servers, with its status: at startup, when modules change, and when the tools' approval changes (another agent, a config change).

### Commands

`:commands` maps each command's name to its definition, or is a function `(fn [ctx])` that gives `[name command]` pairs, in the order clients list them; its ctx has `:config` and `:db`.

| Key | |
|-----|--|
| `:handler` | `(fn [ctx args])`. `args` has each declared argument as a keyword, plus `:raw`, the text after the command, and `:argv`, the typed arguments. |
| `:description` | Shown in the command list. |
| `:arguments` | Optional. `[{:name "key" :required true}]`. |
| `:client-type` | Optional. `:skill`, `:custom-prompt` or `:mcpPrompt`: clients list the command as one of those, as `eca/skills`, `eca/custom-commands` and `eca/mcp` do for theirs. |

The handler returns a string to show in the chat, `{:prompt "..."}` to send a message to the model as if you typed it, or `nil`. ECA's own commands also return typed results, maps with a `:type` (see [the keys only one ECA feature uses](#keys-only-one-eca-feature-uses)).

You type `/name`, or `/<prefix>:<name>` when two modules have a command with the same name.

### Protocol methods

`:rpc` adds methods to [ECA's protocol](../protocol.md), for editors and other clients:

```clojure
:rpc {"acme/status" {:kind :request
                     :handler (fn [_ctx params] {:ok true :echo params})}}
```

| Key | |
|-----|--|
| `:kind` | `:request`, answered with what the handler returns, or `:notification`. |
| `:handler` | `(fn [ctx params])`. It runs on a thread of its own. |

ECA's own methods win over a module's; when two modules have a method, the first one to start keeps it. A module's methods answer while it's active, also before it starts. Once it's disabled or has failed, they're gone, built-in modules' too: requests get "Method not found" and notifications are ignored. Disabling `eca/mcp` drops the `mcp/*` methods, `eca/llm-providers` the `providers/*` ones.

### Events

Event handlers let a module react to what ECA does, change it, or give an answer ECA asks for. This one rejects writes to `.env` files:

```clojure
(def module
  {:id :acme/guard
   :event-handlers
   {:tool/before-call {:match {:tool "eca__write_file"}
                       :handler (fn [_ctx {:keys [arguments]}]
                                  (when (re-find #"\.env$" (get arguments "path" ""))
                                    {:block "Writing .env files isn't allowed."}))}}})
```

There are three kinds of events:

- **notify**: something happened.
- **transform**: the handler can change the event, or stop it with `{:block "reason"}` or `{:stop "reason"}`.
- **handle**: the first handler that answers wins.

A handler is a function `(fn [ctx event])`, a vector of them, or a map with `:handler` and optional `:match`, `:priority` and `:timeout-ms`. `:match` filters events by key, with a value, a set or a regex. A changed event may name who acted for the module with `:by`, e.g. `"hook 'lint'"`, shown when it blocks or stops something. Your [shell hooks](hooks.md) are `eca/hooks`' handlers, at priority `-1000`, so they run after the other modules' handlers; when a module blocks or stops something, the hooks don't run. An external module's handlers run before the hooks and before `eca/approval` makes sure a deny from your config stays (`-900`): a lower priority runs right before those, in its order among the external handlers.

Every chat event also has `:chat-id`, `:agent`, `:model`, `:variant`, and `:parent-chat-id` in a subagent's chat.

| Event | Kind | Keys | Changes it accepts |
|-------|------|------|--------------------|
| `:session/started` | notify | `:workspaces` | — |
| `:session/ready` | notify | Once ECA finished starting, after `:session/started`: the client got the chat config and ECA's own tools. MCP servers start here. | — |
| `:session/ended` | notify | — | — |
| `:chat/input` | transform | `:text`, `:source` (`:user` or `:module`). Runs before commands and skills. | `:text`, `:handled` (a reply, or `true`: the model isn't called) |
| `:chat/started` | notify | `:resumed?`. Runs on a chat's first prompt. | `:additional-context` (added to the chat's startup context), `:stop` |
| `:chat/before-request` | transform | `:prompt`, what was typed for a command or an MCP prompt. Runs once per user message; for a compaction's request only in handlers with `:compaction? true`, the event having `:compaction? true`. | `:prompt` (not for commands and MCP prompts), `:additional-context`, `:block`, `:stop` |
| `:llm/before-request` | transform | `:request` (`:model`, `:instructions`, `:past-messages`, `:user-messages`, `:tools`), `:history`, `:since-checkpoint`, `:turn`, `:budget`. Runs before every LLM call of a chat, tool loop included. | `:request`'s messages, `:instructions` (not in the tool loop) and `:tools`. This changes what the model sees, not the chat history. `:model` can't change yet. |
| `:llm/response-headers` | notify | `:provider`, `:headers`. A response's headers, for a subscription login (OAuth). `eca/llm-providers` reads the quota from them. | — |
| `:llm/request-failed` | handle | `:request` (`:model`, `:user-messages`), `:error` (`:type` like `:context-overflow`, `:message`, `:status`). Runs after ECA's own retries; not again in the turn once a module answered `:retry`. | `{:retry true}` to send it again, or `{:compact {:trigger :instructions}}` to compact and resume, unless the turn can't compact again (see `request-compaction!`). Without an answer, ECA handles the error. `eca/compact-as-summary` answers a context overflow last. |
| `:chat/after-request` | notify | `:response` | `:follow-up` (sent as the next user message), `:stop` |
| `:chat/message-added` | notify | `:message` (`:role`, `:content`) | — |
| `:chat/title` | handle | `:messages` (the conversation, the new request's messages last), `:prompt-messages` (the new request's messages without the attached contexts), `:prompt-count` (the chat's requests, this one included), `:title` (nil when untitled). Asked in the background at every request, unless the user named the chat. | `{:title ".."}` titles the chat; nil keeps its title. `eca/chat-title` answers last: the first request, then the third. |
| `:chat/ended` | notify | `:chat-id`, `:agent`, `:model` | — |
| `:chat/status-changed` | notify | `:status`, `:pending-approval-ids`, `:running-tool-ids`... | — |
| `:chat/usage-updated` | notify | `:usage`, `:session-tokens`, `:context-limit`, `:context-used`. After each response, and when `/context` refreshes the usage. | `:auto-compact-percentage`: where the chat compacts on its own, in percent of the context limit, shown to clients |
| `:tool/before-call` | transform | `:tool` (full name), `:server`, `:arguments`, `:approval`, `:tool-call-id` | `:arguments`, `:approval`, `:reason`, `:block`, `:stop`, `:additional-context` (added to the call's output for the model) |
| `:tool/after-call` | transform | `:tool`, `:server`, `:arguments`, `:result`, `:error?`, `:tool-call-id`. Runs before the model, the editor and postToolCall hooks see the result. | `:result` (a result map or a string), `:additional-context` (for the model only), `:stop` (after this batch of calls) |
| `:tool/called` | transform | `:tool`, `:server`, `:arguments`, `:result`, `:error?`, `:tool-call-id`. Runs once the editor got the result, before the model sees it; postToolCall hooks run here. Not when a `:tool/after-call` handler stopped the turn. | `:result` (what the model sees of it: a result map's `:contents` or a string), `:additional-context`, `:stop` (after this batch of calls) |
| `:chat/next-round` | notify | Runs in the tool loop, after a response's tools ran, before the model gets their results. | `:notes`, the event's and yours: texts the chat's history gets first, each a user message, like `eca/shell`'s finished background jobs |
| `:chat/before-compact` | transform | `:trigger` (`:manual`, `:auto` or `:overflow`), `:instructions` | `:block` (skips the compaction), `:stop`, `:instructions` |
| `:chat/compact` | handle | `:trigger`, `:instructions`. `eca/compact-as-summary` answers last, with its summary. | `{:status :done :resume? true :message ".."}`, or `:status :failed`. Write the result with `checkpoint!`. An interrupted turn goes on unless `:resume? false`. Or `{:request ..}`: the chat's model compacts it, see [the keys only one ECA feature uses](#keys-only-one-eca-feature-uses). |
| `:chat/after-compact` | notify | `:trigger`, `:checkpoint` (`:marker`, `:messages`), `:summary` (its text). Runs after a checkpoint is written. | `:additional-context` (added to the summary), `:stop` |
| `:shell/before-exec` | transform | `:command`, `:cwd`, `:env`, `:timeout-ms`, `:background?`, `:source` (`:tool`, `:hook` or `:module`, named by `:tool`, `:hook` and `:hook-type`, or `:module`). Runs for every shell command ECA runs: shell, git and custom tools, shell hooks and `eca/shell!`. | `:command`, `:cwd`, `:env` |
| `:config/resolve` | transform | `:config`. Runs after all config layers merge; read the config from the event, not through `eca.api`. `eca/agents` merges the agents first, at priority `1000`. The ctx's `:db` has only `:workspace-folders`. | `:config`, except its `modules` key |
| `:config/changed` | notify | `:prev-config`, `:config`. When ECA sees the config change; `:prev-config` is nil the first time, a few seconds after startup. | — |
| `:models/resolve` | transform | `:models`: full model name → capabilities | `:models` |
| `:models/synced` | notify | The models were synced: at startup and when the providers' config changes, after renewing login tokens; not after a login or logout. `eca/llm-providers` fetches the subscription quotas then. | — |

`:additional-context` is a string, `{:text "..." :data {...}}` to keep plain data with it, or a list of them, one context each. A module's context goes to the model marked with the module's id, so `(eca/added-contexts ctx)` finds it again in the chat. It's for the model only: ECA keeps it out of the chat's title, of exports and of what the user sees, and the request a compaction resumes goes without it, so add what the new context window needs in `:chat/after-compact`.

`{:text "..." :label? false}` gives a context without the module's mark, which ECA keeps like the user's text, as the shell hooks' contexts.

### Module keys

| Key | |
|-----|--|
| `:id` | Required. A qualified keyword like `:acme/jira`. `:eca/*` is for built-in modules. |
| `:description`, `:version` | Shown in `/modules`. |
| `:requires` | Ids of modules it needs. It starts after them, and stays off without them. |
| `:default-enabled?` | `false` ships the module turned off. |
| `:config-spec` | The config keys it owns. See [Config](#config). |
| `:config-defaults` | Defaults for keys other modules own, like an approval rule for its tool: `{:toolCall {:approval {:allow {"jira__issue" {}}}}}`. A map, or `(fn [ctx])` giving one; its ctx has `:db`, with the workspaces. |
| `:start`, `:stop` | `(fn [ctx])`. What `:start` returns is the module's state, `(eca/state ctx)`. |
| `:tools`, `:commands` | See [Tools](#tools) and [Commands](#commands). |
| `:tool-servers` | Servers of tools that come and go. See [Tool servers](#tool-servers). |
| `:rpc` | Protocol methods. See [Protocol methods](#protocol-methods). |
| `:prefix` | Prefix of its tools and commands. The name in `:id` by default. |
| `:system-prompts` | Sections it adds to the system prompt: `[{:id :acme/conventions :after :eca/rules :content "..."}]`. `:content` is a string or `(fn [ctx])`. `:before` or `:after` place it next to another section; `:dynamic? true` is for content that changes every turn. |
| `:event-handlers` | `{event-id handler}`. See [Events](#events). |
| `:skills` | Skills it ships: `[{:name "review" :description ".." :body ".."}]`. `:body` is Markdown, or `(fn [ctx])` that gives it when the model loads the skill. Skills on disk win a name. |
| `:contexts` | `{:always (fn [ctx]) :types {"type" {:list (fn [ctx]) :refine (fn [ctx context])}}}`. `:always` gives contexts every chat gets first, like `eca/agents-md`'s AGENTS.md files. `:types` are `@` contexts: `:list` gives the items clients complete, `:refine` what the model gets for one, as `eca/mcp` does for MCP resources. |
| `:transform-tools` | `(fn [ctx tools])`. Changes the tools the model gets, after the config's filters, in module order, like `eca/tool-search` deferring MCP tools. |
| `:chat-statuses` | `{status (fn [ctx params])}`. While a chat is in that status, like `/login`'s `:login`, its prompts go to the function instead of the model. |
| `:providers`, `:apis` | Model providers and LLM APIs. See [Providers and LLM APIs](#providers-and-llm-apis). |

An unknown key is ignored, with a warning in `/modules`.

### Providers and LLM APIs

`:providers` maps a provider id to its declaration. Keys other than these are the provider's config, which `/login` saves when the user logs in by typing an API key, e.g. `{:api "openai-chat" :url "https://api.acme.ai/v1"}`.

| Key | |
|-----|--|
| `:label` | Its name for users, e.g. in the providers panel. |
| `:login` | How users log in: `{:ask [:api-key :url :models]}` asks for those fields; `{:methods [{:key :label :start}] :complete-oauth-code .. :steps {..}}` runs its own flows, as `eca/anthropic`, `eca/openai` and `eca/copilot` do. |
| `:models-fn` | `(fn [{:keys [api-url]}])`. The models it lists itself, `[{:model :tools :reason?}]`, like a running Ollama's. |
| `:models-override-fn` | `(fn [ctx])`. Its models from its own catalog instead of the generic `/models` fetch. |
| `:default-model` | `{:rank n :fn (fn [{:keys [db config]}])}`, or a vector of them: the default model it suggests when its login or key is there, lower ranks first. |
| `:api-fn` | `(fn [{:keys [provider model config]}])`. The API of a request, e.g. by model; else the `:api` of the provider's config. |
| `:request-opts` | `(fn [opts request])`. Its own options of a request to its API. |
| `:capabilities` | What models.dev doesn't tell: capability → model ids, e.g. `{:web-search #{"gpt-5"}}`. |
| `:quota` | `{:fetch (fn [ctx]) :headers (fn [headers])}`: a subscription login's quota. |
| `:errors` | `{:enrich :recoverable? :recover! :retry-after-ms}`, each `(fn [ctx])`: its error messages, recoveries and retry times. |

`:apis` maps an API name, like `"openai-chat"`, to `{:handler (fn [ctx request callbacks])}`. A provider's config names it with `:api`, any module's API: `"openai-chat"`, `"openai-responses"` (also `"openai"`), `"anthropic"`, `"ollama"` and `"bedrock"` are ECA's. The request and callbacks follow ECA's own code: see [ECA's APIs](https://github.com/editor-code-assistant/eca/tree/master/src/eca/modules/providers), e.g. `openai_chat.clj`.

### Keys only one ECA feature uses

These work for any module, as ECA's own code uses them; most modules won't need them.

| Key | |
|-----|--|
| `:signature`, `:resync?` of a section | What the rules and skills sections' prompt cache follows; other sections' are ignored. |
| `:compaction? true` of a handler | A `:chat/before-request` handler gets a compaction's request too. |
| `:cleanup-fn` of a tool | `(fn [resource-key resource])`: frees what a call left, like `eca/shell`'s background processes. |
| `:description-suffix` of a tool | Text kept after a `prompts.tools` override of its description, like `spawn_agent`'s agents. |
| `{:request {:prompt :done? :apply!}}`, a `:chat/compact` answer | The chat's model compacts it: `(prompt instructions tools)` is sent, `(done? ctx)` says when it's finished, then `(apply! ctx trigger)` writes the result, as `eca/compact-as-summary` does. |
| Typed command results | A command's map with a `:type`, as ECA's commands return: `:chat-messages`, `:send-prompt`, `:send-messages`, `:system-text`, `:new-chat-status`, `:delete-chat`, `:side-prompt`. A type ECA doesn't know ends the turn. |

## `eca.api`

Every function that needs it takes the module's `ctx` as its first argument.

| Area | Functions |
|------|-----------|
| Module | `config`, `state`, `module-id`, `client`, `log`, `workspaces` |
| Chat | `chat`, `messages` (`{:since-checkpoint? true}` for what the model sees), `chat-state` and `swap-chat-state!` (plain data saved with the chat, copied by forks and exports, dropped when the chat is cleared), `send-prompt!`, `add-context!` (for the next request; `{:data ..}` keeps plain data with it), `added-contexts` (the module's contexts in the chat, with their data), `checkpoint!` (`{:messages [..] :label ".." :data {..}}`: the model sees only what comes after it), `request-compaction!` (`{:trigger :instructions}`: compacts before the next LLM request), `cancel-compaction!` (drops the module's request) |
| User | `notify!`, `progress!` (in a tool), `confirm` (a question, or `{:title ..}`), `select`, `input`. Outside a chat, or with a client without dialogs, they don't ask: `false` or nil. |
| Tools | `tools`, `call-tool!` |
| LLM | `complete!`, `models`. `/costs` and `/modules` show each module's LLM calls. |
| Runtime | `background!`, `data-dir`, `shell!`, `http` (`{:url :method :headers :query-params :body :json :as}`, through ECA's HTTP client, with its proxy and TLS settings) |
| Results | `error`: a tool's error; a tool returns a string otherwise. |

`eca.api.test` has `mock-ctx`, `run-tool`, `run-command`, `run-event` and `recorded`, to test a module outside ECA's session.

For files and JSON, modules use `babashka.fs`, `cheshire.core`, `slurp` and `spit`, as in Clojure. Inside SCI, they can also use `clojure.string`, `clojure.set`, `clojure.walk` and `clojure.edn`, and Java interop on basic classes like `String`, `Integer`, `Double` and `Exception`. ECA's other namespaces aren't there.

## A REPL on ECA

The `eca/repl` module runs an nREPL inside ECA. Turn it on with `/modules enable eca/repl`, or with `"modules": {"eca/repl": {"enabled": true}}` in your config.

Each ECA picks a free port and tells the model, so you can ask ECA to use its own REPL, e.g. "check in your REPL why my module failed". It needs an nREPL client it can run, like [brepl](https://github.com/licht1stein/brepl).

To connect your editor, `/repl` shows the port; `repl.port` sets a fixed one. It only accepts local connections.

Code evaluated there runs in the modules' SCI context, with `eca.api` and `eca.repl`, which only works there:

```clojure
(require '[eca.api :as eca] '[eca.repl :as repl])
(eca/notify! (repl/ctx) "Hello from the REPL")   ; a ctx on the running ECA, maybe {:module id :chat-id ..}
(repl/modules)                                  ; every module and its status
(repl/install! {:id :me/try :description "Hi"})  ; a module from its map; uninstall! and reload! too
(repl/trace! "<chat-id>")                       ; prints a chat's module events here; untrace! stops it
(repl/chat-raw "<chat-id>")                     ; ECA's state, read-only: also db and config-raw
```

## Limits

- Event handlers of external modules time out after 10 seconds, unless they set `:timeout-ms`. `:start` gets 30 seconds.
- A module that fails 5 times in a row is turned off for the session, and you're told.
- `println` in a module goes to ECA's log, not to the editor.
