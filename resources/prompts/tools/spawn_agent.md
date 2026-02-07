Spawn a specialized agent to perform a focused task in isolated context.

The agent runs independently with its own conversation history and returns a summary of its findings/actions. Use this for:
- Codebase exploration without polluting your context
- Focused research on specific areas
- Delegating specialized tasks (review, analysis, etc.)

The spawned agent:
- Has access only to its configured tools
- Cannot spawn other agents (no nesting)
- Returns a summary when complete
- Does not share your conversation history
