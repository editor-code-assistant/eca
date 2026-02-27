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

## Approval / permissions

By default, ECA asks to call any non read-only tool (default [here](https://github.com/editor-code-assistant/eca/blob/5e598439e606727701a69393e55bbd205c9e16d8/src/eca/config.clj#L88-L96)), but that can easily be configured in several ways via the `toolCall.approval` config.

You can configure the default approval with `byDefault` and/or configure a tool in `ask`, `allow` or `deny` configs.

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

Also check the `plan` agent which is safer.

__The `manualApproval` setting was deprecated and replaced by the `approval` one without breaking changes__

## File Reading

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

## Context window size management

ECA manages the context window through three layers of defense, each addressing a different stage of the problem:

1. **Output Truncation** — prevents individual tool outputs from being too large.
2. **Auto-compact** — proactively summarizes the conversation before it reaches the model's limit.
3. **Context overflow recovery** — reactively handles the case when the context exceeds the limit despite the above.

### Output Truncation

ECA automatically truncates tool call outputs that are too large before sending them to the LLM. When output exceeds the configured limits, ECA:

1. Saves the full output to a cache file in `~/.cache/eca/toolCallOutputs` (cleaned every 7 days).
2. Truncates the output to the configured line limit.
3. Appends an `[OUTPUT TRUNCATED]` notice telling the LLM where the full content is saved and suggesting it use `eca__grep` or `eca__read_file` with offset/limit to access the rest.

You can customize the truncation limits via `toolCall.outputTruncation`:

```javascript title="~/.config/eca/config.json"
{
  "toolCall": {
    "outputTruncation": {
      "lines": 1000,
      "sizeKb": 20
    }
  }
}
```

| Property | Default | Description                                       |
|----------|---------|---------------------------------------------------|
| `lines`  | `2000`  | Maximum number of lines in tool output.            |
| `sizeKb` | `50`    | Maximum size in kilobytes for tool output.         |

Either limit being exceeded will trigger truncation.

### Auto-compact

After each LLM response, ECA checks how much of the model's context window has been used. When usage reaches the configured threshold, ECA automatically compacts the conversation by asking the LLM to produce a structured summary, then replaces the full chat history with that summary and resumes the original task.

You can configure the threshold via `autoCompactPercentage`:

```javascript title="~/.config/eca/config.json"
{
  "autoCompactPercentage": 75
}
```

This can also be set per-agent:

```javascript title="~/.config/eca/config.json"
{
  "agent": {
    "code": {
      "autoCompactPercentage": 80
    }
  }
}
```

| Property                | Default | Description                                                       |
|-------------------------|---------|-------------------------------------------------------------------|
| `autoCompactPercentage` | `75`    | Percentage of context window usage that triggers auto-compaction. |

### Context overflow recovery

In some cases the context window can jump from below the auto-compact threshold to above the model's maximum in a single turn — for example, when multiple large tool results arrive at once. When this happens, the LLM provider rejects the request with a "prompt too long" error.

Instead of showing this error to the user, ECA automatically recovers:

1. **Detects the overflow** — classifies the provider error (works across Anthropic, OpenAI, Google, Ollama, and custom providers).
2. **Prunes old tool results** — walks the chat history backwards, keeping the most recent ~40K tokens of tool output intact and replacing older tool results with a lightweight placeholder. This preserves recent context while freeing enough space.
3. **Triggers auto-compact** — sends the (now smaller) conversation to the LLM for summarization.
4. **Resumes the original request** — after compaction, retries the user's original message against the compacted history.

This happens transparently — the user sees a brief "Context window exceeded. Auto-compacting conversation..." notice, and the task continues without manual intervention.

If recovery itself fails (e.g., the conversation is too large even after pruning), ECA falls back to displaying the error so the user can manually compact or start a new chat.
