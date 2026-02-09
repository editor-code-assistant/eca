# Completion

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

