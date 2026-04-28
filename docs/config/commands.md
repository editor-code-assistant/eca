---
description: "Configure ECA commands: built-in slash commands like /init and /compact, plus custom commands from markdown files."
---

# Commands

![](../images/features/commands.png)

You can configure custom command prompts for project, global or via `commands` config pointing to the path of the commands.
Prompts can use variables like `$ARGUMENTS`, `$1`, `$2`, to replace in the prompt during command call.

!!! tip "Skills support arguments too"

    [Skills](./skills.md#parameterized-skills) also support the same variable substitution when invoked as slash commands, e.g. `/review-pr URL`.

You can configure in multiple different ways:

=== "Local custom commands"

    A `.eca/commands` folder from the workspace root containing `.md` files with the custom prompt.

    ```markdown title=".eca/commands/check-performance.md"
    Check for performance issues in $1 and optimize if needed.
    ```

    ECA will make available a `/check-performance` command after creating that file.

=== "Global custom commands"

    A `$XDG_CONFIG_HOME/eca/commands` or `~/.config/eca/commands` folder containing `.md` files with the custom command prompt.

    ```markdown title="~/.config/eca/commands/check-performance.md"
    Check for performance issues in $1 and optimize if needed.
    ```

    ECA will make available a `/check-performance` command after creating that file.

=== "Config"

    Add to your config the `commands` key. `path` can point to a single `.md` file or a directory. Directories load markdown files recursively. Relative paths are searched from each workspace root if not an absolute path:

    ```javascript title="~/.config/eca/config.json"
    {
      "commands": [{"path": "my-custom-prompt.md"}]
    }
    ```

    ```javascript title="~/.config/eca/config.json"
    // Load all command files from a directory recursively
    {
      "commands": [{"path": "/home/user/commands"}]
    }
    ```
