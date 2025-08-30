# Features

## Chat

Chat is the main feature of ECA, allowing user to talk with LLM to behave like an agent, making changes using tools or just planning changes and next steps.

### Behaviors

![](./images/features/chat-behaviors.png)

Behavior affect the prompt passed to LLM and the tools to include, the current supported behaviors are:

- `plan`: Useful to plan changes and define better LLM plan before changing code via agent mode.
- `agent`: Make changes to code via file changing tools.

### Tools

#### User-Defined Tools

ECA supports user-defined tools, letting you add your own bash scripts as tools that can be invoked by the agent or plan behaviors. These are configured via the `userTools` key in your config (see [configuration](./configuration.md#user-defined-tools)).

**How it works:**
- Define a tool in your config with a bash command and argument schema.
- The tool appears in the tool list and can be invoked by name.
- Arguments are substituted into the bash command using `{{variable}}` syntax.

**Example usage:**

If you have this in your config:

```json
{
  "userTools": {
    "hello": {
      "bash": "echo Hello, {{name}}!",
      "schema": {
        "args": {
          "name": {
            "type": "string",
            "description": "Name to greet"
          }
        },
        "required": ["name"]
      },
      "description": "Say hello to someone"
    }
  }
}
```

You can invoke it in chat or plan mode:
```
/tool hello name=World
```

This will execute: `echo Hello, 'World'!`

**Variable Substitution:**
- Use `{{variableName}}` syntax in your bash commands
- Variables are automatically shell-escaped for security
- All required arguments must be provided according to the schema

**Security:**
- User tools run as local shell scripts. Only add trusted scripts.
- Arguments are automatically shell-escaped to prevent injection attacks.
- Use `requireApproval` to require manual approval before running a tool.


See [configuration](./configuration.md#user-defined-tools) for setup details.

---

![](./images/features/tools.png)

ECA leverage tools to give more power to the LLM, this is the best way to make LLMs have more context about your codebase and behave like an agent.
It supports both MCP server tools + ECA native tools.

!!! warning "Automatic approval"

    By default, ECA auto approve any tool call from LLM, to configure that or for which tools, check `toolCall manualApproval` config or try the `plan` behavior.

### Native tools

ECA support built-in tools to avoid user extra installation and configuration, these tools are always included on models requests that support tools and can be [disabled/configured via config](./configuration.md) `nativeTools`.

#### Filesystem

Provides access to filesystem under workspace root, listing, reading and writing files, important for agentic operations.

- `eca_directory_tree`: list a directory as a tree (can be recursive).
- `eca_read_file`: read a file content.
- `eca_write_file`: write content to a new file.
- `eca_edit_file`: replace lines of a file with a new content.
- `eca_plan_edit_file`: Only used in plan mode, replace lines of a file with a new content.
- `eca_move_file`: move/rename a file.
- `eca_grep`: ripgrep/grep for paths with specified content.

#### Shell

Provides access to run shell commands, useful to run build tools, tests, and other common commands, supports exclude/include commands.

- `eca_shell_command`: run shell command. Supports configs to exclude commands via `:nativeTools :shell :excludeCommands`.

#### Editor

Provides access to get information from editor workspaces.

- `eca_editor_diagnostics`: Ask client about the diagnostics (like LSP diagnostics).

### Contexts

![](./images/features/contexts.png)

User can include contexts to the chat (`@`), including MCP resources, which can help LLM generate output with better quality.
Here are the current supported contexts types:

- `file`: a file in the workspace, server will pass its content to LLM (Supports optional line range).
- `directory`: a directory in the workspace, server will read all file contexts and pass to LLM.
- `repoMap`: a summary view of workspaces files and folders, server will calculate this and pass to LLM. Currently, the repo-map includes only the file paths in git.
- `mcpResource`: resources provided by running MCPs servers.

#### AGENTS.md automatic context

ECA will always include if found the `AGENTS.md` file (configurable via `agentFileRelativePath` config) as context, searching for both `/project-root/AGENT.md` and `~/.config/eca/AGENT.md`.

You can ask ECA to create/update this file via `/init` command.

### Commands

![](./images/features/commands.png)

Eca supports commands that usually are triggered via shash (`/`) in the chat, completing in the chat will show the known commands which include ECA commands, MCP prompts and resources.

The built-in commands are:

`/init`: Create/update the AGENTS.md file with details about the workspace for best LLM output quality.
`/login`: Log into a provider. Ex: `/login github-copilot`, `/login anthropic`
`/costs`: Show costs about current session.
`/resume`: Resume a chat from previous session of this workspace folder.
`/config`: Show ECA config for troubleshooting.
`/doctor`: Show information about ECA, useful for troubleshooting.
`/repo-map-show`: Show the current repoMap context of the session.
`/prompt-show`: Show the final prompt sent to LLM with all contexts and ECA details.

#### Custom commands

It's possible to configure custom command prompts, for more details check [its configuration](./configuration.md#custom-command-prompts)

### Login

It's possible to login to some providers using `/login <provider>`, ECA will ask and give instructions on how to authenticate in that provider and save the login info globally in its cache `~/.cache/eca/db.transit.json`.

Current supported providers with login:
- `anthropic`: with options to login to Claude Max/Pro or create API keys.
- `github-copilot`: via Github oauth.

##  Completion

Soon

## Edit

Soon
