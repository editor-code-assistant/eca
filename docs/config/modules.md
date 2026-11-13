---
description: "ECA modules: list, enable and disable ECA's features, and write your own in Clojure to add tools, commands, prompt sections and event handlers."
---

# Modules

!!! warning "Experimental"

    Modules are new. The module API (`:api 1`) can still change in incompatible ways before it is announced as stable.

ECA's features are modules: the tools, the agents, MCP, the providers, shell hooks... You can list them, turn them off, and add your own: Clojure code that ECA runs with [SCI](https://github.com/babashka/sci), like clj-kondo hooks and clojure-lsp custom linters.

## Listing and turning modules off

`/modules` lists every module: whether it's active, where it comes from, and what it adds (tools, commands, prompt sections, event handlers, config keys).

```
/modules disable eca/mcp
/modules enable eca/mcp
/modules reload acme/jira
```

These take effect at once, for the session. To keep a choice, set it in your config:

```json
{
  "modules": {
    "eca/mcp": {"enabled": false},
    "acme/jira": {"source": "~/dev/eca-jira/jira.clj", "priority": 10}
  }
}
```

| Key | Meaning |
|-----|---------|
| `modules.<id>.enabled` | Turn the module on or off. Disabling a module also disables the modules that require it. |
| `modules.<id>.source` | File or folder of an external module. `~` expands; a relative path resolves against the first workspace root. |
| `modules.<id>.priority` | Order of the module's event handlers, higher first. Default: `0` for external modules, `-100` for built-in ones. |

## Writing a module

A module is a namespace that defines `module`, a map:

```clojure
(ns acme.jira
  (:require [eca.api :as eca]
            [eca.api.http :as http]))

(defn- fetch-issue [ctx key]
  (let [{:keys [jiraUrl jiraToken]} (eca/config ctx)]
    (:body (http/get (str jiraUrl "/rest/api/3/issue/" key) {:oauth-token jiraToken :as :json}))))

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
                                 {:prompt (str "Read Jira issue " key " with jira__issue, then plan and implement it.")})}}})
```

Its keys are ordinary ECA config keys:

```json
{"jiraUrl": "https://acme.atlassian.net"}
```

ECA's config schema accepts any root key, for modules' keys, so editors don't flag typos there. ECA does: it warns about root keys neither it nor a loaded module knows, at startup and in `/doctor`. In a module's `:map` key, entry names stay strings, as in `mcpServers`: `(get-in (eca/config ctx) [:lspServers "python"])`.

### Where modules come from

When two sources define the same module id, the first one keeps it:

1. `eca server --module <path>`, for the session. Repeatable; put it after `server`.
2. `modules.<id>.source` in your config.
3. Each workspace's `.eca/modules/`.
4. `~/.config/eca/modules/`.
5. The `modules/` folder of installed [plugins](plugins.md).

A module is a `.clj` file, or a folder with a `module.clj`; the folder's other namespaces load from it, e.g. `acme.jira.client` from `acme/jira/client.clj`. All external modules share one SCI context, so they can require each other.

ECA reloads a module when its files change, so editing a module file applies it on save. A module that doesn't load shows in `/modules` as failed, with the file and line.

!!! note "Trust"

    Modules run with ECA's permissions. Project modules (a workspace's `.eca/modules/`, or a `source` in its `.eca/config.json`) load without asking, as a project's shell hooks run.

### Module keys

