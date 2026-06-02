---
description: "Stream raw ECA chat history from the DB cache as JSONL: list chats, filter messages by time or role."
---

# read-chat

Inspect ECA's chat database offline by reading `db.transit.json` directly. No running server is required.

Output is always **raw JSONL** (one JSON object per line) in the persisted internal shape, making it suitable for debugging, export, and programmatic processing.

## Quick start

```bash
# List all chats (newest first)
eca read-chat --db-cache-path ~/.cache/eca/db.transit.json

# Resolve the cache from the session's workspace folders
eca read-chat --workspace ~/Code/Clojure/eca --workspace ~/Code/Clojure/eca-worktrees/add-read-chat-command

# Stream raw messages from a specific chat
eca read-chat --db-cache-path ~/.cache/eca/db.transit.json --chat-id <chat-id>

# Last 10 user messages from the past hour
eca read-chat --db-cache-path ~/.cache/eca/db.transit.json \
  --chat-id <chat-id> --since 1h --role user | tail -n 10
```

## Modes

### Listing mode (no `--chat-id`)

Prints one summary object per chat, sorted by `:updated-at` descending. Fields: `id`, `title`, `status`, `model`, `created-at`, `updated-at`, `user-prompt-count`.

`--since`/`--until` filter by chat `:updated-at`.

### Detail mode (`--chat-id`)

Prints one message object per line in chronological order. Fields include `role`, `content`, `content-id`, `created-at`, and any other persisted fields.

`--since`/`--until` filter by message `:created-at`.

## Options

`read-chat` accepts **options only**; positional arguments are rejected.

Provide exactly one input source: `--db-cache-path` or one or more `--workspace` paths.

| Option | Description |
|--------|-------------|
| `--db-cache-path <PATH>` | Path to `db.transit.json`. |
| `--workspace <PATH>` | Workspace folder; repeat in the original session order. |
| `--chat-id <ID>` | Focus on a chat; omit to list all chats. |
| `--role <ROLE>` | Filter messages by exact `role` string (requires `--chat-id`). |
| `--since <DATE>` | Start date (inclusive). |
| `--until <DATE>` | End date (exclusive). |
| `-h`, `--help` | Print help. |

### Role values

Common `--role` filters: `user`, `assistant`, `tool_call`, `tool_call_output`, `reason`, `server_tool_use`, `server_tool_result`, `image_generation_call`, `compact_marker`, `flag`.

## Message content shape

- Plain text may be a string.
- Structured content is often an array like `[{"type":"text","text":"..."}]`.
- `tool_call`: `content` map with `id`, `name`, `arguments`, `server`, `full-name`, `summary`.
- `tool_call_output`: `content` map with `id`, `name`, `error`, `output`, `total-time-ms`.

Tool calls and results share the same `content.id`.

## Date formats

| Format | Example | Meaning |
|--------|---------|---------|
| Relative | `30m`, `2h`, `1d` | Minutes/hours/days ago |
| Date | `2025-01-01` | Midnight UTC |
| Instant | `2025-01-01T14:30:00Z` | Full ISO-8601 |

### Messages without timestamps

Messages created before `:created-at` tracking was added lack timestamps. When filtering by date, they are **excluded** and a stderr warning reports the drop count.

## Scripting examples

=== "Last 10 messages"

    ```bash
    eca read-chat --db-cache-path ~/.cache/eca/db.transit.json \
      --chat-id abc | tail -n 10
    ```

=== "Count messages"

    ```bash
    eca read-chat --db-cache-path ~/.cache/eca/db.transit.json \
      --chat-id abc | wc -l
    ```

=== "Extract user text"

    ```bash
    eca read-chat --db-cache-path ~/.cache/eca/db.transit.json \
      --chat-id abc --role user | \
      jq -r '
        def content_text:
          if type == "string" then .
          elif type == "array" then
            map(
              if .type == "text" then .text // empty
              elif .type == "image" then "[Image: " + (."media-type" // "unknown") + "]"
              else empty
              end
            ) | join("\n")
          else empty
          end;
        .content | content_text
      '
    ```

=== "Extract assistant text"

    ```bash
    eca read-chat --db-cache-path ~/.cache/eca/db.transit.json \
      --chat-id abc --role assistant | \
      jq -r '.content[]? | select(.type == "text") | .text'
    ```

=== "List tool calls"

    ```bash
    eca read-chat --db-cache-path ~/.cache/eca/db.transit.json \
      --chat-id abc --role tool_call | \
      jq -c '.content | {id, name, input: .arguments, summary, server, full_name: ."full-name"}'
    ```

=== "List tool outputs"

    ```bash
    eca read-chat --db-cache-path ~/.cache/eca/db.transit.json \
      --chat-id abc --role tool_call_output | \
      jq -c '
        .content
        | {
            tool_use_id: .id,
            name,
            is_error: .error,
            total_time_ms: ."total-time-ms",
            output_text: ((.output.contents // []) | map(select(.type == "text") | .text // empty) | join("\n"))
          }
      '
    ```

=== "Find chats by model"

    ```bash
    eca read-chat --db-cache-path ~/.cache/eca/db.transit.json | \
      jq -c 'select(.model | contains("claude"))'
    ```

=== "Count user prompts"

    ```bash
    eca read-chat --db-cache-path ~/.cache/eca/db.transit.json | \
      jq '{id: .id, count: .["user-prompt-count"]}'
    ```

=== "Export a chat"

    ```bash
    eca read-chat --db-cache-path ~/.cache/eca/db.transit.json \
      --chat-id abc > chat-export.jsonl
    ```

=== "Recent chats, pretty-printed"

    ```bash
    eca read-chat --db-cache-path ~/.cache/eca/db.transit.json --since 1d | \
      jq '{title, model, updated_at: .["updated-at"]}'
    ```

## DB version mismatch

If the file was written by a different ECA version, a warning is printed:

```text
Warning: DB version mismatch. File has version <old>, expected <current>. Output may be incomplete.
```

The command still proceeds.

## Errors

| Error | Cause |
|-------|-------|
| Missing required input source | Pass `--db-cache-path` or `--workspace`. |
| Conflicting input sources | Use `--db-cache-path` **or** `--workspace`, not both. |
| Unknown option / unexpected argument | Only named options are accepted. |
| Invalid role usage | `--role` requires `--chat-id`. |
| Missing DB file | Check the path or workspace order. |
| Read/parse error | File may be locked by the running server or corrupted. |
| Chat not found | Use listing mode to find valid IDs. |
| Invalid date format | Use relative (`2h`, `30m`, `1d`) or ISO-8601. |

All errors/warnings go to **stderr**; JSONL goes to **stdout**.
