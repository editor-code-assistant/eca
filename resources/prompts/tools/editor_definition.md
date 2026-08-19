Go to the definition of a symbol using the editor's language server (LSP).

Usage:
- `path` must be an absolute file path; `line` is 1-based; `symbol` must appear on that line (as seen in `eca__grep` or `eca__read_file` output).
- Prefer this over `eca__grep` to find where a function/var/class is defined: it is scope and import aware, precise, and cheaper than searching.
- Returns locations as `path:line:character: <line text>` (1-based); multiple locations are possible (e.g. interfaces/overloads).
- If it fails because the language server is still starting, retry it later or fall back to `eca__grep`.
- If it fails for any other reason (no language server, errors), fall back to `eca__grep`.
