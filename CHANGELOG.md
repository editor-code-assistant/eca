# Changelog

## Unreleased

- Custom commands and skills now expose `:arguments` metadata inferred from their content. Previously they always reported empty arguments.
- Native `skill-create`, `plugin-install`, and `plugin-uninstall` commands now declare `:required true` on their arguments in the command listing.

## 0.131.1

- MCP tools that return image content blocks (e.g. an MCP image-generation/edit server) now render those images in the chat UI as `ChatImageContent` and replay them back to the LLM as image inputs on follow-up turns when the model supports vision. Implemented for `openai-responses` (synthetic user-role `input_image` after the `function_call_output`) and `anthropic` (mixed text + image blocks inside `tool_result.content`). `openai-chat` and `ollama` continue to receive a text placeholder until a parallel pattern is implemented there.
- Bugfix: MCP tools without a `description` (which the MCP spec marks optional) no longer break Anthropic chat requests with `tools.<n>.custom.description: Input should be a valid string`. Missing/empty descriptions now fall back to the tool's `title`, then to a synthesized `MCP tool: <name>` string at the MCP boundary so all providers receive a non-null string.
- Hook `matcher` now supports object form keyed by tool selectors with per-tool `argsMatchers`; legacy string regex matchers remain supported.

## 0.131.0

- Add `${plugin:root}` dynamic interpolation for plugin-provided config, hooks, commands, and rules.
- Support OpenAI built-in `image_generation` tool via the Responses API for capable models (`openai/gpt-5.x`, `openai/gpt-4.1`). Generated images are streamed back as a new `image` chat content carrying `mediaType` + base64. Available on every provider whose api is `openai-responses` (`openai`, `github-copilot` responses-api models, `litellm`, custom providers).
- Support image edits via the same `image_generation` tool: assistant-generated images now persist to chat history so subsequent turns can iterate ("now make it blue, smaller, with a red border"), resumed chats replay previously generated images, and clients can attach source images either by file path (existing `FileContext`) or via a new inline base64 `ImageContext` request type for clients without filesystem access.
- Fix inline completion crash when renewing auth tokens before completion requests. #437
- Bugfix: avoid `Divide by zero` crash in chat auto-compact when models.dev reports `0` for a model's context/output limits (e.g. `openai/chatgpt-image-latest`); such limits are now normalized to `nil` and `auto-compact?` skips models without a known positive context window.
- Bugfix: image edit follow-up turns no longer fail on the OpenAI Responses API when prior generations are replayed; generated images are now persisted under a dedicated `image_generation_call` history role and replayed as a user-role `input_image` data URL across providers.
- Bugfix: path-scoped rule enforcement now treats a fetched rule as loaded for the current chat, so matching files do not require fetching the same rule again.

- Support regex patterns in markdown agent tool entries (e.g. `eca__shell_command(npm run .*)`) for fine-grained tool approval, currently limited to `eca__shell_command`.

## 0.130.1

- Add configurable skill paths and recursive directory loading for configured rules, commands, and skills; local skills are also discovered from `.agents/skills`. #423
- Bugfix: `/prompt-show` now renders the system instructions as plain text instead of a raw `{:static :dynamic}` map.
- Fix MCP OAuth success/error page never rendering in the browser by sending the local-callback HTML response before invoking caller-supplied `on-success`/`on-error` hooks; previously the MCP callback synchronously stopped the Jetty server inside `on-success`, racing the response flush.

## 0.130.0

- Improve rules with frontmatter filters, condition variables, path-scoped loading, enforcement support, and clearer documentation. #222
- `preToolCall` hooks now receive `approval: "ask"` for the native `ask_user` tool so notification hooks (e.g. matching `.approval == "ask"`) also fire when the chat is blocked waiting for a user answer, regardless of trust mode.
- New `${cmd:some command}` dynamic string backend that resolves to the trimmed stdout of a shell command, useful for password managers like `pass` or `op`. On macOS the user's interactive shell `$PATH` is queried once so GUI-launched ECA picks up Homebrew, `mise`/`asdf` shims, etc. #430

## 0.129.2

- Add support for gpt-5.5 variants
- Restore trust mode on chat resume: `chat/open` and the `/resume` slash command now emit `config/updated` with `selectTrust` reflecting the resumed chat's persisted trust toggle, so the client indicator stays in sync with the server's auto-approval behavior. #426

## 0.129.1

- Fix token usage not being reported in the UI for Google/Gemini (and other strict OpenAI-compat providers) by opting into `stream_options.include_usage` on streaming chat completion requests. #414

## 0.129.0

- Restore the model used at chat creation when resuming a chat: `chat/open` and the `/resume` slash command now emit `config/updated` to realign the client's selected model to the persisted chat's `:model`, and the next `chat/prompt` prefers that stored model over the agent/global default (stale models still fall through gracefully). #417
- Fix `rewrite` hanging on large files by windowing the inlined file context around the selection instead of sending the whole file; configurable via `rewrite.fullFileMaxLines` (default 2000). #418
- Prefix plugin-sourced commands and skills with their plugin name (`/<plugin-name>:<name>`) to avoid collisions across plugins. When the plugin name and the command/skill name are equal the prefix is dropped. #420
- Fix empty `.sha256` for macOS aarch64 release artifact by using `shasum -a 256` (portable across macOS runners) and enabling `pipefail` so silent pipe failures don't hide.
- Fix install page `eca-desktop` download buttons navigating to the wrong artifact (e.g. Linux/x86_64 AppImage leading to `eca-mac-arm64.dmg`) caused by hidden OS/arch panels still intercepting clicks on top of the visible panel; hidden primary tab and OS panels now use `display: none` so their nested `:checked` rules can't re-activate and leak clicks.
- Fix custom tools hanging on Windows by running them through the same platform-aware shell used by `shell_command`, respecting `toolCall.shellCommand`. #421

## 0.128.2

- Sign and notarize macOS native binaries in release CI.
- Disable `ask_user` tool for subagents since they run non-interactively. #416
- Fix low-quality chat titles on 3rd-message retitle (e.g. literal "Understand" on Opus) by flattening the conversation into a single user message so the title model can't mirror prior planning-mode section headers, adding negative rules/examples to the title prompt, and hardening `sanitize-title` to skip a bare leading markdown header when more content follows.

## 0.128.1

- Fix stale system prompt being reused after switching agent mid-chat by scoping the chat-level prompt cache and the OpenAI Responses `prompt_cache_key` per active agent. #411
- Improve chat title quality on 3rd-message retitle by filtering tool calls, tool results, reasoning and flag entries from the history passed to the title LLM, and by respecting the last compact marker.
- Add the `/model` command allowing the user to change the model directly from the chat.

## 0.128.0

