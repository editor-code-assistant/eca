---
description: "ECA development guide: build from source, run tests, contribute to the open-source AI coding assistant project."
---

# ECA Development

## Building local

- Install [babashka](https://babashka.org/).
- Run `bb debug-cli`, it will generate a JVM embedded binary at project root where you can just `./eca`.

## Project structure

The ECA codebase follows a pragmatic **layered layout** that separates concerns clearly so that you can jump straight to the part you need to change.

### Files overview

   Path                        | Responsibility
   ----------------------------|-------------------------------------------------------
   `bb.edn`                    | Babashka tasks (e.g. `bb test`, `bb debug-cli`) for local workflows and CI, the main entrypoint for most tasks.
   `deps.edn`                  | Clojure dependency coordinates and aliases used by the JVM build and the native GraalVM image.
   `docs/`                     | Markdown documentation shown at https://eca.dev
   `src/eca/main.clj`          | The CLI interface (`eca server`, etc.).
   `src/eca/server.clj`        | stdio **entry point**; wires everything together via `jsonrpc4clj`.
   `src/eca/handlers.clj`      | Entrypoint for all features; every JSON-RPC request/notification lands here.
   `src/eca/messenger.clj`     | Protocol to send requests/notifications back to the client (stdio or remote).
   `src/eca/db.clj`            | In-memory state atom (`db*`); sessions, chats, tool servers — all state lives here.
   `src/eca/config.clj`        | Centralized place to get ECA configs from multiple places (global, local, env, initializationOptions).
   `src/eca/logger.clj`        | Logger interface, logs to stderr (stdout is reserved for JSON-RPC).
   `src/eca/shared.clj`        | Shared utility fns for the whole project.
   `src/eca/llm_api.clj`       | Public façade used by features to call an LLM.
   `src/eca/llm_providers/`    | Vendor adapters (`anthropic.clj`, `openai.clj`, `openai_chat.clj`, `google.clj`, `copilot.clj`, `bedrock.clj`, `azure.clj`, `ollama.clj`, `openrouter.clj`, ...).
   `src/eca/llm_util.clj`      | Streaming/event helpers shared by providers.
   `src/eca/features/`         | **High-level capabilities exposed to the editor**
   ├─ `chat.clj` + `chat/`     | Streaming chat orchestration & tool-call pipeline (lifecycle, history, tool calls).
   ├─ `agents.clj`             | Agents and subagents (definitions, spawning).
   ├─ `commands.clj`           | Chat commands (`/init`, `/login`, `/context`, custom commands, ...).
   ├─ `hooks.clj`              | User-configured hooks triggered on server events.
   ├─ `skills.clj` + `skills/` | Skills discovery and loading.
   ├─ `rules.clj`              | User-defined global/project/path-scoped rules.
   ├─ `context.clj`            | Contexts attached to prompts (files, repo map, ...).
   ├─ `prompt.clj`             | System prompt templates and variable interpolation.
   ├─ `login.clj`              | Provider auth flows.
   ├─ `providers.clj`          | Model/provider resolution.
   ├─ `completion.clj`         | Non-chat LLM feature: code completion.
   ├─ `rewrite.clj`            | Non-chat LLM feature: text rewrite.
   ├─ `index.clj`              | Workspace indexing helpers (repo map).
   ├─ `tools.clj`              | Registry of tool servers and built-in tool descriptors, approval logic.
   └─ `tools/`                 | Implementation of built-in tools:
   ──├─ `filesystem.clj`       | read/write/edit/grep helpers
   ──├─ `shell.clj`            | runs user-approved shell commands, background jobs
   ──├─ `mcp.clj`              | MCP (Model Context Protocol) servers integration
   ──├─ ...                    | other built-in tools: `git.clj`, `task.clj`, `agent.clj`, `ask_user.clj`, `skill.clj`, ...
   ──└─ `util.clj`             | misc helpers shared by tools.
   `src/eca/remote/`           | Optional remote HTTP/SSE server exposing chats to non-stdio clients.
   `src/eca/nrepl.clj`         | Starts an nREPL when built with `bb debug-cli`.

Together these files implement the request flow:

`client/editor` → `stdin JSON-RPC` → `handlers` → `features` → `llm_api` → `llm_provider` → results streamed back via `messenger`.

With this map you can usually answer:

- _"Where does request X enter the system?"_ – look in `handlers.clj`.
- _"How is tool Y executed?"_ – see `src/eca/features/tools/<y>.clj`.
- _"How do we talk to provider Z?"_ – adapter under `llm_providers/`.

### Unit Tests

Run with `bb test` or run tests via Clojure REPL. CI runs the same task.

To run a single namespace or test, use kaocha's focus:

```bash
clojure -M:test --focus eca.features.chat-test
```

### Integration Tests

Run with `bb integration-test`, it will use your `eca` binary at project root (build one with `bb debug-cli` or `bb prod-cli`) to spawn a server process and communicate with it via JSONRPC, testing the whole eca flow like an editor. LLM and MCP servers are mocked (`integration-test/llm_mock`, `integration-test/mcp_mock`), so no API keys are needed.

Useful flags (see `bb integration-test -h`):

- `--dev`: run the server from source via the `clojure` CLI instead of a binary.
- `--ns integration.chat.anthropic-test`: run only specific test namespaces (comma-separated); `-l` lists them.
- `--proxy`: route requests through a transient Tinyproxy to verify proxy support.

## Coding

There are several ways of finding and fixing a bug or implementing a new feature:

- Create a test for your bug/feature, then implement the code following the test (TDD).
- Build a local `eca` JVM embedded binary using `bb debug-cli` (requires `babashka`), and test it manually in your client pointing to it. After started, you can connect to the nrepl port mentioned in eca stderr, do you changes, evaluate and it will be affected on the running eca.
  - Using a debug binary you can connect to the REPL, make changes to the running eca process (really handy).
- Use ECA and tell to use the built-in repl-eca-development skill in this repo to test ECA via LLM.

## Supporting a new editor

When supporting a new editor, it's important to keep UX consistency across editors, check how other editors done or ask for help.

This step-by-step feature implementation help track progress and next steps:

```markdown
- [ ] Create the plugin/extension repo (editor-code-assistant/eca-<editor> would be ideal), ask maintainers for permission.
- Server
  - Manage ECA server process.
    - [ ] Automatic download of latest server.
    - [ ] Allow user specify server path/args.
    - [ ] Commands for Start/stop server from editor.
    - [ ] Show server status (modeline, bottom of editor, etc).
  - [ ] JSONRPC communication with eca server process via stdin/stdout sending/receiving requests and notifications, check [protocol](./protocol.md).
  - [ ] Allow check eca server process stderr for debugging/logs.
  - [ ] Support `initialize` and `initialized` methods.
  - [ ] Support `exit` and `shutdown` methods.
- Chat
  - [ ] Open chat window
  - [ ] Send user messages via `chat/prompt` request.
  - [ ] Clear chat and Reset chat.
  - [ ] Support receive chat contents via `chat/contentReceived` notification.
  - [ ] Present and allow user change agents and models returned from `initialize` request.
  - [ ] Present and add contexts via `chat/queryContext` request
  - [ ] Support tools contents: run/approval/reject via `chat/toolCallApprove` or `chat/toolCallReject`.
  - [ ] Support tools details: showing a file change like a diff.
  - [ ] Support reason/thoughts content blocks.
  - [ ] Show MCPs summary (running, failed, pending).
  - [ ] Support chat commands (`/`) auto completion, querying via `chat/queryCommands`.
  - [ ] Show usage (costs/tokens) from usage content blocks.
  - [ ] keybindings: navigate through chat blocks/messages, clear chat.
- MCP
  - [ ] Open MCP details window
  - [ ] Receive MCP server updates and update chat and mcp-details ux.
- [ ] Basic plugin/extension documentation
```

Create a issue to help track the effort copying and pasting these check box to help track progress, [example](https://github.com/editor-code-assistant/eca/issues/5).

Please provide feedback of the difficulties implementing your server, especially missing docs, to make next integrations smoother!
