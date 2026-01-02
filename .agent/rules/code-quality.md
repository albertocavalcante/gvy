---
description: Code quality standards, TDD, testing, and engineering practices
---

# Code Quality

<critical>
These standards apply to ALL code changes. No exceptions without explicit justification.
</critical>

---

## Test-Driven Development (TDD)

### The Cycle

```
┌─────────────────────────────────────────────────────────┐
│                    TDD CYCLE                            │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  1. RED    → Write failing test                         │
│  2. RUN    → Verify it FAILS (for the right reason)     │
│  3. GREEN  → Write minimal code to pass                 │
│  4. RUN    → Verify it PASSES                           │
│  5. REFACTOR → Clean up, keep tests green               │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### Execution
```bash
# Step 1-2: Write test, verify failure
./gradlew test --tests "*MyTest*my test name*"  # MUST FAIL

# Step 3-4: Implement, verify pass
./gradlew test --tests "*MyTest*my test name*"  # MUST PASS

# Step 5: Refactor, verify still passes
./gradlew test --tests "*MyTest*"
```

### When TDD is Required

| Scenario | TDD Required? |
|----------|---------------|
| Bug fix | ✅ YES — test reproduces bug first |
| New feature | ✅ YES — test defines behavior first |
| Refactoring | ✅ YES — tests prove behavior unchanged |
| Config/wiring only | ⚠️ OPTIONAL — no logic to test |
| Exploratory spike | ⚠️ OPTIONAL — will be rewritten |

When skipping TDD, write tests immediately after implementation.

---

## Test Naming

Use backtick syntax with descriptive sentences:

```kotlin
// ✅ GOOD
@Test fun `completion returns method signatures for class instances`()
@Test fun `parser handles null AST nodes gracefully`()
@Property fun `property - random inputs never cause NPE`()

// ❌ BAD
@Test fun completionReturnsMethodSignatures()
@Test fun testParserHandlesNull()
```

**Rationale**: Backtick names are self-documenting and read like specifications.

---

## Lint Handling

### Pre-Commit Checklist
```bash
./gradlew lintFix   # Auto-fix what's possible
./gradlew lint      # Verify no remaining issues
```

### Handling Lint Issues

| Action | When to Use |
|--------|-------------|
| **FIX** | Default — address the issue properly |
| **SUPPRESS** | Legitimate exception with justification |
| **ASK** | Unclear whether to fix or suppress |

### Suppression Format
```kotlin
@Suppress("MagicNumber")  // Configuration constant, not arbitrary
private const val DEFAULT_TIMEOUT_MS = 5000

@Suppress("TooManyFunctions")  // Facade pattern requires many delegating methods
class CompletionProvider { ... }
```

**Goal**: Zero new lint issues per PR.

---

## Engineering Notes

When introducing heuristics or non-obvious code, annotate with tags:

| Tag | Use Case | Example |
|-----|----------|---------|
| `NOTE:` | Explain trade-off | `// NOTE: Using regex for speed, AST would be more accurate` |
| `TODO:` | Future improvement | `// TODO: Replace with proper parser when available` |
| `FIXME:` | Known issue | `// FIXME: Flaky on Windows due to path separators` |
| `HACK:` | Last resort | `// HACK: Workaround for upstream bug #123, remove when fixed` |

### TODO Format with Issue Link
```kotlin
// TODO(#123): Brief description.
//   See: https://github.com/owner/repo/issues/123
```

---

## Test Debugging

### Viewing Test Output
```bash
# println output requires --info flag
./gradlew test --tests "*MyTest*" --console=plain --info
```

### Debugging Flaky Tests
1. Add `// FIXME: Flaky test - [reason]` comment
2. Investigate root cause (timing, state, randomness)
3. Fix or mark with `@RepeatedTest` / `@Disabled` with issue link

---

## Privacy

<forbidden>
Never expose absolute paths in:
- Code comments
- Commit messages
- PR descriptions
- Documentation
</forbidden>

```kotlin
// ❌ BAD
// See /Users/john/dev/project/file.kt

// ✅ GOOD
// See src/main/kotlin/file.kt
// See $HOME/project/file.kt (if external reference needed)
```

---

## Kotlin Idioms

Prefer idiomatic Kotlin patterns:

| Pattern | Prefer | Avoid |
|---------|--------|-------|
| Null safety | `?.`, `?:`, `let` | `!!` (minimize) |
| Collections | `map`, `filter`, `fold` | Manual loops |
| Data holders | `data class` | Plain class with equals/hashCode |
| Type hierarchies | `sealed class/interface` | Open class with instanceof |
| Utilities | Extension functions | Static utility classes |
| Initialization | `lazy`, `apply`, `also` | Manual init blocks |

---

## Quick Reference

```bash
# Full quality check before commit
./gradlew lintFix test

# Run specific test
./gradlew test --tests "*ClassName*test name*"

# Debug test output
./gradlew test --tests "*Test*" --console=plain --info
```
