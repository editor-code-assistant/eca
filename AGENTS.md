AGENT GUIDE (for coding agents)

Build/Run
- Prereqs: Clojure CLI (tools.deps) and Babashka (bb.edn).
- Local dev binary (nREPL-enabled): bb debug-cli (produces ./eca[.bat]).
- Production-like embedded JVM binary: bb prod-cli; Fat JAR: bb prod-jar; Native (GraalVM): set GRAALVM_HOME, then bb native-cli.

Test
- Unit tests (Kaocha): bb test (equivalent to clojure -M:test).
- Run a single test ns: clojure -M:test --focus eca.shared-test (regex allowed, e.g. --focus "eca.features.*").
- Run a single test var: clojure -M:test --focus "eca.shared-test/some-test".
- Integration tests (require ./eca binary): bb prod-cli && bb integration-test but only run if user requests it.

Lint/Format
- Lint with clojure-lsp (config in .lsp/): `clojure-lsp diagnostics`.
- Use clojure-lsp formatting style, follow idiomatic Clojure style (2-space indent, align let bindings, wrap at ~100 cols).

Code Style
- Namespace preamble: (set! *warn-on-reflection* true); prefer require with :as aliases (e.g., [clojure.string :as string], [eca.features.commands :as f.commands]); use :refer only for a few, explicit symbols.
- Naming: kebab-case for vars/fns, namespaces as eca.<area>[.<subarea>]; predicates end with ? (e.g., clear-history-after-finished?).
- Data: use plain maps with clear keys; prefer assoc-some (eca.shared) to avoid nil assigns; thread with -> / ->> for pipelines.
- Errors/logging: throw ex-info with a data map; log via eca.logger/{debug,info,warn,error}; avoid println to stdout (stderr logging is handled).
- APIs: favor pure functions; side effects live in features/tools or adapters; keep tool I/O and messaging through eca.messenger/f.tools.
- Unit tests should have a single `deftest` for function to be tested with multiple `testing`s for each tested case.
- Unit tests that use file paths and uris should rely on `h/file-path` and `h/file-uri` to avoid windows issues with slashes.

Secrets Management
- `src/eca/secrets.clj` - Main secrets manager for credential file operations:
  - File discovery and priority order (`:netrcFile <FILE>` config → .netrc → _netrc)
  - Cross-platform path construction using io/file (handles / vs \ separators automatically)
  - keyRc format parsing: [login@]machine[:port] (named after Unix "rc" config file tradition)
  - Credential matching logic (exact login match when specified, first match otherwise)
  - Permission validation (Unix: warns if not 0600; Windows: skipped)
- Authentication flow: config `key` → credential files `keyRc` → env var `keyEnv` → OAuth

Notes
- CI runs: bb test and bb integration-test. Ensure these pass locally before PRs.
