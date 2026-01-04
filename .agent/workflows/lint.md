---
description: Deterministic repo quality gate. Default auto-fix loop; optional read-only check mode.
---

# /lint — Assess repo quality

Modes:

- `/lint` (default): auto-fix loop (runs gates and invokes `/lintfix` until green or blocked)
- `/lint check` (alias: `/lint readonly`): read-only check (runs gates once, stops on first failure, no fixes)

User intent override:

- If the user says **"fix all"**, treat it as `/lint` default mode and run end-to-end without asking for follow-up.

Run quality gates and produce a structured report.

Default behavior is to continuously fix lint issues via `/lintfix` until gates are green (or you are blocked).

This workflow is intentionally strict: it is designed to prevent LLMs (and humans) from guessing, drifting scope, or
fixing multiple things at once.

## Agent contract (non-negotiable)

- MUST honor the selected mode:
  - Default: run gates deterministically and fix failures via `/lintfix` until green (or until blocked).
  - `check`/`readonly`: run gates deterministically and stop on first failure (no fixes).
- MUST treat explicit user instruction **"fix all"** as permission to continue the loop end-to-end:
  - Do NOT ask "proceed?" between iterations.
  - Only stop when all required gates are green AND the Step 4 idiomacy scan is completed.
- MUST fix **one failure at a time** (first failing gate) and re-run the same gate to verify (default mode).
- MUST NOT invent commands. If a command or script is unclear, fetch it from the repo files (see “Lazy fetch”).
- MUST record the failure contract on each iteration: `{command, first failing task/script, file(s), rule/message}`.
- MUST behave like a Kotlin linter persona (“style officer”) on the **changed code only**:
  - Step 4 idiomacy scan is REQUIRED (it is not optional and not satisfied by Detekt output alone).
  - Flag non-idiomatic patterns and risky constructs even if tools pass.

## Lazy fetch (avoid stale copy/paste, save tokens)

When you need details, fetch only the minimum relevant snippet:

- Lefthook: read only the failing command’s block in `lefthook.yml` under `pre-commit.commands.<name>`.
- Extension scripts: read only the scripts section in `editors/code/package.json`.
- Gradle entrypoints: prefer `make lint` / `make build`; if you need the exact underlying Gradle task, take it from the
  failing output (don’t guess).

## Precondition

```bash
pwd && git worktree list   # MUST be in a worktree, not main
```

## Step 1 — Determine scope

```bash
# Prefer PR/branch diff (committed changes) when available.
git diff --name-only origin/main...HEAD

# If you're not committed yet (or origin/main...HEAD is empty), fall back to working tree scope:
# - staged changes
git diff --name-only --cached
# - unstaged changes
git diff --name-only
# - untracked files
git ls-files --others --exclude-standard
```

Scope selection rules:

- If `origin/main...HEAD` is non-empty → Scope = committed diff
- Else → Scope = union of (staged + unstaged + untracked)

| Path pattern      | Toolchain | Required gate                                                                                                         |
| ----------------- | --------- | --------------------------------------------------------------------------------------------------------------------- |
| `editors/code/**` | pnpm      | `cd editors/code && pnpm install --frozen-lockfile && pnpm run check-types && pnpm run lint && pnpm run format:check` |
| anything else     | Gradle    | `make lint`                                                                                                           |

## Step 2 — Run gates (auto-fix loop)

Run the required gates. If a gate fails, invoke `/lintfix` and then re-run the same gate. Repeat until all required
gates are green or you are blocked.

In default mode (including when the user says "fix all"), do not pause for confirmation between iterations.

Hard limit: max 10 fix iterations per `/lint` run. If exceeded, STOP and report the last failure contract.

If mode is `check`/`readonly`: run the same gates, but do NOT invoke `/lintfix`; STOP on the first failing gate and
produce the report.

### 2.1 Gradle

```bash
make lint
```

FAIL → record: `{command=make lint, task, file, rule}` → run `/lintfix` → re-run `make lint`.

### 2.2 Extension (skip if no `editors/code/**` in scope)

```bash
cd editors/code && pnpm install --frozen-lockfile && pnpm run check-types && pnpm run lint && pnpm run format:check
```

FAIL → record: `{command=<pnpm script>, file, message}` → run `/lintfix` → re-run the failing command.

### 2.3 Build (only for refactors / multi-module)

```bash
make build
```

## Step 3 — Baseline check (after gates are green)

