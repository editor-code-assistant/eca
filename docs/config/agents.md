---
description: "Configure ECA agents and subagents: custom system prompts, model selection, tool access, and parallel task delegation."
---

# Agents / Subagents

![](../images/features/chat-agents.png)

When using ECA chat, you can choose which agent it will use, each allows you to customize its system prompt, tool call approvals, disabled tools, default model, skills and more.

Agents have a `mode` field that controls where they can be used. It accepts either a single string or a list:

- `primary`: main agent, used in chat, can spawn subagents.
- `subagent`: an agent allowed to be spawned inside a chat to do a specific task and return a output to the primary agent.

Examples:

- `mode: "primary"` — only selectable as a chat agent.
- `mode: "subagent"` — only spawnable by another agent.
- `mode: ["primary", "subagent"]` — usable in both roles.

When `mode` is not set, the agent is usable in both roles (equivalent to `["primary", "subagent"]`).

## Built-in agents

| name         | mode     | description                                                                                                                                                             |
|--------------|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| __code__     | primary  | Default, generic, used to do most tasks, has access to all tools by default                                                                                             |
| __plan__     | primary  | Specialized in building a plan before user switches to code agent and executes the complex task. Has no edit tools available, only preview changes.                     |
| __explorer__ | subagent | Fast agent specialized for exploring codebases. Finds files by patterns, searches code for keywords, or answers questions about the codebase. Read-only, no edit tools. |
| __general__  | subagent | General-purpose agent for researching complex questions and executing multi-step tasks. Can be used to execute multiple units of work in parallel.                      |

## Inheriting from other agents

You can create a new agent that inherits all settings from an existing agent using the `inherit` key. The new agent's settings are deep-merged on top of the inherited agent's settings, so any field you specify overrides the parent's value while the rest is preserved.

This is useful when you want a variant of a built-in or custom agent with small tweaks, without duplicating the entire configuration.

=== "Config"

    ```javascript title="~/.config/eca/config.json"
    {
      "agent": {
        "my-plan": {
          "inherit": "plan",
          "defaultModel": "openai/gpt-5"
        }
      }
    }
    ```

    The `my-plan` agent above inherits all of `plan`'s configuration (disabled tools, tool approval rules, prompts, etc.) but uses a different default model.

=== "Markdown"

    ```markdown title=".eca/agents/my-explorer.md"
    ---
    inherit: explorer
    description: Explorer with a custom model
    defaultModel: google/gemini-2.5-pro
    ---
    ```

## Custom agents and prompts

