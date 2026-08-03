---
description: "Run the ECA server and all its tools inside a sandbox (Docker, bubblewrap, and more) for OS-level isolation, working with any editor."
---

# Sandboxing

[Approval rules](./tools.md#approval-permissions), [hooks](./hooks.md) and disabled tools are policies enforced by the ECA process itself. Sandboxing adds OS-level enforcement on top, useful when working on untrusted codebases or letting agents run with less supervision.

Everything ECA executes — built-in tools (shell commands, file edits), MCP stdio servers, hooks, plugins, custom tools and `${cmd:...}` config interpolation — runs in, or as child processes of, the `eca server` process. __Sandboxing the server process sandboxes the entire tool surface__, whatever editor you use.

The pattern is always the same: instead of your editor spawning `eca server` directly, point it to a wrapper that starts the server inside the sandbox.

## Docker / Podman

ECA publishes a ready-to-use image at [`ghcr.io/editor-code-assistant/sandbox-image`](https://github.com/editor-code-assistant/sandbox-image) (`linux/amd64` and `linux/arm64`), containing the latest `eca` binary and a minimal toolset (git, curl, ripgrep). It's tagged with `latest` and the bundled ECA version.

Create a wrapper script:

```bash title="~/.local/bin/eca-sandboxed"
#!/usr/bin/env bash
exec docker run --rm -i \
  -v "$PWD:$PWD" -w "$PWD" \
  -v "$HOME/.config/eca:/root/.config/eca:ro" \
  ghcr.io/editor-code-assistant/sandbox-image:latest \
  eca "$@"
```

How it works:

- `-i` keeps stdin open, so the JSON-RPC stdio between editor and server flows through `docker run` transparently.
- The workspace is mounted at the __same absolute path__ (`$PWD:$PWD`), so the file URIs your editor sends are valid inside the container: no path translation needed, works with any client. This assumes your editor starts the server from the project directory; replace `$PWD` with the project path otherwise.
- `~/.config/eca` is mounted read-only for your global config.

Then make your editor start the wrapper (`chmod +x` it first) instead of `eca`:

=== "Emacs"

    ```elisp
    (setq eca-custom-command '("/home/you/.local/bin/eca-sandboxed" "server"))
    ;; The host PID is not visible inside the container, see caveats below.
    (setq eca-send-process-id nil)
    ```

    Alternatively, wrap the command in elisp without any script via `eca-process-wrapper-function`,
    see the [eca-emacs sandboxing docs](https://github.com/editor-code-assistant/eca-emacs#sandboxing).

=== "VsCode"

    ```javascript title="your-json-preferences"
    {
      "eca.serverPath": "/home/you/.local/bin/eca-sandboxed"
    }
    ```

=== "IntelliJ"

    Set `Tools` > `ECA` > `Server path` to `/home/you/.local/bin/eca-sandboxed`.

=== "Nvim"

    ```lua
    -- in your eca-nvim setup opts
    {
      server_path = "/home/you/.local/bin/eca-sandboxed",
    }
    ```

### Secrets

`${cmd:...}` [config interpolation](./introduction.md#dynamic-string-contents) resolves __inside__ the sandbox, where your secret manager (1Password, pass, etc) is likely not available. Resolve secrets on the host and inject the result via the `ECA_CONFIG` env var instead:

```bash
exec docker run --rm -i \
  -e "ECA_CONFIG={\"providers\":{\"openrouter\":{\"key\":\"$(op read 'op://Private/OpenRouter API Key/credential')\"}}}" \
  ...
```

### Persisting state

ECA keeps caches, chat history and logins under `~/.cache/eca`, which is ephemeral in the container. To keep them across runs, add a volume:

```bash
  -v "$HOME/.cache/eca-sandbox:/root/.cache/eca" \
```

!!! note
    Prefer a dedicated host dir (like `eca-sandbox` above) over sharing `~/.cache/eca` with a host ECA, so sandboxed and unsandboxed sessions don't mix state.

### Caveats

- __Client processId__: when the editor sends its `processId` on `initialize`, the server watches that PID and exits once it disappears. Inside the container's PID namespace the host PID doesn't exist, so the server would exit right after starting. Either make the client not send it (Emacs: `eca-send-process-id`), or run the container with `--pid=host`, which keeps the watchdog working at the cost of PID isolation.
- __Network__: the container needs egress to your LLM providers (and any HTTP MCP servers); `--network none` will break chat. Restrict selectively (e.g. via a proxy) if needed.
- __Login__: `/login` flows that open a browser or listen on localhost callbacks don't work from inside the container. Prefer API keys, or login on the host and share the state as shown above.
- __Tooling__: the agent can only use what exists in the image. Extend it with your project toolchain so it can build and run tests:

    ```dockerfile
    FROM ghcr.io/editor-code-assistant/sandbox-image:latest
    RUN apt-get update && apt-get install -y --no-install-recommends nodejs npm
    ```

## Linux: bubblewrap and friends

Containers are not required, any command wrapper works. Example with [bubblewrap](https://github.com/containers/bubblewrap), giving a read-only view of the host except the project dir, ECA cache and `/tmp`:

```bash title="~/.local/bin/eca-bwrap"
#!/usr/bin/env bash
exec bwrap \
  --ro-bind / / \
  --dev /dev \
  --proc /proc \
  --tmpfs /tmp \
  --bind "$PWD" "$PWD" \
  --bind "$HOME/.cache/eca" "$HOME/.cache/eca" \
  --die-with-parent \
  eca "$@"
```

Since the filesystem view and PID namespace are unchanged, the path and `processId` caveats above don't apply. Examples for other wrappers (jai, firejail) live in the [eca-emacs sandboxing docs](https://github.com/editor-code-assistant/eca-emacs#sandboxing) and follow the same shape.

On macOS there is no bubblewrap equivalent, prefer the container approach.

## Wrapping only shell commands

A lighter (and weaker) option is to sandbox only what the `shell_command`, `git` and custom tools execute, keeping the server unsandboxed, via the [`toolCall.shellCommand`](./introduction.md#default-config) config:

```javascript title="~/.config/eca/config.json"
{
  "toolCall": {
    "shellCommand": {
      "path": "/home/you/.local/bin/sandboxed-bash",
      "args": ["-c"]
    }
  }
}
```

!!! warning
    This does not cover MCP servers, hooks, plugins or the filesystem tools (which run in the server process). Prefer sandboxing the whole server.

## Defense in depth

Sandboxing composes with ECA's own guardrails:

- [Approval rules](./tools.md#approval-permissions): deny/ask patterns per tool and arguments.
- [Disabled tools](./tools.md#disabled-tools): remove tools from the LLM entirely.
- [Hooks](./hooks.md): `preToolCall` hooks can reject tool calls programmatically.
