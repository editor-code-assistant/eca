# Hooks

Hooks are shell actions that run before or after specific events, useful for notifications, injecting context, modifying inputs, or blocking tool calls.

## Hook Types

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

## Hook Options

- **`matcher`**: Regex for `server__tool-name`, only for `*ToolCall` hooks.
- **`visible`**: Show hook execution in chat (default: `true`).
- **`runOnError`**: For `postToolCall`, run even if tool errored (default: `false`).

## Execution Details

- **Order**: Alphabetical by key. Prompt rewrites chain; argument updates merge (last wins).
- **Conflict**: Any rejection (`deny` or exit `2`) blocks the call immediately.
- **Timeout**: Actions time out after 30s unless `"timeout": ms` is set.

## Input / Output

Hooks receive JSON via stdin with event data (top-level keys `snake_case`, nested data preserves case). Common fields:

- All hooks: `hook_name`, `hook_type`, `workspaces`, `db_cache_path`
- Chat hooks add: `chat_id`, `agent`, `behavior` (deprecated alias)
- Tool hooks add: `tool_name`, `server`, `tool_input`, `approval` (pre) or `tool_response`, `error` (post)
- `chatStart` adds: `resumed` (boolean)

Hooks can output JSON to control execution:

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

## Examples

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

