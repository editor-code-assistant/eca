# Features

## Chat

Chat is the main feature of ECA, allowing user to talk with LLM to behave like an agent, making changes using tools or just planning changes and next steps.

### Tools

ECA leverage tools to give more power to the LLM, this is the best way to make LLMs have more context about your codebase and behave like an agent.
It supports both MCP server tools + ECA native tools, for more details, check [configuration]().

### Native tools

ECA support built-in tools to avoid user extra installation and configuration, these tools are always included on models requests that support tools and can be [disabled/configured via config](./configuration.md) `nativeTools`.

Some native tools like `filesystem` have MCP alternatives, but ECA having them built-in avoid the need to external dependencies like npx.

#### Filesystem

Provides access to filesystem under workspace root, listing and reading files and directories a subset of [official MCP filesystem](https://mcpserverhub.com/servers/filesystem), important for agentic operations, without the need to support NPM or other tools.

- `eca_directory_tree`: list a directory as a tree (can be recursive).
- `eca_read_file`: read a file content.
- `eca_write_file`: write content to a new file.
- `eca_edit_file`: replace lines of a file with a new content.
- `eca_move_file`: move/rename a file.
- `eca_grep`: ripgrep/grep for paths with specified content.

#### Shell

Provides access to run shell commands, useful to run build tools, tests, and other common commands, supports exclude/include commands. 

- `eca_shell_command`: run shell command. Supports configs to exclude commands via `:nativeTools :shell :excludeCommands`.

### Contexts

User can include contexts to the chat (`@`), including MCP resources, which can help LLM generate output with better quality.
Here are the current supported contexts types:

- `file`: a file in the workspace, server will pass its content to LLM (Supports optional line range).
- `directory`: a directory in the workspace, server will read all file contexts and pass to LLM.
- `repoMap`: a summary view of workspaces files and folders, server will calculate this and pass to LLM. Currently, the repo-map includes only the file paths in git.
- `mcpResource`: resources provided by running MCPs servers.

### Commands

Eca supports commands that usually are triggered via shash (`/`) in the chat, completing in the chat will show the known commands which include ECA commands, MCP prompts and resources.

The built-in commands are:

`/costs`: shows costs about current session.
`/repo-map-show`: shows the current repoMap context of the session.
`/resume`: resume a chat from previous session of this workspace folder.

#### Custom commands

It's possible to configure custom command prompts, for more details check [its configuration](./configuration.md#custom-commands)

##  Completion

Soon

## Edit 

Soon

