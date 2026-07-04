ECA Agent Guide (AGENTS.md)

ECA (Editor Code Assistant) is a Clojure server that editors talk to over stdin/stdout JSON-RPC to provide AI coding features (chat, tools, MCP, agents). Shipped as a GraalVM native binary.

- Architecture (request flow: editor → JSON-RPC stdio → `handlers` → `features` → `llm_api` → `llm_providers` → streamed back via `messenger`):
  - `src/eca/server.clj` / `src/eca/main.clj`: stdio server entrypoint / CLI interface.
  - `src/eca/handlers.clj`: entrypoint for all protocol methods, dispatches to features.
  - `src/eca/features/`: high-level capabilities — `chat.clj` (+ `chat/` lifecycle, history, tool-calls), `tools.clj` (+ `tools/` built-in tools: filesystem, shell, git, mcp, task…), `agents.clj`, `skills.clj`, `rules.clj`, `hooks.clj`, `commands.clj`, `context.clj`, `prompt.clj`, `login.clj`, `plugins.clj`.
  - `src/eca/llm_api.clj`: façade used by features to call an LLM; vendor adapters in `src/eca/llm_providers/` (anthropic, openai, google, ollama, copilot…).
  - `src/eca/db.clj`: in-memory state (sessions, chats, MCP); `src/eca/config.clj`: config resolution from multiple sources.
  - `src/eca/messenger.clj`: sends requests/notifications back to the client; `src/eca/remote/`: remote HTTP/SSE server mode.
  - `src/eca/nrepl.clj`: starts an nREPL when nrepl/cider-nrepl are on the classpath (i.e. the `bb debug-cli` build; no flag needed) — port is logged to stderr, and you can eval against the live process.

- Build (requires Clojure CLI + Babashka):
  - All-in-one debug CLI (JVM, nREPL): `bb debug-cli`
  - Production CLI (JVM): `bb prod-cli`  |  Production JAR: `bb prod-jar`
  - In production we use a native image (GraalVM, `GRAALVM_HOME` set): `bb native-cli`

- Test (Kaocha via `:test` alias):
  - Run all unit tests: `bb test`  (same as `clojure -M:test`)
  - Run a single unit test namespace: `clojure -M:test --focus eca.main-test`
  - Run a single unit test var: `clojure -M:test --focus eca.main-test/parse-opts-test`
  - Run all integration tests (requires built `./eca` or `eca.exe` at repo root): `bb integration-test`
  - Run a single integration test: `bb integration-test --dev --ns integration.chat.mcp-remote-test`
  - `--dev` runs the server from source via the `clojure` CLI (no binary build needed); `--list-ns` lists test namespaces; `--proxy` routes via Tinyproxy (must be installed).
  - Integration tests use mock LLM/MCP servers (`integration-test/llm_mock`, `mcp_mock`) — no API keys needed.

- Lint/format:
  - Lint: `clj-kondo --lint src test dev integration-test`
  - Formatting not enforced in CI; follow idiomatic Clojure. `.cljfmt.edn` defines extra indents: `task` and `future*` are `[[:inner 0]]`.

- Namespaces/imports:
  - One file per `ns`; always `(set! *warn-on-reflection* true)` near top.
  - Group `:require` as: Clojure stdlib, third‑party, then `eca.*`; sort within groups.
  - Prefer `:as` aliases; avoid `:refer` except in tests (`clojure.test` and what you use).

- Naming/types/data:
  - Internal Clojure names and domain keys use kebab-case (`chat-id`, `tool-call-id`, `parent-chat-id`).
  - Client protocol/config JSON uses camelCase (`initializationOptions`, `toolCall`, `contentReceived`); keep conversions at boundaries (`shared/map->camel-cased-map`, config normalization).
  - Provider/MCP payloads mirror vendor specs and may use mixed conventions (`input_tokens`, `function_call`, `inputSchema`); do not “fix” these to Clojure style.
  - Hook script stdin uses top-level snake_case for shell ergonomics (`hook_name`, `chat_id`, `db_cache_path`), while nested tool data may keep its original shape.
  -  - Add type hints only to remove reflection where it shows up.

- Errors/logging/flows:
  - Use `ex-info` with data for exceptional paths; return `{:result-code ...}` maps from CLI flows.
  - **stdout is the JSON-RPC channel** — never print to stdout; use `eca.logger/error|warn|info|debug` (stderr-based) for all app logs.
  - If a chat-scoped function contains any `logger/...` call, wrap the relevant body in `logger/with-chat-context`. Pass both `chat-id` and the current chat’s `parent-chat-id`.
  - Consider wrapping chat-scoped functions that call downstream code known to log, or that start `future*` work whose logs should be attributed to the chat. If there is no downstream logging, `with-chat-context` is unnecessary; instead, consider whether the function should log an important chat lifecycle event.

- Tests:
  - Use `clojure.test` + `nubank/matcher-combinators`; keep tests deterministic.
  - Put shared test helpers under `test/eca/test_helper.clj`.
  - CI runs Linux, macOS, and Windows — use `eca.test-helper/file-path` / `file-uri` for Windows-safe paths in tests.

- General:
  - Use concrete Java class type hints when they prevent GraalVM/reflection issues.
  - If changing dependency inputs in `deps.edn` or `bb.edn`, run `nix develop --command deps-lock-update`; PR CI fails if this leaves a `deps-lock.json` diff.
  - ECA's protocol specification of client <-> server lives in docs/protocol.md
  - If changing ECA config structure, remember to update its docs/config.json
  - When adding support to a new feature or fixing an existing GitHub issue, add a concise Unreleased `CHANGELOG.md` entry (max 180 chars, issue number if known).
