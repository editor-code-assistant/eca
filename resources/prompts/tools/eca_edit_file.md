You must use `eca_read_file` to get the file's exact contents before attempting an edit.

## Best Practices

- Prefer small, targeted edits over replacing entire functions
- Match content from `eca_read_file` as closely as possible
- For single occurrence (default): include enough surrounding context to make the match unique
- For multiple occurrences (`all_occurrences: true`): provide the exact literal content to replace.

## Common Issues

- "content not found": read the file again to verify the actual formatting
- "ambiguous match" (single occurrence only): include more surrounding context
- To delete content: use empty string as `new_content`
- To prepend/append: `new_content` must contain both the new and the original content
