# Models

!!! info Login

    Most providers can be configured via `/login` command, otherwise via `providers` config.

Models capabilities and configurations are retrieved from [models.dev](https://models.dev) API.

ECA will return to clients the models configured, either via config or login.

## Built-in providers and capabilities

| model                         | tools (MCP) | reasoning / thinking | prompt caching | web_search | image_input |
|-------------------------------|-------------|----------------------|----------------|------------|-------------|
| OpenAI                        | √           | √                    | √              | √          | √           |
| Anthropic (Also subscription) | √           | √                    | √              | √          | √           |
| Github Copilot                | √           | √                    | √              | X          | √           |
| Ollama local models           | √           | √                    | X              | X          |             |

### Config

Built-in providers have already base initial `providers` configs, so you can change to add models or set its key/url.

For more details, check the [config schema](./configuration.md#all-configs).

Example:

`~/.config/eca/config.json`
```javascript
{
  "providers": {
    "openai": {
      "key": "your-openai-key-here", // configuring a key
      "models": { 
        "o1": {} // adding models to a built-in provider
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

## Custom providers

ECA allows you to configure custom LLM providers that follow API schemas similar to OpenAI or Anthropic. This is useful when you want to use:

- Self-hosted LLM servers (like LiteLLM)
- Custom company LLM endpoints
- Additional cloud providers not natively supported

You just need to add your provider to `providers` and make sure add the required fields

Schema:

| Option                         | Type   | Description                                                                                         | Required |
|--------------------------------|--------|-----------------------------------------------------------------------------------------------------|----------|
| `api`                          | string | The API schema to use (`"openai-responses"`, `"openai-chat"`, or `"anthropic"`)                   | Yes      |
| `urlEnv`                       | string | Environment variable name containing the API URL                                                    | No*      |
| `url`                          | string | Direct API URL (use instead of `urlEnv`)                                                            | No*      |
| `keyEnv`                       | string | Environment variable name containing the API key                                                    | No*      |
| `key`                          | string | Direct API key (use instead of `keyEnv`)                                                            | No*      |
| `completionUrlRelativePath`    | string | Optional override for the completion endpoint path (see defaults below and examples like Azure)     | No       |
| `models`                       | map    | Key: model name, value: its config                                                                  | Yes      |
| `models <model> extraPayload`  | map    | Extra payload sent in body to LLM                                                                   | No       |

_* url and key will be search as env `<provider>_API_URL` / `<provider>_API_KEY`, but require config or to be found to work._

Example:

`~/.config/eca/config.json`
```javascript
{
  "providers": {
    "my-company": {
      "api": "openai-chat",
      "urlEnv": "MY_COMPANY_API_URL", // or "url"
      "keyEnv": "MY_COMPANY_API_KEY", // or "key"
      "models": {
        "gpt-5": {},
        "deepseek-r1": {}
       }
    }
  }
}
```

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

## Providers examples

=== "Anthropic"
    
    1. Login to Anthropic via the chat command `/login`.
    2. Type 'anthropic' and send it.
    3. Type the chosen method
    4. Authenticate in your browser, copy the code.
    5. Paste and send the code and done!

=== "Github Copilot"
    
    1. Login to Github copilot via the chat command `/login`.
    2. Type 'github-copilot' and send it.
    3. Authenticate in Github in your browser with the given code.
    4. Type anything in the chat to continue and done!
    
    _Tip: check [Your Copilot plan](https://github.com/settings/copilot/features) to enable models to your account._

=== "LiteLLM"

    ```javascript
    {
      "providers": {
        "litellm": {
          "api": "openai-responses",
          "url": "https://litellm.my-company.com", // or "urlEnv"
          "key": "your-api-key", // or "keyEnv"
          "models": {
            "gpt-5": {},
            "deepseek-r1": {}
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
    
    ```javascript
    {
      "providers": {
        "openrouter": {
          "api": "openai-chat",
          "url": "https://openrouter.ai/api/v1", // or "urlEnv"
          "key": "your-api-key", // or "keyEnv"
          "models": {
            "anthropic/claude-3.5-sonnet": {},
            "openai/gpt-4-turbo": {},
            "meta-llama/llama-3.1-405b": {}
           }
        }
      }
    }
    ```

=== "DeepSeek"

    [DeepSeek](https://deepseek.com) offers powerful reasoning and coding models:
    
    1. Login via the chat command `/login`.
    2. Type 'deepseek' and send it.
    3. Specify your Deepseek API key.
    4. Inform at least a model, ex: `deepseek-chat`
    5. Done, it should be saved to your global config.
    
    or manually via config:
    
    ```javascript
    {
      "providers": {
        "deepseek": {
          "api": "openai-chat",
          "url": "https://api.deepseek.com", // or "urlEnv"
          "key": "your-api-key", // or "keyEnv"
          "models": {
            "deepseek-chat": {},
            "deepseek-coder": {},
            "deepseek-reasoner": {} 
           }
        }
      }
    }
    ```

=== "Azure OpenAI"

    1. Login via the chat command `/login`.
    2. Type 'azure' and send it.
    3. Specify your API key.
    4. Specify your API url with your resource, ex: 'https://your-resource-name.openai.azure.com'.
    5. Inform at least a model, ex: `gpt-5`
    6. Done, it should be saved to your global config.
    
    or manually via config:

    ```javascript
    {
      "providers": {
        "azure": {
          "api": "openai-responses",
          "url": "https://your-resource-name.openai.azure.com", // or "urlEnv"
          "key": "your-api-key", // or "keyEnv"
          "completionUrlRelativePath": "/openai/responses?api-version=2025-04-01-preview",
          "models": {
            "gpt-5": {}
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

    ```javascript
    {
      "providers": {
        "z-ai": {
          "api": "anthropic",
          "url": "https://api.z.ai/api/anthropic",
          "key": "your-api-key", // or "keyEnv"
          "models": {
            "GLM-4.5": {},
            "GLM-4.5-Air": {}
           }
        }
      }
    }
    ```

=== "Same model with different settings"

    For now, you can create different providers with same model names to achieve that:
   
    ```javascript
    {
     "providers": {
       "openai": {
         "api": "openai-responses",
         "models": { "gpt-5": {} }
       },
       "openai-high": {
         "api": "openai-responses",
         "url": "https://api.openai.com",
         "keyEnv": "OPENAI_API_KEY",
         "models": {
           "gpt-5": {
             "extraPayload": { "reasoning": { "effort": "high" } }
           }
         }
       }
     }
    }
    ```
