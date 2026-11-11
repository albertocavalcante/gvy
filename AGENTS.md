# AGENTS.md

**Groovy Language Server** — Kotlin/JVM LSP implementation.

**North Star:** gvy gives correct, honest and fast answers about Groovy code, and never damages it. Principles,
scorecard and lanes: `docs/north-star.md`; live status: issue #1551.

## Environment Setup

Before running any build commands in a worktree, initialize the environment:

```bash
direnv allow   # Load Java/Gradle environment
java -version  # Verify Java is available
```

> [!NOTE] Each new worktree requires `direnv allow` to be run once.

## Commands

```bash
make build   # Build + tests
make test    # Tests only
make lint    # Check quality
make format  # Fix lint
```

Run one Gradle build at a time. Prefer module-scoped tasks (`./gradlew :gls:test --tests '<Class>'`) over a full
`make build`.

> [!IMPORTANT] **VS Code Extension**: Use `pnpm` (not npm) for `editors/code/`. See `editors/code/AGENTS.md`.

## Rules (ALWAYS APPLY)

1. **Worktrees**: Use the workspace manager (`wt new <branch>` in the worker-pool workspace); otherwise create an
   isolated Git worktree from fresh `origin/main`. **Do not edit or build in the main worktree.**
   - Before any edit, verify the worktree and branch with `git worktree list` and `git branch --show-current`.
   - Preserve other agents' checkouts and edits; do not switch, reset, clean, or stash a shared worktree.
2. **Git**: `git branch --show-current` before commit. `git add <file>` explicitly, NEVER `git add .`
3. **TDD**: Failing test FIRST → implement → refactor
4. **Lint**: `./gradlew lintFix` before commit
5. **GitHub**: Use `gh` CLI, never curl/fetch for GitHub URLs
6. **Completion includes cleanup**: The agent creating a branch/worktree owns its cleanup after a verified merge. Follow
   the squash-aware proof and deletion steps in `.agent/rules/git-workflow.md`; verify remote head deletion, remove only
   the owned clean/unlocked worktree and branch, prune tracking refs, and report anything retained. Open PRs, unique
   commits, and other agents' active work are preserved.

## Architecture

- `gls/`: the LSP server. `parser/native/`: compiler-driven parsing on Groovy's own AST.
- `semantics/`: type inference and the semantic model. `fmt/`: the formatter.
- Module map: `docs/overview.md`; design: `kb/ARCHITECTURE.md`.

## Testing

E2E scenarios are YAML files in `tests/e2e/resources/scenarios` (fixtures in `tests/e2e/resources/workspaces`); see
`kb/testing/e2e-harness.md`. Run one with `make e2e-single SCENARIO=<name>`.

## Where docs go

`docs/` is for users and contributors (North Star, usage, feature matrix). `kb/` holds design records, RFCs, audits and
plans.

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
| Reviewing stale/old PRs         | `.agent/workflows/stale-pr.md`            |
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

## Source installation

To install both components from this checkout, use
`node editors/code/tools/install-extension.js --from-source --installer code --json`. Redirect stderr to a local build
log; inspect the JSON receipt first and read the log only for a failed stage. Use `--doctor` for prerequisites and
`--build-root` (or `GVY_BUILD_ROOT`) when the checkout drive lacks space. Do not substitute a release download for a
requested source build. The command records HEAD and dirty status, verifies packaged hashes, and runs bounded server
smoke checks before calling the editor installer.
