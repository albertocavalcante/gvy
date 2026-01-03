# AGENTS.md

**Groovy Language Server** — Kotlin/JVM LSP implementation.

## Commands

```bash
make build   # Build + tests
make test    # Tests only
make lint    # Check quality
make format  # Fix lint
```

## Rules (ALWAYS APPLY)

1. **Worktrees**: Use `git worktree add` for new work. **Do not edit files in the main worktree.**
   - The main worktree is at `/Users/adsc/dev/ws/gvy/main` — this is READ-ONLY for reference.
   - Before any edit, verify you are in the correct worktree: `git worktree list` or `pwd`.
   - Create worktrees with: `git worktree add ../gvy-<feature-name> -b <branch-name>`.
   - All `cd`, file edits, and git commands must target the worktree path, not `main`.
2. **Git**: `git branch --show-current` before commit. `git add <file>` explicitly, NEVER `git add .`
3. **TDD**: Failing test FIRST → implement → refactor
4. **Lint**: `./gradlew lintFix` before commit
5. **GitHub**: Use `gh` CLI, never curl/fetch for GitHub URLs

## Commits

```
<type>(<scope>): <description>
```

Types: `feat` `fix` `refactor` `test` `docs` `ci` `chore`

## READ BEFORE ACTING

<instruction>
When starting a task, IDENTIFY the task type below and READ the linked document BEFORE writing code or executing commands.
The git workflow rules apply to any branch, commit, or PR work.
</instruction>

| IF task involves...             | THEN read                                 |
| ------------------------------- | ----------------------------------------- |
| Creating branch, committing, PR | `.agent/rules/git-workflow.md`            |
| Writing tests, TDD, naming      | `.agent/rules/code-quality.md`            |
| Addressing PR review comments   | `.agent/workflows/review.md`              |
| Implementing a GitHub issue     | `.agent/workflows/solve.md`               |
| Creating issue for future work  | `.agent/workflows/defer.md`               |
| GitHub API, GraphQL, `gh` CLI   | `.agent/workflows/github-cli.md`          |
| GitHub Actions, CI failures     | `.agent/workflows/github-actions.md`      |
| Merge/rebase conflicts          | `.agent/workflows/conflict-resolution.md` |
| Test failures, debugging        | `kb/TROUBLESHOOTING.md`                   |

## Deferred Work

```kotlin
// TODO(#123): Brief description.
//   See: https://github.com/albertocavalcante/groovy-lsp/issues/123
```

## Helper Files

| Path                | Contains                              |
| ------------------- | ------------------------------------- |
| `.agent/rules/`     | Permanent rules                       |
| `.agent/workflows/` | Step-by-step procedures               |
| `.agent/queries/`   | GraphQL queries                       |
| `.agent/scripts/`   | Python helpers (use `ruff` to format) |
