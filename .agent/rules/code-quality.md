# Code Quality Rules

These rules are ALWAYS applied when writing or modifying code.

## Lint Handling

ALWAYS address lint/detekt issues introduced by your changes. Never ignore them.

For each lint issue, do ONE of:
1. **FIX** it properly (e.g., extract magic numbers to constants, add null checks)
2. **SUPPRESS** with annotation + justification comment when legitimate
3. **ASK** user if unsure about the right approach

It's acceptable to fix lint issues at the end of a change, but they MUST be addressed before committing.

**Goal**: Zero new lint issues per PR.

## Test Naming Convention

ALWAYS use backtick style with descriptive sentences for test names.
NEVER use camelCase for test function names.

✅ Good:
```kotlin
@Test fun `FixContext stores diagnostic correctly`()
@Property fun `property - unregistered random rule names return null handler`()
```

❌ Bad:
```kotlin
@Test fun fixContextStoresDiagnosticCorrectly()
@Property fun nonCodeNarcDiagnosticsReturnEmptyActions()
```

Rationale: Backtick style with spaces is more readable, self-documenting, and follows BDD principles.

## TDD (Test-Driven Development)

Follow TDD for new features. Recommended 99.999% of the time.

### TDD Cycle
1. **Red**: Write a failing test first that defines expected behavior
2. **Green**: Write minimum code to make the test pass
3. **Refactor**: Clean up while keeping tests green

### Order of Operations
1. Create test file with test cases (they may fail to compile initially)
2. Create minimal interface/class to make tests compile
3. Run tests - verify they fail for the right reason
4. Implement until tests pass
5. Refactor if needed

### When to Skip TDD
TDD can be skipped when overhead is excessive (rare cases):
- Pure infrastructure/wiring code with no logic
- Exploratory spikes that will be rewritten
- Trivial one-liner changes

When skipping, write tests immediately after implementation.

## Engineering Notes

When introducing ANY heuristic (regex parsing, string matching on error messages, token-length widening, line-based fallbacks, etc.), ALWAYS annotate the code with an explicit tag:
- `NOTE:` explain the trade-off and why we're doing it now
- `TODO:` describe the deterministic/robust approach we'd prefer long-term
- `FIXME:` if the heuristic is known to be flaky/incorrect in some cases
- `HACK:` only if it's a last-resort workaround (must include exit plan)

The goal is to make heuristics visible, reviewable, and easy to revisit over time.

## Flaky Tests

When a test fails due to a timeout or non-deterministic behavior, add a FIXME comment.
Example: `// FIXME: Flaky test due to timeout`

## Privacy

NEVER expose absolute home directory paths in docs, specs, commits, or PRs.
Use `$HOME` or `~` instead.

## Test Debugging

For test debugging with println: MUST run with `--info` flag.
```bash
./gradlew test --tests "*SomeTest*" --console=plain --info
```

Without `--info` flag, println output will not be visible in test results.
