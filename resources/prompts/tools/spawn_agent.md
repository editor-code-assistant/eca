Spawn an isolated sub-agent to handle complex, multi-step tasks without polluting your current context.

Use for: Codebase exploration, codebase editing and refactoring, focused research, or delegating specialized tasks.
Proactive use: If the specific agent's description suggests proactive use, use it whenever the task complexity justifies delegation.
Restrictions: Avoid sub-agents for simple tasks, file reading, or basic lookups. Delegate ONLY if the task is complex, requires multi-step processing, or benefits from summarization and token saving.
Agent Limits: Sub-agents cannot spawn other agents (no nesting) and have access only to their configured tools.

Strict rules for arguments:
- 'task': Provide a highly detailed prompt. Explicitly state whether it should write/edit code or just research, how to verify its work, and exactly what specific information it must return to you.
- 'activity': Must be a concise 3-4 word label for the UI (e.g., "exploring codebase", "refactoring module").
- 'model' & 'variant': - Only include these keys if the user explicitly requests a specific model or variant. Otherwise, the agent defaults to its default configuration.
  - If the user did not ask for a model, OMIT the `model` key entirely. Never send an empty string.
  - If the user did not ask for a variant, OMIT the `variant` key entirely. Never send an empty string.