You can create an agent and define its prompt, tool call approval and default model. Custom prompts can use [template variables](introduction.md#templates).

=== "Example: my-agent"

    ```javascript title="~/.config/eca/config.json"
    {
      "agent": {
        "my-agent": {
          "prompts": {
            "chat": "${file:/path/to/my-agent-prompt.md}"
          }
        }
      }
    }
    ```

=== "Example: Overriding a tool description"

    ```javascript title="~/.config/eca/config.json"
    {
      "prompts": {
        "tools": {
          "eca__edit_file": "${file:/path/to/my-tool-prompt.md}"
        }
      }
    }
    ```

## Subagents

ECA can spawn foreground subagents in a chat, they are agents which `mode` is `subagent`.

The major advantages of subagents are:

- __Less context polution__: sometimes LLM needs to do a specific task that require multiple tool calls, but after finished, it doesn't need all those tool calls and iterations in its history, subagent works great for that, for example the `explorer` built-in subagent.
- __Less context window usage__: Since subagents work as different chats/context/cleaner context, they have their own context window and when done the tools and process done there doesn't affect the primary agent context window, resulting and bigger conversations and less compaction needed.
- __Parallel subagents__: subagents are spawned as tools, and ECA supports parallel tool calls if LLM supports, this increase speed of task solution if LLM needs for example to explore 2-3 different things with `explorer` subagent, spawning those in parallel.

Subagents can be configured in config or markdown and support/require these fields:

- `mode`: set to `"subagent"` (or `["subagent"]`) to restrict an agent to subagent use only. Omit or include `"primary"` to also allow chat use.
- `description` (required): a short summary of what this subagent will do, this is important to primary agent knows when to delegate to it.
- `systemPrompt` or the markdown content (required unless using `inherit`): Instructions for the subagent to what do when receive a task.

- `inherit` (optional): name of another agent to inherit all settings from. The subagent's own fields are merged on top.
- `spawnableBy` (optional): one primary agent ID or a collection of primary agent IDs allowed to discover and spawn this subagent. When omitted or empty, the subagent remains available to every primary agent.
- `model` (optional): which full model to use for this subagent, using primary agent model if not specified.
- `tools` (optional): same as ECA tool approval logic to control what tools are allowed/askable/denied.
- `disabledTools` (optional): tools to hide from this agent entirely. Same matching as the global [`disabledTools`](tools.md#disabled-tools): a builtin tool name or regex (no `eca__` prefix needed), an exact MCP server name (all its tools), or a regex against the tool full name `server__tool`.
- `maxSteps` (optional): set a max limit of turns/steps that his subagent must finish and return an answer.

### Parent-scoped subagents

Use `spawnableBy` for workflow-specific workers that should only be available to one or more orchestrator agents. It accepts either a single agent ID or a collection:

```yaml
spawnableBy: duel
```

```yaml
spawnableBy:
  - duel
  - another-orchestrator
```

Matching uses exact resolved agent IDs. Markdown agent IDs and Markdown `spawnableBy` values are trimmed and lowercased during loading; JSON configuration values are matched against the configured agent keys exactly.

When `spawnableBy` is omitted or empty, the subagent is unrestricted, preserving the default behavior. When it contains IDs:

- only a listed current primary agent sees the subagent in the `spawn_agent` tool description, `/subagents`, and contextual diagnostics such as the built-in `eca-info` skill;
- a missing current parent agent cannot discover or spawn the restricted subagent;
- direct `spawn_agent` calls are checked server-side and rejected with a generic unavailable error, even if the caller supplies the exact hidden agent ID;
- `spawnableBy` only controls subagent discovery and spawning. An agent whose `mode` also includes `primary` remains selectable as a primary agent;
- existing subagent nesting restrictions remain unchanged.

`spawnableBy` participates in normal agent inheritance. An inherited value is preserved unless the child overrides it through the usual configuration merge behavior.

The `/config` command intentionally remains an administrative, raw resolved-configuration view and can show all agent configuration, including `spawnableBy`. It is not a discovery or spawning surface.

=== "Example: private orchestrator workers"

    ```yaml title=".eca/agents/duel-plan-adversary.md"
    ---
    mode: subagent
    description: Challenge the orchestrator's implementation plan
    spawnableBy: duel
    ---

    Review the proposed plan adversarially and return concrete risks.
    ```

    ```yaml title=".eca/agents/duel-implementer.md"
    ---
    mode: subagent
    description: Implement the plan approved by the duel orchestrator
    spawnableBy:
      - duel
    ---

    Implement only the approved plan and report the changes made.
    ```

    ```yaml title=".eca/agents/duel-code-reviewer.md"
    ---
    mode: subagent
    description: Review the duel implementer's changes
    spawnableBy: duel
    ---

    Review the implementation for correctness, regressions, and missing tests.
    ```

    With a primary agent named `duel`, these workers appear in its `spawn_agent` tool and `/subagents` output. Other primary agents cannot discover or spawn them.

=== "Markdown"

    Agents can be defined in local or global markdown files inside a `agents` folder, those will be merged to ECA config. Example:
    
    ```markdown title="~/.config/eca/agents/my-agent.md"
    ---
    mode: subagent
    description: You sleep one second when asked
    model: ${env:MY_MODEL:anthropic/sonnet-4.5}
    tools:
      byDefault: ask
      deny: 
        - my_mcp__my_tool
      allow: 
        - eca__shell_command
    ---
    
    You should run sleep 1 and return "I slept 1 second"
    ```
    
    !!! info "Agent id"

        The agent id (the key under `agent`) is taken from the YAML `name:` field when present (trimmed and lowercased). Otherwise it falls back to the filename with all extensions stripped, so `architect.agent.md` becomes `architect`. This keeps Claude Code / OpenCode plugin agent files working without renaming.

    !!! info "Pattern-based tool approval in markdown"

        You can append a regex pattern in parentheses after a tool name to restrict approval to calls matching the pattern. Currently only `eca__shell_command` supports this — the pattern is matched against its `command` argument. Multiple entries for the same tool are automatically merged.

        ```yaml
        tools:
          allow:
            - eca__shell_command(npm run .*)
            - eca__shell_command(git diff(\s+.*)?)
            - eca__read_file
        ```

        This is equivalent to `argsMatchers` in JSON config. Patterns on tools other than `eca__shell_command` are currently ignored.

    !!! info "Claude-compatible tools list"

        For Claude Code / OpenCode plugin compatibility, `tools` can also be given as a flat YAML list. It is treated as `byDefault: ask` plus the list as `allow`:

        ```yaml
        tools:
          - eca__read_file
          - eca__grep
        ```

        The entries must be ECA tool ids for the allowlist to take effect; unknown names are accepted but will not match any real tool.

    !!! info "Tool call approval"
        
        For more complex tool call approval, use toolCall via config
    
=== "Config"

    ```javascript title="~/.config/eca/config.json"
    {
      "agent": {
        "sleeper": {
          "mode": "subagent",
          "description": "You sleep one second when asked",
          "systemPrompt": "You should run sleep 1 and return \"I slept 1 second\"",
          "defaultModel": "anthropic/sonnet-4.5",
          "toolCall": {...},
          "maxSteps": 25 // Optional: to limit turns in subagent
        }
      }
    }
    ```

