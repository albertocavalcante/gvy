# AGENTS.md

**Groovy Language Server** — Kotlin/JVM LSP implementation.

## Build Commands
```bash
make build    # Full build + tests
make test     # Tests only
make lint     # Check quality
make format   # Auto-fix lint
```

## Non-Negotiable Rules

1. **Git**: Verify branch before commit. Stage files explicitly (`git add file.kt`, never `git add .`)
2. **TDD**: Write failing test → implement → refactor. Always.
3. **Lint**: Run `./gradlew lintFix` before commit
4. **GitHub**: Use `gh` CLI for all GitHub operations

## Commit Format
```
<type>(<scope>): <description>
```
Types: `feat`, `fix`, `refactor`, `test`, `docs`, `ci`, `chore`

## When to Read Additional Docs

| Task | Read First |
|------|------------|
| Git workflow, branching | `.agent/rules/git-workflow.md` |
| TDD details, test naming | `.agent/rules/code-quality.md` |
| PR review feedback | `.agent/workflows/review.md` |
| Implementing GitHub issues | `.agent/workflows/solve.md` |
| Deferring work | `.agent/workflows/defer.md` |
| GitHub API/CLI patterns | `.agent/workflows/github-cli.md` |
| CI/CD, Actions | `.agent/workflows/github-actions.md` |
| Merge conflicts | `.agent/workflows/conflict-resolution.md` |
| Debugging | `kb/TROUBLESHOOTING.md` |

## Deferred Work Pattern
```kotlin
// TODO(#123): Brief description.
//   See: https://github.com/albertocavalcante/groovy-lsp/issues/123
```

## Structure
```
.agent/rules/      # Permanent rules
.agent/workflows/  # Step-by-step procedures
.agent/queries/    # GraphQL for GitHub API
.agent/scripts/    # Python helpers (format with ruff)
```
