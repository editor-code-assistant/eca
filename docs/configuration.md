# Configuration

## Ways to configure

Check all available configs [here](../src/eca/config.clj#L17).
There are 3 ways to configure ECA following this order of priority:

### InitializationOptions (convenient for editors)

Client editors can pass custom settings when sending the `initialize` request via the `initializationOptions` object:

```javascript
"initializationOptions": {
  "chatBehavior": "agent"
}
```

### Local Config file (convenient for users)

`.eca/config.json`
```javascript
{
  "chatBehavior": "agent"
}
```

### Global config file (convenient for users and multiple projects)

`~/.config/eca/config.json`
```javascript
{
  "chatBehavior": "agent"
}
```

### Env Var

Via env var during server process spawn:

```bash
ECA_CONFIG='{"myConfig": "my_value"}' eca server
```

## Rules

Rules are contexts that are passed to the LLM during a prompt and are useful to tune prompts or LLM behavior.
Rules are Multi-Document context files (`.mdc`) and the following metadata is supported:

- `description`: a description used by LLM to decide whether to include this rule in context, absent means always include this rule.
- `globs`: list of globs separated by `,`. When present the rule will be applied only when files mentioned matches those globs.

There are 3 possible ways to configure rules following this order of priority:

### Project file

A `.eca/rules` folder from the workspace root containing `.mdc` files with the rules.

`.eca/rules/talk_funny.mdc`
```markdown
---
description: Use when responding anything
---

- Talk funny like Mickey!
```

### Global file

A `$XDG_CONFIG_HOME/eca/rules` or `~/.config/eca/rules` folder containing `.mdc` files with the rules.

`~/.config/eca/rules/talk_funny.mdc`
```markdown
---
description: Use when responding anything
---

- Talk funny like Mickey!
```

### Config

Just add to your config the `:rules` pointing to `.mdc` files that will be searched from the workspace root if not an absolute path:

```javascript
{
  "rules": [{"path": "my-rule.mdc"}]
}
```

## MCP

For MCP servers configuration, use the `mcpServers` config, example:

`.eca/config.json`
```javascript
{
  "mcpServers": {
    "memory": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-memory"]
    }
  }
}
```

## Google Gemini

ECA supports Google's Gemini models through two main APIs: the **Gemini Developer API** and the **Vertex AI API**. Each has distinct configuration and authentication methods. Configuration keys are set at the root of the config file.

### Gemini Developer API (API Key)

This is the simplest way to get started. It uses an API key from Google AI Studio.

-   **Authentication:** API Key
-   **Endpoint:** `https://generativelanguage.googleapis.com/v1beta/`
-   **Configuration:**

    ```json
    {
      "geminiApiKey": "YOUR_GEMINI_API_KEY"
    }
    ```

    Alternatively, you can set the `GEMINI_API_KEY` environment variable.

### Vertex AI (API Key)

For projects integrated with Google Cloud Platform (GCP), you can use an API key associated with your GCP project.

-   **Authentication:** API Key (from GCP)
-   **Endpoint:** `https://<LOCATION>-aiplatform.googleapis.com/v1/`
-   **Configuration:**

    ```json
    {
      "googleApiKey": "YOUR_GCP_API_KEY",
      "googleProjectId": "your-gcp-project-id",
      "googleProjectLocation": "your-gcp-region"
    }
    ```
    You can also use the `GOOGLE_API_KEY`, `GOOGLE_PROJECT_ID`, and `GOOGLE_PROJECT_LOCATION` environment variables.

### Vertex AI (Application Default Credentials - ADC)

For a more secure and flexible setup, you can use Application Default Credentials (ADC). This method is used when no `googleApiKey` is provided.

There are two common ways to provide these credentials:

1.  **User ADC (for local development):**
    Authenticate with the `gcloud` CLI. ECA will automatically pick up the credentials.
    ```bash
    gcloud auth application-default login
    ```

2.  **Service Account ADC (for automated environments):**
    Set the `GOOGLE_APPLICATION_CREDENTIALS` environment variable to the absolute path of your service account JSON file.
    ```bash
    export GOOGLE_APPLICATION_CREDENTIALS="/path/to/your/service-account-file.json"
    ```
    Alternatively, you can set this path in your configuration file:
    ```json
    {
      "googleApplicationCredentials": "/path/to/your/service-account-file.json"
    }
    ```

In all ADC scenarios, you still need to provide the project ID and location:

```json
{
  "googleProjectId": "your-gcp-project-id",
  "googleProjectLocation": "your-gcp-region"
}
```
You can also set the `GOOGLE_PROJECT_ID` and `GOOGLE_PROJECT_LOCATION` environment variables.

**Summary of Configuration Options:**

| Key                              | Environment Variable                 | Description                                                                  | Required For                               |
| -------------------------------- | ------------------------------------ | ---------------------------------------------------------------------------- | ------------------------------------------ |
| `geminiApiKey`                   | `GEMINI_API_KEY`                     | API key for the Gemini Developer API.                                        | Gemini Developer API                       |
| `googleApiKey`                   | `GOOGLE_API_KEY`                     | API key for Vertex AI. If not provided, ADC will be used.                    | Vertex AI (API Key)                        |
| `googleProjectId`                | `GOOGLE_PROJECT_ID`                  | Your Google Cloud project ID.                                                | Vertex AI (API Key), Vertex AI (ADC)       |
| `googleProjectLocation`          | `GOOGLE_PROJECT_LOCATION`            | The GCP region for your project (e.g., `us-central1`).                       | Vertex AI (API Key), Vertex AI (ADC)       |


## Custom LLM providers

It's possible to configure ECA to be aware of custom LLM providers if they follow a API schema similar to currently supported ones (openai, anthropic), example for a custom hosted litellm server:

```javascript
{
  "customProviders": {
    "my-company": {
       "api": "openai",
       "urlEnv": "MY_COMPANY_API_URL", // or "url": "https://litellm.my-company.com",
       "keyEnv": "MY_COMPANY_API_KEY", // or "key": "123",
       "models": ["gpt-4.1", "deepseek-r1"],
       "defaultModel": "deepseek-r1"
    }
  }
}
```

With that, ECA will include in the known models something like: `my-company/gpt-4.1`, `my-company/deepseek-r1`.

## All configs

### Schema

```typescript
interface Config {
    openaiApiKey?: string;
    anthropicApiKey?: string;
    geminiApiKey?: string;
    googleApiKey?: string;
    googleProjectId?: string;
    googleProjectLocation?: string;
    rules: [{path: string;}];
    systemPromptTemplate?: string;
    nativeTools: {
        filesystem: {enabled: boolean};
         shell: {enabled: boolean;
                 excludeCommands: string[]};
    };
    disabledTools: string[],
    toolCall?: {
      manualApproval?: boolean,
    };
    mcpTimeoutSeconds: number;
    mcpServers: {[key: string]: {
        command: string;
        args?: string[];
        disabled?: boolean;
    }};
    customProviders: {[key: string]: {
        api: 'openai' | 'anthropic';
        models: string[];
        defaultModel?: string;
        url?: string;
        urlEnv?: string;
        key?: string;
        keyEnv?: string;
    }};
    ollama?: {
        host: string;
        port: string;
        useTools: boolean;
        think: boolean;
    };
    chat?: {
        welcomeMessage: string;
    };
    index?: {
        ignoreFiles: [{
            type: string;
        }];
    };
}
```

### Default values

```javascript
{
  "openaiApiKey" : null,
  "anthropicApiKey" : null,
  "geminiApiKey": null,
  "googleApiKey": null,
  "googleProjectId": null,
  "googleProjectLocation": null,
  "rules" : [],
  "nativeTools": {"filesystem": {"enabled": true},
                  "shell": {"enabled": true,
                            "excludeCommands": []}},
  "disabledTools": [],
  "toolCall": {
    "manualApproval": false,
  },
  "mcpTimeoutSeconds" : 10,
  "mcpServers" : [],
  "customProviders": {},
  "ollama" : {
    "host" : "http://localhost",
    "port" : 11434,
    "useTools": true,
    "think": true
  },
  "chat" : {
    "welcomeMessage" : "Welcome to ECA! What you have in mind?\n\n"
  },
  "index" : {
    "ignoreFiles" : [ {
      "type" : "gitignore"
    } ]
  }
}
```
