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


## Critical Rules

These rules apply to ALL tasks. Violation is unacceptable.

### Git Safety
- **Never commit on main** — Create a feature branch first
- **Stage files explicitly** — Use `git add file1.kt file2.kt`, NEVER `git add .`
- **Verify branch** — Run `git branch --show-current` before any commit

### Code Quality
- **TDD required** — Red → Green → Refactor
- **Fix lint before commit** — `./gradlew lintFix`
- **Backtick test names** — `@Test fun \`descriptive name\`()` not camelCase

### GitHub CLI
- **Use `gh` for GitHub content** — Never WebFetch for GitHub URLs

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

## Tool Reliability

When using `replace_file_content` and encountering "target content not found":
1. STOP. Do not blindly retry.
2. Call `view_file` to see the actual current state.
3. Adjust `TargetContent` to match exactly (whitespace, newlines).
