# Configuration

Check all available configs and its default values [here](#all-configs).

## Ways to configure

There are multiples ways to configure ECA:

=== "Global config file"

    Convenient for users and multiple projects

    `~/.config/eca/config.json`
    ```javascript
    {
      "defaultBehavior": "plan"
    }
    ```

=== "Local Config file"

    Convenient for users

    `.eca/config.json`
    ```javascript
    {
      "defaultBehavior": "plan"
    }
    ```

=== "InitializationOptions"

    Convenient for editors

    Client editors can pass custom settings when sending the `initialize` request via the `initializationOptions` object:

    ```javascript
    "initializationOptions": {
      "defaultBehavior": "plan"
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

### Tool approval / permissions

By default, ECA ask to call any tool, but that's can easily be configureed in many ways via the `toolCall approval` config.

You can configure the default behavior via `byDefault` and/or configure a tool in `ask`, `allow` or `deny` configs.

Check some examples:

=== "Allow any tools by default"

    ```javascript
    {
      "toolCall": {
        "approval": {
          "byDefault": "allow"
        }
      }
    }
    ```

=== "Allow all but some tools"

    ```javascript
    {
      "toolCall": {
        "approval": {
          "byDefault": "allow",
          "ask": {
            "eca_editfile": {},
            "my-mcp__my_tool": {}
          }
        }
      }
    }
    ```

=== "Ask all but all tools from some mcps"

    ```javascript
    {
      "toolCall": {
        "approval": {
          // "byDefault": "ask", not needed as it's eca default
          "allow": {
            "eca": {},
            "my-mcp": {}
          }
        }
      }
    }
    ```
   
=== "Matching by a tool argument"

    __`argsMatchers`__ is a map of argument name by list of [java regex](https://www.regexplanet.com/advanced/java/index.html).

    ```javascript
    {
      "toolCall": {
        "approval": {
          "byDefault": "allow",
          "allow": {
            "eca_shell_command": {"argsMatchers" {"command" [".*rm.*",
                                                             ".*mv.*"]}}
          }
        }
      }
    }
    ```
    
=== "Denying a tool"

    ```javascript
    {
      "toolCall": {
        "approval": {
          "byDefault": "allow",
          "deny": {
            "eca_shell_command": {"argsMatchers" {"command" [".*rm.*",
                                                             ".*mv.*"]}}
          }
        }
      }
    }
    ```

Also check the `plan` behavior which is safer.

__The `manualApproval` setting was deprecated and replaced by the `approval` one without breaking changes__

## Custom Tools

You can define your own command-line tools that the LLM can use. These are configured via the `customTools` key in your `config.json`.

The `customTools` value is an object where each key is the name of your tool. Each tool definition has the following properties:

-   `description`: A clear description of what the tool does. This is crucial for the LLM to decide when to use it.
-   `command`: An array of strings representing the command and its static arguments.
-   `schema`: An object that defines the parameters the LLM can provide.
    -   `properties`: An object where each key is an argument name.
    -   `required`: An array of required argument names.

Placeholders in the format `{{argument_name}}` within the `command` array will be replaced by the values provided by the LLM.

=== "Example config.json"

    ```javascript
    {
      "customTools": {
        "web-search": {
          "description": "Fetches the content of a URL and returns it in Markdown format.",
          "command": ["trafilatura", "--output-format=markdown", "-u", "{{url}}"],
          "schema": {
            "properties": {
              "url": {
                "type": "string",
                "description": "The URL to fetch content from."
              }
            },
            "required": ["url"]
          }
        },
        "file-search": {
          "description": "Finds files within a directory that match a specific name pattern.",
          "command": ["find", "{{directory}}", "-name", "{{pattern}}"],
          "schema": {
            "properties": {
              "directory": {
                "type": "string",
                "description": "The directory to start the search from."
              },
              "pattern": {
                "type": "string",
                "description": "The search pattern for the filename (e.g., '*.clj')."
              }
            },
            "required": ["directory", "pattern"]
          }
        }
      }
    }
    ```


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

## All configs

=== "Schema"

    ```typescript
    interface Config {
        providers?: {[key: string]: {
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
        rules?: [{path: string;}];
        commands?: [{path: string;}];
        systemPromptTemplateFile?: string;
        nativeTools?: {
            filesystem: {enabled: boolean};
            shell: {enabled: boolean,
                    excludeCommands: string[]};
            editor: {enabled: boolean,};
        };
        customTools?: {[key: string]: {
            description: string;
            command: string[];
            schema: {
                properties: {[key: string]: {
                    type: string;
                    description: string;
                }};
                required: string[];
            };
        }};
        disabledTools?: string[],
        toolCall?: {
          approval?: {
            byDefault: 'ask' | 'allow';
            allow?: {{key: string}: {argsMatchers?: {{[key]: string}: string[]}}},
            ask?: {{key: string}: {argsMatchers?: {{[key]: string}: string[]}}},
            deny?: {{key: string}: {argsMatchers?: {{[key]: string}: string[]}}},
          };
        };
        mcpTimeoutSeconds?: number;
        lspTimeoutSeconds?: number;
        mcpServers?: {[key: string]: {
            command: string;
            args?: string[];
            disabled?: boolean;
        }};
        defaultBehavior?: string;
        welcomeMessage?: string;
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
        "approval": {
          "byDefault": "ask",
          "allow": {},
          "ask": {},
          "deny": {}
        }
      },
      "mcpTimeoutSeconds" : 60,
      "lspTimeoutSeconds" : 30,
      "mcpServers" : {},
      "defaultBehavior": "agent"
      "welcomeMessage" : "Welcome to ECA!\n\nType '/' for commands\n\n"
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
