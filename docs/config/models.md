# Models

!!! info Login

    Most providers can be configured via `/login` command, otherwise via `providers` config.

Models capabilities and configurations are retrieved from [models.dev](https://models.dev) API.

ECA will return to clients the models configured, either via config or login.

## Built-in providers and capabilities

| model                                            | tools (MCP) | reasoning / thinking | prompt caching | web_search | image_input |
|--------------------------------------------------|-------------|----------------------|----------------|------------|-------------|
| :simple-openai: OpenAI (Also subscription)       | √           | √                    | √              | √          | √           |
| :simple-anthropic: Anthropic (Also subscription) | √           | √                    | √              | √          | √           |
| :simple-githubcopilot: Github Copilot            | √           | √                    | √              | X          | √           |
| :simple-googlegemini: Google                     | √           | √                    | √              | X          | √           |
| :simple-ollama: Ollama local models              | √           | √                    | X              | X          |             |

## Config

Built-in providers have already base initial `providers` configs, so you can change to add models or set its key/url.

For more details, check the [config schema](./introduction.md).

Example:

```javascript title="~/.config/eca/config.json"
{
  "providers": {
    "openai": {
      "key": "your-openai-key-here", // configuring a key
      "models": {
        "o1": {}, // adding models to a built-in provider
        "o3": {
          "extraPayload": { // adding to the payload sent to LLM
            "temperature": 0.5
          }
        }
      }
    }
  }
}
```

**Environment Variables**: You can also set API keys using environment variables following `"<PROVIDER>_API_KEY"`, examples:

- `OPENAI_API_KEY` for OpenAI
- `ANTHROPIC_API_KEY` for Anthropic

!!! info "Variants"

    ECA supports the variants concept, allowing to customize the payload of models and quickly changing via UI, __useful for swaping different reasoning efforts__, for more information check [variants section](./variants.md)

## Custom providers

ECA allows you to configure custom LLM providers that follow API schemas similar to OpenAI or Anthropic. This is useful when you want to use:

- Self-hosted LLM servers (like LiteLLM)
- Custom company LLM endpoints
- Additional cloud providers not natively supported

You just need to add your provider to `providers` and make sure add the required fields

Schema:

| Option                            | Type    | Description                                                                                                  | Required |
|-----------------------------------|---------|--------------------------------------------------------------------------------------------------------------|----------|
| `api`                             | string  | The API schema to use (`"openai-responses"`, `"openai-chat"`, or `"anthropic"`)                              | Yes      |
| `url`                             | string  | API URL (with support for env like `${env:MY_URL}`)                                                          | No*      |
| `key`                             | string  | API key (with support for `${env:MY_KEY}` or `{netrc:api.my-provider.com}`                                   | No*      |
| `completionUrlRelativePath`       | string  | Optional override for the completion endpoint path (see defaults below and examples like Azure)              | No       |
| `thinkTagStart`                   | string  | Optional override the think start tag tag for openai-chat (Default: "<think>") api                           | No       |
| `thinkTagEnd`                     | string  | Optional override the think end tag for openai-chat (Default: "</think>") api                                | No       |
| `httpClient`                      | map     | Allow customize the http-client for this provider requests, like changing http version                       | No       |
| `models`                          | map     | Key: model name, value: its config                                                                           | Yes      |
| `models <model> extraPayload`     | map     | Extra payload sent in body to LLM                                                                            | No       |
| `models <model> extraHeaders`     | map     | Extra headers sent to LLM request                                                                            | No       |
| `models <model> modelName`        | string  | Override model name, useful to have multiple models with different configs and names that use same LLM model | No       |
| `models <model> reasoningHistory` | string  | Controls reasoning in conversation history: `"all"` (default), `"turn"`, or `"off"`                          | No       |
| `fetchModels`                     | boolean | Enable/disable automatic model loading from `models.dev` (enabled by default when `api` is set)              | No       |

_* url and key will be searched as envs `<provider>_API_URL` and `<provider>_API_KEY`, they require the env to be found or config to work._

Examples:

=== "Custom provider"

    ```javascript title="~/.config/eca/config.json"
    {
      "providers": {
        "my-company": {
          "api": "openai-chat",
          "url": "${env:MY_COMPANY_API_URL}",
          "key": "${env:MY_COMPANY_API_KEY}",
          "models": {
            "gpt-5": {},
            "deepseek-r1": {}
           }
        }
      }
    }
    ```

=== "Custom model settings / payload / header"

    Using `modelName`, you can configure multiple model names using same model with different settings:

    ```javascript title="~/.config/eca/config.json"
    {
      "providers": {
        "openai": {
          "api": "openai-responses",
          "models": {
            "gpt-5": {},
            "gpt-5-high": {
              "modelName": "gpt-5",
              "extraPayload": { "reasoning": {"effort": "high"}},
              "extraHeaders": { "User-Agent": "MyUserAgent"}
            }
          }
        }
      }
    }
    ```

    This way both will use gpt-5 model but one will override the reasoning to be high instead of the default.

=== "Reasoning in conversation history"
	`reasoningHistory` - Controls whether and how the model's reasoning (thinking blocks, reasoning_content) is included in conversation history sent to the model.
	This **only applies** to `openai_chat` API and it controls both tag-based thinking and the preservation of  `reasoning_content`.

	**Available modes:**

	- **`"all"`** (default, safe choice) - Send all reasoning blocks back to the model. The model can see its full chain of thought from previous turns. This is the safest option.
	- **`"turn"`** - Send only reasoning from the current conversation turn (after the last user message). Previous reasoning is discarded before sending to the API.
	- **`"off"`** - Never send reasoning blocks to the model. All reasoning is discarded before API calls.

	**Note:** Reasoning is always shown to you in the UI and stored in chat history—this setting only controls what gets sent to the model in API requests.

    Default: `"all"`.

=== "Dynamic model discovery (models.dev)"

    For providers with `api` configured, ECA loads models from `models.dev` by default.
    Matching is done by provider `url` (against `models.dev` `api` field). If a models.dev provider has no `api` field (for example `anthropic`), ECA falls back to provider id key.

    Set `fetchModels: false` to disable dynamic loading and use only models from your config:

    ```javascript title="~/.config/eca/config.json"
    {
      "providers": {
        "openrouter": {
          "api": "openai-chat",
          "url": "https://openrouter.ai/api/v1",
          "key": "your-api-key",
          "fetchModels": false
        }
      }
    }
    ```

    Static `models` config overrides/extends discovered models, allowing customization.

### API Types

When configuring custom providers, choose the appropriate API type:

- **`anthropic`**: Anthropic's native API for Claude models.
- **`openai-responses`**: OpenAI's new responses API endpoint (`/v1/responses`). Best for OpenAI models with enhanced features like reasoning and web search.
- **`openai-chat`**: Standard OpenAI Chat Completions API (`/v1/chat/completions`). Use this for most third-party providers:
    - OpenRouter
    - DeepSeek
    - Together AI
    - Groq
    - Local LiteLLM servers
    - Any OpenAI-compatible provider

Most third-party providers use the `openai-chat` API for compatibility with existing tools and libraries.

#### Endpoint override (completionUrlRelativePath)

Some providers require a non-standard or versioned completion endpoint path. Use `completionUrlRelativePath` to override the default path appended to your provider `url`.

Defaults by API type:
- `openai-responses`: `/v1/responses`
- `openai-chat`: `/v1/chat/completions`
- `anthropic`: `/v1/messages`

Only set this when your provider uses a different path or expects query parameters at the endpoint (e.g., Azure API versioning).

### Credential File Authentication

ECA also supports standard plain-text .netrc file format for reading credentials.

Use `keyRc` in your provider config to read credentials from `~/.netrc` without storing keys directly in config or env vars.

Example:

```javascript title="~/.config/eca/config.json"
{
  "providers": {
    "openai": {"keyRc": "api.openai.com"},
    "anthropic": {"keyRc": "work@api.anthropic.com"}
  }
}
```

keyRc lookup specification format: `[login@]machine[:port]` (e.g., `api.openai.com`, `work@api.anthropic.com`, `api.custom.com:8443`).

ECA by default search .netrc file stored in user's home directory. You can also provide the path to the actual file to use with `:netrcFile` in ECA config.

Tip for those wish to store their credentials encrypted with tools like gpg or age:

```bash
# via secure tempororay file
gpg --batch -q -d ./netrc.gpg > /tmp/netrc.$$ && chmod 600 /tmp/netrc.$$ && ECA_CONFIG='{"netrcFile": "/tmp/netrc.$$"}' eca server && shred -u /tmp/netrc.$$
```

Further reading on credential file formats:
- [Curl Netrc documentation](https://everything.curl.dev/usingcurl/netrc)
- [GNU Inetutils .netrc documentation](https://www.gnu.org/software/inetutils/manual/html_node/The-_002enetrc-file.html)

Notes:
- Authentication priority (short): `key` (with dynamic string pase support) > OAuth.
- All providers with API key auth can use credential files.

## Providers examples

=== "Anthropic"

    1. Login to Anthropic via the chat command `/login`.
    2. Type 'anthropic' and send it.
    3. Type the chosen method
    4. Authenticate in your browser, copy the code.
    5. Paste and send the code and done!
    
    Warning: Using your Claude Pro/Max subscription in ECA is not officially supported by [Anthropic](https://anthropic.com) and ECA are not responsible for any actions on your account.
    
=== "Azure OpenAI"

    1. Login via the chat command `/login`.
    2. Type 'azure' and send it.
    3. Specify your API key.
    4. Specify your API url with your resource, ex: 'https://your-resource-name.openai.azure.com'.
    5. Inform at least a model, ex: `gpt-5`
    6. Done, it should be saved to your global config.

    or manually via config:

    ```javascript title="~/.config/eca/config.json"
    {
      "providers": {
        "azure": {
          "api": "openai-responses",
          "url": "https://your-resource-name.openai.azure.com",
          "key": "your-api-key",
          "completionUrlRelativePath": "/openai/responses?api-version=2025-04-01-preview",
          "models": {
            "gpt-5": {}
           }
        }
      }
    }
    ```

=== "Codex / Openai"

    1. Login to Openai via the chat command `/login`.
    2. Type 'openai' and send it.
    3. Type the chosen method
    4. Authenticate in your browser, copy the code.
    5. Paste and send the code and done!
    
=== "DeepSeek"

    [DeepSeek](https://deepseek.com) offers powerful reasoning and coding models:

    1. Login via the chat command `/login`.
    2. Type 'deepseek' and send it.
    3. Specify your Deepseek API key.
    4. Inform at least a model, ex: `deepseek-chat`
    5. Done, it should be saved to your global config.

    or manually via config:

    ```javascript title="~/.config/eca/config.json"
    {
      "providers": {
        "deepseek": {
          "api": "openai-chat",
          "url": "https://api.deepseek.com",
          "key": "your-api-key",
          "models": {
            "deepseek-chat": {},
            "deepseek-coder": {},
            "deepseek-reasoner": {}
           }
        }
      }
    }
    ```

=== "Github Copilot"

    1. Login to Github copilot via the chat command `/login`.
    2. Type 'github-copilot' and send it.
    3. Authenticate in Github in your browser with the given code.
    4. Type anything in the chat to continue and done!

    _Tip: check [Your Copilot plan](https://github.com/settings/copilot/features) to enable models to your account._

=== "Google / Gemini"

    1. Login to Google via the chat command `/login`.
    2. Type 'google' and send it.
    3. Choose 'manual' and type your Google/Gemini API key. (You need to create a key in [google studio](https://aistudio.google.com/api-keys))

=== "LiteLLM"

    ```javascript title="~/.config/eca/config.json"
    {
      "providers": {
        "litellm": {
          "api": "openai-responses",
          "url": "https://litellm.my-company.com",
          "key": "your-api-key",
          "models": {
            "gpt-5": {},
            "deepseek-r1": {}
           }
        }
      }
    }
    ```
    
=== "LM Studio"

    This config works with LM studio:

    ```javascript title="~/.config/eca/config.json"
    {
      "providers": {
        "lmstudio": {
            "api": "openai-chat",
            "url": "http://localhost:1234",
            "completionUrlRelativePath": "/v1/chat/completions",
            "httpClient": {
                "version": "http-1.1"
            },
            "models": {
                "your-model": {}
            }
        }
      }
    }
    ```
    
=== "Moonshot"

    ```javascript title="~/.config/eca/config.json"
    {
      "providers": {
        "moonshot": {
          "api": "openai-chat",
          "url": "https://api.kimi.com/coding/v1",
          "key": "your-api-key",
          "models": {
            "kimi-k2.5": {"extraHeaders": {"User-Agent": "KimiCLI/1.3" }},
            "kimi-k2-thinking": {"extraHeaders": {"User-Agent": "KimiCLI/1.3" }},
            "kimi-for-coding": {"extraHeaders": {"User-Agent": "KimiCLI/1.3" }}
          }
        }
      }
    }
    ```

=== "OpenRouter"

    [OpenRouter](https://openrouter.ai) provides access to many models through a unified API:

    1. Login via the chat command `/login`.
    2. Type 'openrouter' and send it.
    3. Specify your Openrouter API key.
    4. Inform at least a model, ex: `openai/gpt-5`
    5. Done, it should be saved to your global config.

    or manually via config:

    ```javascript title="~/.config/eca/config.json"
    {
      "providers": {
        "openrouter": {
          "api": "openai-chat",
          "url": "https://openrouter.ai/api/v1",
          "key": "your-api-key",
          "models": {
            "anthropic/claude-3.5-sonnet": {},
            "openai/gpt-4-turbo": {},
            "meta-llama/llama-3.1-405b": {}
           }
        }
      }
    }
    ```

=== "Z.ai"

    1. Login via the chat command `/login`.
    2. Type 'azure' and send it.
    3. Specify your API key.
    4. Inform at least a model, ex: `GLM-4.5`
    5. Done, it should be saved to your global config.

    or manually via config:

    ```javascript title="~/.config/eca/config.json"
    {
      "providers": {
        "z-ai": {
          "api": "anthropic",
          "url": "https://api.z.ai/api/anthropic",
          "key": "your-api-key",
          "models": {
            "GLM-4.5": {},
            "GLM-4.5-Air": {}
           }
        }
      }
    }
    ```
