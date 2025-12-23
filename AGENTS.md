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

```bash
# Build & Test
./gradlew build                                    # Full build
./gradlew test                                     # All tests
./gradlew test --tests "*ClassName*" --info        # Single test with output
./gradlew lintFix                                  # Auto-fix lint issues

# Environment (macOS with Homebrew - adjust for your OS/JDK setup)
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
```

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

## Tool Reliability

When using `replace_file_content` and encountering "target content not found":
1. STOP. Do not blindly retry.
2. Call `view_file` to see the actual current state.
3. Adjust `TargetContent` to match exactly (whitespace, newlines).
