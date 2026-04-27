---
description: "Use templates and condition variables in ECA configuration files, prompts, and rules."
---

# Templates

ECA supports templating in configurable prompts and rules, so instructions can adapt to the current chat context.

For loading files, environment variables, classpath resources, or netrc credentials in config strings, see [Dynamic string contents](introduction.md#dynamic-string-contents).

## Condition variables

Custom agent prompts and [rules](rules.md) are rendered with [Selmer](https://github.com/yogthos/Selmer). Use condition variables when one prompt or rule should behave differently depending on the current chat.

Available variables:

| Variable | Type | Description |
|----------|------|-------------|
| `isSubagent` | boolean | `true` when the chat is running as a subagent |
| `workspaceRoots` | string | The current workspace root paths |
| `toolEnabled_<tool-name>` | boolean | `true` when a tool is enabled, using its exact full name, e.g. `toolEnabled_eca__shell_command` |

```markdown title="Prompt or rule"
{% if isSubagent %}
Be concise and return only the final result.
{% else %}
Explain important trade-offs and assumptions.
{% endif %}

{% if toolEnabled_eca__shell_command %}
You can run shell commands to verify your work.
{% endif %}

Current workspace roots: {{ workspaceRoots }}
```

If a rule renders to an empty string, ECA skips it and does not add an empty rule block to the system prompt.
