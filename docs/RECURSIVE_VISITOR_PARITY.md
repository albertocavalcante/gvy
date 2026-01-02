# Recursive Visitor Parity Status

> NOTE: Legacy visitor support has been removed and `RecursiveAstVisitor` is now the only implementation. This document
> is retained for historical context.

This document tracks the parity status between the new `RecursiveAstVisitor` and the legacy `NodeVisitorDelegate`.

## Overview

The `RecursiveAstVisitor` is designed to be a drop-in replacement for `NodeVisitorDelegate`, with improved architecture
using composition over inheritance. During the migration period, both visitors ran in parallel under a feature flag to
ensure behavioral compatibility.

## Parity Score: 100%

The recursive visitor achieves 100% parity with the delegate visitor for all common Groovy constructs. The remaining
differences are intentional improvements where the recursive visitor's behavior is more correct according to Groovy's
AST model.

## Known Differences

### 1. Script-Level ExpressionStatement Parents ✅ INTENTIONAL IMPROVEMENT

**Status**: Intentional difference - Recursive visitor behavior is more correct

**Delegate Behavior**:

- Leaves top-level script statements with `null` parent
- Example: In a script like `def x = 1`, the ExpressionStatement has no parent

**Recursive Behavior**:

- Parents top-level statements to the script ClassNode
- Example: The ExpressionStatement correctly identifies the script class as its parent

**Rationale**: In Groovy's AST model, scripts are compiled into classes. The script ClassNode IS the actual parent of
top-level statements. The delegate's null parent is a bug from incomplete tracking in the inheritance-based approach.

**Impact**:

- None for LSP features (hover, completion, go-to-definition all work correctly)
- Recursive behavior provides more accurate parent chains for advanced analyses

**Action**: Accept recursive behavior as correct. The delegate's behavior will not be "fixed" - instead, we'll migrate
to the recursive visitor and deprecate the delegate.

### 2. DeclarationExpression Traversal Order 📝 IMPLEMENTATION DETAIL

**Status**: Minor implementation detail - no functional impact

**Difference**: Both visitors track the same set of nodes, but the recursive visitor explicitly visits left and right
expressions:

```kotlin
// Recursive visitor
override fun visitDeclarationExpression(expression: DeclarationExpression) {
    track(expression) {
        expression.leftExpression.visit(this)
        expression.rightExpression.visit(this)
    }
}

// Delegate visitor
override fun visitDeclarationExpression(expression: DeclarationExpression) {
    pushNode(expression)
    try {
        super.visitDeclarationExpression(expression)
    } finally {
        popNode()
    }
}
```

**Impact**:

- Both track identical nodes
- Parent relationships are the same
- Visit order may differ slightly (no observable effect)

**Action**: No action needed. Both approaches are valid.

## Test Coverage

### ✅ Fully Covered

- **Classes, Fields, Methods**: All class structure elements tracked correctly
- **Annotations**: Including nested annotations, annotation members, and default parameters
- **Control Flow**: if/else, switch/case, for, while, do-while, break, continue
- **Try-Catch-Finally**: Including manual CatchStatement tracking (no dedicated visitor method)
- **Expressions**: Binary, ternary, method calls, property access, closures
- **Complex Declarations**: GStrings, maps, lists, arithmetic expressions
- **Parameters**: Method parameters, closure parameters, catch parameters
- **Imports**: All import types (star, static, static star)
- **Spread Operators**: `def list = [*items1, *items2]`
- **Safe Navigation**: `obj?.method()?.field` (tracked as PropertyExpression with isSafe=true)
- **Elvis Operator**: `value ?: "default"` (tracked as TernaryExpression/ElvisOperatorExpression)
- **Range Expressions**: `(0..10).each { ... }`
- **List/Map Expressions**: Explicit tracking for collection literals

### ⏳ Pending Coverage (Future Work)

- **AST Transformations**: @CompileStatic, @Immutable, etc.
- **Script vs Class Mode**: Differences in top-level code handling

## Parity Test Results

All 6 parity tests passing (including operator test suite):

| Test                                    | Status  | Notes                                      |
| --------------------------------------- | ------- | ------------------------------------------ |
| Class with fields, methods, annotations | ✅ PASS | Validates comprehensive class structure    |
| Control flow constructs                 | ✅ PASS | Switch, loops, break/continue              |
| Parameter and field annotations         | ✅ PASS | Annotation tracking on various targets     |
| Nested annotations and default params   | ✅ PASS | Complex annotation scenarios               |
| Complex declaration expressions         | ✅ PASS | Binary ops, ternary, GStrings, collections |
| Groovy operator expressions             | ✅ PASS | All operators tracked and validated        |

## Migration Status

Migration complete:

- `RecursiveAstVisitor` is the default and only visitor.
- Legacy delegate visitor removed.
- Feature flag removed.

## Performance Characteristics

Expected improvements with recursive visitor:

- **Reduced Virtual Dispatch**: No inheritance chain means less method lookup overhead
- **Better Cache Locality**: Explicit traversal can be optimized for cache efficiency
- **Simpler Call Stack**: Recursive approach has cleaner stack traces for debugging

Benchmarking results (pending Phase 2):

- TBD: Parse time comparison on large files
- TBD: Memory usage comparison
- TBD: GC pressure analysis

## For Contributors

When adding new AST node types or visitor methods:

1. **Add to RecursiveAstVisitor first**: Implement in the recursive visitor with explicit tracking
2. **Add parity test**: Create test case validating identical behavior
3. **Document differences**: If intentional differences exist, document here

## References

- **Implementation**: `parser/native/src/main/kotlin/.../RecursiveAstVisitor.kt`
- **Parity Tests**: `parser/native/src/test/kotlin/.../RecursiveVisitorTraversalTest.kt`
- **Refactoring Plan**: `PARSER_REFACTORING_PLAN.md`
