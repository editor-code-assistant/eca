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
- read: View current TODO state
- plan: Create/replace TODO with goal and tasks (required: goal, tasks)
- add: Append task(s) to existing TODO
- update: Modify a single task metadata by `id` (content, priority, done_when, blocked_by) — cannot change status
- start: Begin work on tasks by `ids` (sets to in_progress; rejects blocked or done tasks)
- complete: Mark tasks by `ids` as done (verify done_when criteria first)
- delete: Remove tasks by `ids`
- clear: Reset entire TODO (removes goal and all tasks)

Workflow:
1. Use 'plan' to create TODO with goal and initial tasks
2. Use 'start' before working on a task — marks it as in_progress
3. Work sequentially by default. Batch 'start' or 'complete' operations (using multiple 'ids') ONLY for independent tasks being executed simultaneously (e.g., via subagents).
4. Use 'complete' only for tasks that are actually finished; for each targeted task that has `done_when`, verify it first — the response tells you which tasks got unblocked
5. Use 'add' if you discover additional work during execution
6. When a plan is fully completed and no further work is needed for the current goal, always use the 'clear' operation to clean up the workspace.

How to use done_when:
- `done_when` is optional.
- Use it for non-trivial tasks where completion must be verifiable (tests passing, behavior implemented, specific file changes, etc.).
- For trivial/obvious tasks, you may omit `done_when` to avoid overhead.

Task Completion Integrity:
- Mark tasks complete as soon as they are finished.
- If a task has `done_when`, ONLY mark it as completed when ALL `done_when` criteria are actually met (for each task in a batch).
- Do NOT complete if: tests failing, implementation partial, or errors unresolved.
- When completing reveals follow-up work, use 'add' to append new tasks.
