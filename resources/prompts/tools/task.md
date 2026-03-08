Task management for planning and tracking work.

This tool is the authoritative task tracker. Any multi-step plan/todo list you intend to execute MUST be represented here (with `plan/start/complete`).

When to Use:
- Non-trivial work where progress tracking helps (multiple tasks, dependencies, or iterative debugging)
- User provides multiple actionable tasks/requests
- User explicitly requests task tracking or a plan/todo list

When NOT to Use:
- A single small action you can complete immediately without losing context
- Purely informational or conversational queries

Operations:
- read: View current task list state
- plan: Create/replace task list with initial tasks (required: tasks)
- add: Append task(s) to existing task list
- update: Modify a single task metadata by `id` (subject, description, priority, blocked_by) — cannot change status
- start: Begin work on tasks by `ids` (sets to in_progress; rejects blocked or done tasks). Requires `active_summary` (MAX 8 WORDS) to briefly state what you are about to do.
- complete: Mark tasks by `ids` as done (verify acceptance criteria in description first)
- delete: Remove tasks by `ids`
- clear: Reset entire task list (removes all tasks)

Task Schema:
- `subject`: A brief, actionable title in imperative form (e.g. "Fix login bug")
- `description`: Detailed description of what needs to be done, including context and STRICT acceptance criteria. Objective verification of these criteria is required for completion.

Workflow & Strict Execution Rules:
1. PLAN: Use 'plan' to create the task list with initial tasks. You MUST wait for the tool to return the generated task ids before doing anything else. Do NOT execute any work or call other tools in the same step as 'plan'.
2. START & SYNC: Use 'start' immediately before working on a task. Start ONLY tasks you are actively working on, or will start working on right now. You are FORBIDDEN to execute any work on a task unless its status is explicitly `in_progress`.
3. DO THE WORK FIRST: After calling 'start', you MUST actually perform the work (write code, use tools, etc.). NEVER call 'complete' preemptively; only call 'complete' after the work has been done and objectively verified against the task's `description` acceptance criteria since the corresponding 'start'.
4. SUBAGENTS & PARALLEL WORK: You may start multiple independent tasks in parallel (e.g., via delegating focused work to subagents). Batch 'start' (multiple ids) ONLY when the work is truly parallel. Only the main agent updates the task list.
5. COMPLETION TIMING: Once a task is verified, close it as soon as possible. Do not delay completion just to align with other tasks.
6. ADAPTABILITY: If completing tasks reveals follow-up work, use 'add' to append new tasks.
7. CLEANUP: When a plan is fully completed and no further work is needed, always use the 'clear' operation to clean up the workspace.
