Spawn or continue an isolated sub-agent to handle complex, multi-step tasks without polluting your current context.

Use for: Codebase exploration, codebase editing and refactoring, focused research, or delegating specialized tasks.
Proactive use: If the specific agent's description suggests proactive use, use it whenever the task complexity justifies delegation.
Restrictions: Avoid sub-agents for simple tasks, file reading, or basic lookups, unless the user explicitly asks. Delegate ONLY if the task is complex, requires multi-step processing, or benefits from summarization and token saving.
Agent Limits: Sub-agents cannot spawn other agents (no nesting) and have access only to their configured tools.

Strict rules for arguments:
- 'task': Provide a highly detailed prompt. Explicitly state whether it should write/edit code or just research, how to verify its work, and exactly what specific information it must return to you.
- 'activity': Optional concise 3-4 word label for the UI (e.g., "exploring codebase", "refactoring module").
- 'chat_id': Optional returned ID to continue a conversation in the same parent chat. Reuse its 'agent' and supply a new 'task'. It keeps its model and variant unless you override them. If continuation is rejected because the subagent is unavailable, omit 'chat_id' to spawn a new subagent and include the needed context in its 'task'.
- 'model' & 'variant': - NEVER include these arguments if the user hasn't explicitly requested a specific model or variant.
