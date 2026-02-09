# Rules

Rules are contexts that are passed to the LLM during a prompt and are useful to tune prompts or LLM responses.
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
