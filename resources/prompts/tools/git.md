Runs git and gh (GitHub CLI) commands.

Always provide the `operation` that best describes what the command does.
Provide a concise `summary` (2-3 words) of what is being done.

# Commits

1. First run: `git status`, `git diff` (staged + unstaged), `git log` (for commit style).
2. Analyze: files changed, nature/purpose, sensitive info check, draft a "why"-focused message.
3. Stage only relevant files (avoid blind `git add .`), then commit via HEREDOC:

<example>
git commit -m "$(cat <<'EOF'
Commit message here.

🤖 Generated with [eca](https://eca.dev)

Co-Authored-By: eca-agent <git@eca.dev>
EOF
)"
</example>

4. If pre-commit hooks modify files, amend or retry ONCE. If it fails again, stop.

# Pull Requests

1. First run: `git status`, `git diff`, `git log`, `git diff main...HEAD` to review ALL commits since diverging.
2. Analyze: all commits (not just latest), nature/purpose/impact, draft 1-3 bullet summary.
3. Push with `-u` if needed, then create PR via HEREDOC:

<example>
gh pr create --title "the pr title" --body "$(cat <<'EOF'
## Summary
<1-3 bullet points>

## Test plan
[Testing checklist]

🤖 Generated with [eca](https://eca.dev)
EOF
)"
</example>

4. Return the PR URL.

# Rules
- Never use interactive flags (`-i`, `--interactive`)
- Never update git config
- Do not push unless creating a PR
- Do not create empty commits
- Always use HEREDOC for multi-line messages
- Use `gh` for all GitHub API interactions (issues, PRs, checks, releases)
- Given a GitHub URL, use `gh` to fetch the information
