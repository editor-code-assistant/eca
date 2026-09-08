Shows a recursive tree of directories and files under the given path.

Usage:
- `path` must be an absolute path.
- Optional: `max_depth` to limit traversal.
- Skips hidden entries (dotfiles and dot-directories) and, inside workspaces, gitignored files and directories.
- Pass an ignored or hidden directory (e.g. `.git`, `target`) as `path` to list its contents anyway.
