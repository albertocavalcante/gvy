# Semantics Module Design

This document describes the design of the `semantics` module, which provides semantic analysis for Groovy source code
based on the **SemanticDB** specification.

## Architecture Overview

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  Groovy Source  │────▶│  Type Inference  │────▶│ SemanticDocument│
│     Files       │     │   & Analysis     │     │   (per-file)    │
└─────────────────┘     └──────────────────┘     └────────┬────────┘
                                                          │
                                                          ▼
                                                ┌─────────────────┐
                                                │ GroovySemanticDB│
                                                │  (workspace DB) │
                                                └────────┬────────┘
                                                          │
                        ┌─────────────────────────────────┼─────────────────────────────────┐
                        │                                 │                                 │
                        ▼                                 ▼                                 ▼
               ┌────────────────┐              ┌─────────────────┐              ┌─────────────────┐
               │ Go-to-Definition│              │ Find References │              │  Hover / Types  │
               └────────────────┘              └─────────────────┘              └─────────────────┘
```

## What is SemanticDB?

**SemanticDB is a data model and specification for semantic information about programs.**

It was created by the [Scalameta](https://scalameta.org/) project and is now used by major tools including
[Metals](https://scalameta.org/metals/) (Scala LSP), [Scalafix](https://scalacenter.github.io/scalafix/) (refactoring),
and [SCIP](https://sourcegraph.com/docs/code-search/code-navigation/writing_an_indexer) (Sourcegraph code intelligence).

### Specification Links

- **Guide**: https://scalameta.org/docs/semanticdb/guide.html
- **Specification**: https://scalameta.org/docs/semanticdb/specification.html
- **Protobuf Schema**: https://github.com/scalameta/scalameta/blob/main/semanticdb/semanticdb/semanticdb.proto

### Core Concepts

SemanticDB defines three primary concepts:

| Concept               | Description                                     | Example                                |
| --------------------- | ----------------------------------------------- | -------------------------------------- |
| **Symbol**            | Unique identifier for a definition              | `com/example/MyClass#myMethod().`      |
| **SymbolInformation** | Metadata about a symbol (kind, type, modifiers) | Method `myMethod` returns `String`     |
| **SymbolOccurrence**  | Location where a symbol appears                 | Line 42, columns 10-18, role=REFERENCE |

### Symbol Format

Symbols follow a hierarchical naming convention:

```
# Package
com/example/

# Class
com/example/MyClass#

# Method
com/example/MyClass#myMethod().

# Field
com/example/MyClass#myField.

# Parameter
com/example/MyClass#myMethod().(param)

# Local variable
com/example/MyClass#myMethod().local0
```

## Why SemanticDB?

### 1. Decoupling Production and Consumption

SemanticDB establishes a clear boundary between tools that **produce** semantic information (compilers, analyzers) and
tools that **consume** it (IDEs, refactoring tools).

```
┌─────────────┐                          ┌─────────────┐
│  Producers  │                          │  Consumers  │
├─────────────┤     ┌──────────────┐     ├─────────────┤
│ Groovy AST  │────▶│  SemanticDB  │────▶│ LSP Server  │
│ Type Checker│     │   (common)   │     │ Refactoring │
│ Compiler    │     └──────────────┘     │ Code Search │
└─────────────┘                          └─────────────┘
```

### 2. Per-File Granularity

Each source file produces one `SemanticDocument`. This enables:

- **Incremental updates**: Only re-analyze changed files
- **Parallel processing**: Analyze files independently
- **Low memory overhead**: Load documents on-demand

### 3. Cross-Language Compatibility

SemanticDB supports multiple languages (Scala, Java, and now Groovy). This enables:

- **Mixed codebases**: Navigate between Groovy and Java seamlessly
- **Shared tooling**: Reuse indexers, analyzers, and UI components
- **Future SCIP integration**: Export to Sourcegraph code intelligence

### 4. Well-Defined Specification

Unlike ad-hoc internal representations, SemanticDB provides:

- **Documented schema**: Clear protobuf definitions
- **Versioning**: Schema evolution with backward compatibility
- **Ecosystem**: Existing tools, libraries, and best practices

## Module Structure

```
semantics/
├── core/           # Core SemanticDB implementation
│   └── src/main/kotlin/.../semantics/
│       ├── db/
│       │   ├── GroovySemanticDB.kt    # Workspace-wide database
│       │   ├── SemanticDocument.kt    # Per-file semantic info
│       │   └── SemanticDocumentBuilder.kt
│       ├── calculator/                 # Type inference
│       │   ├── TypeCalculator.kt
│       │   ├── TypeContext.kt
│       │   └── impl/                   # Expression-specific calculators
│       ├── SemanticType.kt            # Unified type representation
│       ├── TypeLub.kt                 # Least upper bound computation
│       └── delegation/                 # Groovy delegation support
├── dsl/            # DSL-specific semantic extensions
├── native/         # Native (GraalVM) integration
└── openrewrite/    # OpenRewrite type mapping
```

