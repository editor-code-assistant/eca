---
description: "Configure ECA variants: switch between model presets, reasoning effort levels, and custom payload overrides on the fly."
---

# Variants

![](../images/features/variants.png)

Variants are named payload presets for a model, allowing you to quickly switch between different LLM parameters — like reasoning effort — without changing the model itself.

## Built-in Variants

ECA ships with built-in variants for some known models via the `variantsByModel` config which has a good default. For authenticated GitHub Copilot, ECA uses only the model's advertised reasoning capabilities from Copilot's `/models` endpoint and does not guess variants when that metadata is unavailable.

!!! note

    Built-in variants match on the model id, so the Anthropic variants below also apply to the same Claude models served through **Bedrock** (e.g. `us.anthropic.claude-opus-4-7`). For Bedrock, ECA translates the Anthropic-shaped `thinking`/`output_config` payload into Converse's `reasoning_config`/`output_config`.

=== "Anthropic"

    Applies to models matching `sonnet-4-6`, `opus-4-5`, `opus-4-6` (any separator: `-`, `.`, `_`).

    | Variant    | Payload |
    | ---------- | ------- |
    | `low`      | `{"output_config": {"effort": "low"}, "thinking": {"type": "adaptive"}}` |
    | `medium`   | `{"output_config": {"effort": "medium"}, "thinking": {"type": "adaptive"}}` |
    | `high`     | `{"output_config": {"effort": "high"}, "thinking": {"type": "adaptive"}}` |
    | `max`      | `{"output_config": {"effort": "max"}, "thinking": {"type": "adaptive"}}` |

=== "Anthropic (opus 4.7+, sonnet/fable/mythos 5)"

    Applies to models matching `opus-4-7`, `opus-4-8`, `sonnet-5`, `fable-5`, `mythos-5` (any separator: `-`, `.`, `_`).

    | Variant    | Payload |
    | ---------- | ------- |
    | `default`  | `{"thinking": {"type": "adaptive", "display": "summarized"}}` |
    | `low`      | `{"output_config": {"effort": "low"}, "thinking": {"type": "adaptive", "display": "summarized"}}` |
    | `medium`   | `{"output_config": {"effort": "medium"}, "thinking": {"type": "adaptive", "display": "summarized"}}` |
    | `high`     | `{"output_config": {"effort": "high"}, "thinking": {"type": "adaptive", "display": "summarized"}}` |
    | `xhigh`    | `{"output_config": {"effort": "xhigh"}, "thinking": {"type": "adaptive", "display": "summarized"}}` |
    | `max`      | `{"output_config": {"effort": "max"}, "thinking": {"type": "adaptive", "display": "summarized"}}` |

=== "OpenAI"

    Applies to models matching `gpt-5-3-codex`, `gpt-5-2`, `gpt-5-4`, `gpt-5-5`. Excluded for `github-copilot` provider.

    | Variant    | Payload |
    | ---------- | ------- |
    | `none`     | `{"reasoning": {"effort": "none"}}` |
    | `low`      | `{"reasoning": {"effort": "low", "summary": "auto"}}` |
    | `medium`   | `{"reasoning": {"effort": "medium", "summary": "auto"}}` |
    | `high`     | `{"reasoning": {"effort": "high", "summary": "auto"}}` |
    | `xhigh`    | `{"reasoning": {"effort": "xhigh", "summary": "auto"}}` |

=== "OpenAI (gpt-5.6)"

    Applies to models matching `gpt-5.6` (e.g. `gpt-5.6-luna`, `gpt-5.6-terra`, `gpt-5.6-sol`; any separator: `-`, `.`, `_`). Excluded for `github-copilot` provider.

    | Variant    | Payload |
    | ---------- | ------- |
    | `none`     | `{"reasoning": {"effort": "none"}}` |
    | `low`      | `{"reasoning": {"effort": "low", "summary": "auto"}}` |
    | `medium`   | `{"reasoning": {"effort": "medium", "summary": "auto"}}` |
    | `high`     | `{"reasoning": {"effort": "high", "summary": "auto"}}` |
    | `xhigh`    | `{"reasoning": {"effort": "xhigh", "summary": "auto"}}` |
    | `max`      | `{"reasoning": {"effort": "max", "summary": "auto"}}` |

=== "DeepSeek"

    Applies to models matching `deepseek-v4-pro`. Only for providers using the `openai-chat` API.

    | Variant    | Payload |
    | ---------- | ------- |
    | `none`     | `{"thinking": {"type": "disabled"}}` |
    | `high`     | `{"reasoning_effort": "high"}` |
    | `max`      | `{"reasoning_effort": "max"}}` |

## Custom Variants

You can define your own variants per model under `providers.<provider>.models.<model>.variants`. Custom variants are merged with built-in ones — if names clash, your definition wins.

=== "Global config file"

    ```javascript title="~/.config/eca/config.json"
    {
      "providers": {
        "anthropic": {
          "models": {
            "claude-sonnet-4-6": {
              "variants": {
                "creative": {"temperature": 1, "top_p": 0.95}
              }
            }
          }
        }
      }
    }
    ```

=== "Local config file"

    ```javascript title=".eca/config.json"
    {
      "providers": {
        "anthropic": {
          "models": {
            "claude-sonnet-4-6": {
              "variants": {
                "creative": {"temperature": 1, "top_p": 0.95}
              }
            }
          }
        }
      }
    }
    ```

To disable a specific built-in variant, set it to `{}`:

```javascript title="~/.config/eca/config.json"
{
  "providers": {
    "openai": {
      "models": {
        "gpt-5.2": {
          "variants": {
            // removes the "none" and "xhigh" built-in variants
            "none": {},
            "xhigh": {}
          }
        }
      }
    }
  }
}
```

## Agent Default Variant

Set a default variant for an agent:

=== "JSON"

    ```javascript title="~/.config/eca/config.json"
    {
      "agent": {
        "code": {
          "variant": "medium"
        }
      }
    }
    ```

=== "Markdown"

    ```markdown title="~/.config/eca/agents/reviewer.md"
    ---
    mode: subagent
    description: Review code changes
    model: openai/gpt-5.4
    variant: high
    ---

    Review the changes for correctness and regressions.
    ```

Unavailable variants are ignored. An explicit chat or `spawn_agent` variant overrides the agent default. See [Agents](agents.md) for the complete agent specification.

