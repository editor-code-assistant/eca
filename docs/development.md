# ECA Development

## Project structure

The ECA codebase follows a pragmatic **layered layout** that separates concerns clearly so that you can jump straight to the part you need to change.

### Files overview

   Path                     | Responsibility
   -------------------------|-------------------------------------------------------
       `bb.edn`             | Babashka tasks (e.g. `bb test`, `bb debug-cli`) for local workflows and CI, the main entrypoint for most tasks.
   `deps.edn`               | Clojure dependency coordinates and aliases used by the JVM build and the native GraalVM image.
   `docs/`                  | Markdown documentation shown at https://eca.dev
   `src/eca/config.clj`     | Centralized place to get ECA configs from multiple places.
   `src/eca/logger.clj`     | Logger interface to log to stderr.
   `src/eca/shared.clj`     | shared utility fns to whole project.
   `src/eca/db.clj`         | Simple in-memory KV store that backs sessions/MCP, all in-memory statue lives here.
   `src/eca/llm_api.clj`    | Public façade used by features to call an LLM.
   `src/eca/llm_providers/` | Vendor adapters (`openai.clj`, `anthropic.clj`, `ollama.clj`).
   `src/eca/llm_util.clj`   | Token counting, chunking, rate-limit helpers.
   `src/eca/features/`      | **High-level capabilities exposed to the editor**
   ├─ `chat.clj`            | Streaming chat orchestration & tool-call pipeline.
   ├─ `prompt.clj`          | Prompt templates and variable interpolation.
   ├─ `index.clj`           | Embedding & retrieval-augmented generation helpers.
   ├─ `rules.clj`           | Guards that enforce user-defined project rules.
   ├─ `tools.clj`           | Registry of built-in tool descriptors (run, approve…).
   └─ `tools/`              | Implementation of side-effectful tools:
   ──├─ `filesystem.clj`    | read/write/edit helpers 
   ──├─ `shell.clj`         | runs user-approved shell commands 
   ──├─ `mcp.clj`           | Multi-Command Plan supervisor 
   ──└─ `util.clj`          | misc helpers shared by tools.
   `src/eca/messenger.clj`  | To send back to client requests/notifications over stdio.
   `src/eca/handlers.clj`   | Entrypoint for all features.
   `src/eca/server.clj`     | stdio **entry point**; wires everything together via `lsp4clj`.
   `src/eca/main.clj`       | The CLI interface.
   `src/eca/nrepl.clj`      | Starts an nREPL when `:debug` flag is passed.

Together these files implement the request flow: 

`client/editor` → `stdin JSON-RPC` → `handlers` → `features` → `llm_api` → `llm_provider` → results streamed back.
   
With this map you can usually answer:

- _"Where does request X enter the system?"_ – look in `handlers.clj`.
- _"How is tool Y executed?"_ – see `src/eca/features/tools/<y>.clj`.
- _"How do we talk to provider Z?"_ – adapter under `llm_providers/`.

### Unit Tests

Run with `bb test` or run test via Clojure REPL. CI will run the same task.

### Integration Tests

Run with `bb integration-test`, it will use your `eca` binary project root to spawn a server process and communicate with it via JSONRPC, testing the whole eca flow like an editor.

## Coding 

There are several ways of finding and fixing a bug or implementing a new feature:

- Create a test for your bug/feature, then implement the code following the test (TDD).
- Build a local `eca` JVM embedded binary using `bb debug-cli` (requires `babashka`), and test it manually in your client pointing to it. After started, you can connect to the nrepl port mentioned in eca stderr buffer, do you changes, evaluate and it will be affected on the running eca.
  - Using a debug binary you can check eca's stderr buffer and look for a nrepl port, and connect to the REPL, make changes to the running eca process (really handy).

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
  - [ ] Oepn chat window
  - [ ] Send user messages via `chat/prompt` request.
  - [ ] Clear chat and Reset chat.
  - [ ] Support receive chat contents via `chat/contentReceived` notification.
  - [ ] Present and allow user change behaviors and models returned from `initialize` request.
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

Please provide feedback of the dificulties implementing your server, especially missing docs, to make next integrations smoother!
