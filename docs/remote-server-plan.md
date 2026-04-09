# Remote Web Control Server — Design & Implementation

> GitHub Issue: [#333](https://github.com/editor-code-assistant/eca/issues/333)
>
> **Status: v1 implemented.** This document reflects the actual implementation. Sections
> marked with 🔧 contain implementation notes that diverge from or extend the original
> design.

## Overview

Allow a web frontend (hosted at `web.eca.dev`) to connect to a running ECA session
and observe/control chat sessions in real-time. Each ECA process optionally starts an
embedded HTTP server alongside the existing stdio JSON-RPC server. The web UI connects
via REST for commands and SSE for live updates.

**Architecture:** Option A — Embedded HTTP server per ECA process.
User opens different browser tabs for different sessions (host:port) and different tabs
for each chat within a session.

## Config

```json
{
  "remote": {
    "enabled": true,
    "host": "myserver.example.com",
    "port": 7888,
    "password": "my-secret"
  }
}
```

| Field      | Required | Default               | Description                                    |
|------------|----------|-----------------------|------------------------------------------------|
| `enabled`  | no       | `false`               | Enables the remote HTTP server                 |
| `host`     | no       | auto-detected LAN IP  | Host used in the logged URL for `web.eca.dev` to connect back to. Can be a LAN IP, public IP, domain, or tunnel URL (e.g. ngrok, tailscale). Not set → auto-detect via `InetAddress/getLocalHost`. |
| `port`     | no       | random free port      | Port the HTTP server listens on                |
| `password` | no       | auto-generated token  | Auth token; auto-generated and logged if unset |

Server always binds `0.0.0.0` on the configured port. The `host` field only affects the
logged URL — it tells the web frontend where to reach the server, it does not change what
address the server binds to.

## Authentication Flow

Token-based auth using `Authorization: Bearer` header on all requests. No cookies —
avoids `Secure`/`SameSite` issues that break non-localhost connections (LAN IPs, remote).

1. ECA starts, logs to stderr:
   ```
   🌐 Remote server started on port 7888
   🔗 https://web.eca.dev?host=192.168.1.42:7888&token=a3f8b2...
   ```
   The `host` param tells `web.eca.dev` where to connect back to. Resolved as:
   config `host` if set → otherwise auto-detected LAN IP via `InetAddress/getLocalHost`.
   If `InetAddress/getLocalHost` fails or returns loopback, falls back to `127.0.0.1`
   and logs a warning suggesting the user configure `remote.host`.
2. User clicks the URL → arrives at `web.eca.dev` with `host` and `token` in query params.
3. Frontend stores the token in JS memory (or `localStorage` to survive refresh).
4. Frontend strips token from URL via `history.replaceState`.
5. All REST requests include `Authorization: Bearer <token>` header.
6. SSE stream is consumed via `fetch()` + `ReadableStream` (not `EventSource`, which
   doesn't support custom headers). The `Authorization` header is sent on the SSE
   request. Wire format is identical SSE (`event:`/`data:`/`\n\n`).

The auth token is either user-configured (`password` in config) or auto-generated as a
32-byte hex string via `java.security.SecureRandom` (64 characters). Stored in runtime
state only (not persisted to disk).

🔧 Token validation uses constant-time comparison (`MessageDigest/isEqual`) to prevent
timing side-channel attacks.

**No CSRF protection needed** — CSRF is a cookie-only attack vector. Bearer tokens in
headers are not sent automatically by browsers.

**Fallback:** Users can also go to `web.eca.dev` directly and manually enter host:port +
token in a connect form.

## REST API

All API routes are prefixed with `/api/v1/`.
All JSON keys in requests and responses use **camelCase** (matching the existing JSON-RPC
protocol convention). Internal Clojure kebab-case keywords are converted on
serialization.
All responses use `Content-Type: application/json; charset=utf-8` unless noted otherwise.

### HTTP Status Codes

| Status | Meaning                                                   |
|--------|-----------------------------------------------------------|
| `200`  | Successful GET, or POST that returns data                 |
| `204`  | Successful POST/DELETE with no response body              |
| `302`  | Redirect (`GET /`)                                        |
| `400`  | Malformed request body or missing required fields         |
| `401`  | Missing or invalid `Authorization: Bearer` token          |
| `404`  | Chat or tool-call ID not found                            |
| `409`  | Chat in wrong state for operation (e.g. stop on idle)     |
| `500`  | Internal server error                                     |

### Health & Redirect

| Method | Path             | Auth | Description                                    |
|--------|------------------|------|------------------------------------------------|
| `GET`  | `/`              | No   | `302` redirect to `web.eca.dev` with host+token params |
| `GET`  | `/api/v1/health` | No   | `200` — `{"status": "ok", "version": "0.x.y"}` |

### Session

| Method | Path               | Description                                         |
|--------|--------------------|-----------------------------------------------------|
| `GET`  | `/api/v1/session`  | Session info (workspaces, models, agents, config)   |

Response:
```json
{
  "version": "0.x.y",
  "protocolVersion": "1.0",
  "workspaceFolders": ["/home/user/project"],
  "models": [{"id": "...", "name": "...", "provider": "..."}],
  "agents": [{"id": "...", "name": "...", "description": "..."}],
  "mcpServers": [{"name": "...", "status": "running"}]
}
```

### Chats

**List chats:**

`GET /api/v1/chats` → `200`
```json
[{"id": "uuid", "title": "Fix login bug", "status": "idle", "createdAt": 1234567890}]
```

**Get chat:**

`GET /api/v1/chats/:id` → `200` / `404`
```json
{
  "id": "uuid",
  "title": "Fix login bug",
  "status": "idle",
  "createdAt": 1234567890,
  "messages": [{"role": "user", "content": "...", "contentId": "uuid"}, ...],
  "toolCalls": {"tc-id": {"name": "read_file", "status": "called", "arguments": "..."}},
  "task": {"nextId": 1, "tasks": [...]}
}
```

**Send prompt** (creates chat implicitly if `:id` is new — web UI generates UUID):

`POST /api/v1/chats/:id/prompt` → `200` / `400`
```json
// Request
{"message": "fix the login bug", "model": "optional-model-id", "agent": "optional-agent-id"}

// Response
{"chatId": "uuid", "model": "anthropic/claude-sonnet-4-20250514", "status": "running"}
```

**Stop generation:**

`POST /api/v1/chats/:id/stop` → `204` / `404` / `409`

**Approve tool call:**

`POST /api/v1/chats/:id/approve/:tcid` → `204` / `404` / `409`

**Reject tool call:**

`POST /api/v1/chats/:id/reject/:tcid` → `204` / `404` / `409`

**Rollback:**

`POST /api/v1/chats/:id/rollback` → `204` / `404`
```json
// Request
{"contentId": "uuid-of-message-to-rollback-to"}
```

**Clear chat:**

`POST /api/v1/chats/:id/clear` → `204` / `404`

**Delete chat:**

`DELETE /api/v1/chats/:id` → `204` / `404`

**Change model:**

`POST /api/v1/chats/:id/model` → `204` / `404` / `400`
```json
// Request
{"model": "anthropic/claude-sonnet-4-20250514"}
```
Maps to `chat/selectedModelChanged` notification handler.

**Change agent:**

`POST /api/v1/chats/:id/agent` → `204` / `404` / `400`
```json
// Request
{"agent": "code"}
```
Maps to `chat/selectedAgentChanged` notification handler.

**Change variant:**

`POST /api/v1/chats/:id/variant` → `204` / `404` / `400`
```json
// Request
{"variant": "high"}
```

### SSE

`GET /api/v1/events` — `Content-Type: text/event-stream`

🔧 The `?chat=<id>` filter is **not yet implemented** in v1 — all events are sent to all
clients. Planned for a future iteration.

## SSE Events

### Event Types

```
event: session:connected        ← Full state dump on SSE connect
event: session:disconnecting    ← Server shutting down
event: session:message          ← showMessage notifications (errors, warnings, info)

event: chat:content-received    ← Text chunks, tool progress, usage (from IMessenger)
event: chat:status-changed      ← idle/running/stopping transitions
event: chat:cleared             ← Chat history cleared
event: chat:deleted             ← Chat removed

event: config:updated           ← Model/agent/config changes
event: tool:server-updated      ← MCP server status changes
```

### Event Payloads

**`session:connected`** — Full state dump so the web client can bootstrap:
```json
{
  "version": "0.x.y",
  "protocolVersion": "1.0",
  "chats": [{"id": "...", "title": "...", "status": "idle", "createdAt": 123}],
  "models": [{"id": "...", "name": "...", "provider": "..."}],
  "agents": [{"id": "...", "name": "...", "description": "..."}],
  "mcpServers": [{"name": "...", "status": "running"}],
  "workspaceFolders": ["/home/user/project"]
}
```

**`session:disconnecting`** — Server shutting down:
```json
{"reason": "shutdown"}
```

**`session:message`** — from `showMessage`:
```json
{"type": "warning", "message": "Rate limit approaching"}
```

**`chat:content-received`** — the `data` field is the JSON serialization of the same
params passed to `IMessenger.chat-content-received`, with keys camelCased. Examples:
```json
{"chatId": "abc", "role": "assistant", "content": {"type": "text", "text": "Hello...", "contentId": "uuid"}}
{"chatId": "abc", "role": "system", "content": {"type": "progress", "state": "running", "text": "Thinking..."}}
{"chatId": "abc", "role": "system", "content": {"type": "toolCallPrepare", "toolCallId": "tc1", "name": "eca__read_file"}}
{"chatId": "abc", "role": "system", "content": {"type": "toolCalled", "toolCallId": "tc1", "result": "..."}}
```

**`chat:status-changed`**:
```json
{"chatId": "abc", "status": "running"}
```

**`chat:cleared`**:
```json
{"chatId": "abc"}
```

**`chat:deleted`**:
```json
{"chatId": "abc"}
```

**`config:updated`** — partial config fields that changed:
```json
{"models": [...], "agents": [...]}
```

**`tool:server-updated`**:
```json
{"name": "github-mcp", "status": "running"}
```

### Reliability

- **Heartbeat:** Server sends `:` comment line every 15 seconds to detect dead clients.
- **Backpressure:** Each SSE client gets a `core.async` channel with a dropping buffer
  (buffer size: 256 events). `broadcast!` uses `async/offer!` (non-blocking, drops if
  full). Slow clients drop events rather than blocking the main messenger path. Clients
  can recover by re-fetching chat state via REST.
- **Stale cleanup:** Failed writes remove the client from the connection set.

🔧 **Threading model:** Writer loops and heartbeat use `async/thread` (real threads with
blocking `<!!`/`alts!!`), NOT `go-loop`, because SSE writes perform blocking I/O on the
servlet `OutputStream`. Using `go-loop` would starve the shared core.async dispatch pool.

## CORS

Required because `web.eca.dev` (different origin) connects to `localhost:<port>`.

```
Access-Control-Allow-Origin: https://web.eca.dev
Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS
Access-Control-Allow-Headers: Content-Type, Authorization
```

No `Access-Control-Allow-Credentials` needed — auth is via `Authorization` header, not
cookies.

## Error Responses

All errors use a consistent envelope:

```json
{
  "error": {
    "code": "chat_not_found",
    "message": "Chat abc-123 does not exist"
  }
}
```

No raw exceptions or stack traces are returned to the web client.

### Error Codes

| Code                  | HTTP Status | Description                              |
|-----------------------|-------------|------------------------------------------|
| `unauthorized`        | `401`       | Missing or invalid Bearer token          |
| `invalid_request`     | `400`       | Malformed JSON or missing required fields|
| `chat_not_found`      | `404`       | Chat ID doesn't exist in `db*`           |
| `tool_call_not_found` | `404`       | Tool call ID doesn't exist in chat       |
| `chat_wrong_status`   | `409`       | Chat not in valid state for operation    |
| `internal_error`      | `500`       | Unexpected server error                  |

## Key Architecture Decisions

### BroadcastMessenger

Wraps the existing `ServerMessenger`, implements `IMessenger`. Every method:
1. Delegates to the inner `ServerMessenger` (sends to editor via stdio).
2. Converts data to camelCase via `shared/map->camel-cased-map`.
3. Broadcasts the event to all connected SSE clients (via `core.async` channels).

🔧 The camelCase conversion is essential because `IMessenger` methods receive internal
kebab-case Clojure maps. The JSON-RPC layer (stdio) handles its own key conversion, but
SSE broadcasts serialize directly via Cheshire — without explicit conversion, SSE payloads
would have kebab-case keys like `chat-id` instead of `chatId`.

**Exceptions (inner-only, no broadcast):**
- `editor-diagnostics` — web client cannot provide editor diagnostics.
- `rewrite-content-received` — editor-only feature (targets editor selections).

**IMessenger method → SSE event type mapping:**

| IMessenger method          | SSE event type            | Broadcast? |
|----------------------------|---------------------------|------------|
| `chat-content-received`    | `chat:content-received`   | ✅ Yes      |
| `chat-cleared`             | `chat:cleared`            | ✅ Yes      |
| `chat-status-changed` ★    | `chat:status-changed`     | ✅ Yes      |
| `chat-deleted` ★           | `chat:deleted`            | ✅ Yes      |
| `config-updated`           | `config:updated`          | ✅ Yes      |
| `tool-server-updated`      | `tool:server-updated`     | ✅ Yes      |
| `showMessage`              | `session:message`         | ✅ Yes      |
| `editor-diagnostics`       | —                         | ❌ Inner    |
| `rewrite-content-received` | —                         | ❌ Inner    |

★ = **New `IMessenger` methods.** These state transitions currently happen via direct
`swap!` on `db*` without going through the messenger. Adding them to `IMessenger` gives
`BroadcastMessenger` a clean broadcast path. The `ServerMessenger` (stdio) implementation
of these new methods is a no-op — the editor JSON-RPC protocol has no equivalent
notification for these events.

**Call sites for new methods:**
- `chat-status-changed` — called from `prompt` (→ `:running`), `finish-chat-prompt!`
  (→ `:idle`/`:stopping`), and `promptStop` (→ `:stopping`).
- `chat-deleted` — called from `delete-chat` after removing the chat from `db*`.

### Handler Reuse

REST handlers bridge to the same feature functions used by JSON-RPC handlers
(`eca.handlers`, `eca.features.chat`). They receive the same `components` map
`{:db* :messenger :config :metrics}` — no business logic duplication.

### Port Conflict Handling

If the configured port is in use, the server logs a warning and continues without the
remote server. The ECA process does not crash.

### Editor Remains Primary

The editor (stdio) client is always the primary client. The web UI is a secondary client
that can observe and control chat but cannot fulfill editor-specific requests
(`editor/getDiagnostics`). Both clients can send commands concurrently — for tool
approvals, first response wins (promise `deliver` is idempotent).

## Implementation Notes

> The steps below reflect what was actually implemented.

### Config schema
Added `remote` section to `docs/config.json` with `enabled`, `host`, `port`, and
`password` fields. Added `:remote {:enabled false}` to `initial-config*` in `config.clj`.

🔧 Remote config is read via `config/read-file-configs` at startup (before `initialize`),
so only global/env/custom-file configs are available — workspace-level `.eca/config.json`
is not included. This is intentional: workspace folders aren't known until `initialize`.

### SSE connection management (`eca.remote.sse`)
- Atom holding set of SSE client maps `{:ch :os :done-ch}`.
- `add-client!` accepts optional `done-ch` — closed when writer loop terminates, allowing
  callers to block on it for lifecycle management.
- `broadcast!` uses `async/offer!` (non-blocking put, drops if full).
- Writer loop per client: `async/thread` with blocking `<!!`, writes SSE-formatted string,
  catches `IOException` only, closes `done-ch` in `finally`.
- Heartbeat loop: `async/thread` with `alts!!`, writes `:` comment every 15 seconds.

### BroadcastMessenger (`eca.remote.messenger`)
- Implements `IMessenger`, wraps inner `ServerMessenger`.
- Delegates all methods to inner, then converts data to camelCase and broadcasts to SSE.
- `editor-diagnostics` and `rewrite-content-received` → inner only (no broadcast).

### Auth middleware (`eca.remote.auth`)
- Ring middleware with constant-time token comparison (`MessageDigest/isEqual`).
- Returns `401` with error envelope if missing or invalid.
- `/api/v1/health` and `GET /` are exempt from auth.
- Token generation: 32-byte `SecureRandom` hex (64 chars).

### CORS middleware (`eca.remote.middleware`)
- Ring middleware adding CORS headers for `https://web.eca.dev`.
- Handles OPTIONS preflight → 204.

### REST handlers (`eca.remote.handlers`)
- Read endpoints pull from `@db*` directly, write endpoints delegate to `eca.handlers`.
- Shared `session-state` function used by both `GET /api/v1/session` and the
  `session:connected` SSE event to avoid drift.
- `GET /` redirect uses an atom (`host*`) so the URL includes the actual port even when
  port 0 (auto-assigned) was configured.
- SSE endpoint uses a `deftype SSEBody` implementing Ring's `StreamableResponseBody`
  protocol — Jetty calls `write-body-to-stream` which registers the raw servlet
  `OutputStream` as an SSE client and blocks on `done-ch` until disconnect.

### Routes + HTTP server (`eca.remote.routes`, `eca.remote.server`)
- Custom path-segment router via `case` on vectors (no routing library).
- Middleware composition: CORS → Bearer auth → routes (no JSON content-type middleware —
  all responses set their own `Content-Type`).
- `start!` accepts the shared `sse-connections*` atom from `eca.server` (same atom used
  by `BroadcastMessenger`) to avoid the disconnected-atom bug.
- Host resolved as `host+port*` atom, updated after Jetty binds and resolves the actual
  port.

### Integration into ECA startup/shutdown (`eca.server`)
- `start-server!`: reads remote config from file-based sources, creates shared
  `sse-connections*` atom, creates `BroadcastMessenger` wrapping `ServerMessenger`,
  starts Jetty via `remote.server/start!`, stores result in module-level
  `remote-server*` atom.
- `exit`: stops remote server (broadcast `session:disconnecting`, close all SSE, stop
  Jetty with 5s timeout) before shutting down the JSON-RPC server.

### Modified feature functions
- `delete-chat` now accepts `messenger` and calls `messenger/chat-deleted`.
- `clear-chat` now accepts `messenger` and calls `messenger/chat-cleared` (previously
  only `rollback-chat` called `chat-cleared`).
- `finish-chat-prompt!` calls `messenger/chat-status-changed` after status `swap!`.
- `prompt-messages!` calls `messenger/chat-status-changed` when setting `:running`.

### Tests
- `eca.remote.sse-test` — connections, broadcast, backpressure, heartbeat, close-all.
- `eca.remote.auth-test` — token generation, valid/invalid/missing, exempt paths.
- `eca.remote.handlers-test` — health, root, session, chats CRUD, prompt validation.
- `eca.remote.messenger-test` — delegation + camelCase SSE broadcast verification.

## File Summary

### Created

| File | Purpose |
|------|---------|
| `src/eca/remote/server.clj`      | HTTP server lifecycle (start/stop Jetty) |
| `src/eca/remote/routes.clj`      | Ring route table, middleware composition  |
| `src/eca/remote/handlers.clj`    | REST API handlers + `SSEBody` deftype    |
| `src/eca/remote/sse.clj`         | SSE connection management                |
| `src/eca/remote/messenger.clj`   | BroadcastMessenger with camelCase conversion |
| `src/eca/remote/auth.clj`        | Bearer token auth middleware (constant-time) |
| `src/eca/remote/middleware.clj`   | CORS middleware                          |
| `test/eca/remote/sse_test.clj`         | SSE tests                          |
| `test/eca/remote/auth_test.clj`        | Auth tests                         |
| `test/eca/remote/handlers_test.clj`    | Handler tests                      |
| `test/eca/remote/messenger_test.clj`   | BroadcastMessenger tests           |

### Modified

| File | Change |
|------|--------|
| `src/eca/messenger.clj`        | Added `chat-status-changed` and `chat-deleted` to `IMessenger` protocol |
| `src/eca/server.clj`           | No-op implementations in `ServerMessenger`, BroadcastMessenger wrapping, remote server start/stop |
| `src/eca/features/chat.clj`    | Call `chat-status-changed` on `:running` transition, `chat-deleted` in `delete-chat`, `chat-cleared` in `clear-chat`. Updated `delete-chat` and `clear-chat` signatures to accept `messenger` |
| `src/eca/features/chat/lifecycle.clj` | Call `chat-status-changed` in `finish-chat-prompt!` |
| `src/eca/handlers.clj`         | Updated `chat-delete` and `chat-clear` to pass `messenger` to feature functions |
| `test/eca/test_helper.clj`     | Added `chat-status-changed` and `chat-deleted` to `TestMessenger` |
| `src/eca/config.clj`           | Added `:remote {:enabled false}` to `initial-config*` |
| `docs/config.json`             | Added `remote` config schema                     |
| `CHANGELOG.md`                 | Feature entry under Unreleased                 |

## Future Improvements (out of scope for v1)

- **SSE `?chat=<id>` filter** — specified in the API but not yet implemented; all events
  go to all clients
- Request logging middleware
- Subagent chat filtering in list endpoint
- Source tagging on SSE events (editor vs web origin)
- SSE event batching for high-frequency streaming
- Rate limiting on auth failures
- Configurable CORS allowed origins
- Route-level integration tests (current tests call handlers directly)
- `GET /api/v1/chats/:id` message content filtering (messages can be very large)

## Web Frontend Notes

> Notes for implementing the `web.eca.dev` frontend.

### Consuming SSE

The SSE stream **must** be consumed via `fetch()` + `ReadableStream`, not `EventSource`,
because `EventSource` does not support custom `Authorization` headers:

```javascript
const response = await fetch(`http://${host}/api/v1/events`, {
  headers: { 'Authorization': `Bearer ${token}` }
});
const reader = response.body.getReader();
const decoder = new TextDecoder();
// Parse SSE wire format: "event: <type>\ndata: <json>\n\n"
```

### Connection Lifecycle

1. On connect, the first SSE event is `session:connected` with a full state dump
   (chats, models, agents, MCP servers, workspace folders).
2. The frontend should use this to bootstrap its state — no separate REST call needed.
3. On disconnect, re-fetch state via `GET /api/v1/session` + `GET /api/v1/chats` to
   recover any missed events, then reconnect to SSE.
4. A heartbeat (`:` comment) arrives every 15 seconds — if no data for >30s, assume
   disconnected and reconnect.

### Chat Content Events

`chat:content-received` events carry the same payloads as the editor JSON-RPC protocol.
Key content types the frontend should handle:

| `content.type`     | Description                         | Key fields                            |
|--------------------|-------------------------------------|---------------------------------------|
| `text`             | Streamed text chunk                 | `text`, `contentId`                   |
| `progress`         | Status indicator                    | `state` (running/finished), `text`    |
| `metadata`         | Chat metadata update                | `title`                               |
| `usage`            | Token usage stats                   | `inputTokens`, `outputTokens`         |
| `toolCallPrepare`  | Tool call starting                  | `name`, `id`, `argumentsText`         |
| `toolCallRun`      | Tool call approved, about to run    | `name`, `id`, `arguments`             |
| `toolCallRunning`  | Tool call executing                 | `name`, `id`                          |
| `toolCalled`       | Tool call finished                  | `name`, `id`, `error`, `outputs`      |
| `reasonStarted`    | Thinking/reasoning started          | `id`                                  |
| `reasonText`       | Thinking text chunk                 | `id`, `text`                          |
| `reasonFinished`   | Thinking finished                   | `id`, `totalTimeMs`                   |
| `hookActionStarted`  | Hook action started               | `id`, `name`                          |
| `hookActionFinished` | Hook action finished              | `id`, `name`, `status`, `output`      |

### Model/Agent/Variant Changes

`POST /api/v1/chats/:id/model`, `/agent`, `/variant` are **session-wide** operations
despite the chat-scoped URL. They change the selected model/agent for new prompts across
the entire session, matching the editor behavior. The chat-id in the URL is validated
(404 if not found) but not used for scoping.

### Tool Call Approval

When a tool call requires approval, its status in the chat's `toolCalls` map will be
`"waiting-approval"`. The frontend can show an approve/reject UI and call:
- `POST /api/v1/chats/:id/approve/:tcid` — allow the tool call
- `POST /api/v1/chats/:id/reject/:tcid` — deny the tool call

First response wins — if the editor user approves before the web user (or vice versa),
the second approval is a no-op (`promise deliver` is idempotent).
