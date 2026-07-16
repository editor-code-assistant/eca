---
description: "Configure ECA tools: built-in editor tools, MCP server integration, custom CLI tools, and fine-grained approval control."
---

# Tools

![](../images/features/tools.png)

ECA supports 3 types of tools:

- __Native tools__: (edit_file, write_file, read, etc)
- __MCP servers__: if any configured
- __User custom tools__: if defined

## MCP

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

    ECA supports OAuth authentication automatically via MCP spec discovery.

    ```javascript title="~/.config/eca/config.json"
    {
      "mcpServers": {
        "cool-mcp": {
          "url": "https://my-remote-mcp.com/mcp"
        }
      }
    }
    ```

=== "OAuth with pre-registered client"

    Some providers (e.g. Databricks) require a pre-registered OAuth application
    and don't support dynamic client registration. Use `clientId` with the
    client ID from your provider's OAuth app settings.

    ```javascript title="~/.config/eca/config.json"
    {
      "mcpServers": {
        "databricks-sql": {
          "url": "https://my-workspace.cloud.databricks.com/api/2.0/mcp/sql",
          "clientId": "<your-oauth-app-client-id>"
        }
      }
    }
    ```

=== "Confidential OAuth (e.g. Slack)"

    Some providers (e.g. Slack MCP) require confidential OAuth with both a
    `clientId` and `clientSecret`, and require HTTPS pre-registered redirect URIs.

    Setup steps:

    1. Create a Slack App at [api.slack.com/apps](https://api.slack.com/apps)
    2. Enable MCP under *Features > Agents & AI Apps*
    3. Under *OAuth & Permissions > Redirect URLs*, add
       `https://localhost:19284/auth/callback`
    4. Add the user scopes you need (e.g. `search:read.public`, `channels:history`)
    5. Copy the credentials from *Settings > Basic Information > App Credentials*

    When `oauthPort` is set, ECA uses a bundled localhost certificate to serve
    HTTPS on the callback. On first authorization, your browser will show a
    certificate warning — click *Advanced → Proceed* to complete the flow.

    ```javascript title="~/.config/eca/config.json"
    {
      "mcpServers": {
        "slack": {
          "url": "https://mcp.slack.com/mcp",
          "clientId": "<your-slack-app-client-id>",
          "clientSecret": "<your-slack-app-client-secret>",
          "oauthPort": 19284
        }
      }
    }
    ```

=== "Static auth header"

    For servers that accept a static token (e.g. a personal access token),
    set the `Authorization` header directly. This skips OAuth entirely.

    ```javascript title="~/.config/eca/config.json"
    {
      "mcpServers": {
        "my-api": {
          "url": "https://my-remote-mcp.com/mcp",
          "headers": {
            "Authorization": "Bearer ${env:MY_API_TOKEN}"
          }
        }
      }
    }
    ```

=== "Advanced — DCR client name override"

    Some OAuth-protected MCP servers allowlist clients during Dynamic Client
    Registration (DCR) by `client_name`. If the server rejects ECA's default
    registration with a 403, set `clientName` to a value the server accepts.

    ```javascript title="~/.config/eca/config.json"
    {
      "mcpServers": {
        "figma": {
          "url": "https://mcp.figma.com/mcp",
          "clientName": "Claude Code"
        }
      }
    }
    ```

    The DCR attempt, its result and the chosen `client_name` are logged at
    `info`/`warn` level so you can verify behavior in the ECA log.

=== "Per-project auth — authScope"

    By default a server's OAuth token is shared across every project, which is
    convenient when the same account is used everywhere. If you sign in to a
    *different* account (e.g. a different Linear workspace) per project, the
    shared token would otherwise be clobbered. Set `authScope` to control this:

    - `global` (default): one token shared across all projects.
    - `workspace`: a separate token per workspace folder set.
    - any other value: a named bucket shared by every project using that value.

    ```javascript title="myproject/.eca/config.json"
    {
      "mcpServers": {
        "linear": {
          "url": "https://mcp.linear.app/mcp",
          "authScope": "workspace"
        }
      }
    }
    ```

    Like other config values, `authScope` supports the full dynamic-string
    interpolation (`${env:...}`, `${file:...}`, `${cmd:...}`, `${netrc:...}`,
    `${classpath:...}`), e.g. `"authScope": "${env:LINEAR_ORG}"`.

=== "Disabling a MCP"

    Set `"disabled": true` to keep the configuration but prevent ECA from starting the server.

    ```javascript title="~/.config/eca/config.json"
    {
      "mcpServers": {
        "memory": {
          "command": "npx",
          "args": ["-y", "@modelcontextprotocol/server-memory"],
          "disabled": true
        }
      }
    }
    ```

## Custom Tools

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

## Disabled tools

You can completely disable tools so they are never available to the LLM. This is configured via the `disabledTools` config, which accepts a list of strings matched against each tool, in this order:

1. A builtin ECA tool name or regex, no `eca__` prefix needed: `edit_file`, `.*_file`.
2. An exact MCP server name, disabling all tools of that server: `clojure-mcp`.
3. A regex matched against the tool full name `server__tool`: `clojure-mcp__eval.*`, `my-mcp__dangerous_tool`.

Regexes must match the whole name (anchored). It can be set globally, per agent or in the [agent markdown frontmatter](agents.md).

=== "Global"

    ```javascript title="~/.config/eca/config.json"
    {
      "disabledTools": ["eca__shell_command", "my-mcp__dangerous_tool"]
    }
    ```

=== "Per agent"

    ```javascript title="~/.config/eca/config.json"
    {
      "agent": {
        "plan": {
          "disabledTools": ["eca__edit_file", "eca__write_file", "eca__move_file"]
        }
      }
    }
    ```

=== "Whole MCP server per agent"

    ```javascript title="~/.config/eca/config.json"
    {
      "mcpServers": {
        "clojure-mcp": {"command": "..."}
      },
      "agent": {
        "plan": {
          "disabledTools": ["clojure-mcp"]
        }
      }
    }
    ```

!!! tip "Disabled vs Denied"

    `disabledTools` removes the tool entirely from the LLM — it won't even know it exists. `toolCall.approval.deny` rules without `argsMatchers` also remove the tool from the LLM tool list, while rules with `argsMatchers` keep the tool visible and only block matching calls.

## Approval / permissions

By default, ECA asks to call any non read-only tool (check the [default rules](#default-approval-rules)), but that can easily be configured in several ways via the `toolCall.approval` config:

- `byDefault`: `"ask"`, `"allow"` or `"deny"`, used when no rule matches. Default: `"ask"`.
- `deny`, `ask` and `allow`: maps of [tool selector](#tool-selectors) to an optional [`argsMatchers`](#matching-arguments-with-argsmatchers).

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
            "eca__edit_file": {},
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

Also check the `plan` agent which is safer.

__The `manualApproval` setting was deprecated and replaced by the `approval` one without breaking changes__

### How ECA decides: rule precedence

For each tool call, ECA checks the following in order, and the first match wins:

1. __`deny` rules__: always win, even over session-remembered approvals and trust mode.
2. __Session-remembered approvals__: what you approved with "approve and remember for this session" ([below](#approve-remember-for-this-session)).
3. __Tool built-in checks__: some native tools force asking in risky cases regardless of `allow` rules, e.g. filesystem tools and `shell_command` when the path or working directory is outside the workspace roots.
4. __`ask` rules__.
5. __`allow` rules__.
6. __Legacy `manualApproval` config__.
7. __`byDefault`__: `ask` when not set.

Trust mode is applied after that: it promotes an `ask` result to auto-allow but never overrides `deny`.

!!! warning "Agent rules replace global rules"

    `agent.<name>.toolCall.approval` does not deep-merge with the global `toolCall.approval` at runtime: each key present (`allow`, `ask`, `deny` or `byDefault`) entirely replaces the global one. Since the builtin `plan` and `explorer` agents define their own `allow` and `deny` ([default rules](#default-approval-rules)), global `allow` entries you add are ignored while using those agents — configure `agent.plan.toolCall.approval.allow` too if you need them there.

### Tool selectors

The keys of `allow`, `ask` and `deny` are matched by exact name (no regex or glob, unlike `disabledTools`), in one of 3 forms:

| Selector | Matches |
|----------|---------|
| `server__tool`: `eca__shell_command`, `my-mcp__my_tool` | that specific tool |
| builtin tool name: `shell_command` | the ECA native tool, the `eca__` prefix is optional for them |
| server name: `my-mcp`, `eca` | all tools of that server |

For MCP tools the `server__` prefix is required: a bare MCP tool name like `my_tool` is treated as a server name and will match nothing — use `my-mcp__my_tool`.

### Matching arguments with argsMatchers

`argsMatchers` is a map of argument name by list of [java regexes](https://www.regexplanet.com/advanced/java/index.html) tested against the argument value:

- Regexes are anchored, they must match the __whole__ argument value: `"rm"` does not match `rm -rf foo`, use `".*\\brm\\b.*"`.
- The rule matches when __any__ regex of any listed argument matches.
- A rule without `argsMatchers` matches __every__ call of that tool.
- Matchers of an argument absent in the call never match.
- A `deny` rule without `argsMatchers` also hides the tool from the LLM entirely; with `argsMatchers` the tool stays visible and only matching calls are rejected.

#### Chained and piped shell commands

Config `argsMatchers` are tested against the __full command string__ — there is no per-command splitting: for `cat file | grep foo`, an `allow` regex must match that whole string. That's why deny patterns are usually written like `".*\\b(rm|mv|cp)\\b.*"`. Splitting a chain into individual commands only happens for [session remember](#approve-remember-for-this-session).

### Approve & remember for this session

When you approve a tool call choosing "approve and remember for this session", ECA whitelists that tool by name: future calls of it are auto-approved. Remembered approvals live in memory only — they apply to all chats of the running ECA process, are not written to any config file, and are lost on restart. `deny` rules still win over them.

#### Granular approve & remember for shell commands

When you approve a `eca__shell_command` or `eca__git` tool call choosing "approve and remember", ECA remembers the approved commands for the session instead of whitelisting the whole tool: the command string is parsed and each command in a chain (`&&`, `||`, `;`, `|`) produces a key — command + subcommand for multi-command tools (`git checkout`, `npm install`), the plain command otherwise (`rg`). A future shell call runs automatically only when all its commands were already remembered.

ECA fails closed: commands it cannot safely reason about always ask again, like command/process substitution (`$(...)`, backticks), subshells, heredocs, output redirections to files (except `/dev/null`), and wrapper commands that execute arbitrary code (`sudo`, `bash -c`, `xargs`, `env`, ...).

### Trust mode by default

Trust mode auto-accepts all tool calls in a chat (it never overrides `deny`). Editors expose a toggle for it, and you can make **new chats start trusted** for every editor via `chat.defaultTrust`:

```javascript title="~/.config/eca/config.json"
{
  "chat": {
    "defaultTrust": true
  }
}
```

### Default approval rules

Globally ECA allows its read-only builtin tools and asks for everything else:

```javascript
{
  "toolCall": {
    "approval": {
      "byDefault": "ask",
      "allow": {
        "eca__compact_chat": {},
        "eca__preview_file_change": {},
        "eca__read_file": {},
        "eca__directory_tree": {},
        "eca__grep": {},
        "eca__editor_diagnostics": {},
        "eca__skill": {},
        "eca__task": {},
        "eca__ask_user": {},
        "eca__fetch_rule": {},
        "eca__spawn_agent": {}
      }
    }
  }
}
```

The builtin `plan` and `explorer` agents replace these with stricter rules: `allow` only covers the read-only builtin tools plus read-only shell commands (`pwd`, `git diff/log/show`, `find`, `ls`), and `deny` blocks dangerous shell patterns (file mutations like `rm`/`mv`/`cp`/`touch`/`mkdir`, output redirections, pipes to `tee`/`dd`/`xargs`, in-place `sed`/`awk`/`perl`, `git add/commit/push`, `npm install`). Check the up-to-date values in [config.clj](https://github.com/editor-code-assistant/eca/blob/master/src/eca/config.clj).

### Debugging approval rules

Start the server with `--log-level debug` ([how to per editor](../troubleshooting.md)) and search the logs for `Tool call approval decision`, logged on every tool call with the decision and which rule caused it:

```
[CHAT] Tool call approval decision {:decision :deny, :rule {:source :config-deny, :selector "eca__shell_command", :args-matcher ".*\\b(rm|mv|cp|touch|mkdir)\\b.*"}, :tool "eca__shell_command", :agent "plan"}
```

| `:source` | The decision came from |
|-----------|------------------------|
| `:config-deny`, `:config-ask`, `:config-allow` | your `approval` rules; `:selector` and `:args-matcher` show the exact rule |
| `:session-remember` | an "approve and remember for this session" approval |
| `:tool-built-in-check` | the tool itself, e.g. path outside the workspace roots |
| `:legacy-manual-approval` | the deprecated `manualApproval` config |
| `:by-default` | the `byDefault` config |
| `:fallback` | nothing matched (likely a config error), falls back to ask |

Common reasons ECA still asks after you added an `allow` rule:

- The regex must match the whole argument: use `"grep(\\s+.*)?"` instead of `"grep"`.
- Allowing `eca__grep` doesn't cover `grep` run through `eca__shell_command` — they are different tools.
- You are on the `plan`/`explorer` agent (or a custom one) whose `allow` replaces the global one.
- The path or working directory is outside the workspace roots (`:tool-built-in-check`).
- Chained commands: the regex must match the full chained string, e.g. `cat file | grep foo`.
