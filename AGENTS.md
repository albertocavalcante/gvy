# AGENTS.md

**Groovy Language Server** — Kotlin/JVM LSP implementation.

**Quality Bar:** gvy gives correct, honest and fast answers about Groovy code, and never damages it. Principles,
scorecard and tracks: `docs/quality-bar.md`; live status: issue #1551.

## Environment Setup

Before running any build commands in a worktree, initialize the environment:

```bash
direnv allow   # Load Java/Gradle environment
java -version  # Verify Java is available
```

> [!NOTE] Each new worktree requires `direnv allow` to be run once.

Non-interactive agent shells do not run the direnv hook. Load the profile explicitly with
`eval "$(direnv export bash)"`, or set `JAVA_HOME` as described in
[CONTRIBUTING.md](CONTRIBUTING.md#set-up-the-jvm-build).

## Commands

```bash
make build   # Build + tests
make test    # Tests only
make lint    # Check quality
make format  # Fix lint
```

Run one Gradle build at a time per machine, even with one worktree per agent. Prefer module-scoped tasks
(`./gradlew :gls:test --tests '<Class>'`) over a full `make build`.

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
7. **Dependencies can have bugs**: OpenRewrite, `rules_kotlin`, Groovy itself, and every other dependency are fallible.
   Follow `docs/dependency-bugs.md`: investigate attribution, track suspected and confirmed upstream bugs in gvy issues,
   and keep mitigations tested. Prepare upstream reports and patches locally for human review; never submit upstream
   issues, PRs, or comments autonomously. Upstream submission needs explicit approval of the specific material and
   target, plus a check of that project's contribution and AI policies. gvy's routine PR authority does not cover
   upstream repos.
8. **CI accountability**: Read `docs/ci-diagnostics.md`. Review the complete CI result for the publication head,
   including warnings, dependency advice, skipped checks and diagnostic artifacts. Fix new diagnostics or record a
   narrowly reviewed, owned, expiring exception linked to an issue. Report remaining debt and validation limits; a green
   command exit alone is insufficient evidence. Run `gh pr checks <PR> --watch` and verify the PR head still matches the
   reviewed source before shipping. An applicable failing or pending check blocks readiness even when `Validation Check`
   passed; that verdict covers its declared producer graph, not every other workflow. Never silently expand a baseline
   or suppress a category.
9. **Readable Kotlin**: Follow [Kotlin style and readability](docs/kotlin-style.md) for every Kotlin change, including
   tests. Review readability separately from lint: make alternatives and logical groups visible, use meaningful names,
   and prefer clear control flow over dense expressions. Preserve evaluation and failure behavior during style changes.
10. **Never drop a failing case silently**: Do not delete, comment out, skip, weaken or narrow a test, case, assertion,
    check or baseline because it fails; an unexpected failure is a finding. If you do not fix the bug in the same
    change, file a gvy issue, keep the case as an executable record, mark it `FIXME(#n)` and list it under "Known gaps"
    in the PR body. A commit message alone is not documentation. Procedure:
    [code-quality rules](.agent/rules/code-quality.md#never-drop-a-failing-case-silently).
11. **PR lifetime**: A PR merges or carries a named blocker comment within 24 hours of opening. Merge as soon as all
    applicable checks (rule 8) are green and the head SHA equals the reviewed SHA; never hold a PR for a batch. At most
    three PRs open per agent. Draft means CI in progress only; parked work becomes an issue naming the branch, and the
    draft is closed. Check the main CI run after every merge. A PR you stop working on is closed with a comment linking
    the issue that carries the remaining work. Details: [PR lifetime](.agent/rules/git-workflow.md#pr-lifetime).

## Recovery and mixed-version architecture requirements

Before changing parsing, completion/hover/tokens, analysis sessions, target/import selection, caches/indexes, semantic
edits, compiler workers or framework providers, read the
[current contract and PR packets](kb/INCREMENTAL_REPARSE_DELIVERY_PLAN.md#recovery-and-mixed-version-implementation-contract)
and the relevant [SDK boundary](kb/SDK_EXTRACTION_DELIVERY_PLAN.md). These requirements remain active across sessions;
verified implementation adds regression obligations rather than retiring the invariants.

- Ordinary incomplete buffers need useful current-source syntax and unaffected editor facts, including on a cold open. A
  last-good AST, completion-only repair or host compiler upgrade does not satisfy the independent syntax requirement.
- The public syntax/analysis boundary must be compiler-free. Version-specific compiler facts are optional, qualified
  providers; never equate a configured version with the compiler actually loaded.
- One session must support distinct target/compiler/JDK/framework contexts, including shared files. Global selected
  compilers, union classpaths and URI-only semantic stores cannot certify mixed-context analysis.
- Before mixed-context exposure, every semantic entry point uses context-owned state or explicitly reports unavailable
  (M2.4). Unique target membership per file does not make a shared global index safe. Physical edits require affected-
  context impact proof or conservative refusal; context switches must retire client-visible cached results.

Each affected PR identifies its packet, remaining acceptance gates and actual evidence. Update the canonical delivery
record before handoff; keep proposed, reviewed, passed, merged and installed states distinct. Backend choice, exact
supported versions and resource defaults remain experiment-gated. The packet sequence is a staged migration, not an
instruction to rewrite every feature at once. Unrelated changes do not need to read the full program.

## Architecture

The following describes the current implementation; the required migration boundary is recorded above.

- `gls/`: the LSP server. `semantics/`: type inference and the semantic model. `fmt/`: the formatter.
- Native (`parser/native/`, compiler-driven, on Groovy's own AST) serves language features. Hover goes through the
  engine layer and defaults to Native, reusing the captured compiler snapshot. OpenRewrite remains an explicit
  alternative. The separate Core hover engine is retired; `core` aliases Native through 0.5.5 and is removed in 0.5.6.
  Keep `parser/core`: its type solvers, Groovydoc and Core AST visualization consumers remain in use.
- Module map: `docs/overview.md`; design: `kb/ARCHITECTURE.md`.

## Testing

E2E scenarios are YAML files in `tests/e2e/resources/scenarios` (fixtures in `tests/e2e/resources/workspaces`).
Step-by-step: `tests/e2e/resources/scenarios/README.md`; harness design: `kb/testing/e2e-harness.md`. Run one with
`make e2e-single SCENARIO=<name>`.

## Where docs go

Follow the [documentation audience map](CONTRIBUTING.md#where-documentation-belongs). Keep the README focused on
external users, human development procedures in contributor guides, and agent execution rules in `AGENTS.md` and
`.agent/`. `docs/README.md` separates user and contributor entry points; `kb/` holds design records, RFCs, audits, and
plans. Link to canonical procedures instead of duplicating them.

For issues, PRs and Markdown, follow the [visual writing guide](docs/visual-writing.md). Readable visual explanations
are a standing preference; choose a useful format rather than requiring a diagram for every small change.

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
| Writing or reviewing Kotlin     | `docs/kotlin-style.md`                    |
| Writing tests, TDD, naming      | `.agent/rules/code-quality.md`            |
| Addressing PR review comments   | `.agent/workflows/review.md`              |
| Implementing a GitHub issue     | `.agent/workflows/solve.md`               |
| Creating issue for future work  | `.agent/workflows/defer.md`               |
| Suspected dependency bug        | `docs/dependency-bugs.md`                 |
| Reviewing stale/old PRs         | `.agent/workflows/stale-pr.md`            |
| GitHub API, GraphQL, `gh` CLI   | `.agent/workflows/github-cli.md`          |
| GitHub Actions, CI failures     | `.agent/workflows/github-actions.md`      |
| Merge/rebase conflicts          | `.agent/workflows/conflict-resolution.md` |
| Test failures, debugging        | `kb/TROUBLESHOOTING.md`                   |

## Deferred Work

Use `TODO(#n)` for planned work and `FIXME(#n)` for a known defect, always with the issue link:

```kotlin
// TODO(#123): Brief description.
//   See: https://github.com/albertocavalcante/gvy/issues/123

// FIXME(#124): Hover returns the wrong range for X; this test pins today's behaviour.
//   See: https://github.com/albertocavalcante/gvy/issues/124
@Disabled("Fails because of #124")
@Test
fun `hover on X returns the identifier range`() { ... }
```

## Helper Files

| Path                | Contains                              |
| ------------------- | ------------------------------------- |
| `.agent/rules/`     | Permanent rules                       |
| `.agent/workflows/` | Step-by-step procedures               |
| `.agent/queries/`   | GraphQL queries                       |
| `.agent/scripts/`   | Python helpers (use `ruff` to format) |

## Source installation

For an installation or update, read [the project install skill](.agents/skills/gvy-install/SKILL.md). For a requested
remote `main` HEAD or SHA, use `node editors/code/tools/install-extension.js --from-ci --ref main --wait 600 --json`.
This pins the ref once and reuses verified source-built CI artifacts. Resume the recorded SHA instead of chasing
subsequent pushes. Inspect the JSON result before reading build or installer logs.

To install both components from this checkout, use
`node editors/code/tools/install-extension.js --from-source --installer code --json`. Redirect stderr to a local build
log; inspect the JSON receipt first and read the log only for a failed stage. Use `--doctor` for prerequisites and
`--build-root` (or `GVY_BUILD_ROOT`) when the checkout drive lacks space. Do not substitute a release download for a
requested source build. The command records HEAD and dirty status, verifies packaged hashes, and runs bounded server
smoke checks before calling the editor installer.
