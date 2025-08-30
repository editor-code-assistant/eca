# Configuration

Check all available configs and its default values [here](#all-configs).

## Ways to configure

There are multiples ways to configure ECA:

=== "Global config file"

    Convenient for users and multiple projects

    `~/.config/eca/config.json`
    ```javascript
    {
      "chat" : {
        "defaultBehavior": "plan"
      }
    }
    ```

=== "Local Config file"

    Convenient for users

    `.eca/config.json`
    ```javascript
    {
      "chat" : {
        "defaultBehavior": "plan"
      }
    }
    ```

=== "InitializationOptions"

    Convenient for editors

    Client editors can pass custom settings when sending the `initialize` request via the `initializationOptions` object:

    ```javascript
    "initializationOptions": {
      "chat" : {
        "defaultBehavior": "plan"
      }
    }
    ```

=== "Env var"

    Via env var during server process spawn:

    ```bash
    ECA_CONFIG='{"myConfig": "my_value"}' eca server
    ```

## Providers / Models

For providers and models configuration check the [dedicated models section](./models.md#custom-providers).

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

### Manual approval

By default, ECA auto approve any tool call from LLM, to configure that or for which tools, check `toolCall manualApproval` config or try the `plan` behavior before.

## Custom command prompts

You can configure custom command prompts for project, global or via `commands` config pointing to the path of the commands.
Prompts can use variables like `$ARGS`, `$ARG1`, `ARG2`, to replace in the prompt during command call.

=== "Local custom commands"

    A `.eca/commands` folder from the workspace root containing `.md` files with the custom prompt.

    `.eca/commands/check-performance.md`
    ```markdown
    Check for performance issues in $ARG1 and optimize if needed.
    ```

=== "Global custom commands"

    A `$XDG_CONFIG_HOME/eca/commands` or `~/.config/eca/commands` folder containing `.md` files with the custom command prompt.

    `~/.config/eca/commands/check-performance.md`
    ```markdown
    Check for performance issues in $ARG1 and optimize if needed.
    ```

=== "Config"

    Just add to your config the `commands` pointing to `.md` files that will be searched from the workspace root if not an absolute path:

    ```javascript
    {
      "commands": [{"path": "my-custom-prompt.md"}]
    }
    ```

## Rules

Rules are contexts that are passed to the LLM during a prompt and are useful to tune prompts or LLM behavior.
Rules are text files (typically `.md`, but any format works) with the following
optional metadata:

- `description`: a description used by LLM to decide whether to include this rule in context, absent means always include this rule.
- `globs`: list of globs separated by `,`. When present the rule will be applied only when files mentioned matches those globs.

There are 3 possible ways to configure rules following this order of priority:

=== "Project file"

    A `.eca/rules` folder from the workspace root containing `.md` files with the rules.

    `.eca/rules/talk_funny.md`
    ```markdown
    ---
    description: Use when responding anything
    ---

    - Talk funny like Mickey!
    ```

=== "Global file"

    A `$XDG_CONFIG_HOME/eca/rules` or `~/.config/eca/rules` folder containing `.md` files with the rules.

    `~/.config/eca/rules/talk_funny.md`
    ```markdown
    ---
    description: Use when responding anything
    ---

    - Talk funny like Mickey!
    ```

=== "Config"

    Just add toyour config the `:rules` pointing to `.md` files that will be searched from the workspace root if not an absolute path:

    ```javascript
    {
      "rules": [{"path": "my-rule.md"}]
    }
    ```

## User-Defined Tools

ECA supports user-defined tools, allowing you to add your own custom bash scripts as tools that can be invoked by the agent or plan behaviors. This is configured via the `userTools` key in your config file.

### Example

Add a `userTools` section to your `.eca/config.json` or global config:

```json
{
  "userTools": {
    "hello": {
      "bash": "echo Hello, {{ name }} !",
      "schema": {
        "args": {
          "name": {
            "type": "string",
            "description": "Name to greet"
          }
        },
        "required": ["name"]
      },
      "description": "Say hello to someone",
      "requireApproval": false,
      "enabledInPlanMode": true
    }
  }
}
```

- `bash`: The bash command to run. You can use `{{ argname }}` for parameter substitution.
- `schema`: Defines the arguments, types, descriptions, and which are required.
- `description`: (optional) Shown in tool lists and completions.
- `requireApproval`: (optional) If true, requires user approval before running.
- `enabledInPlanMode`: (optional) If true, tool can be used in plan mode.

### Security

User tools run as local shell scripts. Only add tools you trust. Use `requireApproval` for sensitive commands.

### Usage

Once configured, user tools are available like built-in tools. You can invoke them by name (e.g., `/tool hello name=World`).

---

## All configs

=== "Schema"

```typescript
interface Config {
    providers: {[key: string]: {
        api?: 'openai-responses' | 'openai-chat' | 'anthropic';
        url?: string;
        urlEnv?: string;
        key?: string; // when provider supports api key.
        keyEnv?: string;
        completionUrlRelativePath?: string;
        models: {[key: string]: {
          extraPayload?: {[key: string]: any}
        }};
    }};
    defaultModel?: string;
    rules: [{path: string;}];
    commands: [{path: string;}];
    systemPromptTemplateFile?: string;
    nativeTools?: {
        filesystem: {enabled: boolean};
        shell: {enabled: boolean,
                excludeCommands: string[]};
        editor: {enabled: boolean,};
    };
    userTools?: {[key: string]: {
        bash: string;
        schema: {
            required?: string[];
            args: {[key: string]: {
                type: string;
                description: string;
                default?: any;
            }};
        };
        description?: string;
        requireApproval?: boolean;
        enabledInPlanMode?: boolean;
    }};
    disabledTools: string[],
    toolCall?: {
      manualApproval?: boolean | string[], // manual approve all tools or the specified tools
    };
    mcpTimeoutSeconds: number;
    lspTimeoutSeconds: number;
    mcpServers: {[key: string]: {
        command: string;
        args?: string[];
        disabled?: boolean;
    }};
    chat?: {
        defaultBehavior?: string;
        welcomeMessage?: string;
    };
    agentFileRelativePath: string;
    index?: {
        ignoreFiles: [{
            type: string;
        }];
        repoMap?: {
            maxTotalEntries?: number;
            maxEntriesPerDir?: number;
        };
    };
}
```


=== "Default values"

    ```javascript
    {
      "providers": {
          "openai": {"key": null,
                     "url": "https://api.openai.com"},
          "anthropic": {"key": null,
                        "url": "https://api.anthropic.com"},
          "github-copilot": {"url": "https://api.githubcopilot.com"},
          "ollama": {"url": "http://localhost:11434"}
      },
      "defaultModel": null, // let ECA decides the default model.
      "rules" : [],
      "commands" : [],
      "nativeTools": {"filesystem": {"enabled": true},
                      "shell": {"enabled": true,
                                "excludeCommands": []},
                       "editor": {"enabled": true}},
      "disabledTools": [],
      "toolCall": {
        "manualApproval": null,
      },
      "mcpTimeoutSeconds" : 60,
      "lspTimeoutSeconds" : 30,
      "mcpServers" : {},
      "chat" : {
        "defaultBehavior": "agent"
        "welcomeMessage" : "Welcome to ECA!\n\nType '/' for commands\n\n"
      },
      "agentFileRelativePath": "AGENTS.md"
      "index" : {
        "ignoreFiles" : [ {
          "type" : "gitignore"
        } ],
        "repoMap": {
          "maxTotalEntries": 800,
          "maxEntriesPerDir": 50
        }
      }
    }
    ```
