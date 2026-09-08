Read a file’s current content.

Usage:
- `path` must be an absolute path.
- Optional: `line_offset` (0-based start line) and `limit` (max lines).
- UTF-8 text only; use `view_image` for image files. Prefer one well-scoped read over many tiny reads.
