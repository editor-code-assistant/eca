---
description: "ECA modules: every ECA feature is a built-in module. Turn modules on or off, configure them, or write your own in Clojure to extend ECA."
---

# Modules

All ECA features are built-in modules: tools, agents, MCP, providers, hooks and more. Modules are how you extend ECA and configure how a feature works.

You can turn any module off, and write your own in Clojure. ECA runs them with [SCI](https://github.com/babashka/sci), a Clojure interpreter, so there's nothing to build or install.

!!! tip

    Ask ECA to write a module for you: its built-in `eca-manage` skill tells it how.

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

The `eca/repl` module starts an nREPL inside ECA, to call `eca.api` live, try modules before saving them, and look at ECA's state.

Turn it on for the session with `/modules enable eca/repl`, or always, with a fixed port:

```javascript title="~/.config/eca/config.json"
{
  "modules": {"eca/repl": {"enabled": true}},
  "repl": {"port": 7888}
}
```

Connect your editor to `127.0.0.1:7888`, e.g. with CIDER's `cider-connect-clj`:

```clojure
(require '[eca.api :as eca] '[eca.api.dev :as dev] '[eca.api.debug :as debug])

(dev/modules)                                   ; every module, its status and errors
(eca/notify! (dev/ctx) "Hello from the REPL")
(dev/install! {:id :me/try :commands {"try" {:handler (fn [_ _] "It works!")}}})
(debug/chat-raw "<chat-id>")                    ; a chat as ECA keeps it
```

ECA can use the REPL on itself too. Give it an nREPL client it can run, like [brepl](https://github.com/licht1stein/brepl), and tell it the port, for example in a [rule](rules.md): "Evaluate Clojure in your own REPL with `brepl -p 7888`." Then it can check its own state and test modules live.

Without a fixed port, ECA picks a free one, and `/repl` shows it. The REPL only listens on 127.0.0.1, since nREPL has no authentication.

## Limits

- Event handlers of external modules time out after 10 seconds, unless they set `:timeout-ms`. `:start` gets 30 seconds.
- A module that fails 5 times in a row is turned off for the session, and you're told.
- `println` in a module goes to ECA's log, not to the editor.