| Key | Purpose |
|-----|---------|
| `:id` | Required. A qualified keyword, e.g. `:acme/jira`. `:eca/*` is for built-in modules. |
| `:description`, `:version` | Shown in `/modules`. |
| `:api` | The module API version it was written for: `1`. |
| `:requires` | Module ids it needs: it starts after them and stays off without them. |
| `:priority` | Order of its event handlers, higher first. Default `0`. |
| `:default-enabled?` | `false` ships it off until the user enables it. |
| `:config-spec` | The config keys it owns: `{:jiraUrl {:type :string :default ".." :doc ".." :secret? true}}`. Types: `:string`, `:number`, `:boolean`, `:enum` (with `:values`), `:map`, `:array`. A map without `:type` is a level: `{:autoVerify {:onEdit {:type :string}}}`. |
| `:config-defaults` | Values for keys other modules own, e.g. `{:toolCall {:approval {:allow {"jira__issue" {}}}}}`, or `(fn [ctx])` returning them. Below the user's config. |
| `:start`, `:stop` | `(fn [ctx])`. What `:start` returns is the module's state, `(eca/state ctx)`. |
| `:tools` | Tools the model can call, by name. |
| `:prefix` | Names its tools `<prefix>__<name>` and commands `<prefix>:<name>`. Default: the name in `:id`. |
| `:commands` | Slash commands, by name. |
| `:system-prompts` | Sections of the system prompt: `[{:id :acme/conventions :after :eca/rules :content "..."}]`. `:content` is a string or `(fn [ctx])`; `:before`/`:after` place it next to another section; `:dynamic? true` for content that changes every turn. |
| `:event-handlers` | `{event-id handler}`; see [Events](#events). |
| `:defines` | Events it offers to other modules: `{:events {:acme/approved {:kind :notify :doc ".."}}}`. |
| `:providers`, `:apis` | Model providers and LLM APIs. |

### Tools

A tool has `:description`, `:parameters` (a JSON Schema) and `:handler`, `(fn [ctx args])`, where `args` has string keys. It returns a string, or `(eca/text s)`/`(eca/error s)`. Optional: `:summary-fn` and `:require-approval-fn`, `(fn [ctx args])`; `:enabled-fn`, `(fn [ctx])`; `:approval-keys-fn`, `(fn [args])`.

A tool's full name is `<prefix>__<name>`, e.g. `jira__issue`. Approval rules, `disabledTools` and shell hooks name it in full, or by its server, `jira`; without a rule, `toolCall.approval.byDefault` applies (`ask` by default). A module can allow its own tools with `:config-defaults`. A tool with `:replaces "eca__read_file"` takes over that tool, keeping its name, so rules still match it.

Clients list each module's tools as their own tool server, of type `module`.

### Commands

A command has `:handler`, `(fn [ctx args])`, and optionally `:description` and `:arguments` (`[{:name "key" :required true}]`). `args` has the declared arguments by name, `:raw` (the text after the command) and `:argv`. Users type `/name`, or `/<prefix>:<name>` when two modules have a command of that name. The handler returns:

- a string: shown in the chat;
- `{:prompt "..."}`: sent to the model as the user's message;
- `nil`: nothing more to do.

### Events

An event handler is `(fn [ctx event])`, a vector of them, or `{:handler f :match {:tool "eca__write_file"} :priority 10 :timeout-ms 5000}`. Three kinds of events:

- **notify**: something happened; handlers may return only the keys the event accepts.
- **transform**: handlers may change the event, or stop the action with `{:block "reason"}` or `{:stop "reason"}`. `:additional-context` adds up across handlers.
- **handle**: the first handler that returns non-nil gives the answer.

The events ECA sends today:

| Event | Kind | Keys | Accepted changes |
|-------|------|------|------------------|
| `:session/started` | notify | `:workspaces` | — |
| `:session/ended` | notify | — | — |
| `:chat/ended` | notify | `:chat-id`, `:agent`, `:model` | — |
| `:chat/status-changed` | notify | `:status`, `:pending-approval-ids`, `:running-tool-ids`... | — |
| `:chat/usage-updated` | notify | `:usage`, `:session-tokens`, `:context-limit`, `:context-used` | — |
| `:tool/before-call` | transform | `:tool`, `:server`, `:arguments`, `:approval`, `:tool-call-id` | `:arguments`, `:approval`, `:reason`, `:block`, `:stop` |
| `:shell/before-exec` | transform | `:command`, `:cwd`, `:env` (for `eca/shell!`) | `:command`, `:cwd`, `:env` |

More of ECA's events come in later versions. A module can define its own in `:defines` and send them with `(eca/emit! ctx event-id event)`.

## `eca.api`

Every function takes the `ctx` ECA passed to the module's function first.

| Area | Functions |
|------|-----------|
| Module | `config`, `state`, `module-id`, `client`, `log`, `workspaces` |
| Chat | `chat`, `messages` (`{:since-checkpoint? true}` for what the model sees), `chat-state`, `swap-chat-state!` (plain data, saved with the chat), `send-prompt!`, `add-context!` (for the next request) |
| User | `notify!`, `progress!` (in a tool), `ui?`, `confirm`, `select`, `input` |
| Events | `emit!` |
| Tools and commands | `tools`, `call-tool!`, `register-tool!`, `unregister-tool!`, `register-command!`, `unregister-command!` |
| LLM | `complete!`, `models`. Calls are metered: `/costs` and `/modules` show them per module. |
| Runtime | `background!`, `data-dir`, `shell!` |
| Results | `text`, `error` |

Other namespaces:

- `eca.api.json`: `parse`, `generate`.
- `eca.api.http`: `request`, `get`, `post`, `put`, `delete`, through ECA's HTTP client (its proxy and TLS settings).
- `eca.api.fs`: `read-file`, `write-file!`, `exists?`, `directory?`, `list-dir`, `glob`, `create-dirs!`, `delete!`, `path`, `parent`, `file-name`, `last-modified-ms`.
- `eca.api.dev`: `install!`, `uninstall!`, `reload!`, `modules`, for a REPL on a running ECA.
- `eca.api.test`: `mock-ctx`, `run-tool`, `run-command`, `run-event`, `recorded`, to test a module away from ECA's session.

Inside SCI, modules see only these namespaces and `clojure.string`, `clojure.set`, `clojure.walk` and `clojure.edn`. There's no Java interop, `slurp` or `spit`: use `eca.api.fs` and `eca.api/shell!`.

## Limits

- Event handlers of external modules time out after 10 s, unless the entry sets `:timeout-ms`; `:start` after 30 s. A failing handler counts as `nil`.
- After 5 failures in a row, a module is disabled for the session, and you're told.
- `println` in a module goes to ECA's log, never to the editor.
