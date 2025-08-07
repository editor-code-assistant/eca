# Models

## Built-in providers and capabilities


| model     | tools (MCP) | reasoning / thinking | prompt caching | web_search |
|-----------|-------------|----------------------|----------------|------------|
| OpenAI    | √           | √                    | √              | √          |
| Anthropic | √           | √                    | √              | √          |
| Ollama    | √           | √                    | X              | X          |
| Google    | X           | √                    | X              | X          |

### OpenAI

- [o4-mini](https://platform.openai.com/docs/models/o4-mini)
- [o3](https://platform.openai.com/docs/models/o3)
- [gpt-4.1](https://platform.openai.com/docs/models/gpt-4.1)

### Anthropic

- [claude-sonnet-4-0](https://docs.anthropic.com/en/docs/about-claude/models/overview)
- [claude-opus-4-0](https://docs.anthropic.com/en/docs/about-claude/models/overview)
- [claude-3-5-haiku-latest](https://docs.anthropic.com/en/docs/about-claude/models/overview)

### Ollama

- [any local ollama model](https://ollama.com/search)


### Gemini

- https://ai.google.dev/

## Custom providers

ECA support configure extra LLM providers via `customProviders` config, for more details check [configuration](./configuration.md#custom-llm-providers).
