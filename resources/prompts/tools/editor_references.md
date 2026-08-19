Find all references to a symbol using the editor's language server (LSP).

Usage:
- `path` must be an absolute file path; `line` is 1-based; `symbol` must appear on that line (as seen in `eca__grep` or `eca__read_file` output).
- Prefer this over `eca__grep` to find usages of a function/var/class: it is scope and import aware and avoids textual false positives.
- Returns locations as `path:line:character: <line text>` (1-based), capped at 100 references.
- If it fails because the language server is still starting, retry it later or fall back to `eca__grep`.
- If it fails for any other reason (no language server, errors), fall back to `eca__grep`.
