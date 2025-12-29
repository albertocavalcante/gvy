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

## Debugging & Experimentation

When investigating Groovy AST behavior, **run quick `groovy` scripts** to verify assumptions:

```bash
groovy -e '
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.control.*

def code = """
def method1(param) { println param }
def method2(param) { println param }
"""

def ast = new CompilationUnit().tap {
    addSource("test", code)
    compile(Phases.SEMANTIC_ANALYSIS)
}.ast.modules[0]

def m1 = ast.classes[0].methods.find { it.name == "method1" }
def m2 = ast.classes[0].methods.find { it.name == "method2" }

println "m1.param line: ${m1.parameters[0].lineNumber}"
println "m2.param line: ${m2.parameters[0].lineNumber}"
println "Same object? ${m1.parameters[0].is(m2.parameters[0])}"
'
```

This is faster than writing tests and helps understand:
- What fields AST nodes actually have
- Whether identity vs equals() matters
- How Groovy's compiler populates positions, types, etc.

## Critical Rules

These rules apply to ALL tasks. Violation is unacceptable.

### Git Safety
- **Never commit on main** — Create a feature branch first
- **Stage files explicitly** — Use `git add file1.kt file2.kt`, NEVER `git add .`
- **Verify branch** — Run `git branch --show-current` before any commit

### Git Worktrees (preferred for clean PRs)
- **Create from main** — `git fetch origin main` then `git worktree add -b <branch> ../<repo>-<branch> origin/main`
- **Pick a sibling path** — Use a path at the same level as the repo, e.g. `../groovy-lsp-codeql`
- **Keep changes isolated** — Do work only inside the worktree path for that PR
- **Push from the worktree** — `git push -u origin <branch>`
- **Clean up after merge** — `git worktree remove <path>` then `git worktree prune`
- **Optional housekeeping** — `git worktree list` to verify what’s active and remove stale entries

### Code Quality
- **TDD required** — Red → Green → Refactor
- **Fix lint before commit** — `./gradlew lintFix`
- **Backtick test names** — `@Test fun \`descriptive name\`()` not camelCase

### GitHub CLI
- **Use `gh` for GitHub content** — Never WebFetch for GitHub URLs
- **Use temp files for PR bodies** — Write PR descriptions to a temporary `.md` file and pass it with `gh pr create/edit --body-file`; delete the temp file afterward.

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
