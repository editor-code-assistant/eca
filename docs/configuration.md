# Configuration

Check all available configs and its default values [here](#all-configs).

## Ways to configure

There are multiples ways to configure ECA:

=== "Global config file"

    Convenient for users and multiple projects

    ```javascript title="~/.config/eca/config.json"
    {
      "defaultBehavior": "plan"
    }
    ```

=== "Local Config file"

    Convenient for users

    ```javascript title=".eca/config.json"
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
    
### Dynamic string contents

It's possible to retrieve content of any configs with a string value using the `${key:value}` approach, being `key`:

- `file`: `${file:/path/to/my-file}` or `${file:../rel-path/to/my-file}` to get a file content
- `env`: `${env:MY_ENV}` to get a system env value
- `classpath`: `${classpath:path/to/eca/file}` to get a file content from [ECA's classpath](https://github.com/editor-code-assistant/eca/tree/master/resources)
- `netrc`: Support Unix RC [credential files](./models.md#credential-file-authentication)

## Providers / Models

For providers and models configuration check the [dedicated models section](./models.md#custom-providers).

## Tools

### MCP

For MCP servers configuration, use the `mcpServers` config, examples:

=== "Stdio"

    ```javascript title="~/.config/eca/config.json"
    {
      "mcpServers": {
        "memory": {
          "command": "npx",
          "args": ["-y", "@modelcontextprotocol/server-memory"],
          // optional
          "env": {"FOO": "bar"}
        }
      }
    }
    ```

=== "HTTP-streamable"

    ```javascript title="~/.config/eca/config.json"
    {
      "mcpServers": {
        "cool-mcp": {
          "url": "https://my-remote-mcp.com/mcp"
        }
      }
    }
    ```

### Approval / permissions

By default, ECA asks to call any non read-only tool (default [here](https://github.com/editor-code-assistant/eca/blob/5e598439e606727701a69393e55bbd205c9e16d8/src/eca/config.clj#L88-L96)), but that can easily be configured in several ways via the `toolCall.approval` config.

You can configure the default behavior with `byDefault` and/or configure a tool in `ask`, `allow` or `deny` configs.

Check some examples:

=== "Allow any tools by default"

    ```javascript title="~/.config/eca/config.json"
    {
      "toolCall": {
        "approval": {
          "byDefault": "allow"
        }
      }
    }
    ```

=== "Allow all but some tools"

    ```javascript title="~/.config/eca/config.json"
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

    ```javascript title="~/.config/eca/config.json"
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

    ```javascript title="~/.config/eca/config.json"
    {
      "toolCall": {
        "approval": {
          "byDefault": "allow",
          "ask": {
            "shell_command": {"argsMatchers": {"command": [".*rm.*",
                                                               ".*mv.*"]}}
          }
        }
      }
    }
    ```

=== "Denying a tool"

    ```javascript title="~/.config/eca/config.json"
    {
      "toolCall": {
        "approval": {
          "byDefault": "allow",
          "deny": {
            "shell_command": {"argsMatchers": {"command": [".*rm.*",
                                                               ".*mv.*"]}}
          }
        }
      }
    }
    ```

Also check the `plan` behavior which is safer.

__The `manualApproval` setting was deprecated and replaced by the `approval` one without breaking changes__

### File Reading

You can configure the maximum number of lines returned by the `eca__read_file` tool:

```javascript title="~/.config/eca/config.json"
{
  "toolCall": {
    "readFile": {
      "maxLines": 1000
    }
  }
}
```

Default: `2000` lines

### Custom Tools

You can define your own command-line tools that the LLM can use. These are configured via the `customTools` key in your `config.json`.

The `customTools` value is an object where each key is the name of your tool. Each tool definition has the following properties:

-   `description`: A clear description of what the tool does. This is crucial for the LLM to decide when to use it.
-   `command`: An string representing the command and its static arguments.
-   `schema`: An object that defines the parameters the LLM can provide.
    -   `properties`: An object where each key is an argument name.
    -   `required`: An array of required argument names.

Placeholders in the format `{{argument_name}}` within the `command` string will be replaced by the values provided by the LLM.

=== "Example 1"

    ```javascript title="~/.config/eca/config.json"
    {
      "customTools": {
        "web-search": {
          "description": "Fetches the content of a URL and returns it in Markdown format.",
          "command": "trafilatura --output-format=markdown -u {{url}}",
          "schema": {
            "properties": {
              "url": {
                "type": "string",
                "description": "The URL to fetch content from."
              }
            },
            "required": ["url"]
          }
        }
      }
    }
    ```

=== "Example 2"

    ```javascript title="~/.config/eca/config.json"
    {
      "customTools": {
        "file-search": {
          "description": "${file:tools/my-tool.md}",
          "command": "find {{directory}} -name {{pattern}}",
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
Prompts can use variables like `$ARGUMENTS`, `$ARG1`, `ARG2`, to replace in the prompt during command call.

=== "Local custom commands"

    A `.eca/commands` folder from the workspace root containing `.md` files with the custom prompt.

    ```markdown title=".eca/commands/check-performance.md"
    Check for performance issues in $ARG1 and optimize if needed.
    ```

=== "Global custom commands"

    A `$XDG_CONFIG_HOME/eca/commands` or `~/.config/eca/commands` folder containing `.md` files with the custom command prompt.

    ```markdown title="~/.config/eca/commands/check-performance.md"
    Check for performance issues in $ARG1 and optimize if needed.
    ```

=== "Config"

    Just add to your config the `commands` pointing to `.md` files that will be searched from the workspace root if not an absolute path:

    ```javascript title="~/.config/eca/config.json"
    {
      "commands": [{"path": "my-custom-prompt.md"}]
    }
    ```

## Rules

Rules are contexts that are passed to the LLM during a prompt and are useful to tune prompts or LLM behavior.
Rules are text files (typically `.md`, but any format works):

There are 3 possible ways to configure rules following this order of priority:

=== "Project file"

    A `.eca/rules` folder from the workspace root containing `.md` files with the rules.

    ```markdown title=".eca/rules/talk_funny.md"
    - Talk funny like Mickey!
    ```

=== "Global file"

    A `$XDG_CONFIG_HOME/eca/rules` or `~/.config/eca/rules` folder containing `.md` files with the rules.

    ```markdown title="~/.config/eca/rules/talk_funny.md"
    - Talk funny like Mickey!
    ```

=== "Config"

    Just add toyour config the `:rules` pointing to `.md` files that will be searched from the workspace root if not an absolute path:

    ```javascript title="~/.config/eca/config.json"
    {
      "rules": [{"path": "my-rule.md"}]
    }
    ```

## Behaviors / prompts

ECA allows to totally customize the prompt sent to LLM via the `behavior` config, allowing to have multiple behaviors for different tasks or workflows.

=== "Example: my-behavior"

    ```javascript title="~/.config/eca/config.json"
    {
      "behavior": {
        "my-behavior": {
          "systemPrompt": "${file:/path/to/my-behavior-prompt.md}"
        }
      }
    }
    ```
    
## Hooks

Hooks are actions that can run before or after an specific event, useful to notify after prompt finished or to block a tool call doing some check in a script.

Allowed hook types:

- `preRequest`: Run before prompt is sent to LLM, if a hook output is provided, append to user prompt.
- `postRequest`: Run after prompt is finished, when chat come back to idle state.
- `preToolCall`: Run before a tool is called, if a hook exit with status `2`, reject the tool call.
- `postToolCall`: Run after a tool was called.

__Input__: Hooks will receive input as json with information from that event, like tool name, args or user prompt.

__Output__: All hook actions allow printing output (stdout) and errors (stderr) which will be shown in chat.

__Matcher__: Specify whether to apply this hook checking a regex applying to `mcp__tool-name`, applicable only for `*ToolCall` hooks.

__Visible__: whether to show or not this hook in chat when executing, defaults to true.

Examples:

=== "Notify after prompt finish"

    ```javascript title="~/.config/eca/config.json"
    {
      "hooks": {
        "notify-me": {
          "type": "postRequest",
          "visible": false,
          "actions": [
            {
              "type": "shell",
              "shell": "notify-send \"Hey, prompt finished!\""
            }
          ]
        }
      }
    } 
    ```
    
=== "Ring bell sound when pending tool call approval"

    ```javascript title="~/.config/eca/hooks/my-hook.sh"
    [[ $(jq '.approval == "ask"' <<< "$1") ]] && canberra-gtk-play -i complete
    ```

    ```javascript title="~/.config/eca/config.json"
    {
      "hooks": {
        "notify-me": {
          "type": "preToolCall",
          "visible": false,
          "actions": [
            {
              "type": "shell",
              "shell": "${file:hooks/my-hook.sh}"
            }
          ]
        }
      }
    }
    ```


=== "Block specific tool call checking hook arg"

    ```javascript title="~/.config/eca/config.json"
    {
      "hooks": {
        "check-my-tool": {
          "type": "preToolCall", 
          "matcher": "my-mcp__some-tool",
          "actions": [
            {
              "type": "shell",
              "shell": "tool=$(jq '.\"tool-name\"' <<< \"$1\"); echo \"We should not run the $tool tool bro!\" >&2 && exit 2"
            }
          ]
        }
      }
    }
    ```
    
## Completion

You can configure which model and system prompt ECA will use during its inline completion:

=== "Example"

    ```javascript title="~/.config/eca/config.json"
    {
      "completion": {
        "model": "github-copilot/gpt-4.1",
        "systemPrompt": "${file:/path/to/my-prompt.md}"
      }
    }
    ```

## Rewrite

Configure the model and system prompt used for ECA's rewrite feature via the `rewrite` config. By default, ECA follows the same model selection as chat unless overwritten:

=== "Example"

    ```javascript title="~/.config/eca/config.json"
    {
      "rewrite": {
        "model": "github-copilot/gpt-4.1",
        "systemPrompt": "${file:/path/to/my-prompt.md}"
      }
    }
    ```

## Opentelemetry integration

To configure, add your OTLP collector config via `:otlp` map following [otlp auto-configure settings](https://opentelemetry.io/docs/languages/java/configuration/#properties-general). Example:

```javascript title="~/.config/eca/config.json"
{
  "otlp": {
    "otel.exporter.otlp.metrics.protocol": "http/protobuf",
    "otel.exporter.otlp.metrics.endpoint": "https://my-otlp-endpoint.com/foo",
    "otel.exporter.otlp.headers": "Authorization=Bearer 123456"
  }
}
```

## All configs

=== "Schema"

    ```typescript
    interface Config {
        providers?: {[key: string]: {
            api?: 'openai-responses' | 'openai-chat' | 'anthropic';
            url?: string;
            key?: string; // when provider supports api key.
            keyRc?: string; // credential file lookup in format [login@]machine[:port]
            completionUrlRelativePath?: string;
            thinkTagStart?: string;
            thinkTagEnd?: string;
            models: {[key: string]: {
              modelName?: string;
              extraPayload?: {[key: string]: any}
            }};
        }};
        defaultModel?: string;
        hooks?: {[key: string]: {
                type: 'preToolCall' | 'postToolCall' | 'preRequest' | 'postRequest';
                matcher: string;
                visible?: boolean;
                actions: {
                    type: 'shell';
                    shell: string;
                }[];
            };
        };
        rules?: [{path: string;}];
        commands?: [{path: string;}];
        behavior?: {[key: string]: {
            systemPrompt?: string;
            defaultModel?: string;
            disabledTools?: string[];
            toolCall?: {
                approval?: {
                    byDefault?: 'ask' | 'allow' | 'deny';
                    allow?: {[key: string]: {argsMatchers?: {[key: string]: string[]}}};
                    ask?: {[key: string]: {argsMatchers?: {[key: string]: string[]}}};
                    deny?: {[key: string]: {argsMatchers?: {[key: string]: string[]}}};
                };
            };
        }};
        customTools?: {[key: string]: {
            description: string;
            command: string;
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
          readFile?: {
            maxLines?: number;
          };
          shellCommand?: {
            summaryMaxLength?: number,
          };
        };
        mcpTimeoutSeconds?: number;
        lspTimeoutSeconds?: number;
        mcpServers?: {[key: string]: {
            url?: string; // for remote http-stremable servers
            command?: string; // for stdio servers
            args?: string[];
            disabled?: boolean;
        }};
        defaultBehavior?: string;
        welcomeMessage?: string;
        compactPromptFile?: string;
        index?: {
            ignoreFiles: [{
                type: string;
            }];
            repoMap?: {
                maxTotalEntries?: number;
                maxEntriesPerDir?: number;
            };
        };
        completion?: {
            model?: string;
            systemPrompt?: string;
        };
        rewrite?: {
            model?: string;
            systemPrompt?: string;
        };
        otlp?: {[key: string]: string};
        netrcFile?: string;
    }
    ```

=== "Default values"

    ```javascript
    {
      "providers": {
          "openai": {"url": "https://api.openai.com"},
          "anthropic": {"url": "https://api.anthropic.com"},
          "github-copilot": {"url": "https://api.githubcopilot.com"},
          "google": {"url": "https://generativelanguage.googleapis.com/v1beta/openai"},
          "ollama": {"url": "http://localhost:11434"}
      },
      "defaultModel": null, // let ECA decides the default model.
      "netrcFile": null, // search ~/.netrc or ~/_netrc when null.
      "hooks": {},
      "rules" : [],
      "commands" : [],
      "disabledTools": [],
      "toolCall": {
        "approval": {
          "byDefault": "ask",
          "allow": {"eca__directory_tree": {},
                    "eca__read_file": {},
                    "eca__grep": {},
                    "eca__preview_file_change": {},
                    "eca__editor_diagnostics": {}},
          "ask": {},
          "deny": {}
        },
        "readFile": {
          "maxLines": 2000
        },
        "shellCommand": {
          "summaryMaxLength": 30,
        },
      },
      "mcpTimeoutSeconds" : 60,
      "lspTimeoutSeconds" : 30,
      "mcpServers" : {},
      "behavior" {
        "agent": {"systemPrompt": "${classpath:prompts/agent_behavior.md}",
                  "disabledTools": ["preview_file_change"]},
        "plan": {"systemPrompt": "${classpath:prompts/plan_behavior.md}",
                  "disabledTools": ["edit_file", "write_file", "move_file"],
                  "toolCall": {"approval": {"deny": {"eca__shell_command":
                                                     {"argsMatchers": {"command" [".*>.*",
                                                                                  ".*\\|\\s*(tee|dd|xargs).*",
                                                                                  ".*\\b(sed|awk|perl)\\s+.*-i.*",
                                                                                  ".*\\b(rm|mv|cp|touch|mkdir)\\b.*",
                                                                                  ".*git\\s+(add|commit|push).*",
                                                                                  ".*npm\\s+install.*",
                                                                                  ".*-c\\s+[\"'].*open.*[\"']w[\"'].*",
                                                                                  ".*bash.*-c.*>.*"]}}}}}}
      }
      "defaultBehavior": "agent",
      "welcomeMessage" : "Welcome to ECA!\n\nType '/' for commands\n\n",
      "compactPromptFile": "prompts/compact.md",
      "index" : {
        "ignoreFiles" : [ {
          "type" : "gitignore"
        } ],
        "repoMap": {
          "maxTotalEntries": 800,
          "maxEntriesPerDir": 50
        }
      },
      "completion": {
        "model": "openai/gpt-4.1",
        "systemPrompt": "${classpath:prompts/inline_completion.md}"
      },
      "rewrite": {
        "systemPrompt": "${classpath:prompts/rewrite.md}"
      }
    }
    ```
