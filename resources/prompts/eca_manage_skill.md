# Changing ECA

How to change ECA itself: its config, models and providers, tool approval, agents, skills, rules, commands, hooks, MCP servers, plugins and modules.

## Choose the simplest way

Offer the simplest supported way that meets the user's need, in this order:

1. **Config** (`config.json`): models and providers, tool approval, agents, MCP servers, variants, context management... Most needs end here.
2. **A skill, rule or command**, as Markdown files. A skill teaches a task, and the model loads it when it needs it. A rule is guidance the model always gets, or gets for some paths. A command is a prompt the user sends with `/name`.
3. **A hook**: a shell command ECA runs on an event, e.g. before a tool call, to check, block, change or notify.
4. **A module**: Clojure code ECA runs, for what the above can't do: tools with logic, event handlers that change what ECA does, prompt sections computed per chat.
5. **A plugin**: a git repo or folder that shares any of the above.

Say in a sentence or two which way you chose and why. When two ways fit about equally, ask the user.

## Read ECA's docs first

Never guess a config key, a file location, a format or an API: read its page in ECA's docs first.

- Config files, where they live and how they merge: https://eca.dev/config/introduction/
- Every config key, with its type and default, as a JSON schema: https://eca.dev/config.json
- Models and providers: https://eca.dev/config/models/
- Tools and tool approval: https://eca.dev/config/tools/
- Skills: https://eca.dev/config/skills/
- Rules: https://eca.dev/config/rules/
- Commands: https://eca.dev/config/commands/
- Agents and subagents: https://eca.dev/config/agents/
- Hooks: https://eca.dev/config/hooks/
- Plugins: https://eca.dev/config/plugins/
- Modules, and the `eca.api` functions they call: https://eca.dev/config/modules/
- Also `variants`, `context-management`, `completion`, `rewrite`, `chat-inline`, `metrics`, `remote`, `network`, `sandboxing` and `examples`, at `https://eca.dev/config/<page>/`.

Read a page with a tool that reads web pages if you have one, or with `curl -sL <url>`. The same page as Markdown, much shorter than its HTML, is at `https://raw.githubusercontent.com/editor-code-assistant/eca/master/docs/config/<page>.md`.

## Make the change

- Ask whether it's for this project (the workspace's `.eca/` folder) or for all projects (`~/.config/eca/`), unless the request says so.
- Read a config file before changing it, keep the user's other settings, and keep it valid JSON.
- After a change, tell the user how to check it works, e.g. a command to type or a new chat to open.
- ECA loads a module in `.eca/modules/` or `~/.config/eca/modules/` within seconds, and reloads it on each save. Load this skill again to see whether it's active, or why it failed to load.
