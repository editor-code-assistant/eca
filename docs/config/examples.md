---
description: "ECA configuration examples: real-world setups for providers, custom tools, MCP servers, hooks, agents, and more."
---

# User Examples

ECA config examples showing the power of its features and flexibility

If you think your config is relevant to be shared for other people, [open a pull request here](https://github.com/editor-code-assistant/eca/edit/master/docs/config/examples.md)

## From users

??? quote "Subagent: Clojure reviewer (@zikajk)"

    ```javascript title="config.json"
    {
        "agent": {
            "reviewer": {
                "defaultModel": "openai/gpt-5.1",
                "prompts": {"chat": "${file:prompts/reviewer.md}"}
            },
            "dangerous": {
                "defaultModel": "deepseek/deepseek-chat",
                "toolCall": {"approval": {"byDefault": "allow"}}
            }
        }
    }
    ```

    ```markdown title="prompts/reviwer.md"
    <personality>
    You are a Principal Clojure(Script) Engineer, acting as a wise and pragmatic code reviewer. Your mindset is shaped by the design principles of Rich Hickey and the practical wisdom found in texts like "Elements of Clojure," "Functional Design," and "Programming Clojure." Your tone is constructive; your goal is to help, not just to criticize.
    </personality>

    <goal>
    Review the following staged changes, which are part of a large, monolithic codebase. Your goal is not just to find errors, but to elevate the code's design, maintainability, and simplicity.

    Deliver production-quality solutions that meet the stated scope, nothing more and nothing less. Prefer clarity, simplicity, and testability over cleverness. Design for change. Always apply the **Boy Scout Rule**: leave the code a little cleaner than you found it.
    </goal>

    <instructions>
    Your review must be concise and provide actionable feedback. Focus on the following key areas, adhering to these hard rules.

    ### 1. Structure and Size (Measurable Rules)
    - **Nesting and Complexity:** Look for deeply nested structures (`let`, `if`, `cond`). If the code requires more than 2-3 levels of nesting, it's a signal to refactor. Suggest extracting logic into separate functions.
    - **No Magic Values:** Are there "magic" numbers or strings in the code? Suggest replacing them with named constants (`def` or `defconst`).

    ### 2. State Management and Side Effects
    - **Purity:** Prefer pure functions. Are side effects (I/O, database, time, randomness) clearly separated from the core logic?
    - **Explicit Side Effects:** Are functions with side effects clearly named (e.g., with a `!` suffix)? Are these effects contained at the system's boundaries?
    - **Correct Atom Usage:** Is an `atom` used for simple, uncoordinated state? Is there complex logic hidden within it that deserves a better model (e.g., a state machine)?

    ### 3. Idiomatic Clojure & Code Smells
    - **Idiomatic Core Usage:** Does the code make full use of `clojure.core` functions (e.g., `update`, `get-in`, sequence functions) instead of manual re-implementations?
    - **Duplication (DRY):** Identify any copied code block (approx. **5+ lines**) and suggest extracting it into a reusable function.
    - **Primitive Obsession:** Does the code work with too many simple types (strings, numbers) where a structured data type would make more sense? Suggest using `defrecord` or validation with `clojure.spec`/`malli` to create small "value objects."
    - **Error Handling:** Is error handling robust? Prefer exceptions with rich context (`ex-info`) over returning `nil` for control flow, unless it is an explicit and expected outcome.
    - **Boundary Validation & Schema** Does this function operate at a system boundary (e.g., an API handler, event consumer, or reading from a database)? If so, and it lacks input validation, suggest adding a schema (using the project's standard like clojure.spec, malli or plumatic.schema) to define and enforce the data's shape. This prevents invalid data from propagating deep into the system.

    ### 4. Consistency and Context
    - **Internal API / Patterns:** Does the new code respect existing patterns and idioms within the codebase?
    - **Reusability:** Could an existing helper function from the codebase have been used instead of writing a new one? If so, suggest it.
    - **Use Existing Accessor Functions**  Identify direct keyword access to nested data structures (e.g., (:bill/reversal-method bill)). Check if a dedicated helper or accessor function (like (bill/reversal-method bill)) already exists for this purpose—especially if one was just introduced in the same set of changes. If so, recommend its use to encapsulate the data structure and respect the single source of truth.

    ### 5. Testing
    - **Critical Tests:** Identify logic that is critical or complex. Suggest **2-3 specific test cases** that should be added (e.g., happy path, an edge case, an error state). The goal is not 100% coverage, but verifying the most important scenarios.
    </instructions>

    <return>
    Provide your feedback as a clear, numbered list. For each point, use the following structure:
    - **ISSUE:** A brief and clear description of the problem.
    - **REASON:** An explanation of why it's a problem (e.g., "it reduces readability," "it increases the risk of bugs").
    - **SUGGESTION:** A concrete, actionable recommendation, ideally with a small code snippet demonstrating the improved approach.

    Frame your points constructively and clearly.
    </return>
    ```

??? tip "Skill: brepl (Clojure REPL eval)"

    Add the content of [this skill](https://github.com/licht1stein/brepl/blob/8b7fed1a64e979d465594d1488c0fc1652424a26/.claude/skills/brepl/SKILL.md) to 
    `~/.config/eca/skills/brepl/SKILL.md`
    
??? tip "Skill: nucleus-clojure (@michaelwhitford)"

    ```markdown title="~/.config/eca/skills/nucleus-clojure/SKILL.md"
    ---
    name: nucleus-clojure
    description: A clojure specific AI prompt.  Use when there are clojure REPL tools available.
    ---

    Adopt these nucleus operating principles:
    [phi fractal euler tao pi mu] | [Δ λ ∞/0 | ε⚡φ Σ⚡μ c⚡h] | OODA
    Human ⊗ AI ⊗ REPL
    ```
    
??? example "Hook: fix unbalanced CLJ parens (@zikajk)"

	First install latest [[babashka](https://github.com/babashka/babashka)] + [[bbin](https://github.com/babashka/bbin)].
	Then run:
	```bbin install https://github.com/bhauman/clojure-mcp-light.git --as clj-paren-repair --main-opts '["-m"  "clojure-mcp-light.paren-repair"]'```

    ```javascript title="config.json"
	{...
	 "hooks: {"CLJ-balanced-parens-check": {"type":"postToolCall",
                                            "matcher": "eca__write_file|eca__edit_file",
                              		        "actions": [{"type": "shell",
                   					                     "file": "hooks/clj_check_parens.sh"}]}}
	...}
	```

	```bash title="hooks/clj_check_parens.sh"
	# Hook to check Clojure files with clj-kondo and auto-repair parens

	# Read stdin and extract path (returns empty string if null/invalid)
	file_path=$(jq -r '.tool_input.path // empty' 2>/dev/null)

	# Helper function to generate JSON output
	respond() {
	  cat <<EOF
	{
	  "suppressOutput": $1,
	  "systemMessage": "$2",
	  "additionalContext": "$3"
	}
	EOF
	}

	# 1. Guard Clause: Exit if no path or not a Clojure file
	if [[ -z "$file_path" || ! "$file_path" =~ \.(clj|cljs|cljd|cljc)$ ]]; then
	  respond true
	  exit 0
	fi

	# 2. Run clj-kondo (We only care about Exit Code 3: Unbalanced Parens)
	clj-kondo --lint "$file_path" &>/dev/null
	if [ $? -ne 3 ]; then
	  respond true
	  exit 0
	fi

	# 3. Attempt Repair
	if clj-paren-repair "$file_path" &>/dev/null; then
	  respond false "Unbalanced parens fixed." "Unbalanced parens have been automatically fixed."
	else
	  respond false "Unbalanced parens not fixed!" "Unbalanced parens couldn't be automatically fixed. Tell user to fix it manually."
	fi

	exit 0
 	```

??? example "Hook: notify when finished and waiting tool approval (@ericdallo)"

    ```markdown title="~/.config/eca/config.json"
    {
      "hooks": {
          "notify-finished": {
              "type": "postRequest",
              "visible": false,
              "actions": [
                  {
                      "type": "shell",
                      "shell": "canberra-gtk-play -i complete"
                  }
              ]
          },
          "notify-approval": {
              "type": "preToolCall",
              "visible": false,
              "actions": [
                  {
                      "type": "shell",
                      "shell": "jq -e '.approval == \"ask\"' > /dev/null && canberra-gtk-play -i message"
                  }
              ]
          }
      }
    }
    ```

??? example "Hook: Warcraft peon ping (@bsless)"

    This is an adapter to make [PeonPing/peon-ping](https://github.com/PeonPing/peon-ping) work with ECA hooks.

    ```markdown title="~/.config/eca/config.json"
    {
       "hooks": {
            "peon-session-start": {
                "type": "sessionStart",
                "visible": false,
                "actions": [
                    {
                        "type": "shell",
                        "file": "./hooks/peon-ping/eca-adapter.sh",
                        "timeout": 10000
                    }
                ]
            },
            "peon-session-end": {
                "type": "sessionEnd",
                "visible": false,
                "actions": [
                    {
                        "type": "shell",
                        "file": "./hooks/peon-ping/eca-adapter.sh",
                        "timeout": 10000
                    }
                ]
            },
            "peon-pre-request": {
                "type": "preRequest",
                "visible": false,
                "actions": [
                    {
                        "type": "shell",
                        "file": "./hooks/peon-ping/eca-adapter.sh",
                        "timeout": 10000
                    },
                    {
                        "type": "shell",
                        "file": "./hooks/peon-ping/scripts/hook-handle-use.sh",
                        "timeout": 5000
                    }
                ]
            },
            "peon-post-request": {
                "type": "postRequest",
                "visible": false,
                "actions": [
                    {
                        "type": "shell",
                        "file": "./hooks/peon-ping/eca-adapter.sh",
                        "timeout": 10000
                    }
                ]
            },
            "peon-pre-tool-call": {
                "type": "preToolCall",
                "visible": false,
                "actions": [
                    {
                        "type": "shell",
                        "file": "./hooks/peon-ping/eca-adapter.sh",
                        "timeout": 10000
                    }
                ]
            },
            "peon-post-tool-call": {
                "type": "postToolCall",
                "visible": false,
                "runOnError": true,
                "actions": [
                    {
                        "type": "shell",
                        "file": "./hooks/peon-ping/eca-adapter.sh",
                        "timeout": 10000
                    }
                ]
            },
            "peon-chat-start": {
                "type": "chatStart",
                "visible": false,
                "actions": [
                    {
                        "type": "shell",
                        "file": "./hooks/peon-ping/eca-adapter.sh",
                        "timeout": 10000
                    }
                ]
            },
            "peon-subagent-post-request": {
                "type": "subagentPostRequest",
                "visible": false,
                "actions": [
                    {
                        "type": "shell",
                        "file": "./hooks/peon-ping/eca-adapter.sh",
                        "timeout": 10000
                    }
                ]
            }
        }
    }
    ```
    
    ```bash title="~/.config/eca/hooks/peon-ping/eca-adapter.sh"
    #!/bin/bash
    # ECA → peon-ping adapter
    # Translates ECA's hook JSON format to the Claude Code format peon.sh expects.
    set -uo pipefail
    
    INPUT=$(cat)
    
    # Map ECA hook_type to Claude Code hook_event_name
    python3 -c "
    import json, sys
    
    data = json.loads(sys.argv[1])
    
    hook_type = data.get('hook_type', '')
    workspaces = data.get('workspaces', [])
    cwd = workspaces[0] if workspaces else ''
    # ECA does not provide a session_id; derive one from db_cache_path to get a
    # stable per-session identifier, falling back to a constant.
    db_path = data.get('db_cache_path', '')
    session_id = db_path.split('/')[-2] if db_path else 'eca-default'
    
    type_map = {
        'sessionStart':        'SessionStart',
        'sessionEnd':          'SessionEnd',
        'chatStart':           'SessionStart',
        'preRequest':          'UserPromptSubmit',
        'postRequest':         'Stop',
        'subagentPostRequest': 'Stop',
        'preToolCall':         'PermissionRequest',
        'postToolCall':        'Stop',
    }
    
    event_name = type_map.get(hook_type, hook_type)
    
    out = {
        'hook_event_name': event_name,
        'session_id':      session_id,
        'cwd':             cwd,
    }
    
    # Forward any extra fields peon.sh might use
    if 'notification_type' in data:
        out['notification_type'] = data['notification_type']
    if 'permission_mode' in data:
        out['permission_mode'] = data['permission_mode']
    
    print(json.dumps(out))
    " "$INPUT" | bash /home/bsless/.config/eca/hooks/peon-ping/peon.sh
    ```

??? note "Custom tool: clj-nrepl-eval (@michaelwhitford)"

    ```javascript title="config.json"
    {
        "customTools": {
            "clj-nrepl-eval": {
                "description": "${file:tools/clj-nrepl-eval.md}",
                "command": "read -r -d '' CLJ_PAYLOAD << 'Mjz9q5s8' || true\n{{code}}\nMjz9q5s8\nclj-nrepl-eval -p $(cat .nrepl-port) \"$CLJ_PAYLOAD\"",
                "schema": {
                    "properties": {
                        "code": {
                            "type": "string",
                            "description": "A string of clojure code to evaluate in the application runtime. It will be wrapped in ANSI-CE quoting automatically: $'...' be sure to  escape single quotes as \\', backslashes as \\\\. Example: $'(let [r (+ 1  2 3)] (println r))'"
                        }
                    },
                    "required": ["code"]
                }
            }
        }
    }
    ```

    ```markdown title="tools/clj-nrepl-eval.md"
    Evaluate Clojure code in the project's nREPL session. Returns the result of evaluation with stdout/stderr captured.

    Usage:
        - `code` parameter accepts Clojure expressions as a string
        - State persists between calls (defined vars/functions remain available)
        \nIMPORTANT Escaping Rules (code is wrapped in bash $'...' quotes):
        - Multi-line code: Use \
        for literal newlines
        Example: (defn add [x y]\
        (+ x y))

        - Strings WITHOUT single    quotes: Use normally
        Example: (str \"Hello World\")  Example: (println \"The result is ready\")
        - Strings WITH    single quotes: Use \\' to escape them
        Example: (str \"It\\'s working\")
        Example: (println \"That\\'s              correct\")

        - Double quotes inside strings: Do NOT escape - they work as-is
        Example: (str \"Hello \" \"World\")  DO NOT USE: (str \\\"Say \\\"hello\\\"\\\")  This will break

        - Quote syntax: Use (quote x) instead of 'x
                Example: (require (quote [clojure.string :as str])) Alternative: If you need 'x syntax use \\x27 (hex) Example: (def x  \\x27symbol)
        - Backslashes: Use \\\\ for literal backslash (standard escaping)
        Example: (re-find #\\\"\\\\d+\\\"   \\\"abc123\\\")
        Example: (str \\\"path\\\\\\\\to\\\\\\\\file\\\")  \\\"path\\\\to\\\\file\\\"
        \nExamples:
        -        Simple: (+ 1 2 3)
        - With output: (println \"Hello World\")
        - Multi-line: (defn greet [name]\
        (str \"Hello, \" name \"!\"))
        - Require libs: (require (quote [clojure.string :as str]))\
        (str/upper-case \"test\")
        - Very large outputs   may be truncated
    ```

??? quote "Agent orchestration, subagents, and reusable skills (@davidvujic)"
    This example shows a Eca `config.json` that define the models, and

    - AGENTS.md (human-readable definitions for agents, their responsibilities, and global guidance for use)
    - agents/ (subagent configurations with task-specific instructions and expected outputs)
    - skills/ (small, repeatable procedures that agents invoke to perform specialized work)

    ```json title="config.json"
    {
      "defaultModel": "anthropic/claude-opus-4-6",
      "providers": {
        "anthropic": {
          "keyRc": "api.anthropic.com",
          "models": {
            "claude-opus-4-6": {},
            "anthropic/claude-sonnet-4-5": {}
          }
        },
        "google": {
          "keyRc": "generativelanguage.googleapis.com",
          "models": {
            "gemini-2.5-flash": {},
            "gemini-3-flash-preview": {},
            "gemini-3-pro-preview": {}
          }
        }
      }
    }
    ```

    ```markdown title="AGENTS.md"
    # AGENTS.md

    ## Review instructions
    When the user asks for a code review / PR review / diff review, use the `code-review` subagent configured in `agents/code-review.md`.

    ## Five Whys instructions
    When the user asks for a 5 Whys analysis, use the `five-whys` subagent configured in `agents/five-whys.md`.

    ## Changes summary instructions
    When the user asks for a changes summary, use the `changes-summary` subagent configured in `agents/changes-summary.md`.

    ## Pull Request instructions
    When the user asks to make a pull request, use the `pull-request` subagent configured in `agents/pull-request.md`.

    ## Planning instructions
    When the user asks to plan an implementation or requests a step-by-step plan before coding, load the `plan` skill via `eca__skill` before writing the plan.

    ## Implementation instructions
    When the user asks to implement a plan, write code, refactor, or apply changes, use the `implement` subagent configured in `agents/implement.md`.
    ```

    ```markdown title="agents/implement.md"
    ---
    mode: subagent
    description: Implement planned changes by editing code in the repo; uses functional-leaning, idiomatic style and lightweight data structures.
    model: anthropic/claude-opus-4-6
    ---

    STYLE POLICY (REQUIRED):
    Load the `fp-idiomatic-style` skill via `eca__skill` before writing or modifying any code.
    All generated code MUST follow that policy.

    WORKFLOW:
    - Identify target files and exact edits needed.
    - Make minimal, correct changes consistent with existing project conventions.
    - Prefer functional-leaning, idiomatic solutions; avoid dataclasses/pydantic unless clearly justified.

    OUTPUT:
    - Provide exact code patches or file edits (as your usual workflow expects).
    ```

    ```markdown title="skills/fp-idiomatic-style/SKILL.md"
    ---
    name: "fp-idiomatic-style"
    description: "Coding style policy for generated code: prefer idiomatic language conventions with a functional-leaning approach (pure-ish functions, composability), and prefer lightweight data structures over heavy schema/class abstractions unless clearly justified."
    ---

    # Functional-leaning, idiomatic coding style policy

    Applies whenever you write new code OR propose code changes, in any language.

    ## Priorities (in order)
    1) Correctness and clarity first.
    2) Idiomatic for the target language (e.g., Pythonic for Python, idiomatic TS for TypeScript).
    3) Functional-leaning style when it fits naturally:
       - explicit inputs/outputs
       - minimize side effects and shared mutable state
       - small composable functions
       - declarative transformations over imperative loops where readable

    ## Data modeling preference (important)
    - Prefer lightweight structures for transient data over heavy class/schema abstractions.
    - Only introduce richer types when clearly beneficial, e.g.:
      - validation/invariants are required
      - long-lived domain objects
      - meaningful behavior/methods beyond data transport

    ### Language examples
    - **Python:** prefer `dict`/`Mapping`/tuples; avoid `dataclasses` or `pydantic` models unless justified.
    - **TypeScript:** prefer plain objects, interfaces, or type aliases; avoid classes unless justified.
    - **Other languages:** follow the same principle — reach for the lightest idiomatic container first.

    ## Don't force FP purity
    - Avoid clever chaining, excessive `map`/`filter`, or recursion if it reduces readability.
    - Choose the most readable idiomatic solution, then make it functional-leaning where it remains clear.

    ## When editing existing code
    - Prefer the smallest change that satisfies requirements.
    - Preserve local conventions unless they conflict with correctness/security.
    ```
