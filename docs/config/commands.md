---
description: "Configure ECA commands: built-in slash commands like /init and /compact, plus custom commands from markdown files."
---

# Commands

![](../images/features/commands.png)

You can configure custom command prompts for project, global or via `commands` config pointing to the path of the commands.
Prompts can use positional variables like `$ARGUMENTS`, `$1`, `$2`, or [named `{{name}}` variables](#frontmatter-description-and-named-arguments), to replace in the prompt during command call.

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

## Frontmatter: description and named arguments

A command file may start with optional YAML frontmatter to set a human-readable `description` (shown in the command list instead of the file path) and to declare named arguments.

Use `{{name}}` placeholders in the body to define named arguments. When a command uses named placeholders, ECA renders the body with [Selmer](https://github.com/yogthos/Selmer), mapping the call arguments to the placeholders in the order they first appear:

```markdown title=".eca/commands/weather.md"
---
description: Generate a weather report
arguments:
  - name: city
    description: City to report on
    required: true
  - name: units
    description: metric or imperial
    required: false
---
Write a weather report for {{city}} using {{units}} units.
```

Calling `/weather Paris metric` renders `Write a weather report for Paris using metric units.`

Notes:

- The `arguments` list is optional and only attaches `description`/`required` metadata that editors use when prompting for values. Named arguments default to `required: true`; set `required: false` to make one optional.
- For positional commands (`$1`, `$ARG1`), `arguments` entries are matched by position, letting you name and describe each placeholder.
- Positional (`$1`/`$ARGS`) and named (`{{name}}`) placeholders cannot be mixed in the same command; such files are ignored.
- Commands without frontmatter keep working exactly as before.
