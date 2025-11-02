Simulate an edit or new file creation; returns only the proposed content (no changes applied).

Usage:
- `path` must be an absolute path.
- Existing files: provide `original_content` and `new_content`; match exactly, including whitespace.
- New files: set `original_content = ""` and provide full `new_content`.
- Prefer small, targeted edits over replacing entire functions.
- Read-only; to apply, use `eca_edit_file` or `eca_write_file`. To preview deletion, set `new_content` to an empty string.
