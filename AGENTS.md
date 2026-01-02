# AGENTS.md

> Cross-platform configuration for AI coding agents.
> Works with: [Antigravity](https://antigravity.dev), GitHub Copilot, Cursor, Aider, and [more](https://agents.md).

## Project

**Groovy Language Server (LSP)** — A Kotlin/JVM implementation.

| Aspect | Value |
|--------|-------|
| Language | Kotlin |
| Build | Gradle (Kotlin DSL) |
| Tests | JUnit 5 |
| Java | 17 |

---

## Quick Start

```bash
make help           # See all commands
make build          # Full build with tests
make test           # Run tests only
make lint           # Check code quality
make format         # Auto-fix lint issues
make jar            # Fast build (skip tests)
```

For Gradle-specific tasks: `./gradlew <task>`

---

## Critical Rules

<critical>
These rules are NON-NEGOTIABLE. Violations require immediate correction.
</critical>

### 1. Git Safety
```bash
git branch --show-current    # ALWAYS verify before commit
git add file1.kt file2.kt    # ALWAYS stage explicitly (never `git add .`)
```

### 2. Test-Driven Development
```
RED → GREEN → REFACTOR
```
Write failing test FIRST. Implement SECOND. No exceptions for bug fixes or features.

### 3. Lint Before Commit
```bash
./gradlew lintFix
```

### 4. GitHub CLI for GitHub Content
```bash
gh pr view 123              # NOT curl/wget for GitHub URLs
gh issue view 456
```

---

## Detailed Rules & Workflows

| Document | Purpose |
|----------|---------|
| `.agent/rules/git-workflow.md` | Branching, commits, squash merge |
| `.agent/rules/code-quality.md` | TDD, lint, test naming, Kotlin idioms |
| `.agent/workflows/review.md` | PR review feedback handling |
| `.agent/workflows/solve.md` | Issue implementation protocol |
| `.agent/workflows/defer.md` | Deferring work to GitHub issues |
| `.agent/workflows/github-cli.md` | GitHub API patterns |
| `.agent/workflows/github-actions.md` | CI/CD, SHA pinning |
| `.agent/workflows/conflict-resolution.md` | Merge conflict handling |
| `kb/TROUBLESHOOTING.md` | Debugging techniques |

---

## Commit & PR Format

Use [Conventional Commits](https://www.conventionalcommits.org/):

```bash
git commit -m "feat(completion): add method signatures"
git commit -m "fix(parser): handle null AST nodes"
```

PR titles follow the same format. See `.agent/rules/git-workflow.md` for details.

---

## Deferred Work

When work is out of scope:

```kotlin
// TODO(#123): Brief description.
//   See: https://github.com/albertocavalcante/groovy-lsp/issues/123
```

See `.agent/workflows/defer.md` for the full protocol.

---

## Agent Helpers

```
.agent/
├── rules/          # Permanent rules (git, code quality)
├── workflows/      # Step-by-step procedures (/review, /solve, etc.)
├── queries/        # GraphQL queries for GitHub API
└── scripts/        # Python helpers for complex operations
```

### Python Scripts
```bash
ruff format .agent/scripts/*.py    # Format before commit
ruff check --fix .agent/scripts/   # Lint
```
