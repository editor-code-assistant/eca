# Privacy

ECA is a local command-line tool that runs entirely on your machine. It does not operate a hosted service, collect analytics, or transmit data to ECA maintainers. This document explains what data ECA handles, where it goes, and what you control.

## Architecture

ECA runs as a local process on your computer. There are no ECA servers, no user accounts, no cookies, and no tracking. All LLM communication goes directly from your machine to the provider you configure — ECA never acts as an intermediary that stores or inspects your data in transit.

## What data is sent to LLM providers

When you use ECA, the following data is sent directly to your configured LLM provider (e.g., Anthropic, OpenAI, GitHub Copilot):

- **System prompt** — ECA's agent instructions, your custom rules, skill metadata, and any context you've attached (file contents, cursor position, repository structure).
- **Conversation messages** — Your prompts, assistant responses, and tool call inputs/outputs (which may include file contents, shell command output, and search results).
- **Tool definitions** — Names and descriptions of available tools (including MCP server tools).

**You control what gets sent.** ECA only includes file contents you explicitly reference or that the assistant reads via tools during the session. No files are sent without being part of the active conversation flow.

## What data is stored locally

ECA stores the following on your machine:

| Location | Contents | Retention |
|---|---|---|
| `~/.config/eca/config.json` | Your configuration (providers, models, settings) | Until you delete it |
| `~/.cache/eca/db.transit.json` | Auth tokens (OAuth, API keys) for providers and MCP servers | Until you delete it or log out |
| `~/.cache/eca/<workspace>/db.transit.json` | Conversation history for each project | Auto-deleted after 7 days |
| `~/.cache/eca/toolCallOutputs/` | Truncated tool call outputs | Auto-deleted after 7 days |
| `~/.cache/eca/plugins/` | Cloned plugin repositories | Until you delete them |
| `~/.local/share/eca/memory/` | Durable memory files and usage ledger (memory feature) | Until you delete it |

Paths respect `$XDG_CONFIG_HOME`, `$XDG_DATA_HOME` and `$XDG_CACHE_HOME` if set. No data is stored remotely by ECA.

## MCP servers

If you configure [MCP (Model Context Protocol)](https://modelcontextprotocol.io) servers, data flows to them as well:

- **Local MCP servers** (stdio transport) run as local processes — data stays on your machine.
- **Remote MCP servers** (HTTP transport) receive tool call arguments, which may include code snippets, file contents, or queries depending on the tool invoked.
- **All MCP tool results are forwarded to your LLM provider** as part of the conversation context.

You choose which MCP servers to configure. ECA ships with no MCP servers enabled by default.

### Slack data via the Slack MCP server

When you connect ECA to [Slack's MCP server](https://docs.slack.dev/ai/slack-mcp-server/) (`https://mcp.slack.com/mcp`):

- Authentication is OAuth 2.0 with PKCE. The OAuth code exchange happens directly between your machine and Slack — no ECA-operated server is involved.
- Slack access and refresh tokens are stored locally on your machine in `~/.cache/eca/db.transit.json`. Tokens never leave your machine except when ECA calls Slack's APIs on your behalf.
- ECA does **not** store, cache, or analyze Slack messages, channels, or files. Slack tool results are returned to your LLM provider as part of the conversation just like any other MCP tool — and only for tools the assistant actually invokes.
- You control which Slack workspaces ECA can access by approving (or revoking) the Slack OAuth grant. To revoke access, remove the ECA app from your workspace at `https://<your-workspace>.slack.com/apps/manage`.
- If you delete `~/.cache/eca/`, all locally stored Slack tokens and any tool-result history are erased.

## Telemetry

ECA includes optional [OpenTelemetry](https://opentelemetry.io) (OTLP) support for exporting metrics.

- **Disabled by default.** Unless you explicitly add an `otlp` section to your config, no telemetry is collected or sent.
- When enabled, only operational metrics are exported (e.g., task counters, OS info, ECA version). **No prompts, file contents, or conversation data are ever included in telemetry.**
- You control the OTLP endpoint — metrics go wherever you configure them.

## Provider privacy policies

ECA supports multiple LLM providers. Your data is governed by the privacy policy of whichever provider you use, these are some of providers that were tested with ECA and their policies:

| Provider | Privacy Policy |
|---|---|
| Anthropic | https://www.anthropic.com/legal/privacy |
| OpenAI | https://openai.com/policies/privacy-policy |
| GitHub Copilot | https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement |
| Google (Gemini) | https://policies.google.com/privacy |
| Azure OpenAI | https://privacy.microsoft.com/en-us/privacystatement |
| DeepSeek | https://www.deepseek.com/privacy |
| OpenRouter | https://openrouter.ai/privacy |
| xAI | https://x.ai/legal/privacy-policy |
| Ollama | Runs locally — no external data transmission |

You can also configure custom providers. In that case, refer to that provider's own privacy documentation.

## What ECA does NOT do

- ❌ Collect personal information (no accounts, emails, or payment data)
- ❌ Send data to ECA maintainers or any third party beyond your configured providers
- ❌ Use cookies, browser tracking, or analytics
- ❌ Sell or share your data
- ❌ Train models on your data (model training policies are determined by each provider)
- ❌ Store your conversations remotely

## Your control

- **Choose your provider** — use a local model via Ollama for fully offline, private operation.
- **Review before sending** — ECA shows you the conversation as it happens; you see what's being sent.
- **Delete local data** — remove `~/.cache/eca/`, `~/.config/eca/` and `~/.local/share/eca/` to erase all ECA data from your machine.
- **API key management** — store keys in environment variables or `.netrc` instead of config files if preferred.

## Questions

If you have privacy-related questions or concerns, please open an issue on the [ECA GitHub repository](https://github.com/editor-code-assistant/eca).
