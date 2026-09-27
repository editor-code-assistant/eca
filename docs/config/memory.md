# Durable memory

ECA can keep a small store of reusable knowledge across chats. Memory is opt-in and uses plain Markdown files with YAML frontmatter, not a database. When enabled, ECA scans the memory directories before each user prompt turn and adds a bounded index as trailing context. Write guidance and directory paths are in the static system instructions. The agent reads and writes memory with its normal filesystem tools (`eca__read_file`, `eca__write_file`, `eca__grep`, and others). There are no dedicated memory tools.

```json
{
  "memory": {
    "enabled": true,
    "writeMode": "agent",
    "index": {
      "maxEntries": 100,
      "maxTokens": 2000
    }
  }
}
```

`enabled` defaults to `false`; the whole subsystem is off until you turn it on. When memory is disabled, the index is not injected and the `memory/list` method returns an error.

## Two tiers

Memory lives in two tiers, injected into the prompt in this order (per workspace root first, then global):

| Tier | Directory | Audience | Write policy |
|---|---|---|---|
| Personal per-project | `<memory-root>/projects/<project-slug>` | Personal, one project | Default write target |
| Global | `<memory-root>/global` | Personal, all projects | Agent may save (subject to `writeMode`) |

Memory is personal-only by design. To share knowledge with the whole team, put it in the project's `AGENTS.md` or `.eca/rules/` instead — those are versioned, reviewed, and already loaded into every prompt. (A shared per-project tier at `<workspace-root>/.eca/memory` was considered and cut from V1; it may return later shaped by real usage.)

`<memory-root>` is `<XDG_CONFIG_HOME or ~/.config>/eca/memory` — one place to browse, back up, symlink, or wipe all personal memory. ECA pre-creates the global dir and every workspace's personal dir at prompt-build time (idempotent).

`<project-slug>` is derived from the canonicalized workspace path: a human-readable slug (runs of non-alphanumeric characters become a single `-`, dashes trimmed from both ends) suffixed with a short hash of the canonical path — e.g. `/home/akiz/Code/Clojure/eca` → `home-akiz-Code-Clojure-eca-1a2b3c4d`. The hash keeps distinct projects from colliding when their readable slugs would be identical, and trimming dashes keeps the directory shell-friendly. Linked git worktrees map to their main worktree root, so they share personal memory. In multi-workspace chats each root contributes its personal dir; the index labels entries by root basename (`<basename>` for personal, `global` for global).

## File format

Keep one topic per file. The full absolute path identifies a memory; names and labels are for display only. Renaming or moving a file changes its identity. Files are Markdown with YAML frontmatter (metadata between `---` lines):

```markdown
---
name: Docker IPv6 gotcha
description: CI fails on Alpine with localhost; use 127.0.0.1 instead
type: gotcha
tags: docker, ci
---

Full free-text content here.
```

- `name` (required) and `description` (required — the description is what the index shows).
- `type` (optional): `solution`, `decision`, `gotcha`, `procedure`, `fact`, `preference`, or `metric`; defaults to `note` when absent.
- `tags` (optional): comma-separated or a YAML list.
- Readers accept unknown metadata keys and `type` values. Agents should create the fields listed above and preserve unknown fields and types when editing existing files. This permits later format additions without making existing memories invalid.
- The body may open or close with an optional `summary:` line — one plain sentence answering "what do I do with this?" — for quick human skimming and as durable evidence for the consolidation audit (the per-turn tag index is volatile and cannot serve as that evidence).
- Scanning is recursive (`**/*.md`); a flat layout is recommended, but hand-organized subdirectories work too.
- Parsing is lenient: files missing `name`/`description` or with malformed frontmatter are skipped with a logged warning — a bad file never breaks prompt building.

## Always-on index

With memory enabled, every user prompt turn gets a generated index appended as trailing context. (The write guidance — policies, file format, conventions — and the annotated directory listing are session-stable, so they live in the static system instructions instead, riding the cached prompt prefix like rules and skills do; only the volatile index wastes per-turn tokens.) The index groups memories under one heading per active memory directory, with a `Tags in use:` vocabulary line per group (distinct tags, most-used first, hard-capped, with a `(n)` count on any tag used more than once — this is what lets the agent reuse an existing tag instead of inventing a near-variant when saving, while the wording stays descriptive on purpose: it reports what exists, and a genuinely new topic may still introduce a new tag), followed by one compact line per memory:

