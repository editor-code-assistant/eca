Spawn a new agent to handle multistep tasks in isolated context.

The agent runs independently with its own conversation history and returns a summary of its findings/actions.
Use this for:
- Codebase exploration without polluting your context
- Focused research on specific areas
- Delegating specialized tasks (review, analysis, etc.)

The spawned agent:
- Has access only to its configured tools
- Cannot spawn other agents (no nesting)
- Must return a summary when complete

Usage notes:
- Your prompt should contain a highly detailed task description for the agent to perform autonomously and you should specify exactly what information the agent should return back to you in its final message.
- Clearly tell the agent whether you expect it to write code or just to do research and how to verify its work if possible.
- If the agent description suggests proactive use, use it whenever the task complexity justifies delegation.
- Avoid sub-agents for simple tasks, file reading, or basic lookups. Delegate only if the task is complex, requires multi-step processing, or benefits from summarization and token saving.
