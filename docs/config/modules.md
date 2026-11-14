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
    | [`eca/skills`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/features/tools/skill.clj) | Skills: `SKILL.md` folders the model loads when it needs them. |
    | [`eca/rules`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/rules.clj) | Rules: global, project and path-scoped. |
    | [`eca/mcp`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/mcp.clj) | MCP servers: their tools, prompts, resources and instructions. |
    | [`eca/tool-search`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/tools/tool_search.clj) | Hides MCP tools behind `search_tools` when there are too many. |
    | **Your extensions** | |
    | [`eca/custom-tools`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/tools/custom_tools.clj) | Tools from the `customTools` config: shell command templates. |
    | [`eca/custom-commands`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/custom_commands.clj) | Commands from Markdown files and the `commands` config. |
    | [`eca/hooks`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/hooks.clj) | Your shell hooks. |
    | [`eca/plugins`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/plugins.clj) | Plugin marketplaces: install, update and remove bundles. |
    | **Chat** | |
    | [`eca/approval`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/approval.clj) | Tool approval from the `toolCall.approval` rules. |
    | [`eca/compact-as-summary`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/compact_as_summary.clj) | When the context fills up, the chat model summarizes the chat. |
    | [`eca/repo-map`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/repo_map.clj) | A map of the repository, for the `repoMap` context. |
    | [`eca/create`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/create.clj) | `/init`, `/skill-create` and the `eca-manage` skill. |
    | **LLM APIs** | |
    | [`eca/api-anthropic`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/anthropic.clj) | The Anthropic Messages API. |
    | [`eca/api-openai-responses`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/openai.clj) | The OpenAI Responses API. |
    | [`eca/api-openai-chat`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/openai_chat.clj) | The OpenAI Chat Completions API, which OpenAI-compatible providers speak. |
    | [`eca/api-ollama`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/ollama.clj) | The Ollama chat API. |
    | [`eca/api-bedrock`](https://github.com/editor-code-assistant/eca/blob/master/src/eca/modules/providers/bedrock.clj) | The AWS Bedrock Converse API. |
    | **Providers** | |
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
- `priority` orders the module's event handlers, higher first. It's `0` by default, and `-100` for built-in modules.

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
  (:require [eca.api :as eca]
            [eca.api.http :as http]))

(defn- fetch-issue [ctx key]
  (let [{:keys [jiraUrl jiraToken]} (eca/config ctx)]
    (:body (http/get (str jiraUrl "/rest/api/3/issue/" key)
                     {:oauth-token jiraToken :as :json}))))

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

`:tools` maps each tool's name to its definition:

| Key | |
|-----|--|
| `:description` | Tells the model what the tool does. |
| `:parameters` | The tool's arguments, as a JSON Schema. |
| `:handler` | `(fn [ctx args])`, with string keys in `args`. Returns a string, `(eca/text s)` or `(eca/error s)`. |
| `:enabled-fn` | Optional `(fn [ctx])`. The model gets the tool only when it returns true. |
| `:require-approval-fn` | Optional `(fn [ctx args])`. When it returns true, ECA asks before running the call, even if your config allows the tool. |
| `:summary-fn` | Optional `(fn [ctx args])`. The short text editors show for the call. |
| `:approval-keys-fn` | Optional `(fn [args])`. What "approve and remember" remembers, like each command of a shell call. |
| `:replaces` | Optional. A built-in tool it takes over, like `"eca__read_file"`. |

A tool's full name is `<prefix>__<name>`: `jira__issue` above. The prefix is the name in the module's `:id`, unless the module sets `:prefix`. Use the full name in approval rules and `disabledTools`, or just `jira` for all the module's tools. Like any tool, ECA asks before running it unless your config allows it.

### Commands

`:commands` maps each command's name to its definition:

| Key | |
|-----|--|
| `:handler` | `(fn [ctx args])`. `args` has each declared argument as a keyword, plus `:raw`, the text after the command. |
| `:description` | Shown in the command list. |
| `:arguments` | Optional. `[{:name "key" :required true}]`. |

The handler returns a string to show in the chat, `{:prompt "..."}` to send a message to the model as if you typed it, or `nil`.

You type `/name`, or `/<prefix>:<name>` when two modules have a command with the same name.

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

A handler is a function `(fn [ctx event])`, a vector of them, or a map with `:handler` and optional `:match`, `:priority` and `:timeout-ms`. `:match` filters events by key, with a value, a set or a regex. Module handlers run before your [shell hooks](hooks.md); when a module blocks or stops something, the hooks don't run.

