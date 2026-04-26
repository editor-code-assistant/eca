---
description: "Configure ECA inline completion: AI-powered code suggestions as you type, with custom model and prompt settings."
---

# Completion

![](../images/features/inline_completion.png)

You can configure which model and system prompt ECA will use during its inline completion:

=== "Example"

    ```javascript title="~/.config/eca/config.json"
    {
      "completion": {
        "model": "github-copilot/gpt-4.1",
        "windowRadius": 6,
        "requestTimeoutMs": 30000
      },
      "prompts": {
        "completion": "${file:/path/to/my-prompt.md}"
      }
    }
    ```

## Region-replace completions

When the client advertises the `codeAssistant.completionCapabilities.regionReplace`
capability at `initialize`, ECA may return a completion whose range covers an
existing piece of code around the cursor — possibly starting *before* the
cursor and/or spanning multiple lines. The client is expected to replace the
text in that range with the suggestion atomically, the same way Cursor's "Tab"
suggestion does. Clients without that capability still receive the legacy
"insert at cursor" shape.

The model is asked to rewrite a small editable window centered on the cursor
(`windowRadius` lines above and below). The server diffs the rewritten window
against the original to produce the precise replacement range.

The two flows use independent system prompts so each is focused on a single
output contract:

- `prompts.completion` — legacy single-tag (`<ECA_TAG>`) insertion prompt,
  used for clients that do not advertise `regionReplace`.
- `prompts.completionRegionReplace` — rewrite-window prompt with
  `<ECA_WINDOW_START>` / `<ECA_WINDOW_END>` / `<ECA_CURSOR>` markers, used
  when the capability is on.

Either key supports the usual dynamic strings (`${file:...}`,
`${classpath:...}`) and may be overridden independently.

