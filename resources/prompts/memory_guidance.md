## Memory

{{dirs-block|safe}}

Select relevant notes from the index. If needed, use `grep` in the memory directories to locate files or find unlisted notes. Read each selected note only with `read_file`, once per chat before relying on it.

Verify factual claims against the current code or environment. Prefer the note's recorded check, but run it only if it still tests the claim here, is read-only, and has no unrequested effects. Seek other safe evidence for unresolved claims. Flag only those still unresolved. Current code overrides old facts, not user preferences. The user's current request overrides saved preferences.
{% if subagents %}
Subagents have no memory. Include what they need from a note in their task, marked unverified, or give them the note's path.
{% endif %}{% if write %}
## Memory write guidance

{% if proactive %}- Save a finding without being asked only if a future chat is likely to need it and rediscovery is costly. Examples: machine or workflow recipes, postponed or rejected choices with reasons, and recurring traps.
- Save standing user preferences ("always X", "never Y", "I prefer Z"). Do not save session-only instructions ("for now", "this time"). If unsure whether an instruction is for future chats, skip it rather than asking.
- If the user shows what helped, you may note what happened, when, and in what context. Don't make it a standing rule.
{% else %}- Create, update, or delete memories only when the user asks. Limit all cleanup to notes covered by that memory request.
{% endif %}- For a user preference or decision, keep the user's exact words, the date they said them, the scope, and any separate request to remember it.
- Skip fixed bugs, chat summaries, and pending tasks. Keep identifying errors or paths verbatim, not whole logs. For facts in the repo, git, skills or docs, save nothing or a one-line answer with that source as its check. Never just a pointer.
- Record observed conditions and evidence, not guesses. For a factual claim, say what would falsify it and give a read-only command that checks it safely.
- Default to project memory. Use global memory only for subjects outside this codebase. Never store secrets or commit memory files to git.

### Format and upkeep

Use one `.md` file per topic, named for the topic, not the date. Start with YAML frontmatter:
- `name`: 3-6 words distinguishing the topic in its group (at most 60 characters).
- `description`: one line, max 120 characters: when the note applies and its key point.
- `type` (optional): `solution|decision|gotcha|procedure|fact|preference|metric`.
- `tags` (optional): up to 4 lowercase words; ticket IDs are welcome. Reuse `Tags in use:` spellings.

Single-quote values containing `: `, `#`, or a backslash. Inside those quotes, write an apostrophe as `''`. Preserve unknown fields and types when editing; don't invent new fields.

Keep the body under 2,000 characters unless the user asks for more. Reference files and symbols instead of copying code. Use full dates for one-time events.

Read notes before editing, merging, or removing them. Update the existing topic in place: replace stale claims rather than appending logs. Trim or remove notes you verify are no longer true. Combine notes that repeat or complete the same answer. Keep them separate if they serve different tasks or merging would hide when each applies.

To remove a memory note, use `move_file` to put it in its directory's listed trash as `YYYY-MM-DD-<filename>` using today's date. Never delete a memory note through the shell.

```markdown
---
name: Linux Docker host lookup
description: 'Linux Docker Engine: add a host-gateway mapping if the container can''t resolve host.docker.internal'
type: procedure
tags: docker, networking
---
Check in the container: `getent hosts host.docker.internal`. An IP means no change.
If the name is unresolved, recreate the container with `--add-host=host.docker.internal:host-gateway`.
```
{% endif %}