- Add `eca-desktop` as an official client in docs and landing page.
- New `chat/list` JSON-RPC request returning a summary list of persisted chats for the current workspace (id, title, status, createdAt, updatedAt, model, messageCount). Supports optional `limit` and `sortBy` params. Lets clients populate a chat sidebar on startup without requiring the user to resume each chat manually.
- New `chat/open` JSON-RPC request that replays a persisted chat to the client by emitting `chat/cleared` (messages), `chat/opened` and the full sequence of `chat/contentReceived` notifications without mutating server state. Intended to be paired with `chat/list` to render a chat the user has not opened in the current client session.
- New `mcp/addServer` and `mcp/removeServer` JSON-RPC requests for managing MCP server definitions at runtime. The server persists entries to the owning config file (project `.eca/config.json` or global), preserving comments and formatting via rewrite-json, and broadcasts a new `tool/serverRemoved` notification on successful removal. Mirrored over REST via `POST /api/v1/mcp` and `DELETE /api/v1/mcp/:name`.
- Extend `mcp/updateServer` to accept `env` and `headers` params, and migrate its persistence off the cheshire round-trip that stripped comments from the user's config.

## 0.127.1

- Auto allow ask_user tool by default.
- Fix chat titles becoming empty when conversation history contains thinking blocks (Anthropic).
- Add Claude Opus 4.7 support with adaptive thinking, `xhigh` effort level, and summarized thinking display.

## 0.127.0

- Support `ask_user` tool allowing LLM to ask the user questions with optional selectable options. #338
- Remove redundant system message when background jobs finish or are killed.
- Fix Anthropic chat titles being empty or garbled on 3rd message retitle by passing conversation history as past-messages.

## 0.126.0

- Chat titles now re-generate at the 3rd user message using full conversation context for more accurate titles.
- Sanitize chat titles to remove newlines, control characters, and markdown header prefixes.
- Trust mode can now be toggled via `chat/update` and applies immediately to the next tool call without requiring a new prompt.

## 0.125.0

- Refresh auth token before each LLM API call, preventing stale tokens during long-running tool calls.
- Add background shell command support via `background` parameter on `shell_command` tool and new `bg_job` tool for managing long-running processes. #77
- Add `disabled` field to MCP server notifications, allowing clients to distinguish between stopped and config-disabled servers. #403

## 0.124.5

- Fix Github Enterprise models fetch. #402

## 0.124.4

- Fix github-copilot retry loops in chats.
- Fix Github Enterprise to use the proper url during prompts. #402

## 0.124.3

- Fix `/resume` broken for OpenAI chats: handle nil reasoning text during replay, preserve prompt-id after chat replacement, and clear UI before replaying messages. #400
- Add GitHub Enterprise support for Copilot authentication via `auth.url` and `auth.clientId` provider config. #402
- Add `chat/update` notification for renaming chats. Chat titles are now persisted to the database and broadcast to all connected clients including remote web interface.

## 0.124.2

- Fix OpenAI Responses API tool calls not executing when streaming response returns empty output, and fix spurious retries caused by stale tool-call state with Copilot encrypted IDs. #398
- Fix auth refresh propagation during streaming and `spawn_agent` tool calls, preventing mid-session failures.

## 0.124.1

- Add `cacheRetention` provider config for Anthropic to support 1-hour prompt cache TTL. Set to `"long"` for sessions with pauses longer than 5 minutes.

## 0.124.0

- Add `chatRetentionDays` config to control chat and cache cleanup retention period, default changed from 7 to 14 days. Set to 0 to disable cleanup. #393
- Preserve full chat history across compactions using tombstone markers instead of replacing messages. #394
- Add message flags — named checkpoints for resuming and forking chats. #395
- Fix OpenAI models getting stuck at toolCallPrepare when streaming response returns empty output in response.completed. #398

## 0.123.3

- Fix exceptions on openai responses models when creating tasks.
- Fix potential infinite auto-compact loop when context overflow persists after compaction. #391
- Improve Anthropic prompt caching: split system prompt into static/dynamic blocks, add cache markers to the tools array, and memoize static instructions per chat.

## 0.123.2

- Wait for pending MCP tool list refresh before reading tools after tool execution, fixing race where dynamically loaded tools were not immediately available.

## 0.123.1

- Fix OAuth HTTPS server crash in native image by building SSLContext in-memory instead of relying on ring-jetty's keystore path reflection.

## 0.123.0

