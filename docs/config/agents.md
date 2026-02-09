# Agents

When using ECA chat, you can choose which agent it will use, each allows you to customize its system prompt, tool call approvals, disabled tools, default model, skills and more.

## Built-in agents

- `code`: default agent, generic, used to do most tasks.
- `plan`: specialized agent to build a plan before switching to code agent and executing the complex task.

## Custom agents and prompts

You can create an agent and define its prompt, tool call approval and default model.

=== "Example: my-agent"

    ```javascript title="~/.config/eca/config.json"
    {
      "agent": {
        "my-agent": {
          "prompts": {
            "chat": "${file:/path/to/my-agent-prompt.md}"
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

