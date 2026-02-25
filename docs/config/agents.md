# Agents / Subagents

![](../images/features/chat-agents.png)

When using ECA chat, you can choose which agent it will use, each allows you to customize its system prompt, tool call approvals, disabled tools, default model, skills and more.

There are 2 types of agents defined via `mode` field (when absent, defaults to primary):

- `primary`: main agents, used in chat, can spawn subagents.
- `subagent`: an agent allowed to be spawned inside a chat to do a specific task and return a output to the primary agent.

## Built-in agents

| name         | mode     | description                                                                                                                                                             |
|--------------|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| __code__     | primary  | Default, generic, used to do most tasks, has access to all tools by default                                                                                             |
| __plan__     | primary  | Specialized in building a plan before user switches to code agent and executes the complex task. Has no edit tools available, only preview changes.                     |
| __explorer__ | subagent | Fast agent specialized for exploring codebases. Finds files by patterns, searches code for keywords, or answers questions about the codebase. Read-only, no edit tools. |
| __general__  | subagent | General-purpose agent for researching complex questions and executing multi-step tasks. Can be used to execute multiple units of work in parallel.                      |

## Custom agents and prompts

You can create an agent and define its prompt, tool call approval and default model.

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

- `mode` (required): `subagent` always, this is waht differ a primary agent than a subagent for ECA.
- `description` (required): a short summary of what this subagent will do, this is important to primary agent knows when to delegate to it.
- `systemPrompt` or the markdown content (required): Instructions for the subagent to what do when receive a task.

- `model` (optional): which full model to use for this subagent, using primary agent model if not specified.
- `tools` (optional): same as ECA tool approval logic to control what tools are allowed/askable/denied.
- `maxSteps` (optional): set a max limit of turns/steps that his subagent must finish and return an answer.

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

