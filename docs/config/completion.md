---
description: "Configure ECA inline completion: AI-powered code suggestions as you type, with custom model and prompt settings."
---

# Completion

![](../images/features/inline_completion.png)

You can configure which model and system prompt ECA will use during its inline completion:

=== "Example"

    ```javascript title="~/.config/eca/config.json"
    {
      "completion": {
        "model": "github-copilot/gpt-4.1"
      },
      "prompts": {
        "completion": "${file:/path/to/my-prompt.md}"
      }
    }
    ```

