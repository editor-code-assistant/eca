# Changelog

## Unreleased

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
