---
description: "Configure ECA durable memory: Markdown notes the agent saves and reads across chats, with /memories and /memory-consolidate."
---

# Durable memory

Memory lets the agent keep what it learned for later chats: a fix that worked, a trap in your setup, how you like to work. The notes are plain Markdown files on your machine, so you can read, edit, back up or delete them like any other file. Memory is the built-in `eca/memory` [module](modules.md) and is off by default.

The agent uses its normal file tools (`read_file`, `write_file`, `edit_file`, `move_file`, `grep`); there are no special memory tools. At the start of a chat, ECA tells the agent which notes exist, so it knows what it can open.

## Turn it on

Run `/modules enable eca/memory` for the current session, or turn it on in your config. The `memory` keys below show the defaults:

```json
{
  "modules": {
    "eca/memory": {"enabled": true}
  },
  "memory": {
    "writeMode": "agent",
    "index": {
      "maxEntries": 100,
      "maxTokens": 2000
    }
  }
}
```

A project can turn memory on or off for itself in `<workspace-root>/.eca/config.json`, with the same `modules` key. ECA picks up the change within seconds. An open chat gets the note list with its next request, but the agent's memory instructions arrive only in a new chat, or after `/sync-system-prompt` (or with `chat.autoSyncSystemPrompt`).

With the module off, ECA adds no new notes or instructions, and `/memories` and `/memory-consolidate` are not available. An open chat keeps the notes and instructions it already got. Your files stay where they are.

## Where notes live

| Tier | Directory | Used for |
|---|---|---|
| Project | `<memory-root>/projects/<project-slug>` | Knowledge about one project. The default. |
| Global | `<memory-root>/global` | Knowledge that is true outside this project, for example about your machine or how you work. |

`<memory-root>` is `<XDG_DATA_HOME or ~/.local/share>/eca/memory`: one place to browse, back up or wipe all memory. `<project-slug>` is a readable form of the workspace path plus a short hash, so two projects with similar paths get separate directories. Linked git worktrees share the directory of their main checkout. With several workspace roots, each root has its own project directory. ECA creates the directories when they are missing.

Memory is personal. Knowledge for the whole team belongs in the project's `AGENTS.md` or `.eca/rules/`, which are versioned and reviewed.

Inside each memory directory, ECA skips hidden files and folders: on Unix this includes `.note.md` and `.obsidian/`; on Windows the hidden attribute counts, not the leading dot. Writing or moving a file there still changes it on disk, but ECA reports that it will not index the note.

To keep memory somewhere else, link the whole memory root or a whole memory directory. ECA does not follow links inside a memory directory.

## What the agent saves

`writeMode` decides when the agent writes:

- `agent` (default): the agent saves useful findings on its own, such as a failed command, the conditions that caused it, and the fix that worked. It does not save a bug that the chat fixes, because the fix lives in the repo and git. When you give an instruction about how you work without a limit ("never use subagents", "always run the tests"), it saves it as a preference with your exact words, the date and the situation. An instruction limited to now ("in this session...") is not saved. If it's unclear whether you meant an instruction for future chats, the agent skips it rather than asking. If you show what helped in a specific situation, it may note what helped and when, without treating it as a rule.
- `explicit`: the agent creates, changes or deletes notes only when you ask.

You never need special words. "Remember that I prefer short answers" or "use this in future chats" is enough. For rules that should always apply to a project, use `AGENTS.md` or `.eca/rules/` instead.

The agent does not save:

- what it would see anyway while working, for example the content of a file it opens,
- what the repo and git will show, such as a bug the chat fixes,
- to-do lists; a decision you made to postpone something, with the reason, is fine,
- secrets or credentials.

