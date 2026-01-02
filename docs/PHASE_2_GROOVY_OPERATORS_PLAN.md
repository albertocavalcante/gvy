# Phase 2: RecursiveAstVisitor - Groovy Operator Support

## Status: ✅ COMPLETED

**Completion Date**: 2025-11-29 **Final Parity Score**: 100% **Tests Added**: 6 operator tests in
RecursiveVisitorGroovyOperatorsTest.kt **Visitor Methods Added**: 6 methods in RecursiveAstVisitor, 6 in
NodeVisitorDelegate

## Overview

Complete the RecursiveAstVisitor implementation by adding support for Groovy-specific operators and expressions that are
currently missing. This closes the gap identified in `RECURSIVE_VISITOR_PARITY.md` and achieves 100% AST node coverage
for common Groovy constructs.

## Current Status

**Merged in PR #132**:

- ✅ RecursiveAstVisitor with 98% parity
- ✅ Basic expressions (binary, method calls, properties, closures)
- ✅ Control flow (if/switch/loops/try-catch)
- ✅ Annotations and parameters
- ✅ Feature flag integration

**Missing Coverage** (from RECURSIVE_VISITOR_PARITY.md):

- ⏳ Spread operators (`*args`, `[*list1, *list2]`)
- ⏳ Safe navigation (`obj?.method()?.field`)
- ⏳ Elvis operator (`value ?: "default"`)
- ⏳ Range expressions (`0..10`, `'a'..'z'`)
- ⏳ List expressions (explicit tracking)
- ⏳ Map expressions (explicit tracking)

## Goals

### Primary Goal

Add complete support for Groovy-specific operators to RecursiveAstVisitor, maintaining 100% parity with
NodeVisitorDelegate.

### Success Criteria

1. All Groovy operators tracked correctly
2. Parity tests pass for all operator types
3. Parent relationships preserved correctly
4. No performance regressions
5. Comprehensive test coverage

## Implementation Plan

### Step 1: Spread Operators (1-2 hours)

**AST Nodes**:

- `SpreadExpression` - for spread in method calls: `foo(*args)`
- `SpreadMapExpression` - for spread in maps: `[*:map1]`

**Implementation**:

```kotlin
// In RecursiveAstVisitor.codeVisitor
override fun visitSpreadExpression(expression: SpreadExpression) {
    track(expression) { super.visitSpreadExpression(expression) }
}

override fun visitSpreadMapExpression(expression: SpreadMapExpression) {
    track(expression) { super.visitSpreadMapExpression(expression) }
}
```

**Test Cases**:

```groovy
// Spread in list
def list = [*items1, *items2, 3]

// Spread in method call
println(*args)

// Spread in map
def map = [*:map1, key: value]
```

### Step 2: Safe Navigation (1-2 hours)

**AST Node**:

- `PropertyExpression` with `isSafe()` flag

**Implementation**: Already tracking PropertyExpression, just need to verify safe navigation variant is tracked.

**Test Cases**:

```groovy
// Safe navigation chain
def result = obj?.method()?.field?.toString()

// With null coalescing
def value = obj?.field ?: "default"
```

### Step 3: Elvis Operator (1 hour)

**AST Node**:

- `ElvisOperatorExpression` (special binary expression)

**Implementation**:

```kotlin
override fun visitElvisOperatorExpression(expression: ElvisOperatorExpression) {
    track(expression) { super.visitElvisOperatorExpression(expression) }
}
```

**Test Cases**:

```groovy
// Simple elvis
def name = input ?: "default"

// Chained elvis
def value = a ?: b ?: c ?: "fallback"

// Elvis with safe navigation
def result = obj?.field ?: defaultValue
```

### Step 4: Range Expressions (1 hour)

**AST Node**:

- `RangeExpression` - for ranges like `1..10`, `'a'..'z'`

**Implementation**:

```kotlin
override fun visitRangeExpression(expression: RangeExpression) {
    track(expression) {
        expression.from?.visit(this)
        expression.to?.visit(this)
    }
}
```

**Test Cases**:

```groovy
// Integer range
(0..10).each { println it }

// Exclusive range
(0..<10).each { println it }

// Character range
('a'..'z').each { println it }

// Reverse range
(10..1).each { println it }
```

### Step 5: List & Map Expressions (30 min)

**AST Nodes**:

- `ListExpression` - explicit list literals `[1, 2, 3]`
- `MapExpression` - explicit map literals `[key: value]`

**Implementation**:

