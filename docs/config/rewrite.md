---
description: "Configure ECA rewrite: select code, describe the change, and accept or reject the AI-generated diff."
---

# Rewrite

![](../images/features/rewrite.gif)

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

## Large files

To keep the prompt size bounded, ECA caps how many lines of the target file are inlined as context. When a file exceeds `rewrite.fullFileMaxLines` (default `2000`), ECA sends a window of that many lines centered on your selection instead of the whole file. Tune it if your model has a larger or smaller context window:

=== "Example"

    ```javascript title="~/.config/eca/config.json"
    {
      "rewrite": {
        "fullFileMaxLines": 4000
      }
    }
    ```

