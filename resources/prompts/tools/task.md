Task management for planning and tracking work.

This tool is the authoritative tracker for executable work in the current chat. Use it with `plan/start/complete` for multi-step work you intend to execute.

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

Task Creation Guidance:
- Order tasks by expected execution flow. Put prerequisite tasks before tasks that depend on them, and use `blocked_by` to record those dependencies explicitly.
- Create tasks as independently executable units with concise, outcome-focused subjects. Keep steps separate if one can be completed before the next begins. If several planned steps will be carried out in the same unit of work, combine them into one task and put the substeps in the description.

Workflow & Strict Execution Rules:
1. PLAN: Use 'plan' to create the task list with initial tasks. You MUST wait for the tool to return the generated task ids before doing anything else. Do NOT execute any work or call other tools in the same step as 'plan'.
2. START & SYNC: Use 'start' immediately before beginning work on a task. Start ONLY tasks you are about to work on. Do not start tasks preemptively. You are FORBIDDEN to execute any work on a task unless its status is explicitly `in_progress`.
3. DO THE WORK FIRST: After calling 'start', you MUST actually perform the work (write code, use tools, etc.). NEVER call 'complete' preemptively; only call 'complete' after the work has been done and objectively verified against the task's `description` acceptance criteria since the corresponding 'start'.
4. SUBAGENTS & PARALLEL WORK: For sequential work, keep only one task `in_progress`; later tasks must stay pending until you start them. Multiple tasks may be `in_progress` only for concurrent separate workstreams (e.g. separate subagents). Only the main agent updates the task list.
5. COMPLETION TIMING: Once a task is verified, close it as soon as possible. Do not delay completion just to align with other tasks.
6. ADAPTABILITY: If completing tasks reveals follow-up work, use 'add' to append new tasks.
7. CLEANUP: When all tasks are done and no further work remains, use 'clear'.