These are instructions to the model, not limits on the file tools. Tool approval decides what really gets written (see [Safety and approval](#safety-and-approval)). An invalid `writeMode` uses `explicit`, and `/doctor` warns about it. The instructions themselves come from the bundled `resources/prompts/memory_guidance.md` and cannot be replaced in config.

## What the agent sees

The agent does not get the note bodies up front. It gets a short **index**: for each note its name, type, date of last change and description, grouped per directory. Each group also has a `Tags in use:` line, so the agent reuses existing tags. When a note looks relevant, the agent opens it with `read_file`. To verify a claim, the agent prefers the note’s recorded check. It runs the check as written only if it still safely observes the condition here without unrequested effects; otherwise it skips the check.

```
<memory-index description="Saved notes from earlier chats (name, type, date of last change, description). ECA adds this block, not the user. It replaces any earlier memory block in this chat. Do not copy it into a summary.">
## eca
Tags in use: ci, docker
- Docker IPv6 gotcha [gotcha] (2026-03-04) — CI fails on Alpine with localhost; use 127.0.0.1 instead
## global
- …
</memory-index>
```

When you start a chat, ECA gives the agent a list of saved notes. It sends the list again if the chat is summarized or no longer has the old list.

If you change a note in your editor or another chat, the agent gets an update with your next message. Tag-only edits don't trigger an update. ECA sends whichever is shorter: a list of what changed or a fresh list of saved notes. If you deleted a note, the change list names it. A fresh list simply leaves it out.

These updates don't appear in your chat. Notes the agent just wrote or edited may appear once in the next update so the chat stays in sync with disk.

Inside each directory, the notes the agent reads more often and the notes that changed recently come first.

### Limits

Two settings keep the index small:

- `index.maxEntries` (default 100): the most notes the index lists.
- `index.maxTokens` (default 2000): the size of the whole block. The tokens are an estimate: ECA counts 4 characters as one token, the same estimate it uses for the context window, because each provider counts tokens differently.

Project notes get 70% of each limit and global notes 30%. A tier that needs less leaves the rest to the other. When notes don't fit, the index says how many it shows, marks tags with hidden notes with `*` (for example `raylib(5)*`), and tells the agent to grep the memory directories for a topic before working on it. The change lists have no limit. Memory offers best-effort recall once notes exceed the limits; for instructions that must be present in every matching chat, use `AGENTS.md`, `.eca/rules/` or `~/.config/eca/rules/`.

If either limit differs from the one last used in the chat, ECA sends a fresh index on the next request if it fits. This also applies to resumed chats.

To make the agent search instead of reading a list, set `index.maxEntries` to `0`: the index then keeps only the tags lines and the notice. Set `index.maxTokens` to `0` to send no index and no change lists at all; the agent still knows where the directories are.

### Subagents and read-only agents

Subagents get no memory. An agent that can start subagents is told so, and passes what a subagent needs in its task. Agents without write tools, such as `plan`, get the index and the reading instructions but not the writing ones.

## A note file

One topic per file. A note is a Markdown file with YAML frontmatter (the metadata between the `---` lines):

```markdown
---
name: Docker IPv6 gotcha
description: CI fails on Alpine with localhost; use 127.0.0.1 instead
type: gotcha
tags: docker, ci
---

On Alpine runners `localhost` resolves to `::1` first, and the service listens only on IPv4.
Fix: use `127.0.0.1` in the CI config.
```

- `name` and `description` are required. The description is what the index shows.
- `type` is optional: `solution`, `decision`, `gotcha`, `procedure`, `fact`, `preference` or `metric`. Without it, the note is a `note`.
- `tags` are optional, comma-separated or a YAML list. ECA reads at most 4, for example three topics and a ticket id.
- Put a value in single quotes when it contains `: `, `#` or a backslash: `tags: 'auth, #679'`. In YAML, a `#` after a space starts a comment and silently drops the rest of the line.
- The body is free Markdown. The agent aims for well under 2,000 characters unless you ask for more. Nothing enforces a size, and the index never shows the body.
- Other fields and types are allowed, and the agent is told to keep them when it edits the note.
- Subdirectories work, but a flat directory is easier to browse.

ECA checks every note the agent writes into a memory directory: the name must end in `.md`, and the frontmatter must have a `name` and a `description`, and `name`, `description` and `tags` must be read as text (not a bare `679`, `yes` or `2026-11-12`). Unquoted `#` comments in YAML are not diagnosed separately; follow the quoting rule above when a value contains `#`. When a check fails, the file stays written and the agent gets an error that lists every problem, so it can fix them in its next call. A note without a `name` or `description`, or whose frontmatter doesn't parse, stays out of the index until it is fixed, and `/memories` lists it as a skipped file. A note with a non-text value stays in the index, with the text YAML keeps. Files you write by hand are read the same way.

## See and edit your notes

`/memories` shows the store: how many notes the index lists (`Memories in the index: 30 of 80`), each directory with its notes (type, name, date, description, file, tags), the notes that the limits leave out, and the files ECA skipped, with the reason. The output is only for you: it is not sent to the model and does not count as a read. It shows the store as it is now, which is not exactly what an older chat saw. While the chat is working, the command is refused.

To change or delete a note, edit the file in your editor or ask the agent.

## Clean up with `/memory-consolidate`

Memory has no background cleanup. Run `/memory-consolidate` when you want the agent to review your notes in the current chat. Its snapshot lists every note's usage, tags, and description, plus any broken files. If the body is at most 2,500 characters after trimming surrounding whitespace, the snapshot includes it in full. For longer bodies the agent reads the file. Using the normal file tools, it:

- merges notes about the same topic, keeping the dates and your quotes of each,
- checks facts against the repo and removes what is outdated, finished, or already in the repo,
- removes secrets and filler, but keeps evidence, your quotes, exact errors, and file references,
- moves a note to the right tier (project or global),
- suggests moving team rules to `AGENTS.md`, `.eca/rules/` or a skill, without moving them.

Age or a low read count alone is no reason to delete a note, and length alone is no reason to shorten one. When the agent can't decide, it keeps the note and asks you in the final report. The report names every note: what it created, changed, moved or deleted and why, what it wants to ask you, and what it kept.

The agent deletes a note by moving it into the trash: `<memory-root>/trash/projects/<project-slug>` or `<memory-root>/trash/global`, with the date in front of the name (`2026-11-17-old-note.md`). ECA never reads the trash. To undo a deletion, ask the agent to move the file back with `move_file`; the note then keeps its read history. ECA never empties the trash; delete old files there yourself.

The command takes two optional arguments:

- `scope`: `project` or `global`. The default is both, because a project note often repeats a global one. Only an exact first word counts as a scope; anything else is part of your request.
- `instructions`: your request for this run, for example `/memory-consolidate global only entries changed in the last 14 days` or `/memory-consolidate delete everything not read in the last 30 days`. No quotes are needed. Quote the request only when it starts with `project` or `global` and you don't mean a scope. The request decides which notes the agent looks at and removes, even by age. The rules about secrets, the file format and preferences still apply, and the agent starts with one sentence saying how it understood you.

Every change follows tool approval, as below. The command does not change approval or chat trust, and it does not let the agent turn what you said in the current chat into new lasting preferences.

The prompt is yours to change: set `prompts.memoryConsolidate` in your config (the default is `resources/prompts/memory_consolidate.md`), or copy it into a [custom command](./commands.md).

## Safety and approval

A note is text that the model wrote and that later chats read, so treat a memory write like a change to your own rules. The main risk is prompt injection: a web page, a file or a tool result can tell the agent to "save a note", and that note then reaches every later chat of the project, or of all projects for a global note. Memory is not in git, so `git status` and code review don't show it.

ECA limits the risk: memory is off by default, every write is checked for a valid note, your client shows each write as a file change, and note text cannot break out of the memory block. Whether a note is true or safe, only you can judge.

For approval, the memory directories of the chat and their trash count as inside the workspace (the module adds them to [`toolCall.approval.insideWorkspace`](tools.md#folders-outside-the-workspace)). So the agent reads and searches notes without asking, and writes, moves and deletions follow your rules for workspace files. With the default `byDefault: "ask"`, every change asks and shows you the change. An `allow` rule for the write tools lets them write notes without asking, in both write modes, during `/memory-consolidate` and in subagents. `deny` rules always win; a trusted chat and "approve and remember" skip the questions as for any tool. So `explicit` mode alone does not guarantee a question before each write.

If you allow the write tools in the workspace but want a question for every memory change, add `ask` rules for the memory paths. The pattern below matches both `/` and `\` (Windows) and covers the trash too; if you link the memory root somewhere else, use that path instead:

```json
{
  "toolCall": {
    "approval": {
      "ask": {
        "eca__write_file": {"argsMatchers": {"path": [".*[/\\\\]eca[/\\\\]memory[/\\\\].*"]}},
        "eca__edit_file": {"argsMatchers": {"path": [".*[/\\\\]eca[/\\\\]memory[/\\\\].*"]}},
        "eca__move_file": {"argsMatchers": {"source": [".*[/\\\\]eca[/\\\\]memory[/\\\\].*"],
                                            "destination": [".*[/\\\\]eca[/\\\\]memory[/\\\\].*"]}}
      }
    }
  }
}
```

Use `writeMode: "explicit"` when the agent often reads content you don't trust.

## Usage data

ECA keeps its own bookkeeping in `<memory-root>/usage.edn`. The agent never sees or edits it.

- **Reads:** each time the agent opens a note with `read_file`, ECA counts a read. Reads rank the index. Grep hits, writes, and reads during `/memory-consolidate` don't count.
- **Date of last change:** the date in the index changes when a note's body changes, not when only its tags or description change. So a cleanup that only retags notes does not make old notes look new. For a note ECA sees for the first time, the date comes from the file.
- **Writes:** ECA records which chats wrote each note (up to 10: the first one and the 9 newest), and marks the writes of a `/memory-consolidate` run. A record leads to its chat while `chatRetentionDays` keeps that chat. ECA itself does not use these records; they are there for you, or for a tool, to find where a note came from.

Rewriting a note at the same path keeps its read counts and write records. `move_file` carries those records to the new path, including moves to the trash and back. Full scans remove records for missing files. If another chat scans between a rename and the update to `usage.edn`, the records can be lost, but the note itself is unaffected. Moves made through the shell do not transfer the records. Several ECA processes can share the file. The format is internal and can change. Deleting `usage.edn` resets the ranking and the write records, and the dates start again from the file times; the notes stay as they are.
