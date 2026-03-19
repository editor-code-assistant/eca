# Remote Web Control Server — Design Plan

> GitHub Issue: [#333](https://github.com/editor-code-assistant/eca/issues/333)

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
| `enabled`  | yes      | —                     | Enables the remote HTTP server                 |
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

Optional `?chat=<id>` filter: when provided, `chat:*` events are filtered to only that
chat. `session:*`, `config:*`, and `tool:*` events are always sent regardless of filter.

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
  (buffer size: 256 events). Slow clients drop events rather than blocking the main
  messenger path. Clients can recover by re-fetching chat state via REST.
- **Stale cleanup:** Failed writes remove the client from the connection set.

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
2. Broadcasts the event to all connected SSE clients (via `core.async` channels).

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

## Implementation Steps

### Step 1 — Config schema
Add `remote` section to `docs/config.json` with `enabled`, `host`, `port`, and `password`
fields. Parse and validate in config loading.

### Step 2 — SSE connection management (`eca.remote.sse`)
- Atom holding set of SSE client connections.
- Each client: Ring async response channel + `core.async` channel with dropping buffer.
- `broadcast!` function: puts event on all client channels.
- Writer loop per client: takes from channel, writes SSE-formatted string, removes client on error.
- Heartbeat loop: writes `:` comment to all clients every 15 seconds.

### Step 3 — BroadcastMessenger (`eca.remote.messenger`)
- Implements `IMessenger`.
- Wraps inner `ServerMessenger`.
- Delegates all methods to inner + broadcasts to SSE.
- `editor-diagnostics` → inner only.

### Step 4 — Auth middleware (`eca.remote.auth`)
- Ring middleware: reads `Authorization: Bearer <token>` header, validates against the
  configured or auto-generated token.
- Returns `401 Unauthorized` with error envelope if missing or invalid.
- `/api/v1/health` and `GET /` are exempt from auth.

### Step 5 — CORS middleware (`eca.remote.middleware`)
- Ring middleware adding CORS headers (`Authorization` in allowed headers).
- Handles OPTIONS preflight requests.
- No `Access-Control-Allow-Credentials` needed (Bearer auth, not cookies).

### Step 6 — REST handlers (`eca.remote.handlers`)
- Read endpoints: pull from `db*` directly.
- Write endpoints: bridge to existing handler functions in `eca.features.chat`.
- Consistent error envelope on all error paths.
- `GET /` redirect to `web.eca.dev`.

### Step 7 — Routes + HTTP server (`eca.remote.routes`, `eca.remote.server`)
- Ring route table with `/api/v1/` prefix.
- Middleware composition: CORS → Bearer auth → JSON parsing → routes.
- Jetty lifecycle: start on configured port, stop on shutdown.
- Graceful port conflict handling (catch `BindException`, log warning, continue without
  remote server).

### Step 8 — Integration into ECA startup/shutdown (`eca.server`)
- `start-server!`: if remote enabled:
  1. Create SSE connections atom.
  2. Create `BroadcastMessenger` wrapping `ServerMessenger`, passing SSE connections atom
     (constructor: `(->BroadcastMessenger inner-messenger sse-connections-atom)`).
  3. Start Jetty on configured port, passing SSE connections atom and `components` to
     route handlers.
  4. Log clickable `web.eca.dev` URL to stderr.
- `shutdown`: send `session:disconnecting` SSE event, wait up to 5 seconds for in-flight
  responses, close SSE connections, stop Jetty. Running chat generations are not
  interrupted — they continue on the stdio side.

### Step 9 — CHANGELOG
Add feature entry under Unreleased referencing #333.

### Step 10 — Tests
- `eca.remote.sse-test` — heartbeat, backpressure, stale client cleanup.
- `eca.remote.auth-test` — Bearer token validation, missing/invalid token rejection.
- `eca.remote.handlers-test` — REST endpoints with mock `db*`.
- `eca.remote.messenger-test` — BroadcastMessenger delegation + SSE broadcasting.

## File Summary

### Create

| File | Purpose |
|------|---------|
| `src/eca/remote/server.clj`      | HTTP server lifecycle (start/stop Jetty) |
| `src/eca/remote/routes.clj`      | Ring route table, middleware composition  |
| `src/eca/remote/handlers.clj`    | REST API handlers                        |
| `src/eca/remote/sse.clj`         | SSE connection management                |
| `src/eca/remote/messenger.clj`   | BroadcastMessenger                       |
| `src/eca/remote/auth.clj`        | Bearer token auth middleware              |
| `src/eca/remote/middleware.clj`   | CORS middleware                          |
| `test/eca/remote/sse_test.clj`         | SSE tests                          |
| `test/eca/remote/auth_test.clj`        | Auth tests                         |
| `test/eca/remote/handlers_test.clj`    | Handler tests                      |
| `test/eca/remote/messenger_test.clj`   | BroadcastMessenger tests           |

### Modify

| File | Change |
|------|--------|
| `src/eca/messenger.clj`        | Add `chat-status-changed` and `chat-deleted` to `IMessenger` protocol |
| `src/eca/server.clj`           | Implement new methods in `ServerMessenger` (no-op), start/stop remote server, wrap messenger |
| `src/eca/features/chat.clj`    | Call `chat-status-changed` on status transitions                       |
| `src/eca/features/chat/lifecycle.clj` | Call `chat-status-changed` in `finish-chat-prompt!`, call `chat-deleted` in `delete-chat` |
| `docs/config.json`             | Add `remote` config schema                     |
| `CHANGELOG.md`                 | Feature entry under Unreleased                 |

## Future Improvements (out of scope for v1)

- Request logging middleware
- Subagent chat filtering in list endpoint
- Source tagging on SSE events (editor vs web origin)
- SSE event batching for high-frequency streaming
- Rate limiting on auth failures
- Configurable CORS allowed origins
