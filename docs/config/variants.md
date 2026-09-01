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

=== "Anthropic (opus 4.7+, opus/sonnet/fable/mythos 5)"

    Applies to models matching `opus-4-7`, `opus-4-8`, `opus-5`, `sonnet-5`, `fable-5`, `mythos-5` (any separator: `-`, `.`, `_`).

    | Variant    | Payload |
    | ---------- | ------- |
    | `default`  | `{"thinking": {"type": "adaptive", "display": "summarized"}}` |
    | `low`      | `{"output_config": {"effort": "low"}, "thinking": {"type": "adaptive", "display": "summarized"}}` |
    | `medium`   | `{"output_config": {"effort": "medium"}, "thinking": {"type": "adaptive", "display": "summarized"}}` |
    | `high`     | `{"output_config": {"effort": "high"}, "thinking": {"type": "adaptive", "display": "summarized"}}` |
    | `xhigh`    | `{"output_config": {"effort": "xhigh"}, "thinking": {"type": "adaptive", "display": "summarized"}}` |
    | `max`      | `{"output_config": {"effort": "max"}, "thinking": {"type": "adaptive", "display": "summarized"}}` |

=== "Anthropic (openai-chat gateways, e.g. OpenRouter)"

    Applies to the same Claude models when served through providers using the `openai-chat` API (e.g. OpenRouter). The top-level `verbosity` param is mapped by OpenRouter to Anthropic's `output_config.effort` upstream. Excluded for `github-copilot` provider (variants come from Copilot's `/models` discovery).

    For `sonnet-4-6`, `opus-4-5`, `opus-4-6` (thinking is opt-in via the unified `reasoning` param):

    | Variant    | Payload |
    | ---------- | ------- |
    | `low`      | `{"verbosity": "low", "reasoning": {"enabled": true}}` |
    | `medium`   | `{"verbosity": "medium", "reasoning": {"enabled": true}}` |
    | `high`     | `{"verbosity": "high", "reasoning": {"enabled": true}}` |
    | `max`      | `{"verbosity": "max", "reasoning": {"enabled": true}}` |

    For `opus-4-7`, `opus-4-8`, `opus-5`, `sonnet-5`, `fable-5`, `mythos-5` (thinking always on, `verbosity` is the only effort lever):

    | Variant    | Payload |
    | ---------- | ------- |
    | `low`      | `{"verbosity": "low"}` |
    | `medium`   | `{"verbosity": "medium"}` |
    | `high`     | `{"verbosity": "high"}` |
    | `xhigh`    | `{"verbosity": "xhigh"}` |
    | `max`      | `{"verbosity": "max"}` |

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

    Applies to models matching `deepseek-v4-pro` and `deepseek-v4-flash`. Only for providers using the `openai-chat` API.

    | Variant    | Payload |
    | ---------- | ------- |
    | `none`     | `{"thinking": {"type": "disabled"}}` |
    | `high`     | `{"reasoning_effort": "high"}` |
    | `max`      | `{"reasoning_effort": "max"}}` |

## Discovered Variants

Some providers (GitHub Copilot, OpenAI OAuth, and gateways like OpenRouter or Synthetic) tell ECA which reasoning effort levels a model supports, and ECA builds the variants for you. Nothing to configure: just pick an effort from the model's variant list.

Discovered variants are only used when nothing else defines variants for the model. Your custom variants always win.

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