## Data Model

### SemanticDocument

Per-file container for semantic information:

```kotlin
data class SemanticDocument(
    val uri: URI,                      // File identifier
    val symbols: List<SymbolInfo>,     // Definitions in this file
    val occurrences: List<SymbolOccurrence>  // All symbol usages
)
```

### SymbolInfo

Information about a defined symbol:

```kotlin
data class SymbolInfo(
    val symbol: String,        // Unique ID (SemanticDB format)
    val kind: SymbolKind,      // CLASS, METHOD, FIELD, etc.
    val range: Range,          // Source location
    val name: String,          // Simple name
    val owner: String?,        // Parent symbol ID
    val type: SemanticType?    // Inferred type
)
```

### SymbolOccurrence

A reference to a symbol at a specific location:

```kotlin
data class SymbolOccurrence(
    val symbol: String,        // References SymbolInfo.symbol
    val range: Range,          // Source location
    val role: OccurrenceRole   // DEFINITION, REFERENCE, CALL, etc.
)
```

### SemanticType

Unified type representation for Groovy's type system:

```kotlin
sealed interface SemanticType {
    data class Known(val fqn: String, val typeArgs: List<SemanticType>)
    data class Primitive(val kind: PrimitiveKind)
    data class Dynamic(val hint: String?)      // Groovy's 'def'
    data class Unknown(val reason: String)     // Inference failed
    data class Union(val types: Set<SemanticType>)  // Multi-branch
    data class Array(val componentType: SemanticType)
    data object Null
}
```

## GroovySemanticDB

The workspace-wide database that indexes all `SemanticDocument` instances:

```kotlin
class GroovySemanticDB {
    // Primary storage
    fun getDocument(uri: URI): SemanticDocument?
    fun updateDocument(uri: URI, doc: SemanticDocument)
    fun removeDocument(uri: URI)

    // Symbol lookup (with LRU cache)
    fun findSymbolDefinition(symbolId: String): Pair<URI, SymbolInfo>?
    fun findAllOccurrences(symbolId: String): List<Pair<URI, SymbolOccurrence>>

    // Position-based queries
    fun findSymbolAtPosition(uri: URI, line: Int, column: Int): SymbolInfo?
    fun findOccurrenceAtPosition(uri: URI, line: Int, column: Int): SymbolOccurrence?
}
```

### Performance Optimizations

1. **Concurrent access**: `ConcurrentHashMap` for thread-safe LSP operations
2. **LRU cache**: Frequently accessed symbols cached for fast repeated lookups
3. **Indexed lookups**: Secondary indexes for symbol ID → locations mapping

## Type Inference

The `calculator` package implements expression-level type inference:

```kotlin
interface TypeCalculator<T : Expression> {
    val expressionType: KClass<T>
    fun calculate(expr: T, context: TypeContext): SemanticType
}
```

Supported expressions:

| Calculator                     | Handles                               |
| ------------------------------ | ------------------------------------- |
| `ConstantExpressionCalculator` | Literals (strings, numbers, booleans) |
| `VariableExpressionCalculator` | Variable references                   |
| `MethodCallCalculator`         | Method invocations                    |
| `PropertyAccessCalculator`     | Property/field access                 |
| `BinaryExpressionCalculator`   | Binary operators (+, -, ==, etc.)     |
| `TernaryExpressionCalculator`  | Ternary `?:` expressions              |
| `ClosureExpressionCalculator`  | Closure literals                      |
| `ListExpressionCalculator`     | List literals `[1, 2, 3]`             |
| `MapExpressionCalculator`      | Map literals `[a: 1, b: 2]`           |

## LSP Integration

The semantics module powers these LSP features:

| Feature               | SemanticDB Usage                 |
| --------------------- | -------------------------------- |
| **Go to Definition**  | `findSymbolDefinition(symbolId)` |
| **Find References**   | `findAllOccurrences(symbolId)`   |
| **Hover**             | `SymbolInfo.type` → display type |
| **Document Symbols**  | `SemanticDocument.symbols`       |
| **Workspace Symbols** | Query across all documents       |

## Future: SCIP Export

SemanticDB's design aligns with [SCIP](https://github.com/sourcegraph/scip) (Sourcegraph's Code Intelligence Protocol).
Future work could export `GroovySemanticDB` to SCIP format for Sourcegraph integration.

See [scip-java design](https://github.com/sourcegraph/scip-java/blob/main/docs/design.md) for a reference implementation
of SemanticDB → SCIP conversion.

## References

- [SemanticDB Guide](https://scalameta.org/docs/semanticdb/guide.html)
- [SemanticDB Specification](https://scalameta.org/docs/semanticdb/specification.html)
- [Metals Architecture](https://scalameta.org/metals/docs/)
- [SCIP-Java Design](https://github.com/sourcegraph/scip-java/blob/main/docs/design.md)
- [SCIP Specification](https://github.com/sourcegraph/scip)
