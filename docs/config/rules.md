---
description: "Configure ECA rules: define coding standards, conventions, and constraints the LLM must follow globally or per project."
---

# Rules

Rules let you influence how the model behaves without changing an agent prompt. Use them for coding standards, review checklists, project conventions, safety constraints, or file-specific guidance.

A rule is a Markdown file. By default, rule content is rendered into the system prompt for every chat where the rule matches. Add YAML frontmatter when a rule should apply only to specific agents, models, or paths.

## Rules vs Skills

- Use rules to make ECA automatically follow project conventions, safety constraints, or guidance for matching paths.
- Use [skills](./skills.md) to teach ECA reusable workflows, tools, or domain knowledge it can load on demand for specific tasks.

## Rule locations

ECA loads rules from 3 sources:

=== "Project file"

    Files inside `.eca/rules` under a workspace root.

    ```markdown title=".eca/rules/code-style.md"
    Prefer small, focused functions and idiomatic Clojure.
    ```

=== "Global file"

    Files inside `$XDG_CONFIG_HOME/eca/rules` or `~/.config/eca/rules`.

    ```markdown title="~/.config/eca/rules/answers.md"
    Be concise and explain trade-offs when suggesting code changes.
    ```

=== "Config"

    Paths listed in the `rules` config key. `path` can point to a single rule file or a directory. Directories are loaded recursively, loading all files within. Relative paths are searched from each workspace root. Absolute paths inside a workspace behave as project rules; absolute paths outside workspaces behave as global rules.

    ```javascript title="~/.config/eca/config.json"
    {
      "rules": [{"path": "my-rule.md"}]
    }
    ```

    ```javascript title="~/.config/eca/config.json"
    // Load all rules from a directory recursively
    {
      "rules": [{"path": "/home/user/rules"}]
    }
    ```

## Static and path-scoped rules

Most rules should be **static rules**: rules without `paths`. Their full content is automatically included in the system prompt. Use them for guidance that should always be available, such as coding style, response tone, or repository-wide conventions.

```markdown title=".eca/rules/clojure-style.md"
Prefer immutable data, kebab-case names, and small functions.
```

Use **path-scoped rules** when the guidance only matters for matching files. Add `paths` in frontmatter. Instead of injecting the full rule into every prompt, ECA lists a small catalog of matching path-scoped rules. The model can then call `fetch_rule` with the exact rule id and target path to load the full content when needed.

```markdown title=".eca/rules/html-style.md"
---
paths: "**.html"
---

Use semantic HTML and keep accessibility in mind.
```

If `fetch_rule` is unavailable in the current chat, path-scoped rules are treated as inactive and are omitted from the prompt. Use the `/rules` command to inspect which rules are available for the current agent and model.

## Frontmatter

Rules support YAML frontmatter. Recognized fields are `agent`, `model`, `paths`, and `enforce`. Rules without frontmatter are static rules that apply to all agents and models.

### `agent`

Restricts a rule to one agent or a list of agents.

```markdown title=".eca/rules/code-only.md"
---
agent: code
---

Prefer making the smallest safe code change.
```

```markdown title=".eca/rules/shared.md"
---
agent:
  - code
  - plan
---

Call out risky assumptions before proceeding.
```

### `model`

Restricts a rule to models whose full model identifier matches a regex pattern. The full identifier includes the provider, such as `anthropic/claude-sonnet-4-20250514` or `openai/gpt-5.2`.

```markdown title=".eca/rules/high-reasoning-models.md"
---
model:
  - ".*claude-sonnet-4.*"
  - ".*gpt-5.*"
---

Spend more time validating edge cases before editing.
```

Patterns are partial-match regexes using `re-find`, so `gpt-4` also matches `openai/gpt-4o-mini`. Use anchors (`^...$`) when you need an exact match.

### `paths`

Marks a rule as path-scoped. It accepts one glob pattern or a list of patterns matched against workspace-relative paths.

```markdown title=".eca/rules/frontend.md"
---
paths:
  - "src/**.{ts,tsx}"
  - "lib/**.ts"
---

Follow the project's frontend conventions.
```

For project rules, patterns are matched relative to the workspace root that owns the rule. For global rules, patterns can match files inside any current workspace root.

Path matching uses Java NIO `PathMatcher` glob syntax. Common patterns:

| Pattern | Matches | Does not match |
|---------|---------|----------------|
| `src/*.clj` | `src/foo.clj` | `src/nested/foo.clj` |
| `src/**/*.clj` | `src/nested/foo.clj` | `src/foo.clj` |
| `src/**.clj` | `src/foo.clj`, `src/nested/foo.clj` | `test/foo.clj` |
| `docs/**.md` | `docs/index.md`, `docs/config/rules.md` | `src/docs.md` |

Unlike many shell glob matchers, `**/` does not match the zero-directory case. For example, `src/**/*.clj` matches `src/nested/foo.clj`, but not `src/foo.clj`. Use `src/**.clj` or `{src/*.clj,src/**/*.clj}` when you need both.

### `enforce`

`enforce` only applies to path-scoped rules. It has no effect without `paths`, because static rules are already included in the system prompt.

For matching path-scoped rules, `enforce` controls when ECA requires the rule to be fetched before a builtin file tool proceeds.

| Value | Meaning |
|-------|---------|
| `modify` | Fetch before modifying a matching file. This is the default. |
| `read` | Fetch before reading a matching file with `read_file`. |
| `[read, modify]` | Fetch before both reading and modifying. |

```markdown title=".eca/rules/api-style.md"
---
paths: "src/api/**.ts"
---

Follow the API naming and error-response conventions.
```

```markdown title=".eca/rules/sensitive-data.md"
---
paths: "src/data/**.clj"
enforce:
  - read
  - modify
---

Never expose PII fields in API responses.
```

When `enforce` is omitted, it defaults to `modify`.

## How to use rules

Use this rule of thumb:

- Use a static rule when the model should always know the instruction.
- Use a path-scoped rule when the instruction only matters for certain files.
- Add `enforce: read` or `enforce: modify` when the model must fetch the matching rule before using the corresponding builtin file tool.
- Use `agent` and `model` filters when the rule is only relevant for specific chat modes or model families.

Path-scoped rules keep the base prompt smaller while still making file-specific guidance available. When the model calls `fetch_rule`, ECA validates the exact rule id and absolute target path, renders the rule content, and records that the rule was fetched for that path in the current chat. You can also use this to influence behavior for a specific provider. For example, if you want more tool calls instead of user prompts, you can do something like:
```markdown title=".eca/rules/copilot-ask-user.md"
---
model: "github-copilot/.*"
---

Strongly prefer using the `eca__ask_user` tool over ending your turn or asking questions inline in your response.
```

## Condition variables

Rules support [template variables](template.md#condition-variables) using [Selmer](https://github.com/yogthos/Selmer). If a rule renders to an empty string, ECA skips it.

```markdown title=".eca/rules/context-aware.md"
{% if isSubagent %}
Be concise and return only the final result.
{% else %}
Provide explanations and mention important trade-offs.
{% endif %}
```
