---
description: Deterministic protocol to fix the first failing lint gate via small verified loops, minimal diffs, and baseline discipline.
---

# /lintfix — Fix lint failures

Fix the first failing gate from `/lint`.

This workflow is designed to be used as a tight inner-loop, and `/lint` may invoke it repeatedly until all required
gates are green.

`/lint` may also invoke this workflow for **actionable idiomacy/smell fixes** found during the Step 4 smell scan, even
when Detekt is green. In that case, treat the smell as the “failure” to fix and verify with `make lint` (and `make test`
if behavior might change).

This workflow is intentionally strict: it is designed to stop LLMs from guessing, fixing multiple things at once, or
introducing stale/copy-pasted “knowledge”.

## Hard rules

1. MUST fix **first failure only** → verify → repeat (the “repeat” happens by re-running the gate and then returning to
   `/lint` for the next iteration).
2. MUST NOT change unrelated code (“drive-by refactors” forbidden).
3. MUST read the failing file before editing; do not guess intent.
4. Baseline MUST NOT grow. Shrink allowed only after fixes.
5. No blanket suppressions (`@Suppress` at file/class level).
6. Every fix has a verification command that goes red → green.

## Lazy fetch (required)

- If you need to know what a hook or script does, fetch it from repo files.
- Do not maintain copy/pasted mappings inside docs or prompts.
- Token discipline: include only the smallest snippet that proves what you’re about to run/change.

## Step 1 — Reproduce failure

Run the failing gate from `/lint`:

```bash
# Gradle
make lint

# Extension (if applicable)
cd editors/code && pnpm run check-types && pnpm run lint && pnpm run format:check
```

Record: `{command, task/script, file, rule/message}`.

### Lefthook failures

If failure came from `lefthook run pre-commit`:

1. Note the failing command name from output (e.g., `kotlin-lint`).
2. **Fetch** the command definition from `lefthook.yml` under `pre-commit.commands.<name>`.
   - Prefer a minimal search + small read (don’t paste the whole file).
3. Run the underlying `run:` value directly.

## Step 2 — Classify and fix

| Failure type                    | Fix action                                 | Verify command                                             |
| ------------------------------- | ------------------------------------------ | ---------------------------------------------------------- |
| Formatting (Spotless)           | `make format`                              | `make lint`                                                |
| Detekt rule                     | Refactor (see hierarchy below)             | `./gradlew :<module>:detekt --rerun-tasks`                 |
| Compiler warning                | Fix deprecated API / unchecked cast / etc. | `./gradlew :<module>:compileKotlin`                        |
| Extension lint/types            | Fix TS error                               | `cd editors/code && pnpm run check-types && pnpm run lint` |
| Extension format                | `cd editors/code && pnpm run format`       | `cd editors/code && pnpm run format:check`                 |
| Idiomacy/smell (tools may pass) | Small refactor on change surface           | `make lint`                                                |

### Detekt refactor hierarchy (try in order)

1. Rename / narrow visibility
2. Extract function or class
3. Replace magic value with constant
4. Early return instead of nesting
5. Split long function
6. Prefer idiomatic Kotlin patterns when simplifying (stdlib transformations, safer null handling, and `runCatching`
   when converting exceptions into values)
7. **Last resort**: targeted `@Suppress("RuleId")` with comment explaining why

## Step 3 — Verify loop

```
while failing:
    1. Run narrow verify command
    2. If PASS → run `make lint` (promote)
    3. If still FAIL → adjust fix, repeat
    4. After 3 attempts on same issue → STOP, document blocker
```

## Step 4 — Baseline discipline

```bash
git diff -- '**/detekt-baseline.xml'
```

- If baseline **grew** → revert and fix the underlying issue instead.
- If baseline **shrank** → OK (you fixed things).

## Step 5 — Final gate

```bash
make lint
# If behavior changed:
make test
```

Extension (if in scope):

```bash
cd editors/code && pnpm run check-types && pnpm run lint && pnpm run format:check && pnpm run test:all
```

## Output

```
## Fix Summary
- Failure: {task/script, file, rule}
- Fix: {what changed}
- Verify: {command} → PASS
- Baseline: UNCHANGED | REDUCED
```
