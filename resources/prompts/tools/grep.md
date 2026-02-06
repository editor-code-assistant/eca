Find files whose contents match a regular expression.

Usage:
- `path` must be an absolute path.
- `pattern` is a regular expression to match file contents.
- Optional: `include` file-glob filter (e.g., "*.clj", "*.{clj,cljs}").
- Optional: `max_results` limits the number of returned results.
- Optional: `output_mode` controls the output format:
  - `"files_with_matches"` (default): Returns matching file paths only, one per line.
  - `"content"`: Returns matching lines with file path and line numbers (format: `filepath:linenum:content`).
  - `"count"`: Returns match counts per file (format: `filepath:count`).
