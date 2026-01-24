# Configuration

Check all available configs and its default values [here](#all-configs).

Also, check [Examples](./examples.md) which contains examples of users ECA configs.

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

## Proxy Configuration

ECA supports proxies with basic cleartext authentication via the de-facto env vars:

```bash
HTTP_PROXY="http://user:pass@host:port"
HTTPS_PROXY="http://user:pass@host:port"
http_proxy="http://user:pass@host:port"
https_proxy="http://user:pass@host:port"
```

Lowercase var wins if both are set. Credentials (if used) must match for HTTP and HTTPS.

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

=== "HTTP (streamable or sse)"

    ECA supports OAuth authentication as well

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
    
## Skills

Skills are folders with `SKILL.md` which teachs LLM how to solve a specific task or gain knowledge about it.
Following the [agentskills](https://agentskills.io/) standard, ECA search for skills following `~/.config/eca/skills/some-skill/SKILL.md` and `.eca/skills/some-skill/SKILL.md` which should contain `name` and `description` metadatas.

When sending a prompt request to LLM, ECA will send only name and description of all available skills, LLM then can choose to load a skill via `eca__skill` tool if that matches user request. 

Check the examples:

=== "Simple lint skill"

    ```markdown title="~/.config/eca/skills/lint-fix/SKILL.md"
    ---
    name: lint-fix
    description: Learn how to lint and fix the code
    ---
    
    # Instructions
    
    Run `clojure-lsp diagnostics` to lint the code
    ```
    
=== "More complex skill using scripts"

    ```markdown title="~/.config/eca/skills/gif-generator/SKILL.md"
    ---
    name: gif-generator
    description: Knowledge and utils to create gifs. Provide concepts and scripts, use when requested to create gifs.
    ---
    
    - Use scripts/gif-generate.py passing gif name and dimensions.
    - <More complex instructions here>
    ...
    ```
    
    ```pyton title="~/.config/eca/skills/gif-generator/scripts/generator.py"
    from PIL import Image
    # Python code that generates a gif here
    ....
    ```

=== "Enable/disable specific skills"

    It's possible to control which skills LLM have access globally or for a specific behavior.
    You just need to define a tool call approval for the `eca__skill` for a specific skill `name`:
    
    Example disabling all skills but one for a behavior

    ```javascript title="~/.config/eca/config.json"
    {
      "toolCall": {
        "approval": {
          "deny": {
            "eca__skill": {}
          }
        }
      },
      "behavior": {
        "reviewer": {
          "toolCall": {
            "approval": {
              "allow": {
                "eca__skill": {"argsMatchers": {"name": ["my-skill"]}}
              }
            }
          }
        }
      }
    }
    ```
    
You can have more directories and contents like `scripts/`, `references/`, `assets/` for a skill making it really powerful, check [the spec](https://agentskills.io/specification#optional-directories) for more details.

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
    
## Custom command prompts

You can configure custom command prompts for project, global or via `commands` config pointing to the path of the commands.
Prompts can use variables like `$ARGUMENTS`, `$ARG1`, `ARG2`, to replace in the prompt during command call.

You can configure in multiple different ways:

=== "Local custom commands"

    A `.eca/commands` folder from the workspace root containing `.md` files with the custom prompt.

    ```markdown title=".eca/commands/check-performance.md"
    Check for performance issues in $ARG1 and optimize if needed.
    ```

    ECA will make available a `/check-performance` command after creating that file.

=== "Global custom commands"

    A `$XDG_CONFIG_HOME/eca/commands` or `~/.config/eca/commands` folder containing `.md` files with the custom command prompt.

    ```markdown title="~/.config/eca/commands/check-performance.md"
    Check for performance issues in $ARG1 and optimize if needed.
    ```

    ECA will make available a `/check-performance` command after creating that file.

=== "Config"

    Just add to your config the `commands` pointing to `.md` files that will be searched from the workspace root if not an absolute path:

    ```javascript title="~/.config/eca/config.json"
    {
      "commands": [{"path": "my-custom-prompt.md"}]
    }
    ```

    ECA will make available a `/my-custom-prompt` command after creating that file.

## Behaviors / prompts

ECA allows to totally customize the prompt sent to LLM via the `prompts` config, but you can use the `behavior` config allowing to have multiple behaviors for different tasks or workflows.

You can even customize the tool description via `prompts tools <toolName>`.

=== "Example: my-behavior"

    ```javascript title="~/.config/eca/config.json"
    {
      "behavior": {
        "my-behavior": {
          "prompts": {
            "chat": "${file:/path/to/my-behavior-prompt.md}"
          }
        }
      }
    }
    ```

=== "Example: Overriding a tool description"

    ```javascript title="~/.config/eca/config.json"
    {
      "prompts": {
        "tools": {
          "eca__edit_file": "${file:/path/to/my-tool-prompt.md}"
        }
      }
    }
    ```

## Hooks

Hooks are shell actions that run before or after specific events, useful for notifications, injecting context, modifying inputs, or blocking tool calls.

### Hook Types

| Type | When | Can Modify |
|------|------|------------|
| `sessionStart` | Server initialized | - |
| `sessionEnd` | Server shutting down | - |
| `chatStart` | New chat or resumed chat | Can inject `additionalContext` |
| `chatEnd` | Chat deleted | - |
| `preRequest` | Before prompt sent to LLM | Can rewrite prompt, inject context, stop request |
| `postRequest` | After prompt finished | - |
| `preToolCall` | Before tool execution | Can modify args, override approval, reject |
| `postToolCall` | After tool execution | Can inject context for next LLM turn |

### Hook Options

- **`matcher`**: Regex for `server__tool-name`, only for `*ToolCall` hooks.
- **`visible`**: Show hook execution in chat (default: `true`).
- **`runOnError`**: For `postToolCall`, run even if tool errored (default: `false`).

### Execution Details

- **Order**: Alphabetical by key. Prompt rewrites chain; argument updates merge (last wins).
- **Conflict**: Any rejection (`deny` or exit `2`) blocks the call immediately.
- **Timeout**: Actions time out after 30s unless `"timeout": ms` is set.

### Input / Output

Hooks receive JSON via stdin with event data (top-level keys `snake_case`, nested data preserves case). Common fields:

- All hooks: `hook_name`, `hook_type`, `workspaces`, `db_cache_path`
- Chat hooks add: `chat_id`, `behavior`
- Tool hooks add: `tool_name`, `server`, `tool_input`, `approval` (pre) or `tool_response`, `error` (post)
- `chatStart` adds: `resumed` (boolean)

Hooks can output JSON to control behavior:

```javascript
{
  "additionalContext": "Extra context for LLM",  // injected as XML block
  "replacedPrompt": "New prompt text",           // preRequest only
  "updatedInput": {"key": "value"},              // preToolCall: merge into tool args
  "approval": "allow" | "ask" | "deny",          // preToolCall: override approval
  "continue": false,                             // stop processing (with optional stopReason)
  "stopReason": "Why stopped",
  "suppressOutput": true                         // hide hook output from chat
}
```

Plain text output (non-JSON) is treated as `additionalContext`.

To reject a tool call, either output `{"approval": "deny"}` or exit with code `2`.

### Examples

=== "Notify after prompt"

    ```javascript title="~/.config/eca/config.json"
    {
      "hooks": {
        "notify-me": {
          "type": "postRequest",
          "visible": false,
          "actions": [{"type": "shell", "shell": "notify-send 'Prompt finished!'"}]
        }
      }
    }
    ```

=== "Ring bell sound when pending tool call approval"

    ```javascript title="~/.config/eca/hooks/my-hook.sh"
    jq -e '.approval == "ask"' > /dev/null && canberra-gtk-play -i complete
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
              "file": "hooks/my-hook.sh"
            }
          ]
        }
      }
    }
    ```

=== "Inject context on chat start"

    ```javascript title="~/.config/eca/config.json"
    {
      "hooks": {
        "load-context": {
          "type": "chatStart",
          "actions": [{
            "type": "shell",
            "shell": "echo '{\"additionalContext\": \"Today is '$(date +%Y-%m-%d)'\"}'"
          }]
        }
      }
    }
    ```

=== "Rewrite prompt"

    ```javascript title="~/.config/eca/config.json"
    {
      "hooks": {
        "add-prefix": {
          "type": "preRequest",
          "actions": [{
            "type": "shell",
            "shell": "jq -c '{replacedPrompt: (\"[IMPORTANT] \" + .prompt)}'"
          }]
        }
      }
    }
    ```

=== "Block tool with JSON response"

    ```javascript title="~/.config/eca/config.json"
    {
      "hooks": {
        "block-rm": {
          "type": "preToolCall",
          "matcher": "eca__shell_command",
          "actions": [{
            "type": "shell",
            "shell": "if jq -e '.tool_input.command | test(\"rm -rf\")' > /dev/null; then echo '{\"approval\":\"deny\",\"additionalContext\":\"Dangerous command blocked\"}'; fi"
          }]
        }
      }
    }
    ```

=== "Modify tool arguments"

    ```javascript title="~/.config/eca/config.json"
    {
      "hooks": {
        "force-recursive": {
          "type": "preToolCall",
          "matcher": "eca__directory_tree",
          "actions": [{
            "type": "shell",
            "shell": "echo '{\"updatedInput\": {\"max_depth\": 3}}'"
          }]
        }
      }
    }
    ```

=== "Use external script file"

    ```javascript title="~/.config/eca/config.json"
    {
      "hooks": {
        "my-hook": {
          "type": "preToolCall",
          "actions": [{"type": "shell", "file": "~/.config/eca/hooks/check-tool.sh"}]
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
        "model": "github-copilot/gpt-4.1"
      }
      "prompts": {
        "completion": "${file:/path/to/my-prompt.md}"
      }
    }
    ```

## Rewrite

Configure the model and system prompt used for ECA's rewrite feature via the `rewrite` config. By default, ECA follows the same model selection as chat unless overwritten:

=== "Example"

    ```javascript title="~/.config/eca/config.json"
    {
      "rewrite": {
        "model": "github-copilot/gpt-4.1"
      },
      "prompts": {
        "rewrite": "${file:/path/to/my-prompt.md}"
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
            fetchModels?: boolean;
            url?: string;
            key?: string; // when provider supports api key.
            keyRc?: string; // credential file lookup in format [login@]machine[:port]
            completionUrlRelativePath?: string;
            thinkTagStart?: string;
            thinkTagEnd?: string;
            models: {[key: string]: {
              modelName?: string;
              extraPayload?: {[key: string]: any};
              reasoningHistory?: "all" | "turn" | "off";
            }};
        }};
        defaultModel?: string;
        hooks?: {[key: string]: {
                type: 'sessionStart' | 'sessionEnd' | 'chatStart' | 'chatEnd' |
                      'preRequest' | 'postRequest' | 'preToolCall' | 'postToolCall';
                matcher?: string; // regex for server__tool-name, only *ToolCall hooks
                visible?: boolean;
                runOnError?: boolean; // postToolCall only
                actions: {
                    type: 'shell';
                    shell?: string; // inline script
                    file?: string;  // path to script file
                    timeout?: number; // ms, default 30000
                }[];
            };
        };
        rules?: [{path: string;}];
        enabledSkills?: string[];
        disabledTools?: string[],
        commands?: [{path: string;}];
        behavior?: {[key: string]: { 
            defaultModel?: string;
            disabledTools?: string[];
            enabledSkills?: string[];
            autoCompactPercentage?: number;
            toolCall?: {
                approval?: {
                    byDefault?: 'ask' | 'allow' | 'deny';
                    allow?: {[key: string]: {argsMatchers?: {[key: string]: string[]}}};
                    ask?: {[key: string]: {argsMatchers?: {[key: string]: string[]}}};
                    deny?: {[key: string]: {argsMatchers?: {[key: string]: string[]}}};
                };
            };
            prompts?:{
                chat?: string;
                chatTitle?: string;
                compact?: string;
                init?: string;
                completion?: string;
                rewrite?: string;
                tools?: {[name: string]: string};
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
        autoCompactPercentage?: number;
        index?: {
            ignoreFiles: [{
                type: string;
            }];
            repoMap?: {
                maxTotalEntries?: number;
                maxEntriesPerDir?: number;
            };
        };
        prompts?: {
            chat?: string;
            chatTitle?: string;
            compact?: string;
            init?: string;
            completion?: string;
            rewrite?: string;
            tools?: {[name: string]: string};
        };
        completion?: {
            model?: string;
        };
        rewrite?: {
            model?: string;
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
      "enabledSkills": [".*"],
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
        "agent": {"prompts": {"chat": "${classpath:prompts/agent_behavior.md}"},
                  "disabledTools": ["preview_file_change"]},
        "plan": {"prompts": {"chat": "${classpath:prompts/plan_behavior.md}"},
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
      "autoCompactPercentage": 85,
      "index" : {
        "ignoreFiles" : [ {
          "type" : "gitignore"
        } ],
        "repoMap": {
          "maxTotalEntries": 800,
          "maxEntriesPerDir": 50
        }
      },
      "prompts": {
        "chat": "${classpath:prompts/agent_behavior.md}", // default to agent
        "chatTitle": "${classpath:prompts/title.md}",
        "compact": "${classpath:prompts/compact.md}",
        "init": "${classpath:prompts/init.md}",
        "completion": "${classpath:prompts/inline_completion.md}",
        "rewrite": "${classpath:prompts/rewrite.md}"
      },
      "completion": {
        "model": "openai/gpt-4.1"
      }
    }
    ```
