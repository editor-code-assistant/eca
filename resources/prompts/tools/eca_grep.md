Find files whose contents match a regular expression.

Usage:
- `path` must be an absolute path.
- `pattern` is a regular expression to match file contents.
- Optional: `include` file-glob filter (e.g., "*.clj", "*.{clj,cljs}").
- Optional: `max_results` limits the number of returned paths.
- Returns matching file paths, one per line.
