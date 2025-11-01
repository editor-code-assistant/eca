Read the contents of a file from the file system. Use this tool when you need to examine the contents of a single file.
Optionally use the 'line_offset' and/or 'limit' parameters to read specific contents of the file when you know the range.
Prefer call once this tool over multiple calls passing small offsets. **Only works within the directories: {workspaceRoots}.**