- Bump plumcp to 0.2.0-beta6.
- Fix MCP server start/stop blocking the protocol thread, causing ECA to become unresponsive.
- Dispatch request and notification handlers off the protocol thread to prevent blocking.
- Fix false positves rejection on plan agent.
- Add `chat/promptSteer` notification for injecting user messages into a running prompt at the next LLM turn boundary. [#386](https://github.com/editor-code-assistant/eca/issues/386)

## 0.122.1

- Fix `/resume` getting stuck when resumed chat had stale compaction flags.
- Add `clientSecret` and `oauthPort` support for MCP servers requiring confidential OAuth with HTTPS pre-registered redirect URIs (e.g. Slack MCP).

## 0.122.0

- Improve summary of filesystem and shell functions making cleaner.
- Fix `move_file` not working for renaming.
- Support parameterized skills via slash commands with `$ARGS`/`$ARGUMENTS`/`$1`/`$2` substitution, e.g. `/review-pr URL`. #384

## 0.121.1

- Fix remote connection for tailscale + docs.

## 0.121.0

- Add `providers/list`, `providers/login`, `providers/loginInput`, `providers/logout` requests and `providers/updated` notification for settings-based provider/model management.
- Add LiteLLM, LM Studio, Mistral, and Moonshot as built-in providers with login support.
- Fix Z-AI provider config using wrong API type and URL.
- Improve explorer subagent prompt, remove role based approach.
- Remove model/variant list from spawn_agent tool description so it doesn't use models when it is not asked to. #369
- Add `streamIdleTimeoutSeconds` config option to make the LLM stream idle timeout configurable (default: 120s).

## 0.120.1

- Update the compact prompt to read the active eca__task list after compacting.
- Fix MCP OAuth discovery failing for servers whose authorization_server URL has a trailing slash (e.g. Miro) due to malformed well-known endpoint URL.

## 0.120.0

- Add `mcp/disableServer` and `mcp/enableServer` notifications to toggle MCP servers from the client, persisting the `disabled` flag in config.json.

## 0.119.0

- Add `/fork` command to clone current chat into a new chat with the same history and settings, and `chat/opened` server notification.
- Fix `/resume` leaving the chat stuck after replaying messages because the resumed chat's `:prompt-finished?` flag blocked finalization.

## 0.118.1

- Fix ECA stop timing out.
- Add `$/progress` server notification for initialization progress reporting (models sync, plugins, MCP servers, remote server, cleanup).

## 0.118.0

- Show the selected `variant` in `spawn_agent` subagent details and harden restrictions regarding the use of the optional model in sub-agent. #369
- Fix MCP OAuth browser not opening on Windows by using `cmd /c start` instead of `java.awt.Desktop`, which is unavailable in the native image.
- Improve server shutdown speed for remote MCP servers.
- Extract git/gh instructions from `shell_command` into a dedicated `git` tool with a condensed prompt, reducing per-turn token overhead.
- Fix crash when `@` signs in prompts (e.g. email addresses) are misinterpreted as context mentions. #379

## 0.117.1

- Fix remote server on Windows stealing TLS traffic from Tailscale/WireGuard when using the same port, by binding to specific interfaces instead of `0.0.0.0` when tunnel adapters are detected.
- Fix plugin-defined hooks not firing for `chatStart` and `sessionStart` due to plugins resolving after hooks fired. #374
- Fix workspace cache resume when worktrees are dynamically added mid-session by using the initial workspace set for the cache key. #372
- Lots of improvements to stop prompt behavior:
  - Fix exception when stopping while a subagent chat run.
  - Fix duplicate "Prompt stopped" message when stopping with a running subagent.
  - Fix delayed newline appearing after stopping a prompt by suppressing belated status notifications.
  - Reduce stream watchdog poll interval for faster stop responsiveness.

## 0.117.0

- Fix `/compact` triggering empty-response retries and rejected tool errors after the compact tool finishes.
- Start remote server as https using built-in cert/private key. Fixes mixed content issues.
- Fix stopping a prompt during `web_search` leaving chat in unrecoverable state. #375

## 0.116.6

- Bump plumcp to 0.2.0-beta5.
- Fix auto-continue clobbering new prompt status and losing the stop button.
- Add configurable shell for `shell_command` tool via `toolCall.shellCommand.path` and `toolCall.shellCommand.args`. #370
- Fix providers disappearing from `/login` after saving an API key. eca-emacs#196
- Fix `remote.enabled` in project-local `.eca/config.json` being ignored when a global config also exists.

## 0.116.5

- Add `/remote` command to display connection URL, password and setup guide. Password is no longer shown in logs or welcome message.
- Improve server port binding for remote.

## 0.116.4

- Fix remote server failing to bind when other services (e.g. Tailscale) hold the same ports on different network interfaces, by falling back to `127.0.0.1` when `0.0.0.0` is unavailable.

## 0.116.3

- Fix remote server failing to bind when other services (e.g. Tailscale) hold the same ports on different network interfaces, by falling back to `127.0.0.1` when `0.0.0.0` is unavailable.

## 0.116.2

- Fix stopping a prompt corruping other cases of chat.

## 0.116.1

- Fix stopping a prompt corrupting chat history with empty content blocks, causing subsequent API errors.

## 0.116.0

- Auto-retry Anthropic streams that end prematurely with empty responses, and auto-continue when response is truncated (e.g. unclosed code blocks).
- Fix resume crash when conversation contained server-side tool use (e.g. web search).
- Add remote web control server for browser-based chat observation and control via `web.eca.dev`. #333

## 0.115.5

- Fix chat getting stuck when LLM streaming connection hangs or is cancelled.

## 0.115.4

- Fix auto-compact race condition where stale messages were sent to model after compaction, causing context overflow in subagents. #357

## 0.115.3

- Fix MCP server auth being invalidated when only URL query parameters change.
- Fix MCP HTTP transport using stale auth token instead of reading latest from state, and retry initialization with token refresh on 403/4xx errors.
- Fix MCP initialize request sending server config name as client name instead of "ECA".

## 0.115.2

- Fix STDIO MCP server deadlock caused by list_changed notification handlers blocking the reader thread.
- Consider Anthropic internal server error as a retriable error.
- Improve MCP error logging to show error code, message, and data instead of null.
- Improve error handling when MCP promtps fail on server side.
- Use rewrite-json to edit jsons without losing formatting.
- Allow shell output redirections to `/tmp/` in plan/explorer agents instead of denying them.
- Fix crash when hook returns additionalContext and message has string content (e.g. `/compact` command).

## 0.115.1

- Improve OAuth callback error page to show error code, description, and error URI from the authorization server response.
- Bump plumcp to 0.2.0-beta4, simplify MCP tool call error handling now that HTTP 400/404/500 are returned as JSON-RPC errors.
- Fix MCP server incorrectly marked as running when HTTP transport returns 4xx (e.g. 401/403) during initialization.

## 0.115.0

- Improve native image size for smaller eca binaries
  - Drop UPX compression for all native image builds.
  - Optimize native image for size with `-Os`.
- Add optional `model` and `variant` parameters to `spawn_agent` tool, allowing users to run subagents on a different model or variant than the current conversation.
- Improve MCP OAuth spec compliance: add Protected Resource Metadata discovery (RFC 9728), OIDC Discovery 1.0 fallback for authorization server metadata, fix `scope` parameter formatting, include `resource` parameter (RFC 8707) in authorization and token requests, and support `clientId` in MCP server config for pre-registered OAuth applications.
- Add trust mode: clients can send `trust: true` in `chat/prompt` to auto-accept tool calls that would require manual approval.
- Improve MCP tool call error handling

## 0.114.2

- Fix completion requests failing with OpenAI subscription models by always streaming on the Responses API. eca-emacs#183
- Disable upx for Windows binaries. #362

## 0.114.1

- Fix native image on Windows not running on older CPUs by enabling `-march=compatibility`. #362
- Fix simple summaries in tool calls for ollama.
- Add MCP server instructions to LLM context. #361

## 0.114.0

- Delete chats older than 7 days on server startup.
- Use human-readable workspace cache directory names (e.g. `my-project_a1b2c3d4`), with automatic migration from old hash-only format.
- Show MCP server URL in the details page and allow editing command/args/url inline with `mcp/updateServer` endpoint.
- Add `mcp/updateServer` to support change mcp commands/urls from client UI.

## 0.113.1

- Fix MCP server threads blocking ECA shutdown when stuck during initialization; startup now uses daemon threads with interrupt-based cancellation for clean exit.
- Re-initialize MCP session and retry tool call on HTTP 404 (session expired) or 5xx (e.g. pod swap dropping the SSE stream), per MCP Streamable HTTP spec.
- Fix `preRequest` hook `all_messages` containing only the current turn instead of the full conversation history.

## 0.113.0

- Clarify Task tool prompt for task ordering, task granularity, concurrent starts, and clearing finished task lists.
- Improve MCP server auth: stop auto-opening browser on OAuth, add `mcp/connectServer` request for client-driven auth and `mcp/logoutServer` notification for clearing credentials.
- Replace MCP Java SDK with plumcp for MCP client communication. SSE transport is no longer supported (deprecated in MCP spec 2025-03-26).
- Fix crash on invalid YAML frontmatter in plugin skill files.
- Show "Waiting subagent" instead of "Calling tool" in progress messages during subagent execution.

## 0.112.1

- Fix MCP OAuth credentials cache not invalidating when the server URL changes.
- Add `isSubagent` condition variable for chat system instructions
- **Breaking:** Replace `bodyPattern` with `errorPattern` in `retryRules`, which matches against any error text (response body, error message, or exception message).
  - Retry on "Remote host terminated the handshake" TLS errors.
  - Fix empty "Error: " message on connection failures (e.g. DNS resolution, connection refused) and retry them as transient errors.

## 0.112.0

- Add `plugins` config for loading external configuration from git repos or local paths. #349
  - Plugins can provide skills, MCP servers, agents, commands, hooks, rules, and arbitrary config overrides.
  - Add commands `/plugins`, `/plugin-install`, and `/plugin-uninstall`.
  - Add official plugin marketplace as built-in plugin source (`eca`), available by default at `plugins.eca.dev`.
- Fix race condition where stopping a prompt and immediately sending a new one could cause two concurrent prompts with no way to stop the older one.
- Support `inherit` key in agent config to inherit settings from another agent.

## 0.111.0

- Fix MCP server initialization crash (`String cannot be cast to IPersistentCollection`) when OAuth metadata endpoint returns a non-JSON or error response.
- Auto-approve `eca__read_file` for tool call output cache files (`~/.cache/eca/toolCallOutputs`).
- Support configurable retry rules per provider via `retryRules`, allowing users to define custom HTTP status and/or body pattern matching for automatic retries with optional labels.

## 0.110.3

- Fix `outputTruncation.sizeKb` not being honored
- Improve tasks feature prompt

## 0.110.2

- Fix MCP OAuth client registration failing for servers that require Content-Type header
- Prevent MCP servers from getting stuck at "starting" when OAuth or other initialization errors occur.
- Fix GitHub Copilot premium request overconsumption:
  - Chat titles LLM request don't spend premium requests.
  - Subagents now use same premium request of primary agent.
  - gpt-5.3-codex and gpt-5.4 now spend way less premium requests.

## 0.110.1

- Fix MCP Streamable HTTP servers stuck at starting due to Java SDK DummyEvent bug on 202 notification responses, and spurious OAuth detection from proxies returning 401 without www-authenticate on HEAD requests.
- Retry MCP server initialization on transient HTTP errors (e.g. chunked encoding EOF from infrastructure closing SSE connections).

- Add support for gpt-5.4 variants

## 0.110.0

- Fix rollback when there are subagents messages in chat.
- Add Task tool #246
- Use OpenAI Responses API for GitHub Copilot models that require it (gpt-5.3-codex, gpt-5.4).

## 0.109.6

- Allow manually starting MCP servers even when configured with `disabled: true` (still not auto-started).
- Fix MCP OAuth discovery for servers that don't support HEAD requests (e.g. Glean) by falling back to POST, and fix two-step metadata discovery (PRM → Authorization Server Metadata per RFC 8414).

## 0.109.5

- Fix clear messages to reset usage tokens as well.

## 0.109.4

- Fix clear messages to reset last api used as well.
- Avoid server stuck during initialize due to threads unavailable. #169

## 0.109.3

- Fix invalid request 400, for tool calls with nil arguments failing when using Anthropic models via GitHub Copilot. #340

## 0.109.2

- Add `chat/clear` message.

## 0.109.1

- Strip markdown code fences from rewrite results via new `rewrite/contentReceived` `replace` content type. #336

## 0.109.0

- Improve /login text.
- Add support to workspace folders change via git worktree via `workspace/didChangeWorkspaceFolders`.

## 0.108.1

- Support other than text type of tool responses in MCPs. #331

## 0.108.0

- Add support for auto retry for http codes: 429, 500, 502, 503 and 529. #224

## 0.107.0

- Fix home (~) expand for command paths in config
- Add Context overflow recovery feature.

## 0.106.0

- Change Openai summary from detailed -> auto.
- Add variants feature #302
  - By default no variant is set, using model default.
  - Some models (openai/anthropic) will have built-in variants (low, medium, high, max etc).
  - Users can create its own variants for their providers/models.

## 0.105.0

- Add network configuration for custom CA certificates and mTLS client certificates. #327
  - New `network` config key with `caCertFile`, `clientCert`, `clientKey`, and `clientKeyPassphrase`.
  - Environment variable fallbacks: `SSL_CERT_FILE`, `NODE_EXTRA_CA_CERTS`, `ECA_CLIENT_CERT`, `ECA_CLIENT_KEY`, `ECA_CLIENT_KEY_PASSPHRASE`.
  - Custom CA certificates are additive to the JVM default trust store.

## 0.104.2

- Avoid showing empty chats in resume. #326
- Fix anthropic adaptive thinking option causing invalid request errors.
- Fix anthropic web server search corner case causing invalid request errors.

## 0.104.1

- Improve fetch models logging when failling. #313

## 0.104.0

- Allow disable chat title generation via `chat title` as `false`. #322

## 0.103.2

- Support dynamic string content in markdown configs (agents, skills). #317

## 0.103.1

- Improve /doctor command to show clearly model used + login providers.
- Fix disabled mcp tools not showing properly in mcp details UI.

## 0.102.0

- Fix `directory_tree` returning empty results for paths outside workspace folders.
- Fix anthropic "invalid_request_error" that may happen when doing server web-search + thinking, causing errors in chat.
- Allow fetching models from providers with no api keys required. #313
- Fix usage tokens for anthropic when doing server web-searches. #307
- Show server websearches as tool calls.
- Improve plan/explorer delegation guidance and reduce friction for common read-only investigation workflows.
- Make anthropic/claude-sonnet-4-6 default model for anthropic.

## 0.101.1

- Allow non JWT tokens for custom providers with openai api. #303
- Fix NPE when first time setting up ECA with no model. #304
- Minor explorer/tool call truncation prompt improvements.

## 0.101.0

- Foreground subagents support! #160

## 0.100.4

- Avoid tool call errors on anthropic api.
- fix model capabilities not being set properly for some custom models.

## 0.100.3

- Improve regex of denied commands in plan agent.
- Fix fetch models regression. #299

## 0.100.2

- Bump MCP java sdk to 0.17.2.
- Avoid duplicated sonnet-4-5 model.

## 0.100.1

- Improve anthropic login wording.
- Fix regression in check for available models. #297

## 0.100.0

- Add config.json schema. #293
- Use `models.dev` as the only dynamic model source for configured providers, with `fetchModels: false` as an opt-out to use only static config models. #292
- Deprecate `behavior` in favor of `agent`.

## 0.99.0

- Truncate tool call outputs automatically to avoid hit context size limit. #284
  - Save output to eca cache folde and tell LLM where the full content is.
  - Add `toolCall outputTruncation` config to customize size or lines.
- Improve shell command summary by stripping `cd <workspace-root> &&` prefix.

## 0.98.5

- Add openai via subscription `gpt-5.3-codex` model.

## 0.98.4

- Add claude-opus-4-6 model.

## 0.98.3

- Fix "approve and remember" tool call when checking files outside workspace.
- Improve MCP server shutdown. #287
- Improve grep tool to support different output modes. #289

## 0.98.2

- Lower default `autoCompactPercentage` from 85 -> 75.
- Add `x-initiator` to `github-copilot` requests, improving the usage of premium requests counter. #138

## 0.98.1

 - Fix openai-chat tool calls freezing when providers emit duplicate/invalid tool_calls[].id values.

## 0.98.0

- Add support for adding `extraHeaders` to models configuration.

## 0.97.7

- Remove the need to use codex instructions for codex sub, replacing with ECA prompt like all other providers.

## 0.97.6

- Improve Anthropic API to support more models like kimi-k2.5.

## 0.97.5

- Support moonshot models via anthropic API.

## 0.97.4

- Fix openai request when using non codex models but using openai subscription.

## 0.97.3

- Fix non sse remote mcps not working.

## 0.97.2

- Do not try to oauth remote mcp servers if they have Authorization header.

## 0.97.1

- Fix regression in last release MCPs not being started properly.

## 0.97.0

- Support update list of mcp tools when requested from server via list_changed notification.

## 0.96.1

- Fix `/resume` duplicating chats. #278
- Reduce noise in auto-compaction, not mentioning the summary. #280

## 0.96.0

- Add `/skill-create` command to create a ECA skill from a prompt. #277

## 0.95.0

- (OpenAI Chat) - Configurable reasoning history via `reasoningHistory` (model-level, default: all)
- Fix exception in autocompact that could happen some times.

## 0.94.2

- Fix autocompact not cleaning tokens in memory and thinking it should auto compact again.

## 0.94.1

- Fix tools prompt override not working via config.

## 0.94.0

- Mention in chat some missing Anthropic responses errors when happens.
- Auto compact via percentage defined in `autoCompactPercentage`. #257
- Do not remove conversation history visually when compacting chat.

## 0.93.2

- Fix `/compact` removing chat history when prompt is stopped or some error happens. #142

## 0.93.1

- Fix chat title generation regression.

## 0.93.0

- Improve copilot login to mention to enable model at Copilot settings page.
- New config API for prompts:
  - Support override any tool description via `prompts tools <toolName>` #271
  - Support override `/init` system prompt via `prompts init`
  - Support override `/compact` system prompt via `prompts compact`
  - Support override chat title system prompt via `prompts chatTitle`
  - Deprecate `systemPrompt` in favor of `prompts chat`
  - Deprecate `completion systemPrompt` in favor of `prompts completion`
  - Deprecate `rewrite systemPrompt` in favor of `prompts rewrite`

## 0.92.3

- Improve error handling on chat messages, avoiding stuck and losing chat history. #272
- Fix directories not working in user prompt. #273

## 0.92.2

- Fix whitespace handle in uris. #270

## 0.92.1

- Add `x-llm-application-name: eca` to prompt requests, useful to track and get metrics when using LLM gateways.

## 0.92.0

- Fix Gemini (OpenAI compatible). #247
- Improve login wording + add cancel option. #205

## 0.91.2

- Fix `eca_shell_command` to include stderr output even when exit 0.

## 0.91.1

- Fix openai pro login exceptions related to graalvm.

## 0.91.0

- Codex login support via `/login openai` and selecting `pro` option. #261
  - Add `gpt-5.2-codex` model.

## 0.90.1

- Fix tokens not renewing between tool calls. #258
- Fix diff correct numbers in added/removed. #259
- Fix post tool call hook trigger
- Fix Gemini tool calling. #247

## 0.90.0

- Skills support following https://agentskills.io . #241
  - Make skills available via commands as well e.g. `/my-skill`.
  - new command `/skills` to list available skills.

## 0.89.0

- Support http MCP servers that require oauth. #51
- Add basic username/password proxy authentication support and recognize lowercase http[s]_proxy env var alongside HTTP[S]_PROXY. #248
- Avoid tool call of invalid names

## 0.88.0

- Add dynamic model discovery via `fetchModels` provider config for OpenAI-compatible `/models` endpoints
- Improve error handling for incompatible models messages in chat. #209
- Support `server__tool_name` in `disabledTools` config as well.
- Fix clojure-mcp regression where ECA could not edit files via clojure-mcp even reading before using its tools.

## 0.87.2

- Fix openai-chat tool call + support for Mistral API #233
- Skip missing/unreadable @file references when building context
- Fix regression: /compact not working for some models. Related to #240

## 0.87.1

- Improve read-file summary to show final range properly.
- Improve model capabilities for providers which model name has slash: `my-provider/anthropic/my-model`

## 0.87.0

- Support Google Gemini thought signatures.
- Support `gemini-3-pro-preview` and `gemini-3-flash-preview` models in Google and Copilot providers.
- Fix deepseek reasoning with openai-chat API #228
- Support `~` in dynamic string parser.
- Support removing nullable values from LLM request body if the value in extraPayload is null. #232

## 0.86.0

- Improve agent behavior prompt to mention usage of editor_diagnostics tool. #230
- Use selmer syntax for prompt templates.


## 0.85.3

- Support `openai/gpt-5.2` and `github-copilot/gpt-5.2` by default.

## 0.85.2

- Support `providers <provider> httpClient version` config, allowing to use http-1.1 for some providers like lmstudio. #229

## 0.85.1

- Fix backwards compatibility for chat rollback.

## 0.85.0
- Enhanced hooks documentation with new types (sessionStart, sessionEnd, chatStart, chatEnd), JSON input/output schemas, execution options (timeout)

- Fix custom tools to support argument numbers.
- Improve read_file summary to mention offset being read.
- Enhanced hooks documentation with new types (sessionStart, sessionEnd, chatStart, chatEnd), JSON input/output schemas, execution options (timeout)
- Support rollback only messages, tool call changes or both in `chat/rollback`.

## 0.84.2

- Fix `${netrc:...} ` to consider `:netrcFile` config properly.

## 0.84.1

- Fix `${netrc:...} ` to consider `:netrcFile` config.

## 0.84.0

- Improve `/compact` UI in chat after running, cleaning chat and showing the new summary.
- Better config values dynamic string parse:
    - Support `${classapath:path/to/eca/classpath/file}` in dynamic string parse.
    - Support `${netrc:api.foo.com}` in dynamic string parse to parse keys. #200
    - Support default env values in `${env:MY_ENV:default-value}`.
    - Support for ECA_CONFIG and custom config file.
- Deprecate configs:
  - `systemPromptFile` in favor of `systemPrompt` using `${file:...}` or `${classpath:...}`
  - `urlEnv` in favor of `url` using `${env:...}`
  - `keyEnv` in favor of `key` using `${env:...}`
  - `keyRc` in favor of `key` using `${netrc:...}`
  - `compactPromptFile` in favor of `compactPrompt` using `${classpath:...}`

## 0.83.0

- Support dynamic string parse (`${file:/path/to/something}` and `${env:MY_ENV}`) in all configs with string values. #200

## 0.82.1

- Fix custom tools output to return stderr when tool error. #219

## 0.82.0

- Support nested folder for rules and commands. #220

## 0.81.0

- Support rollback file changes done by `write_file`, `edit_file` and `move_file`. #218
- Improve rollback to keep consistent UI before the rollback, fixing tool names and user messages.

## 0.80.4

- Fix binary for macos amd64. #217

## 0.80.3

- Fix release

## 0.80.2

- Update anthropic default models to include opus-4.5
- Update anthropic default models to use alias names.

## 0.80.1

- Add new models to GitHub config (Gpt 5.1 and Opus 4.5).

## 0.80.0

- Add support to rollback messages via `chat/rollback` and `chat/clear` messages. #42

## 0.79.1

- Improve system prompt to add project env context.

## 0.79.0

- Fix absolute paths being interpreted as commands. #199
- Remove non used sync models code during initialize. #100
- Fix system prompt to mention the user workspace roots.

## 0.78.4

- Fix regression exceptions on specific corner cases with log obfuscation.

## 0.78.3

- Add `openai/gpt-5.1` to default models.

## 0.78.2

- Add workspaces to `/doctor`
- Improve LLM request logs to include headers.

## 0.78.1

- Fix regression: completion broken after rewrite feature API changes.

## 0.78.0

- Prefix tool name with server to LLM: <server>__<toolname>. #196
- Remove `eca_` prefix from eca tools, we already pass server prefix (eca) after #196.
- Add `approval` arg to preToolCall hook input.
- Add error rewrite message content message.

## 0.77.1

- Fix token renew when using rewrite feature.
- Improve rewrite error handling.

## 0.77.0

- Custom providers do not require the existense of `key` or `keyEnv`. #194
- New feature: rewrite. #13

## 0.76.0

- Updated instructions for `/login` command and invalid input handling.
- Fix server name on `chat/contentReceived` when preparing tool call.
- Fix variable replacing in some tool prompts.
- Improve planning mode prompt and tool docs; clarify absolute-path usage and preview rules.
- Centralize path approval for tools and always list all missing required params in INVALID_ARGS.

## 0.75.4

- Fix 0.75.3 regression on custom openai-chat providers.

## 0.75.3

- Support custom think tag start and end for openai-chat models via `think-tag-start` and `think-tag-end` provider configs. #188
- Bump MCP java sdk to 0.15.0.

## 0.75.2

- Add missing models supported by Github Copilot
- Fix regression: openai-chat tool call arguments error on some models.

## 0.75.1

- Improve protocol for tool call output formatting for tools that output json.
- Fix inconsistencies in `eca_read_file` not passing correct content to LLM when json.

## 0.75.0

- Improved file contexts: now use :lines-range
- BREAKING ECA now only supports standard plain-text netrc as credential file reading. Drop authinfo and gpg decryption support. Users can choose to pass in their own provisioned netrc file from various secure source with `:netrcFile` in ECA config.

## 0.74.0

- Improved `eca_edit_file` to automatically handle whitespace and indentation differences in single-occurrence edits.
- Fix contexts in user prompts (not system contexts) not parsing lines ranges properly.
- Support non-stream providers on openai-chat API. #174

## 0.73.5

- Support use API keys even if subscription is logged. #175

## 0.73.4

- Fix tool call approval ignoring eca tools.

## 0.73.3

- Fix tool call approval ignoring configs for mcp servers.

## 0.73.2

- Fix tool call approval thread lock.

## 0.73.1

- Improve chat title generation.
- Fix completion error handling.
- Default to `openai/gpt-4.1` on completion.

## 0.73.0

- Add `:config-file` cli option to pass in config.
- Add support for completion. #12

## 0.72.2

- Run `preToolCall` hook before user approval if any. #170

## 0.72.1

- Only include `parallel_tool_calls` to openai-responses and openai-chat if true. #169

## 0.72.0

- Support clojureMCP dry-run flags for edit/write tools, being able to show preview of diffs before running tool.

## 0.71.3

- Assoc `parallel_tool_calls` to openai-chat only if truth.

## 0.71.2

- Fix regression in `/compact` command. #162
- Fix to use local zone for time presentation in `/resume`.

## 0.71.1

- Use web-search false if model capabiltiies are not found.

## 0.71.0

- Support `/resume` a specific chat.

## 0.70.6

- Fix `openai-chat` api not following `completionUrlRelativePath`.

## 0.70.5

- Fix web-search not working for custom models using openai/anthropic apis.

## 0.70.4

- Support `visible` field in hooks configuration to show or not in client.

## 0.70.3

- Deprecate prePrompt and postPrompt in favor of preRequest and prePrompt.

## 0.70.2

- Fix model capabilities for models with custom names.

## 0.70.1

- Fix prePrompt hook.

## 0.70.0

- Add hooks support. #43

## 0.69.1

- Fix regression on models with no extraPayload.

## 0.69.0

- Support multiple model configs with different payloads using same model name via `modelName` config. (Ex: gpt-5 and gpt-5-high but both use gpt-5)

## 0.68.1

- Add `anthropic/haiku-4.5` model by default.

## 0.68.0

- Unwrap mentioned @contexts in prompt appending as user message its content. #154

## 0.67.0

- Improved flaky test #150
- Obfuscate env vars in /doctor.
- Bump clj-otel to 0.2.10
- Rename $ARGS to $ARGUMENTS placeholder alias for custom commands.
- Support recursive AGENTS.md file inclusions with @file mention. #140

## 0.66.1

- Improve plan behavior prompt. #139

## 0.66.0

- Add support for secrets stored in authinfo and netrc files
- Added tests for stopping concurrent tool calls. #147
- Improve logging.
- Improve performance of `chat/queryContext`.

## 0.65.0

- Added ability to cancel tool calls. Only the shell tool currently. #145
- Bump mcp java sdk to 0.14.1.
- Improve json output for tools that output json.

## 0.64.1

- Fix duplicated arguments on `toolCallPrepare` for openai-chat API models. https://github.com/editor-code-assistant/eca-emacs/issues/56

## 0.64.0

- Add `server` to tool call messages.

## 0.63.3

- Fix last word going after tool call for openai-chat API.

## 0.63.2

- Fix retrocompatibility with some models not working with openai-chat like deepseek.

## 0.63.1

- Add `gpt-5-codex` model as default for `openai` provider.

## 0.63.0

- Support "accept and remember" tool call per session and name.
- Avoid generating huge chat titles.

## 0.62.1

- Add `claude-sonnet-4.5` for github-copilot provider.
- Add `prompt-received` metric.

## 0.62.0

- Use a default of 32k tokens for max_tokens in openai-chat API.
- Improve rejection prompt for tool calls.
- Use `max_completion_tokens` instead of `max_tokens` in openai-chat API.
- Support context/tokens usage/cost for openai-chat API.
- Support `anthropic/claude-sonnet-4.5` by default.

## 0.61.1

- More tolerant whitespace handling after `data:`.
- Fix login for google provider. #134

## 0.61.0

- Fix chat titles not working for some providers.
- Enable reasoning for google models.
- Support reasoning blocks in models who use openai-chat api.

## 0.60.0

- Support google gemini as built-in models. #50

## 0.59.0

- Deprecate repoMap context, will be removed in the future.
  - After lots of tunnings and improvements, the repoMap is no longer relevant as `eca_directory_tree` provides similar and more specific view for LLM to use.
- Support `toolCall shellCommand summaryMaxLength` to configure UX of command length. #130

## 0.58.2

- Fix MCP prompt for native image.

## 0.58.1

- Improve progress notification when tool is running.

## 0.58.0

- Bump MCP java sdk to 0.13.1
- Improve MCP logs on stderr.
- Support tool call rejection with reasons inputed by user. #127

## 0.57.0

- Greatly reduce token consuming of `eca_directory_tree`
  - Ignoring files in gitignore
  - Improving tool output for LLM removing token consuming chars.

## 0.56.4

- Fix renew oauth tokens when it expires in the same session.

## 0.56.3

- Fix metrics exception when saving to db.

## 0.56.2

- Fix db exception.

## 0.56.1

- Fix usage reporting.

## 0.56.0

- Return new chat metadata content.
  - Add chat title via prompt to LLM.

## 0.55.0

- Add support for OpenTelemetry via `otlp` config.
  - Export metrics of server tasks, tool calls, prompts, resources.

## 0.54.4

- Use jsonrpc4clj instead of lsp4clj.
- Bump graalvm to 24 and java to 24 improving native binary perf.

## 0.54.3

- Avoid errors on multiple same MCP server calls in parallel.

## 0.54.2

- Fix openai cache tokens cost calculation.

## 0.54.1

- Improve welcome message.

## 0.54.0

- Improve large file handling in `read-file` tool:
  - Replace basic truncation notice with detailed line range information and next-step instructions.
  - Allow users to customize default line limit through `tools.readFile.maxLines` configuration (keep the current 2000 as default).
- Moved the future in :on-tools-called and stored it in the db. #119
- Support `compactPromptFile` config.
- Fix tools not being listed for servers using mcp-remote.

## 0.53.0

- Add `/compact` command to summarize the current conversation helping reduce context size.
- Add support for images as contexts.

## 0.52.0

- Support http-streamable http servers (non auth support for now)
- Fix promtps that send assistant messages not working for anthropic.

## 0.51.3

- Fix manual anthropic login to save credentials in global config instead of cache.

## 0.51.2

- Minor log improvement of failed to start MCPs.

## 0.51.1

- Bump mcp java sdk to 1.12.1.
- Fix mcp servers default timeout from 20s -> 60s.

## 0.51.0

- Support timeout on `eca_shell_command` with default to 1min.
- Support `@cursor` context representing the current editor cursor position. #103

## 0.50.2

- Fix setting the `web-search` capability in the relevant models
- Fix summary text for tool calls using `openai-chat` api.

## 0.50.1

- Bump mcp-java-sdk to 0.12.0.

## 0.50.0

- Added missing parameters to `toolCallRejected` where possible.  PR #109
- Improve plan prompt present plan step.
- Add custom behavior configuration support. #79
  - Behaviors can now define `defaultModel`, `disabledTools`, `systemPromptFile`, and `toolCall` approval rules.
  - Built-in `agent` and `plan` behaviors are pre-configured.
  - Replace `systemPromptTemplateFile` with `systemPromptFile` for complete prompt files instead of templates.
- Remove `nativeTools` configuration in favor of `toolCall` approval and `disabledTools`.
  - Native tools are now always enabled by default, controlled via `disabledTools` and `toolCall` approval.

## 0.49.0

- Add `totalTimeMs` to reason and toolCall content blocks.

## 0.48.0

- Add nix flake build.
- Stop prompt does not change the status of the last running toolCall. #65
- Add `toolCallRunning` content to `chat/contentReceived`.

## 0.47.0

- Support more providers login via `/login`.
  - openai
  - openrouter
  - deepseek
  - azure
  - z-ai

## 0.46.0

- Remove the need to pass `requestId` on prompt messages.
- Support empty `/login` command to ask what provider to login.

## 0.45.0

- Support user configured custom tools via `customTools` config. #92
- Fix default approval for read only tools to be `allow` instead of `ask`.

## 0.44.1

- Fix renew token regression.
- Improve error feedback when failed to renew token.

## 0.44.0

- Support `deny` tool calls via `toolCall approval deny` setting.

## 0.43.1

- Safely rename `default*` -> `select*` in `config/updated`.

## 0.43.0

- Support `chat/selectedBehaviorChanged` client notification.
- Update models according with supported models given its auth or key/url configuration.
- Return models only authenticated or logged in avoid too much models on UI that won't work.

## 0.42.0

- New server notification `config/updated` used to notify clients when a relevant config changed (behaviors, models etc).
- Deprecate info inside `initialize` response, clients should use `config/updated` now.

## 0.41.0

- Improve anthropic extraPayload requirement when adding models.
- Add message to when config failed to be parsed.
- Fix context completion for workspaces that are not git. #98
- Fix session tokens calculation.

## 0.40.0

- Drop `agentFileRelativePath` in favor of behaviors customizations in the future.
- Unwrap `chat` config to be at root level.
- Fix token expiration for copilot and anthropic.
- Considerably improve toolCall approval / permissions config.
  - Now with thave multiple optiosn to ask or allow tool calls, check config section.

## 0.39.0

- Fix session-tokens in usage notifications.
- Support context limit on usage notifications.
- Fix session/message tokens calculation.

## 0.38.3

- Fix anthropic token renew.

## 0.38.2

- Fix command prompts to allow args with spaces between quotes.
- Fix anthropic token renew when expires.

## 0.38.1

- Fix graalvm properties.

## 0.38.0

- Improve plan-mode (prompt + eca_preview_file_change tool) #94
- Add fallback for matching / editing text in files #94

## 0.37.0

- Require approval for `eca_shell_command` if running outside workspace folders.
- Fix anthropic subscription.

## 0.36.5

- Fix pricing for models being case insensitive on its name when checking capabilities.

## 0.36.4

- Improve api url error message when not configured.

## 0.36.3

- Fix `anthropic/claude-3-5-haiku-20241022` model.
- Log json error parsing in configs.

## 0.36.2

- Add login providers and server command to `/doctor`.

## 0.36.1

- Improved the `eca_directory_tree` tool. #82

## 0.36.0

- Support relative contexts additions via `~`, `./` `../` and `/`. #61

## 0.35.0

- Anthropic subscription support, via `/login anthropic` command. #57

## 0.34.2

- Fix copilot requiring login in different workspaces.

## 0.34.1

- Fix proxy exception. #73

## 0.34.0

- Support custom UX details/summary for MCP tools. #67
  - Support clojureMCP tools diff for file changes.

## 0.33.0

- Fix reasoning titles in thoughts blocks for openai-responses.
- Fix hanging LSP diagnostics requests
- Add `lspTimeoutSeconds` to config
- Support `HTTP_PROXY` and `HTTPS_PROXY` env vars for LLM request via proxies. #73

## 0.32.4

- Disable `eca_plan_edit_file` in plan behavior until better idea on what plan behavior should do.

## 0.32.3

- Consider `AGENTS.md` instead of `AGENT.md`, following the https://agents.md standard.

## 0.32.2

- Fix option to set default chat behavior from config via `chat defaultBehavior`. #71

## 0.32.1

- Fix support for models with `/` in the name like Openrouter ones.

## 0.32.0

- Refactor config for better UX and understanding:
  - Move `models` to inside `providers`.
  - Make `customProviders` compatible with `providers`. models need to be a map now, not a list.

## 0.31.0

- Update copilot models
- Drop uneeded `ollama useTools` and `ollama think` configs.
- Refactor configs for config providers unification.
  - `<provider>ApiKey` and `<providerApiUrl>` now live in `:providers "<provider>" :key`.
  - Move `defaultModel` config from customProvider to root.

## 0.30.0

- Add `/login` command to login to providers
- Add Github Copilot models support with login.

## 0.29.2

- Add `/doctor` command to help with troubleshooting

## 0.29.1

- Fix args streaming in toolCallPrepare to not repeat the args. https://github.com/editor-code-assistant/eca-nvim/issues/28

## 0.29.0

- Add editor tools to retrieve information like diagnostics. #56

## 0.28.0

- Change api for custom providers to support `openai-responses` instead of just `openai`, still supporting `openai` only.
- Add limit to repoMap with default of 800 total entries and 50 per dir. #35
- Add support for OpenAI Chat Completions API for broad third-party model support.
  - A new `openai-chat` custom provider `api` type was added to support any provider using the standard OpenAI `/v1/chat/completions` endpoint.
  - This enables easy integration with services like OpenRouter, Groq, DeepSeek, Together AI, and local LiteLLM instances.

## 0.27.0

- Add support for auto read `AGENT.md` from workspace root and global eca dir, considering as context for chat prompts.
- Add `/prompt-show` command to show ECA prompt sent to LLM.
- Add `/init` command to ask LLM to create/update `AGENT.md` file.

## 0.26.3

- breaking: Replace configs `ollama host` and `ollama port` with `ollamaApiUrl`.

## 0.26.2

- Fix `chat/queryContext` to not return already added contexts
- Fix some MCP prompts that didn't work.

## 0.26.1

- Fix anthropic api for custom providers.
- Support customize completion api url via custom providers.

## 0.26.0

- Support manual approval for specific tools. #44

## 0.25.0

- Improve plan-mode to do file changes with diffs.

## 0.24.3

- Fix initializationOptions config merge.
- Fix default claude model.

## 0.24.2

- Fix some commands not working.

## 0.24.1

- Fix build

## 0.24.0

- Get models and configs from models.dev instead of hardcoding in eca.
- Allow custom models addition via `models <modelName>` config.
- Add `/resume` command to resume previous chats.
- Support loading system prompts from a file.
- Fix model name parsing.

## 0.23.1

- Fix openai reasoning not being included in messages.

## 0.23.0

- Support parallel tool call.

## 0.22.0

- Improve `eca_shell_command` to handle better error outputs.
- Add summary for eca commands via `summary` field on tool calls.

## 0.21.1

- Default to gpt-5 instead of o4-mini when openai-api-key found.
- Considerably improve `eca_shell_command` to fix args parsing + git/PRs interactions.

## 0.21.0

- Fix openai skip streaming response corner cases.
- Allow override payload of any LLM provider.

## 0.20.0

- Support custom commands via md files in `~/.config/eca/commands/` or `.eca/commands/`.

## 0.19.0

- Support `claude-opus-4-1` model.
- Support `gpt-5`, `gpt-5-mini`, `gpt-5-nano` models.

## 0.18.0

- Replace `chat` behavior with `plan`.

## 0.17.2

- fix query context refactor

## 0.17.1

- Avoid crash MCP start if doesn't support some capabilities.
- Improve tool calling to avoid stop LLM loop if any exception happens.

## 0.17.0

- Add `/repo-map-show` command. #37

## 0.16.0

- Support custom system prompts via config `systemPromptTemplate`.
- Add support for file change diffs on `eca_edit_file` tool call.
- Fix response output to LLM when tool call is rejected.

## 0.15.3

- Rename `eca_list_directory` to `eca_directory_tree` tool for better overview of project files/dirs.

## 0.15.2

- Improve `eca_edit_file` tool for better usage from LLM.

## 0.15.1

- Fix mcp tool calls.
- Improve eca filesystem calls for better tool usage from LLM.
- Fix default model selection to check anthropic api key before.

## 0.15.0

- Support MCP resources as a new context.

## 0.14.4

- Fix usage miscalculation.

## 0.14.3

- Fix reason-id on openai models afecting chat thoughts messages.
- Support openai o models reason text when available.

## 0.14.2

- Fix MCPs not starting because of graal reflection issue.

## 0.14.1

- Fix native image build.

## 0.14.0

- Support enable/disable tool servers.
- Bump mcp java sdk to 0.11.0.

## 0.13.1

- Improve ollama model listing getting capabilities, avoiding change ollama config for different models.

## 0.13.0

- Support reasoning for ollama models that support think.

## 0.12.7

- Fix ollama tool calls.

## 0.12.6

- fix web-search support for custom providers.
- fix output of eca_shell_command.

## 0.12.5

- Improve tool call result marking as error when not expected output.
- Fix cases when tool calls output nothing.

## 0.12.4

- Add chat command type.

## 0.12.3

- Fix MCP prompts for anthropic models.

## 0.12.2

- Fix tool calls

## 0.12.1

- Improve welcome message.

## 0.12.0

- Fix openai api key read from config.
- Support commands via `/`.
- Support MCP prompts via commands.

## 0.11.2

- Fix error field on tool call outputs.

## 0.11.1

- Fix reasoning for openai o models.

## 0.11.0

- Add support for file contexts with line ranges.

## 0.10.3

- Fix openai `max_output_tokens` message.

## 0.10.2

- Fix usage metrics for anthropic models.

## 0.10.1

- Improve `eca_read_file` tool to have better and more assertive descriptions/parameters.

## 0.10.0

- Increase anthropic models maxTokens to 8196
- Support thinking/reasoning on models that support it.

## 0.9.0

- Include eca as a  server with tools.
- Support disable tools via config.
- Improve ECA prompt to be more precise and output with better quality

## 0.8.1

- Make generic tool server updates for eca native tools.

## 0.8.0

- Support tool call approval and configuration to manual approval.
- Initial support for repo-map context.

## 0.7.0

- Add client request to delete a chat.

## 0.6.1

- Support defaultModel in custom providers.

## 0.6.0

- Add usage tokens + cost to chat messages.

## 0.5.1

- Fix openai key

## 0.5.0

- Support custom LLM providers via config.

## 0.4.3

- Improve context query performance.

## 0.4.2

- Fix output of errored tool calls.

## 0.4.1

- Fix arguments test when preparing tool call.

## 0.4.0

- Add support for global rules.
- Fix origin field of tool calls.
- Allow chat communication with no workspace opened.

## 0.3.1

- Improve default model logic to check for configs and env vars of known models.
- Fix past messages sent to LLMs.

## 0.3.0

- Support stop chat prompts via `chat/promptStop` notification.
- Fix anthropic messages history.

## 0.2.0

- Add native tools: filesystem
- Add MCP/tool support for ollama models.
- Improve ollama integration only requiring `ollama serve` to be running.
- Improve chat history and context passed to all LLM providers.
- Add support for prompt caching for Anthropic models.

## 0.1.0

- Allow comments on `json` configs.
- Improve MCP tool call feedback.
- Add support for env vars in mcp configs.
- Add `mcp/serverUpdated` server notification.

## 0.0.4

- Add env support for MCPs
- Add web_search capability
- Add `o3` model support.
- Support custom API urls for OpenAI and Anthropic
- Add `--log-level <level>` option for better debugging.
- Add support for global config file.
- Improve MCP response handling.
- Improve LLM streaming response handler.

## 0.0.3

- Fix ollama servers discovery
- Fix `.eca/config.json` read from workspace root
- Add support for MCP servers

## 0.0.2

- First alpha release

## 0.0.1
