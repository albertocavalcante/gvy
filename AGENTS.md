# AGENTS.md

> A cross-platform configuration file for AI coding agents.  
> Works with: Antigravity, GitHub Copilot, Cursor, Aider, and [many more](https://agents.md).

<!-- 
EXPERIMENTAL: This multi-file structure was introduced in PR #292.
Previous single-file version: see commit before PR #292 merge.
To revert: `git show main~1:AGENTS.md > AGENTS.md` and remove .agent/ .gemini/ directories.
-->

## Project

**Groovy Language Server (LSP)** — A Kotlin/JVM implementation.

| Aspect | Value |
|--------|-------|
| Primary Language | Kotlin |
| Build Tool | Gradle (Kotlin DSL) |
| Test Framework | JUnit 5 |
| Java Version | 17 |

## Commands

> [!TIP]
> **Use the Makefile!**  
> This project includes a comprehensive `Makefile` to simplify common tasks.  
> Run `make help` to see all available commands.

```bash
# Common Tasks
make build          # Full build with tests
make test           # Run all tests
make lint           # Check code quality
make format         # Fix linting issues
make jar            # Fast build (skips tests)

# Advanced
make e2e            # Run end-to-end tests
make run-stdio      # Run LSP in stdio mode
```

> If you need to use Gradle directly (e.g., for specific task arguments), try to use the `make` wrappers first, or fall back to `./gradlew` only when necessary.

## Debugging & Troubleshooting

See [kb/TROUBLESHOOTING.md](kb/TROUBLESHOOTING.md) for comprehensive debugging procedures including:
- Groovy AST experimentation with `groovy -e`
- Test debugging techniques (System.err.println, test reports)
- Compilation phase issues
- Symbol resolution debugging

## Critical Rules

These rules apply to ALL tasks. Violation is unacceptable.

### Git Safety
- **Never commit on main** — Create a feature branch first
- **Stage files explicitly** — Use `git add file1.kt file2.kt`, NEVER `git add .`
- **Verify branch** — Run `git branch --show-current` before any commit

### Git Worktrees (REQUIRED for new PRs)

**ALWAYS** use worktrees for new feature branches. Never work directly in main worktree.

```bash
# Step 1: Fetch latest main
git fetch origin main

# Step 2: Create worktree with new branch from origin/main
git worktree add -b fix/my-feature ../groovy-lsp-my-feature origin/main

# Step 3: Enable direnv in the new worktree
cd ../groovy-lsp-my-feature
direnv allow

# Step 4: Push and create PR
git push -u origin fix/my-feature
gh pr create --base main

# Step 5: After PR merge, clean up
git worktree remove ../groovy-lsp-my-feature
git worktree prune
```

### Test-Driven Development (MANDATORY)

**NEVER** implement fixes before writing a failing test. The sequence is non-negotiable:

1. **RED**: Write test that reproduces the bug or specifies the feature
2. **RUN**: Execute test, verify it FAILS (if it passes, your test is wrong)
3. **GREEN**: Implement minimal code to make test pass
4. **RUN**: Execute test, verify it PASSES
5. **REFACTOR**: Clean up code while keeping tests green

```bash
# Example TDD workflow
./gradlew test --tests "*MyTest.*my failing case*"  # Must FAIL first
# ... implement fix ...
./gradlew test --tests "*MyTest.*my failing case*"  # Must PASS now
```

**Violations**: Implementing code before test fails = revert and start over.

### Code Quality
- **Fix lint before commit** — `./gradlew lintFix`
- **Backtick test names** — `@Test fun \`descriptive name\`()` not camelCase

### GitHub CLI
- **Use `gh` for GitHub content** — Never WebFetch for GitHub URLs
- **Use temp files for PR bodies** — Write PR descriptions to a temporary `.md` file and pass it with `gh pr create/edit --body-file`; delete the temp file afterward.
- **Use temp files for multiline commits** — Write commit message to `/tmp/commit-msg.txt` and use `git commit -F /tmp/commit-msg.txt`. Alternatively, use multiple `-m` flags: `git commit -m "title" -m "body line 1" -m "body line 2"`.

## Planning Standards

Implementation plans MUST be:
- **Split by PR**: Separate PRs for distinct logical changes
- **Self-contained**: Each PR buildable and testable independently
- **Concrete**: Include code snippets, not just "refactor method"

## Workflows & Rules

For detailed procedures, see:

| Path | Description |
|------|-------------|
| `.agent/rules/git-workflow.md` | Git workflow, branching, squash merge handling |
| `.agent/rules/code-quality.md` | Lint handling, TDD, test naming, engineering notes |
| `.agent/workflows/github-cli.md` | GitHub CLI patterns for API and PR reviews |
| `.agent/workflows/github-actions.md` | SHA pinning, workflow debugging |
| `.agent/workflows/github-issues.md` | Issue creation and label conventions |
| `.agent/workflows/conflict-resolution.md` | Rebase/merge conflict step-by-step |
| `.agent/workflows/next.md` | Codebase review and next improvements planning |
| `.agent/workflows/review.md` | PR review, address feedback, verify CI |
| `.agent/workflows/defer.md` | Defer work to GitHub issue with TODO tracking |

## Deferred Work

When improvements are **beyond current scope** or you want to track TODOs:

1. **Create GitHub issue** — `gh issue create ...` with labels
2. **Add TODO(#N)** — Reference issue in code comment
3. **Reply to reviewer** — Link to issue if PR feedback

```kotlin
// TODO(#123): Brief description.
//   See: https://github.com/albertocavalcante/groovy-lsp/issues/123
```

See `/defer` workflow for full pattern.

## Tool Reliability

When using `replace_file_content` and encountering "target content not found":
1. STOP. Do not blindly retry.
2. Call `view_file` to see the actual current state.
3. Adjust `TargetContent` to match exactly (whitespace, newlines).

## Agent Helpers

The `.agent/` directory contains tools to simplify deterministic agent workflows.

### Structure

- **`workflows/`**: Process documentation and step-by-step guides (e.g., `/pr-address-reviews`).
- **`queries/`**: Saved GraphQL queries for reproducible data fetching.
- **`scripts/`**: Python scripts for logic that is too complex or fragile for shell one-liners.

### Python Helpers

Scripts in `.agent/scripts/` provide robust alternatives to complex shell pipes.

- **Formatting**: All Python scripts must be formatted with `ruff`.
  ```bash
  ruff format .agent/scripts/my_script.py
  ruff check --fix .agent/scripts/my_script.py
  ```
- **Usage**: Call them from workflows or directly.
  ```bash
  python3 .agent/scripts/inventory_threads.py /tmp/data.json
  ```
