Analyze this codebase and create or update an AGENTS.md file containing the essential information an AI coding agent needs to work effectively in this repository.

Create or update it at the repository root. If {{workspaceRoots}} contains multiple roots, inspect them and ask which root should receive AGENTS.md before writing.

## Step 1: Explore the codebase

Read the actual contents of files like these (skip any that don't exist — this is not an exhaustive list):

- **Manifest/package files**: package.json, deps.edn, project.clj, Cargo.toml, pyproject.toml, go.mod, pom.xml, build.gradle, mix.exs, etc.
- **Build/task runner configs**: Makefile, bb.edn, Justfile, Taskfile.yml, package.json scripts, etc.
- **CI config**: .github/workflows/*, .gitlab-ci.yml, .circleci/, azure-pipelines.yml, etc.
- **Existing AI instructions**: AGENTS.md, CLAUDE.md, .cursor/rules/, .cursorrules, .github/copilot-instructions.md, .windsurfrules, .clinerules, etc.
- **README**, **CONTRIBUTING**, or **DEVELOPERS** docs, etc.
- **Linter/formatter configs**: .clj-kondo/, .eslintrc*, ruff.toml, .golangci.yml, biome.json, .prettierrc, etc.

Do not guess from filenames — read the actual file contents.

From what you read, identify (among other things):
- **Build commands** — especially non-standard ones (wrapper scripts, aliases, specific flags)
- **Test commands** — including how to run a single test (namespace, file, or individual test)
- **Lint/format commands** — exact invocations
- **Project structure** — monorepo, multi-module, or single project; workspace conventions
- **Code style rules that DIFFER from language defaults** — imports, formatting, naming, types, error handling
- **Non-obvious gotchas** — required env vars, setup steps, platform-specific issues
- **Repo conventions** — branch naming, PR/commit style, changelog practices
- **Architectural constraints** — things that constrain how code should be written (e.g., "must work with GraalVM native image", "no reflection")

Do not invent missing conventions. If missing information is essential to avoid misleading instructions, ask targeted questions (via ask_user tool) before writing AGENTS.md. Otherwise omit unknown conventions rather than guessing.

## Step 2: Write AGENTS.md

Write a concise AGENTS.md. Every line must pass this test: **would removing it cause an agent to make mistakes or work less efficiently?** If not, cut it.

Include:
- Non-standard build/test/lint commands (especially single-test invocation)
- Style rules that differ from defaults
- Testing quirks and instructions
- Repo etiquette (branch/PR/commit conventions)
- Required env vars or setup steps
- Non-obvious gotchas or architectural constraints
- Concise architecture notes that are not obvious from filenames: major boundaries, request/data flow, extension points, generated-code areas, or constraints that affect how changes should be made
- Key information from existing AI tool configs — read instructions meant for other AI tools and adapt them for AGENTS.md

Exclude:
- File-by-file structure or component lists (agents can discover these by reading code)
- Standard language conventions agents already know
- Generic advice ("write clean code", "handle errors")
- Information that changes frequently — reference the source file instead
- Detailed tutorials — link to docs instead

Use short sections with bullet points, not paragraphs.

If AGENTS.md already exists in the chosen repository root, read it first. Improve it by filling gaps, removing outdated information, and tightening the language. Preserve what's already correct and useful.
