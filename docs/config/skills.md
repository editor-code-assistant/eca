---
description: "Configure ECA skills: structured knowledge units that teach the LLM task-specific abilities following the agentskills.io spec."
---

# Skills

![](../images/features/skills.png)

Skills are folders with `SKILL.md` which teachs LLM how to solve a specific task or gain knowledge about it.
Following the [agentskills](https://agentskills.io/) standard, ECA searches for skills following `~/.config/eca/skills/some-skill/SKILL.md`, `.eca/skills/some-skill/SKILL.md`, and `.agents/skills/some-skill/SKILL.md` which should contain `name` and `description` metadatas.

When sending a prompt request to LLM, ECA will send only name and description of all available skills, LLM then can choose to load a skill via `eca__skill` tool if that matches user request.

Check the examples:

=== "Simple lint skill"

    ```markdown title="~/.config/eca/skills/lint-fix/SKILL.md"
    ---
    name: lint-fix
    description: Learn how to lint and fix the code
    ---

    # Instructions

    Run `clojure-lsp diagnostics` to lint the code
    ```

=== "More complex skill using scripts"

    ```markdown title="~/.config/eca/skills/gif-generator/SKILL.md"
    ---
    name: gif-generator
    description: Knowledge and utils to create gifs. Provide concepts and scripts, use when requested to create gifs.
    ---

    - Use scripts/gif-generate.py passing gif name and dimensions.
    - <More complex instructions here>
    ...
    ```

    ```pyton title="~/.config/eca/skills/gif-generator/scripts/generator.py"
    from PIL import Image
    # Python code that generates a gif here
    ....
    ```

=== "Enable/disable specific skills"

    It's possible to control which skills LLM have access globally or for a specific agent.
    You just need to define a tool call approval for the `eca__skill` for a specific skill `name`:

    Example disabling all skills but one for an agent

    ```javascript title="~/.config/eca/config.json"
    {
      "toolCall": {
        "approval": {
          "deny": {
            "eca__skill": {}
          }
        }
      },
      "agent": {
        "reviewer": {
          "toolCall": {
            "approval": {
              "allow": {
                "eca__skill": {"argsMatchers": {"name": ["my-skill"]}}
              }
            }
          }
        }
      }
    }
    ```

=== "Config"

    Add to your config the `skills` key. `path` can point to a single skill directory (containing `SKILL.md`) or a directory containing multiple skill subdirectories. Directories load `SKILL.md` files recursively. Relative paths are searched from each workspace root if not an absolute path:

    ```javascript title="~/.config/eca/config.json"
    {
      "skills": [{"path": "/home/user/skills"}]
    }
    ```

## Parameterized skills

Skills can receive arguments when invoked as slash commands, using the same variable substitution as [custom commands](./commands.md): `$ARGS`, `$ARGUMENTS`, and positional `$1`, `$2`, etc.

When arguments are provided, ECA substitutes them directly into the skill body instead of asking the LLM to load the skill via `eca__skill`.

```markdown title="~/.config/eca/skills/review-pr/SKILL.md"
---
name: review-pr
description: Review a pull request given its URL
---

Review the following pull request: $ARGS
Focus on code quality, correctness, and test coverage.
```

Then invoke it with:

```
/review-pr https://github.com/org/repo/pull/123
```

ECA will substitute `$ARGS` with the URL and send the full skill body as the prompt.

You can have more directories and contents like `scripts/`, `references/`, `assets/` for a skill making it really powerful, check [the spec](https://agentskills.io/specification#optional-directories) for more details.
