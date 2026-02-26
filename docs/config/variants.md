---
description: "Configure ECA variants: switch between model presets, reasoning effort levels, and custom payload overrides on the fly."
---

# Variants

![](../images/features/variants.png)

Variants are named payload presets for a model, allowing you to quickly switch between different LLM parameters — like reasoning effort — without changing the model itself.

## Built-in Variants

ECA ships with built-in variants for some known models via the `variantsByModel` config which has a good default.

=== "Anthropic"

    Applies to models matching `sonnet-4-6`, `opus-4-5`, `opus-4-6` (any separator: `-`, `.`, `_`).

    | Variant    | Payload |
    | ---------- | ------- |
    | `low`      | `{"output_config": {"effort": "low"}, "thinking": {"type": "adaptive"}}` |
    | `medium`   | `{"output_config": {"effort": "medium"}, "thinking": {"type": "adaptive"}}` |
    | `high`     | `{"output_config": {"effort": "high"}, "thinking": {"type": "adaptive"}}` |
    | `max`      | `{"output_config": {"effort": "max"}, "thinking": {"type": "adaptive"}}` |

=== "OpenAI"

    Applies to models matching `gpt-5-3-codex`, `gpt-5-2`. Excluded for `github-copilot` provider.

    | Variant    | Payload |
    | ---------- | ------- |
    | `none`     | `{"reasoning": {"effort": "none"}}` |
    | `low`      | `{"reasoning": {"effort": "low", "summary": "auto"}}` |
    | `medium`   | `{"reasoning": {"effort": "medium", "summary": "auto"}}` |
    | `high`     | `{"reasoning": {"effort": "high", "summary": "auto"}}` |
    | `xhigh`    | `{"reasoning": {"effort": "xhigh", "summary": "auto"}}` |

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

You can set a default variant for an agent so it starts with that variant pre-selected:

```javascript title="~/.config/eca/config.json"
{
  "agent": {
    "code": {
      "variant": "medium"
    }
  }
}
```

If the configured variant doesn't exist for the current model, it will be ignored.

