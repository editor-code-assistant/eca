# Rewrite

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

