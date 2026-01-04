---
description: Deterministic assessment of repo quality (read-only): run the right gates, stop on first failure, produce a short report.
---

# /lint — Assess repo quality (read-only)

Run quality gates and produce a structured report.

This workflow is intentionally strict: it is designed to prevent LLMs (and humans) from guessing, drifting scope, or
“fixing while assessing”.

## Agent contract (non-negotiable)

- MUST stop on the first failing gate and hand off to `/lintfix`.
- MUST NOT modify code in `/lint`.
- MUST NOT invent commands. If a command or script is unclear, fetch it from the repo files (see “Lazy fetch”).
- MUST record the failure contract: `{command, first failing task/script, file(s), rule/message}`.
- MUST behave like a Kotlin linter persona (“style officer”) on the **changed code only**: flag non-idiomatic patterns
  and risky constructs even if tools pass.

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
git diff --name-only origin/main...HEAD
```

| Path pattern      | Toolchain | Required gate                                                                                                         |
| ----------------- | --------- | --------------------------------------------------------------------------------------------------------------------- |
| `editors/code/**` | pnpm      | `cd editors/code && pnpm install --frozen-lockfile && pnpm run check-types && pnpm run lint && pnpm run format:check` |
| anything else     | Gradle    | `make lint`                                                                                                           |

## Step 2 — Run gates (stop on first failure)

### 2.1 Gradle

```bash
make lint
```

FAIL → record: `{command=make lint, task, file, rule}`. STOP and run `/lintfix`.

### 2.2 Extension (skip if no `editors/code/**` in scope)

```bash
cd editors/code && pnpm install --frozen-lockfile && pnpm run check-types && pnpm run lint && pnpm run format:check
```

FAIL → record: `{command=<pnpm script>, file, message}`. STOP and run `/lintfix`.

### 2.3 Build (only for refactors / multi-module)

```bash
make build
```

## Step 3 — Baseline check

```bash
find . -name "detekt-baseline.xml" 2>/dev/null
git diff --stat -- '**/detekt-baseline.xml'
```

- Additions in diff → **FAIL** (baseline grew).
- Deletions only or unchanged → OK.

## Step 4 — Smell scan

This step is where the LLM acts as an additional linter.

Rules:

- ONLY review files in `git diff --name-only origin/main...HEAD`.
- Be strict about Kotlin idioms and risk patterns.
- Do NOT propose “nice-to-have” refactors; only log issues that are actionable and related to the change surface.

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

### C) Null-safety + contracts

7. New `!!` without a strong invariant?
   - Prefer `requireNotNull(...)`, `checkNotNull(...)`, safe calls, or explicit domain types.
8. Nullable types widened without necessity (turning non-null into nullable)?

### D) Error handling (idiomatic + safe)

9. Broad `catch (Exception)` without context/logging or without rethrowing?
10. `try/catch` used where `runCatching { }` + `.onFailure { }` / `.getOrElse { }` would make intent clearer? - Use
    `runCatching` when you’re intentionally converting exceptions into a value/result flow. - Keep `try/catch` when you
    must handle specific exceptions differently or need precise control flow.

### E) Behavioral risk + tests

11. Behavior changed but tests unchanged?
12. Performance cliff added (new repeated parsing, accidental O(N^2), unnecessary allocations in hot paths)?

Any YES → write a 1-line note under “Smells” and point to `/lintfix` (or create a deferred issue).

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
- Scope: [file list | PR | repo]
- Gradle: PASS | FAIL {task, file, rule}
- Extension: PASS | FAIL | SKIPPED
- Baseline: UNCHANGED | REDUCED | GREW
- Smells: none | [list]
- Next: [single action]
```