```
## Memory
## eca
Tags in use: ci, docker
- Docker IPv6 gotcha [gotcha] (updated 3d ago) — CI fails on Alpine with localhost; use 127.0.0.1 instead
## global
- …
```

Entries are grouped by personal directory in workspace-root order, then global. Within each group, the prompt index ranks entries by read history and the recorded body-change time, with older observations given less weight. Without recorded reads, the most recently changed bodies come first. File modification time supplies the initial body-change date; later metadata-only edits do not reset it. See Usage tracking below. With zero memories, no index is injected. The memory directories remain available in the static write guidance.

The index is limited by `index.maxEntries` (default 100) and `index.maxTokens` (default 2000, estimated for the whole block). When not every entry fits, entries are removed from the end and a notice tells the agent to search the memory directories for the required topic. If the notice itself cannot fit, or `maxTokens` is zero, the index is omitted. The static memory guidance and directory list remain enabled. The index is not injected into subagent chats.

The `memory/indexLoaded` notification reports the included `count`, the available `totalCount`, and the included items (see [protocol.md](../protocol.md)). Clients can display "30 of 80 memory entries included". Entries outside the index remain searchable. A budget too small for any index text still reports the total, with zero included. Notifications are sent when the index, items, or total change; an initially empty store sends nothing.

## Usage tracking

ECA keeps a small local bookkeeping ledger at `<memory-root>/usage.edn`, recording how often each memory's *body* is consulted: `read_file` opens of files inside memory dirs bump a per-file read count and last-read timestamp. The same ledger also stores a hash of each memory's body plus the timestamp at which that hash last changed, which is what "updated Nd ago" and the consolidation snapshot's age column report. This separates *when the knowledge changed* from *when the file was written*: `/memory-consolidate` normalizes tags and trims descriptions, so it rewrites files without changing what they say, and reporting raw mtime would let one run present months-old memories as fresh and corrupt the staleness signal its own next run depends on. A merge or a rewritten body moves the date; a retag does not. The first time a file is seen the date is seeded from its mtime, so enabling this on an existing store changes nothing. Deliberately **not** counted: grep hits, writes (a write already bumps the file's mtime), and reads made during a `/memory-consolidate` run itself (maintenance would inflate the signal it feeds). Reads by subagents are attributed through their parent chat, so a consolidating chat's own subagent reads are excluded too.

The ledger is internal state, not part of the public memory format. It uses absolute paths as keys and removes records for files that no longer exist on load or update. Each update locks the ledger across threads and ECA processes, reads the latest disk state, applies the change, and replaces the file through a unique temporary file. Replacement is atomic where the filesystem supports it. Invalid EDN is treated as empty data with a logged warning; failed writes are logged. These failures do not change the memory files themselves. The agent never edits the ledger.

Usage data is consumed in two places only: the order of entries in the always-on index (above), and the usage snapshot injected into `/memory-consolidate` (below). It is never rendered per memory in the index.

## Write policy

`writeMode` defaults to `agent` and accepts:

- `agent`: the agent may automatically save well-supported technical findings, such as a failed command, the conditions that caused it, and a verified fix. User preferences and instructions require clear permission to remember them for future chats.
- `explicit`: the agent is instructed to write, update, or delete memory only when you ask, including technical findings.

In both modes, ordinary instructions apply to the current chat by default. "Never use subagents" is not a request to save a lasting rule, even if repeated. "Remember that I prefer short answers" or "Use this in future chats" gives permission to save that preference. No special tag or exact phrase is needed. If future use is unclear, the agent should skip saving rather than repeatedly ask. To define standing project rules, use `AGENTS.md` or `.eca/rules/`.

These policies are model instructions, not strict file-write restrictions. Technical findings must include their conditions and evidence, and must be checked before reuse; a single failure does not establish that a tool never works.

An invalid `writeMode` value uses `explicit`. There is no separate `off` write mode; `memory.enabled: false` disables the memory feature, but does not restrict normal file tools.

While memory is enabled, reads and previews inside memory directories do not require approval solely because the path is outside the workspace. In `agent` mode, writes and moves receive the same path trust. In `explicit` mode, writes do not receive this extra trust. Normal workspace rules, configured approval rules, remembered approvals, and chat trust still apply. Thus, explicit mode does not guarantee a confirmation for every write. Configured `toolCall.approval` `deny` rules always win over trust.

