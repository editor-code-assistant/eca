(ns eca.modules.providers.variants
  "The built-in model variants, as `variantsByModel` entries: model-family
   regex → `{:variants :api :excludeProviders}`. Each API module adds the
   entries for its API through `:config-defaults`.")

(set! *warn-on-reflection* true)

(def ^:private openai-variants
  {"none" {:reasoning {:effort "none"}}
   "low" {:reasoning {:effort "low" :summary "auto"}}
   "medium" {:reasoning {:effort "medium" :summary "auto"}}
   "high" {:reasoning {:effort "high" :summary "auto"}}
   "xhigh" {:reasoning {:effort "xhigh" :summary "auto"}}})

(def ^:private openai-gpt-5-6-variants
  "Variants for gpt-5.6 models (gpt-5.6-sol, gpt-5.6-terra, gpt-5.6-luna).
   Same reasoning effort levels as `openai-variants` (none, low, medium, high,
   xhigh) plus \"max\". Note: codex also defines \"ultra\" but downgrades it to
   \"max\" on the wire, so it is not included here."
  {"none" {:reasoning {:effort "none"}}
   "low" {:reasoning {:effort "low" :summary "auto"}}
   "medium" {:reasoning {:effort "medium" :summary "auto"}}
   "high" {:reasoning {:effort "high" :summary "auto"}}
   "xhigh" {:reasoning {:effort "xhigh" :summary "auto"}}
   "max" {:reasoning {:effort "max" :summary "auto"}}})

(def ^:private openai-gpt-6-variants
  "Variants for gpt-6 models (gpt-6-astra). Same as `openai-gpt-5-6-variants`
   minus \"none\": OpenAI documents that GPT-6 Astra rejects the none reasoning
   effort. Codex also lists \"ultra\" but downgrades it to \"max\" on the wire."
  {"low" {:reasoning {:effort "low" :summary "auto"}}
   "medium" {:reasoning {:effort "medium" :summary "auto"}}
   "high" {:reasoning {:effort "high" :summary "auto"}}
   "xhigh" {:reasoning {:effort "xhigh" :summary "auto"}}
   "max" {:reasoning {:effort "max" :summary "auto"}}})

(defn ^:private chat-completions-variants
  "Chat Completions counterpart of a Responses API variant table: the same
   effort levels, sent as the top-level `reasoning_effort` string that
   /chat/completions expects instead of the `reasoning.effort` object, which
   OpenAI-compatible gateways reject as an unknown parameter (#609)."
  [responses-variants]
  (into {}
        (map (fn [[variant-name {{:keys [effort]} :reasoning}]]
               [variant-name {:reasoning_effort effort}]))
        responses-variants))

(def ^:private openai-chat-variants (chat-completions-variants openai-variants))
(def ^:private openai-chat-gpt-5-6-variants (chat-completions-variants openai-gpt-5-6-variants))
(def ^:private openai-chat-gpt-6-variants (chat-completions-variants openai-gpt-6-variants))

(def ^:private openai-responses-apis
  "Provider `api` values routed to the Responses API (`openai` is a legacy alias)."
  ["openai-responses" "openai"])

(def ^:private anthropic-variants
  {"low" {:output_config {:effort "low"} :thinking {:type "adaptive"}}
   "medium" {:output_config {:effort "medium"} :thinking {:type "adaptive"}}
   "high" {:output_config {:effort "high"} :thinking {:type "adaptive"}}
   "max" {:output_config {:effort "max"} :thinking {:type "adaptive"}}})

(def ^:private anthropic-v2-variants
  {"default" {:thinking {:type "adaptive" :display "summarized"}}
   "low" {:output_config {:effort "low"} :thinking {:type "adaptive" :display "summarized"}}
   "medium" {:output_config {:effort "medium"} :thinking {:type "adaptive" :display "summarized"}}
   "high" {:output_config {:effort "high"} :thinking {:type "adaptive" :display "summarized"}}
   "xhigh" {:output_config {:effort "xhigh"} :thinking {:type "adaptive" :display "summarized"}}
   "max" {:output_config {:effort "max"} :thinking {:type "adaptive" :display "summarized"}}})

(def ^:private openai-chat-claude-variants
  "Claude 4.5/4.6 models served through OpenAI-compatible gateways (e.g.
   OpenRouter). The top-level `verbosity` param is mapped by OpenRouter to
   Anthropic's output_config.effort; thinking is opt-in via the unified
   `reasoning` param."
  {"low" {:verbosity "low" :reasoning {:enabled true}}
   "medium" {:verbosity "medium" :reasoning {:enabled true}}
   "high" {:verbosity "high" :reasoning {:enabled true}}
   "max" {:verbosity "max" :reasoning {:enabled true}}})

(def ^:private openai-chat-claude-v2-variants
  "Adaptive-thinking Claude models (Opus 4.7+, Opus/Sonnet/Fable/Mythos 5)
   served through OpenAI-compatible gateways (e.g. OpenRouter). Thinking is
   always on, and `verbosity` is the only effort lever: OpenRouter ignores
   `reasoning.effort` for these models."
  {"low" {:verbosity "low"}
   "medium" {:verbosity "medium"}
   "high" {:verbosity "high"}
   "xhigh" {:verbosity "xhigh"}
   "max" {:verbosity "max"}})

(def ^:private deepseek-variants
  {"none" {:thinking {:type "disabled"}}
   "high" {:reasoning_effort "high"}
   "max" {:reasoning_effort "max"}})

(def ^:private glm-variants
  {"none" {:reasoning_effort "none"}
   "high" {:reasoning_effort "high"}
   "max" {:reasoning_effort "max"}})

;; Same Claude regexes appear twice with different payload dialects per API;
;; the (?:...) wrapper only keeps the map keys unique.

(def anthropic-messages
  "Claude over the Anthropic Messages payload, which Bedrock also speaks."
  {".*sonnet[-._]4[-._]6|opus[-._]4[-._][56]" {:variants anthropic-variants
                                               :api ["anthropic" "bedrock"]}
   ".*opus[-._]4[-._][78]|.*opus[-._]5|.*sonnet[-._]5|.*fable[-._]5|.*mythos[-._]5" {:variants anthropic-v2-variants
                                                                                     :api ["anthropic" "bedrock"]}})

(def openai-responses
  {".*gpt[-._]5(?:[-._](?:2|4|5)(?!\\d)|[-._]3[-._]codex)" {:variants openai-variants
                                                            :api openai-responses-apis
                                                            :excludeProviders ["github-copilot"]}
   ".*gpt[-._]5[-._]6(?!\\d)" {:variants openai-gpt-5-6-variants
                               :api openai-responses-apis
                               :excludeProviders ["github-copilot"]}
   ;; gpt-6 family (gpt-6-astra), not gpt-6.x point releases.
   ".*gpt[-._]6(?![-._]?\\d)" {:variants openai-gpt-6-variants
                               :api openai-responses-apis
                               :excludeProviders ["github-copilot"]}})

(def openai-chat
  {"(?:.*sonnet[-._]4[-._]6|opus[-._]4[-._][56])" {:variants openai-chat-claude-variants
                                                   :api "openai-chat"
                                                   :excludeProviders ["github-copilot"]}
   "(?:.*opus[-._]4[-._][78]|.*opus[-._]5|.*sonnet[-._]5|.*fable[-._]5|.*mythos[-._]5)" {:variants openai-chat-claude-v2-variants
                                                                                         :api "openai-chat"
                                                                                         :excludeProviders ["github-copilot"]}
   ;; Same GPT families served through openai-chat providers (e.g.
   ;; LiteLLM/Azure gateways): /chat/completions takes a top-level
   ;; `reasoning_effort` string instead of `reasoning.effort` (#609).
   "(?:.*gpt[-._]5(?:[-._](?:2|4|5)(?!\\d)|[-._]3[-._]codex))" {:variants openai-chat-variants
                                                                :api "openai-chat"
                                                                :excludeProviders ["github-copilot"]}
   "(?:.*gpt[-._]5[-._]6(?!\\d))" {:variants openai-chat-gpt-5-6-variants
                                   :api "openai-chat"
                                   :excludeProviders ["github-copilot"]}
   "(?:.*gpt[-._]6(?![-._]?\\d))" {:variants openai-chat-gpt-6-variants
                                   :api "openai-chat"
                                   :excludeProviders ["github-copilot"]}
   ".*deepseek[-._]v4[-._](?:pro|flash)" {:variants deepseek-variants
                                          :api "openai-chat"}})

(def any-api
  "Entries without an `:api`: they apply whatever API serves the model, so
   every API module adds them."
  {"(?i).*glm[-._]5[-._]2" {:variants glm-variants}})
