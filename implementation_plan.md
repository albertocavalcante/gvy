# Implementation Plan for #552

## Goal

Implement `ParserProvider` interface from `parser/api` in both `parser/native` and `parser/core` modules, enabling
parser-agnostic LSP integration.

## Changes

### 1. Update Build Dependencies

- `parser/native/build.gradle.kts`: Add `api(project(":parser:api"))`
- `parser/core/build.gradle.kts`: Add `api(project(":parser:api"))`

### 2. parser/native Implementation

- `NativeParserProvider.kt`: Implements `ParserProvider`, wraps `GroovyParserFacade`
- `NativeParseUnit.kt`: Implements `ParseUnit`, wraps native `ParseResult`
- Maps native diagnostics/symbols to `parser/api` model types

### 3. parser/core Implementation

- `CoreParserProvider.kt`: Implements `ParserProvider`, wraps `GroovyParser`
- `CoreParseUnit.kt`: Implements `ParseUnit`, wraps core `ParseResult<CompilationUnit>`
- Maps core AST/problems to `parser/api` model types

### 4. Capabilities

```kotlin
// parser/native
ParserCapabilities(
    supportsErrorRecovery = true,
    supportsCommentPreservation = false,
    supportsSymbolResolution = true,
    supportsRefactoring = false,
)

// parser/core
ParserCapabilities(
    supportsErrorRecovery = true,
    supportsCommentPreservation = true,
    supportsSymbolResolution = true,
    supportsRefactoring = false,
)
```

## Verification

- [ ] `./gradlew :parser:native:test` passes
- [ ] `./gradlew :parser:core:test` passes
- [ ] New tests for `NativeParserProvider` and `CoreParserProvider`