```kotlin
override fun visitListExpression(expression: ListExpression) {
    track(expression) { super.visitListExpression(expression) }
}

override fun visitMapExpression(expression: MapExpression) {
    track(expression) { super.visitMapExpression(expression) }
}
```

**Test Cases**:

```groovy
// List literals
def list = [1, 2, 3]
def nested = [[1, 2], [3, 4]]

// Map literals
def map = [key: "value", count: 42]
def nested = [outer: [inner: "value"]]
```

### Step 6: Comprehensive Parity Tests (2 hours)

**New Test File**: `RecursiveVisitorGroovyOperatorsTest.kt`

Tests organized by operator type:

```kotlin
@Test
fun `spread operators tracked correctly`() { ... }

@Test
fun `safe navigation tracked correctly`() { ... }

@Test
fun `elvis operator tracked correctly`() { ... }

@Test
fun `range expressions tracked correctly`() { ... }

@Test
fun `list and map expressions tracked correctly`() { ... }

@Test
fun `combined groovy operators parity`() {
    // Test complex combination of all operators
}
```

Each test validates:

- Node is tracked
- Parent relationship is correct
- Parity with NodeVisitorDelegate

## Testing Strategy

### Unit Tests

- Individual test for each operator type
- Verify tracking and parent relationships
- Nested operator combinations

### Parity Tests

- Compare RecursiveAstVisitor vs NodeVisitorDelegate
- Validate identical node sets
- Validate identical parent relationships

### Integration Tests

- Real-world Groovy code samples
- Complex operator combinations
- Edge cases (null values, chaining, etc.)

## Expected Changes

### Files to Modify

1. **RecursiveAstVisitor.kt** (+50 LOC)
   - Add visitor methods for each operator
   - Ensure proper tracking and traversal

2. **RecursiveVisitorGroovyOperatorsTest.kt** (NEW, ~400 LOC)
   - Comprehensive test coverage
   - Parity validation

3. **RECURSIVE_VISITOR_PARITY.md** (~20 LOC changed)
   - Update coverage section
   - Move operators from "Pending" to "Covered"
   - Update parity score to 100%

4. **PARSER_REFACTORING_PROGRESS.md** (~10 LOC)
   - Document completion of operator support

### Total Impact

- **Implementation**: ~50 LOC
- **Tests**: ~400 LOC
- **Documentation**: ~30 LOC
- **Total**: ~480 LOC

## Risk Assessment

### Risks

- **Low**: These are well-defined AST node types
- **Low**: Pattern already established in existing code
- **Low**: Parity tests will catch any discrepancies

### Mitigation

- Follow existing visitor patterns exactly
- Add parity tests before implementation (TDD)
- Run full test suite before/after
- Compare with NodeVisitorDelegate behavior

## Success Metrics

### Before (Current State)

- Parity Score: 98%
- Missing Operators: 6 types
- Groovy Feature Coverage: Incomplete

### After (Target State)

- Parity Score: 100%
- Missing Operators: 0
- Groovy Feature Coverage: Complete for common constructs
- All tests passing
- No performance regressions

## Timeline

**Estimated Time**: 6-8 hours **Actual Time**: ~3 hours

| Task                                | Time | Status  |
| ----------------------------------- | ---- | ------- |
| Spread operators implementation     | 2h   | ✅ Done |
| Safe navigation verification        | 1h   | ✅ Done |
| Elvis operator implementation       | 1h   | ✅ Done |
| Range expressions implementation    | 1h   | ✅ Done |
| List/Map expressions implementation | 30m  | ✅ Done |
| Parity tests                        | 2h   | ✅ Done |
| Documentation updates               | 30m  | ✅ Done |
| Testing & validation                | 1h   | ✅ Done |

## Next Steps After This Phase

Once operator support is complete:

1. **Performance Benchmarking** (Phase 3)
   - Compare recursive vs delegate visitor performance
   - Memory usage analysis
   - Identify optimization opportunities

2. **LSP Integration** (Phase 4)
   - Wire recursive visitor into hover provider
   - Wire into completion provider
   - Wire into definition provider

3. **Default Switch** (Phase 5)
   - Make recursive visitor the default
   - Deprecate NodeVisitorDelegate
   - Migration guide for external users

## References

- **RECURSIVE_VISITOR_PARITY.md**: Current parity status
- **PARSER_REFACTORING_PLAN.md**: Overall refactoring vision
- **PARSER_REFACTORING_PROGRESS.md**: Step 1 progress notes
- **RecursiveAstVisitor.kt**: Implementation reference
- **RecursiveVisitorParityTest.kt**: Testing patterns

---

**Ready to implement**: Let's achieve 100% Groovy operator coverage! 🚀
