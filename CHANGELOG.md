# Changelog

## Unreleased

- Drop `agentFileRelativePath` in favor of behaviors customizations in the future.
- Unwrap `chat` config to be at root level.
- Fix token expiration for copilot and anthropic.

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
