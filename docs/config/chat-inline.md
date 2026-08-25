---
description: "Configure ECA inline chat: ask from any buffer and render the answer inline, backed by a regular chat."
---

![](../images/features/inline-chat.gif)

# Inline chat

Editors use the `chat/inlinePrompt` protocol method to ask ECA from any buffer and render the answer inline (e.g. an overlay near the cursor). The session is a regular chat behind the scenes: tool calls, approvals and follow-ups work as usual, and it can fork an existing chat to reuse its history as context.

Configure the model, agent, and variant used for inline chats via the `chatInline` config. By default, ECA follows the same selection as chat (or the source chat when forking) unless overwritten:

=== "Example"

    ```javascript title="~/.config/eca/config.json"
    {
      "chatInline": {
        "model": "github-copilot/gpt-4.1",
        "agent": "code",
        "variant": "low"
      }
    }
    ```

`model` and `variant` apply when the inline chat is created (explicit client choice > `chatInline` config > source chat > default) and stick to the chat afterwards. `agent` applies to every inline prompt where the client doesn't specify one.
