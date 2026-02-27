---
description: "How ECA manages context window size: output truncation, auto-compaction, and automatic overflow recovery."
---

# Context window management

ECA manages the context window through three layers of defense, each addressing a different stage of the problem:

1. **Output Truncation** — prevents individual tool outputs from being too large.
2. **Auto-compact** — proactively summarizes the conversation before it reaches the model's limit.
3. **Context overflow recovery** — reactively handles the case when the context exceeds the limit despite the above.

## Output Truncation

ECA automatically truncates tool call outputs that are too large before sending them to the LLM. When output exceeds the configured limits, ECA:

1. Saves the full output to a cache file in `~/.cache/eca/toolCallOutputs` (cleaned every 7 days).
2. Truncates the output to the configured line limit.
3. Appends an `[OUTPUT TRUNCATED]` notice telling the LLM where the full content is saved and suggesting it use `eca__grep` or `eca__read_file` with offset/limit to access the rest.

You can customize the truncation limits via `toolCall.outputTruncation`:

```javascript title="~/.config/eca/config.json"
{
  "toolCall": {
    "outputTruncation": {
      "lines": 1000,
      "sizeKb": 20
    }
  }
}
```

| Property | Default | Description                                       |
|----------|---------|---------------------------------------------------|
| `lines`  | `2000`  | Maximum number of lines in tool output.            |
| `sizeKb` | `50`    | Maximum size in kilobytes for tool output.         |

Either limit being exceeded will trigger truncation.

## Auto-compact

After each LLM response, ECA checks how much of the model's context window has been used. When usage reaches the configured threshold, ECA automatically compacts the conversation by asking the LLM to produce a structured summary, then replaces the full chat history with that summary and resumes the original task.

You can configure the threshold via `autoCompactPercentage`:

```javascript title="~/.config/eca/config.json"
{
  "autoCompactPercentage": 75
}
```

This can also be set per-agent:

```javascript title="~/.config/eca/config.json"
{
  "agent": {
    "code": {
      "autoCompactPercentage": 80
    }
  }
}
```

| Property                | Default | Description                                                       |
|-------------------------|---------|-------------------------------------------------------------------|
| `autoCompactPercentage` | `75`    | Percentage of context window usage that triggers auto-compaction. |

## Context overflow recovery

In some cases the context window can jump from below the auto-compact threshold to above the model's maximum in a single turn — for example, when multiple large tool results arrive at once. When this happens, the LLM provider rejects the request with a "prompt too long" error.

Instead of showing this error to the user, ECA automatically recovers:

1. **Detects the overflow** — classifies the provider error (works across Anthropic, OpenAI, Google, Ollama, and custom providers).
2. **Prunes old tool results** — walks the chat history backwards, keeping the most recent ~40K tokens of tool output intact and replacing older tool results with a lightweight placeholder. This preserves recent context while freeing enough space.
3. **Triggers auto-compact** — sends the (now smaller) conversation to the LLM for summarization.
4. **Resumes the original request** — after compaction, retries the user's original message against the compacted history.

This happens transparently — the user sees a brief "Context window exceeded. Auto-compacting conversation..." notice, and the task continues without manual intervention.

If recovery itself fails (e.g., the conversation is too large even after pruning), ECA falls back to displaying the error so the user can manually compact or start a new chat.