Every chat event also has `:chat-id`, `:agent`, `:model`, `:variant`, and `:parent-chat-id` in a subagent's chat.

| Event | Kind | Keys | Changes it accepts |
|-------|------|------|--------------------|
| `:session/started` | notify | `:workspaces` | — |
| `:session/ended` | notify | — | — |
| `:chat/input` | transform | `:text`, `:source` (`:user` or `:module`). Runs before commands and skills. | `:text`, `:handled` (a reply, or `true`: the model isn't called) |
| `:chat/started` | notify | `:resumed?`. Runs on a chat's first prompt. | `:additional-context` (added to the chat's startup context), `:stop` |
| `:chat/before-request` | transform | `:prompt`. Runs once per user message. | `:prompt`, `:additional-context`, `:block`, `:stop` |
| `:llm/before-request` | transform | `:request` (`:model`, `:instructions`, `:past-messages`, `:user-messages`, `:tools`), `:history`, `:since-checkpoint`, `:turn`, `:budget`. Runs before every LLM call of a chat, tool loop included. | `:request`'s messages, `:instructions` (not in the tool loop) and `:tools`. This changes what the model sees, not the chat history. `:model` can't change yet. |
| `:llm/request-failed` | handle | `:request` (`:model`, `:user-messages`), `:error` (`:type` like `:context-overflow`, `:message`, `:status`). Runs after ECA's own retries, once per turn. | `{:retry true}` to send it again, or `{:compact {:trigger :instructions}}` to compact and resume. Without an answer, ECA handles the error. |
| `:chat/after-request` | notify | `:response` | `:follow-up` (sent as the next user message), `:stop` |
| `:chat/message-added` | notify | `:message` (`:role`, `:content`) | — |
| `:chat/title` | handle | `:messages`, `:retitle?` | `{:title ".."}`, used instead of the model's |
| `:chat/ended` | notify | `:chat-id`, `:agent`, `:model` | — |
| `:chat/status-changed` | notify | `:status`, `:pending-approval-ids`, `:running-tool-ids`... | — |
| `:chat/usage-updated` | notify | `:usage`, `:session-tokens`, `:context-limit`, `:context-used` | — |
| `:tool/before-call` | transform | `:tool` (full name), `:server`, `:arguments`, `:approval`, `:tool-call-id` | `:arguments`, `:approval`, `:reason`, `:block`, `:stop`, `:additional-context` (added to the call's output for the model) |
| `:tool/after-call` | transform | `:tool`, `:server`, `:arguments`, `:result`, `:error?`, `:tool-call-id`. Runs before the model, the editor and postToolCall hooks see the result. | `:result` (a result map or a string), `:additional-context` (for the model only), `:stop` (after this batch of calls) |
| `:chat/before-compact` | transform | `:trigger` (`:manual`, `:auto` or `:overflow`), `:instructions` | `:block` (skips the compaction), `:stop`, `:instructions` |
| `:chat/compact` | handle | `:trigger`, `:instructions`. Asked before `eca/compact-as-summary` writes its summary. | `{:status :done :resume? true :message ".."}`, or `:status :failed`. Write the result with `checkpoint!`. An interrupted turn goes on unless `:resume? false`. |
| `:chat/after-compact` | notify | `:trigger`, `:checkpoint` (`:marker`, `:messages`). Runs after a checkpoint is written. | `:additional-context` (added to the summary), `:stop` |
| `:shell/before-exec` | transform | `:command`, `:cwd`, `:env`, `:timeout-ms`, `:background?`, `:source` (`:tool`, `:hook` or `:module`, named by `:tool`, `:hook` and `:hook-type`, or `:module`). Runs for every shell command ECA runs: shell, git and custom tools, shell hooks and `eca/shell!`. | `:command`, `:cwd`, `:env` |
| `:config/resolve` | transform | `:config`. Runs after all config layers merge; read the config from the event, not through `eca.api`. | `:config`, except its `modules` key |
| `:models/resolve` | transform | `:models`: full model name → capabilities | `:models` |

A module can also define its own events in `:defines` and send them with `(eca/emit! ctx event-id event)`.

### Module keys

| Key | |
|-----|--|
| `:id` | Required. A qualified keyword like `:acme/jira`. `:eca/*` is for built-in modules. |
| `:description`, `:version` | Shown in `/modules`. |
| `:api` | The module API version it's written for: `1`. |
| `:requires` | Ids of modules it needs. It starts after them, and stays off without them. |
| `:priority` | Order of its event handlers, higher first. `0` by default. |
| `:default-enabled?` | `false` ships the module turned off. |
| `:config-spec` | The config keys it owns. See [Config](#config). |
| `:config-defaults` | Defaults for keys other modules own, like an approval rule for its tool: `{:toolCall {:approval {:allow {"jira__issue" {}}}}}`. |
| `:start`, `:stop` | `(fn [ctx])`. What `:start` returns is the module's state, `(eca/state ctx)`. |
| `:tools`, `:commands` | See [Tools](#tools) and [Commands](#commands). |
| `:prefix` | Prefix of its tools and commands. The name in `:id` by default. |
| `:system-prompts` | Sections it adds to the system prompt: `[{:id :acme/conventions :after :eca/rules :content "..."}]`. `:content` is a string or `(fn [ctx])`. `:before` or `:after` place it next to another section; `:dynamic? true` is for content that changes every turn. |
| `:event-handlers` | `{event-id handler}`. See [Events](#events). |
| `:defines` | Events it offers other modules: `{:events {:acme/approved {:kind :notify :doc ".."}}}`. |
| `:providers`, `:apis` | Model providers and LLM APIs. |

## `eca.api`

Every function takes the module's `ctx` as its first argument.

| Area | Functions |
|------|-----------|
| Module | `config`, `state`, `module-id`, `client`, `log`, `workspaces` |
| Chat | `chat`, `messages` (`{:since-checkpoint? true}` for what the model sees), `chat-state` and `swap-chat-state!` (plain data saved with the chat), `send-prompt!`, `add-context!` (for the next request), `checkpoint!` (`{:messages [..] :label ".." :data {..}}`: the model sees only what comes after it), `request-compaction!` (`{:trigger :instructions}`: compacts before the next LLM request) |
| User | `notify!`, `progress!` (in a tool), `ui?`, `confirm`, `select`, `input` |
| Events | `emit!` |
| Tools and commands | `tools`, `call-tool!`, `register-tool!`, `unregister-tool!`, `register-command!`, `unregister-command!` |
| LLM | `complete!`, `models`. `/costs` and `/modules` show each module's LLM calls. |
| Runtime | `background!`, `data-dir`, `shell!` |
| Results | `text`, `error` |

Other namespaces:

- `eca.api.json`: `parse`, `generate`.
- `eca.api.http`: `request`, `get`, `post`, `put`, `delete`, through ECA's HTTP client, with its proxy and TLS settings.
- `eca.api.fs`: `read-file`, `write-file!`, `exists?`, `directory?`, `list-dir`, `glob`, `create-dirs!`, `delete!`, `path`, `parent`, `file-name`, `last-modified-ms`.
- `eca.api.test`: `mock-ctx`, `run-tool`, `run-command`, `run-event`, `recorded`, to test a module outside ECA's session.
- `eca.api.dev`: `ctx`, `modules`, `install!`, `uninstall!`, `reload!`, for the [REPL](#a-repl-on-eca).
- `eca.api.debug`: `db`, `chat-raw`, `config-raw`, `trace!`, `untrace!`. Read-only, and only while the REPL runs.

Inside SCI, modules can also use `clojure.string`, `clojure.set`, `clojure.walk` and `clojure.edn`, and Java interop on basic classes like `String`, `Integer`, `Double` and `Exception`. There's no `slurp` or `spit`: use `eca.api.fs` and `eca/shell!`.

## A REPL on ECA

The `eca/repl` module runs an nREPL inside ECA. Turn it on with `/modules enable eca/repl`, or with `"modules": {"eca/repl": {"enabled": true}}` in your config.

Each ECA picks a free port and tells the model, so you can ask ECA to use its own REPL, e.g. "check in your REPL why my module failed". It needs an nREPL client it can run, like [brepl](https://github.com/licht1stein/brepl).

To connect your editor, `/repl` shows the port; `repl.port` sets a fixed one. It only accepts local connections.

## Limits

- Event handlers of external modules time out after 10 seconds, unless they set `:timeout-ms`. `:start` gets 30 seconds.
- A module that fails 5 times in a row is turned off for the session, and you're told.
- `println` in a module goes to ECA's log, not to the editor.