```bash
find . -name "detekt-baseline.xml" 2>/dev/null

# If you're linting committed changes (origin/main...HEAD scope):
git diff --stat origin/main...HEAD -- '**/detekt-baseline.xml'

# If you're linting working tree changes (fallback scope):
git diff --stat --cached -- '**/detekt-baseline.xml'
git diff --stat -- '**/detekt-baseline.xml'
```

- Additions in diff → **FAIL** (baseline grew).
- Deletions only or unchanged → OK.

## Step 4 — Smell scan

This step is where the LLM acts as an additional linter.

Rules:

- ONLY review files in the **Scope** from Step 1.
- Be strict about Kotlin idioms and risk patterns.
- Do NOT propose “nice-to-have” refactors; only log issues that are actionable and related to the change surface.

Policy:

- If you find clear correctness or maintainability risks in the change surface, you MAY fix them immediately (small,
  verified diffs) even if tools pass.
- If you find subjective/style improvements, log them under “Smells” and proceed.
- If user intent is **"fix all"**, you MUST fix actionable idiomacy issues in scope (correctness/maintainability/risk),
  and then re-run the relevant gate(s) (at minimum `make lint`). Only purely subjective items may be logged.

For each changed `.kt` file, answer YES/NO:

### A) Design + responsibility

1. Duplicated logic that should be extracted?
2. Function/class doing multiple things (mixed concerns / mixed layers)?
3. Public API got less clear (naming lies, widened visibility, unclear types)?

### B) Kotlin idioms + functional style

4. Non-idiomatic patterns (Java-style loops/collectors) where Kotlin stdlib is clearer?
   - Prefer `map`, `mapNotNull`, `associateBy`, `groupBy`, `fold`, `any/all/none`, `firstNotNullOf`, `sequence {}` when
     it reduces complexity.
5. Unnecessary mutability (`var`, mutable collections) where `val` + transformations suffice?
6. Overuse of nesting (`let`/`also` chains) instead of early returns or `when` expressions?
7. Prefer declarative DSLs over mutable builder patterns – use Kotlin DSLs for configuration where possible.

### C) Null-safety + contracts

8. New `!!` without a strong invariant?
   - Prefer `requireNotNull(...)`, `checkNotNull(...)`, safe calls, or explicit domain types.
9. Nullable types widened without necessity (turning non-null into nullable)?

### D) Error handling (idiomatic + safe)

10. Broad `catch (Exception)` without context/logging or without rethrowing?
11. `try/catch` used where `runCatching { }` + `.onFailure { }` / `.getOrElse { }` would make intent clearer? - Use
    `runCatching` when you’re intentionally converting exceptions into a value/result flow. - Keep `try/catch` when you
    must handle specific exceptions differently or need precise control flow.

### E) Behavioral risk + tests

12. Behavior changed but tests unchanged?
13. Performance cliff added (new repeated parsing, accidental O(N^2), unnecessary allocations in hot paths)?

Any YES → write a 1-line note under “Smells” and point to `/lintfix` (or create a deferred issue).

Important: do NOT stop at Detekt-only findings.

- If tools are green but the Step 4 scan finds actionable issues, treat them as the next work item and fix them
  (verified by re-running `make lint`, and `make test` when behavior might have changed).

#### Few-shot (how to flag issues deterministically)

Use these patterns as a strict reference when scanning.

1. Prefer transformations over mutable loops when it improves clarity:

```kotlin
// Smell: mutable accumulation with multiple branches
val out = mutableListOf<Foo>()
for (x in xs) {
	if (x.isValid()) out += Foo(x)
}

// Better (often):
val out = xs.filter { it.isValid() }.map { Foo(it) }
```

2. Prefer `runCatching` when you are intentionally producing a value on failure:

```kotlin
// Smell: try/catch used only to default a value
val bytes = try {
	Files.readAllBytes(path)
} catch (e: IOException) {
	null
}

// Better (often):
val bytes = runCatching { Files.readAllBytes(path) }.getOrNull()
```

3. Avoid `!!` when you can enforce invariants:

```kotlin
// Smell:
val id = request.id!!

// Better (often):
val id = requireNotNull(request.id) { "request.id is required" }
```

## Output

Produce exactly:

```
## Lint Report
- Scope: [committed(origin/main...HEAD) | working-tree(staged/unstaged/untracked)], [file list | PR | repo]
- Gradle: PASS | FAIL {task, file, rule}
- Extension: PASS | FAIL | SKIPPED
- Baseline: UNCHANGED | REDUCED | GREW
- Idiomatic: PASS | FAIL | N/A (no Kotlin in scope)
- Smells: none | [list]
- Next: [single action]
```
