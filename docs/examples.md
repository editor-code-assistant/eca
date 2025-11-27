# Examples

ECA config examples showing the power of its features and flexibility:

## From users

??? info "Custom behavior: Clojure reviewer (@zikajk)"

    ```javascript title="config.json"
    {
        "behavior": {
            "reviewer": {
                "defaultModel": "openai/gpt-5.1",
                "systemPrompt": "${file:prompts/reviewer.md}"}
            },
            "dangerous": {
                "defaultModel": "deepseek/deepseek-chat",
                "toolCall": {"approval": {"byDefault": "allow"}}
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

??? info "Custom tool: clj-nrepl-eval (Michael Whitford)"

    ```javascript title="config.json"
    {
        "customTools": {
            "clj-nrepl-eval": {
                "description": "${file:tools/clj-nrepl-eval.md}",
                "command": "clj-nrepl-eval -p $(cat .nrepl-port) $'{{code}}'",
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

