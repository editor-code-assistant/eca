Load deferred tools so you can call them.

Some tools are deferred: they are listed by name and short description in the "Deferred Tools" section of your system prompt, but their full descriptions and input schemas are not loaded, so you cannot call them yet.

Use this tool to load the ones you need:
- Search with keywords describing the capability you want (e.g. "create pull request", "query database"), not the exact tool name.
- Omit `query` to list every deferred tool.
- Matches are loaded immediately and become callable from your next message onward, with their full input schemas returned here.
- Prefer a single search with a focused query over many searches; each loaded tool consumes context.
- If a deferred tool looks relevant to the task, load it before concluding the capability is unavailable.
