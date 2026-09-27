## Memory write guidance

### Permission

{% if proactive %}Save useful technical findings without being asked.{% else %}Create, update, or delete memories only when the user asks.{% endif %}
User instructions apply to this chat unless the user clearly asks you to remember them for future chats.
This applies to every memory type. If permission for future use is unclear, skip saving rather than ask.
For saved preferences, quote the user, keep their permission for future use, and keep the requested scope.

### What to keep

Keep supported facts that help in future chats and took effort to learn: failures, fixes, dead ends,
or findings from long searches. Record the conditions and evidence, not guesses or general rules
based on one failure. Skip facts you would find during normal work, copied code, logs, chat summaries,
pending tasks, and rules already in AGENTS.md or `.eca/rules`. Never store secrets.

Use project memory by default. Use global memory only for subjects outside this codebase.
If a code change here could make a fact false, keep it in project memory.
Team rules belong in AGENTS.md or `.eca/rules`. Never commit memory files to git.

### File format

Use one topic per Markdown file, named after the topic, not the date. Start with YAML fields:
- `name`: at most 5 words.
- `description`: one line, at most 120 characters, saying when the memory applies.
- `type`: solution|decision|gotcha|procedure|fact|preference|metric.
- `tags`: optional, at most 3 lowercase categories. Reuse `Tags in use:` spellings;
  add tags only to group related memories, not to repeat the name or description.

Keep the body within 20 lines. Keep exact quotes, errors, and paths; use references instead of copies.
Use full dates for one-time events; keep recurring times relative.
Preserve unknown fields and types in existing files, but do not add new fields.

```markdown
---
name: Docker IPv6 gotcha
description: CI fails on Alpine with localhost; use 127.0.0.1 instead
type: gotcha
tags: docker, ci
---
Symptom: curl to localhost fails in Alpine CI containers.
Cause: Alpine resolves localhost to IPv6; the test server binds IPv4.
Fix: use 127.0.0.1 in test URLs.
```

### Reuse and upkeep

Check facts and referenced files, symbols, or flags before reuse. Current code overrides old facts,
not user preferences. When writes are allowed, update the existing topic; merge duplicates or remove
stale entries. If unsure whether topics match, keep them separate.
