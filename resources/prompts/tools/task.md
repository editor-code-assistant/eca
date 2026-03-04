Task management for planning and tracking work.

When to Use:
- Tasks requiring 3 or more distinct steps
- User provides multiple tasks (numbered lists, comma-separated items)
- Non-trivial work that benefits from planning and tracking
- User explicitly requests task tracking or a todo list

When NOT to Use:
- Single, trivial task completable in very few steps
- Purely informational or conversational queries
- Quick fixes where tracking adds no organizational value

Operations:
- read: View current task list state
- plan: Create/replace task list with initial tasks (required: tasks)
- add: Append task(s) to existing task list
- update: Modify a single task metadata by `id` (subject, description, priority, blocked_by) — cannot change status
- start: Begin work on tasks by `ids` (sets to in_progress; rejects blocked or done tasks). Requires `active_summary` to summarize what you are about to do.
- complete: Mark tasks by `ids` as done (verify acceptance criteria in description first)
- delete: Remove tasks by `ids`
- clear: Reset entire task list (removes all tasks)

Workflow:
1. Use 'plan' to create task list with initial tasks
2. Use 'start' before working on a task — marks it as in_progress and requires an `active_summary`
3. Work sequentially by default. Batch 'start' or 'complete' operations (using multiple 'ids') ONLY for independent tasks being executed simultaneously (e.g., via subagents).
4. Use 'complete' only for tasks that are actually finished; verify acceptance criteria first — the response tells you which tasks got unblocked
5. Use 'add' if you discover additional work during execution
6. When a plan is fully completed and no further work is needed, always use the 'clear' operation to clean up the workspace.

Task Schema:
- `subject`: A brief, actionable title in imperative form (e.g. "Fix login bug")
- `description`: Detailed description of what needs to be done, including context and acceptance criteria

Task Completion Integrity:
- Mark tasks complete as soon as they are finished.
- ONLY mark it as completed when ALL acceptance criteria from the `description` are actually met (for each task in a batch).
- Do NOT complete if: tests failing, implementation partial, or errors unresolved.
- When completing reveals follow-up work, use 'add' to append new tasks.
