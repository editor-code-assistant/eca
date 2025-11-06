Apply precise content replacement in a file.

Usage:
- `path` must be an absolute path; `original_content` and `new_content` are required.
- You must use your `eca__read_file` tool at least once in the conversation before editing.
- `original_content` must be copied verbatim from the file you read (use `eca__read_file` output) — do not invent or modify it.
- `original_content` must be exact (including whitespace); include enough surrounding context for a unique match.
- Prefer small, targeted edits over replacing entire functions.
- Requires exactly one match; otherwise it fails. Add context to disambiguate, or set `all_occurrences` to true to update all matches.
- If the edit fails, you must reread the file and call eca__edit_file again.
- To delete content, set `new_content` to an empty string.
- To prepend/append content, `new_content` must include both the new and the original content.