Independently of `writeMode`, `write_file`/`edit_file` calls that target a memory dir are rejected when the resulting content would not parse as a valid memory file (YAML frontmatter mapping with non-blank `name` and `description`) — a malformed file would otherwise vanish from the index silently. The rejection happens before any bytes hit disk, so the agent can fix the content and retry.

The write guidance lives in the static system instructions, alongside the directory list, rather than the per-turn index. It defines write policies, the file format, topic conventions, and what not to save. ECA always uses the bundled `resources/prompts/memory_guidance.md`; there is no public configuration option to replace it. The renderer accepts template text directly for internal tests. For local development, edit the resource, reload `eca.features.memory.render` to reset its cached template, and start a fresh chat.

## Trust

Memory is personal and local: nothing here is meant for a team repo, and the agent never git-commits memory files on its own. If knowledge should reach the whole team, it belongs in `AGENTS.md` or `.eca/rules/` — those enter every prompt like code, so review them like code.

## What NOT to save

- Nothing you would see anyway while doing the work; no session-only context. Test: *"how did you learn it? from a file you were going to open anyway, then don't save it."*
- Deferred or postponed work ("do X later"), even across sessions: that is a plan or task, not memory.
- Memory is durable cross-session knowledge; in-chat work tracking belongs to the task/plan tools, not memory.
- Never store secrets or credentials. Memory is working knowledge, not enforced rules — verify against current code before asserting as fact. Preferences are the exception: code that violates a preference you stated is legacy, not a counter-example.

## Consolidating memory

Run `/memory-consolidate` to clean up memory in chat with normal file tools. The command is available when memory is enabled. There is no background cleanup. It asks the agent to merge duplicates, check stale facts, remove secrets, and shorten entries while keeping evidence, user quotes, exact errors, and file references. Age or low read counts alone do not justify deletion. Useful content from often-read entries is kept when merging or replacing them. Team rules or procedures can be suggested for AGENTS.md, `.eca/rules`, or skills, but are not moved.

Small, clear edits or new files are applied directly. Deletions, merges, and large or uncertain rewrites need your confirmation first, unless you waive that step. The final report lists each file created, changed, or deleted and why.

When run, the command's prompt includes the memory directory list and a per-file usage snapshot (age, read count, last-read age), injected directly because command turns do not receive the per-turn memory index. The scope is exactly the dirs of the current chat: the workspace's personal dir and the global dir — run it once per project you want cleaned; global is included in every run because project↔global duplication is a real class to fix. The snapshot also lists any broken memory files (unparseable/malformed) that are invisible to the index, with their skip reasons. You can also pass trailing instructions, e.g. `/memory-consolidate delete everything not read in the last 30 days`. Trailing instructions can relax the confirmation gate (`/memory-consolidate just do it, don't ask`); even then, every deleted or merged file is listed in the final report so the destructive part stays visible.

The command's prompt is fully transparent and overridable like any other prompt: set `prompts.memoryConsolidate` in config (the default ships as `resources/prompts/memory_consolidate.md`), or copy it into a [custom command](./commands.md) file and adapt it for your own workflow.

Runs of `/memory-consolidate` follow the same approval rules described above. The command is an explicit user request to maintain memory, but it does not change tool approvals or chat trust. It is not permission to turn current-chat instructions into lasting preferences. Existing preferences with unclear permission are reported for user review, not silently deleted or given broader scope.

## Protocol and per-project enablement

Clients can build memory panels with `memory/list` (see [protocol.md](../protocol.md)). It groups entries by memory directory and lists the most recently modified files first in each group, using actual file modification times. This differs from the usage-based prompt index. Ranking data and the prompt ranking formula remain internal and can change without changing the public file format. Edit or delete memories in your editor or ask the agent; the files are plain Markdown.

A project can override the global `memory.enabled` by adding `"memory": {"enabled": true|false}` to its own `<workspace-root>/.eca/config.json` — no protocol method needed. That lets a project opt out of globally enabled memory, or opt in when memory is off globally. The change is picked up like any other config edit (the config listener refreshes within seconds), but an already-built chat may still require a new chat to refresh its tools and prompt context.
