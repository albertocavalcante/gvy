# Groovy LSP Architecture

> **Last Updated:** December 21, 2025\
> **Target LSP Version:** 3.17

---

## Overview

Groovy LSP is a modular Language Server Protocol implementation for Apache Groovy. It provides IDE features like
completion, navigation, and diagnostics for Groovy projects, with specialized support for frameworks like Jenkins
Pipelines and Spock.

---

## Module Structure

```
groovy-lsp/
├── groovy-lsp/           # Main LSP server and providers
├── parser/               # Groovy parsing libraries
│   ├── native/           # Groovy native AST coupling
│   └── core/             # JavaParser-inspired API
├── groovy-common/        # Shared utilities
├── groovy-formatter/     # Code formatting (OpenRewrite)
├── groovy-diagnostics/   # Linting and error detection
│   ├── api/              # Diagnostic provider interface
│   └── codenarc/         # CodeNarc integration
├── groovy-jenkins/       # Jenkins Pipeline support
├── groovy-spock/         # Spock Framework support
├── groovy-gdsl/          # GDSL script execution
├── groovy-build-tool/    # Build server integration (BSP)
└── tests/                # E2E and integration tests
```

### Dependency Graph

```
                ┌─────────────────┐
                │   groovy-lsp    │
                │  (LSP Server)   │
                └────────┬────────┘
                         │
       ┌─────────────────┼─────────────────┐
       │                 │                 │
       ▼                 ▼                 ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│groovy-jenkins│  │ groovy-spock │  │groovy-build- │
│              │  │              │  │    tool      │
└──────┬───────┘  └──────┬───────┘  └──────┬───────┘
       │                 │                 │
       └────────┬────────┴─────────────────┘
                │
                ▼
       ┌────────────────┐
       │ parser/native  │
       │ groovy-common  │
       └────────┬───────┘
                │
                ▼
       ┌────────────────┐
       │  groovy-gdsl   │
       └────────────────┘
                │
                ▼
       ┌────────────────┐
       │ groovy-        │
       │ diagnostics    │
       └────────────────┘
```

---

## Module Details

### groovy-lsp (Core Server)

The main LSP server implementation using LSP4J.

**Key Components:**

| Component                   | Purpose                      |
| --------------------------- | ---------------------------- |
| `GroovyLanguageServer`      | LSP server entry point       |
| `GroovyTextDocumentService` | Handles document operations  |
| `GroovyWorkspaceService`    | Handles workspace operations |
| `DocumentManager`           | Manages open documents       |
| `WorkspaceIndex`            | Indexes workspace symbols    |

**Providers:**

| Provider                 | LSP Method                    | Status |
| ------------------------ | ----------------------------- | ------ |
| `CompletionProvider`     | `textDocument/completion`     | ✅     |
| `HoverProvider`          | `textDocument/hover`          | ✅     |
| `DefinitionProvider`     | `textDocument/definition`     | ✅     |
| `TypeDefinitionProvider` | `textDocument/typeDefinition` | ✅     |
| `ReferenceProvider`      | `textDocument/references`     | ✅     |
| `SignatureHelpProvider`  | `textDocument/signatureHelp`  | ✅     |
| `CodeActionProvider`     | `textDocument/codeAction`     | ⏳     |
| `RenameProvider`         | `textDocument/rename`         | ⏳     |
| `SemanticTokenProvider`  | `textDocument/semanticTokens` | ⏳     |

**Directory Structure:**

```
groovy-lsp/src/main/kotlin/
├── server/
│   ├── GroovyLanguageServer.kt
│   ├── GroovyTextDocumentService.kt
│   └── GroovyWorkspaceService.kt
├── providers/
│   ├── completion/
│   ├── hover/
│   ├── definition/
│   ├── references/
│   ├── codeaction/
│   ├── diagnostics/
│   └── symbols/
├── services/
│   ├── DocumentManager.kt
│   ├── WorkspaceIndex.kt
│   └── ConfigurationService.kt
└── util/
```

---

### parser/native

AST parsing tightly coupled to Groovy's native types (`ClassNode`, `MethodNode`, etc.).

**Key Components:**

| Component             | Purpose                         |
| --------------------- | ------------------------------- |
| `GroovyParser`        | Parses Groovy source to AST     |
| `ErrorRecoveryParser` | Handles partial/broken code     |
| `AstUtils`            | AST traversal utilities         |
| `PositionNodeVisitor` | Position-aware AST visitor      |
| `RecursiveAstVisitor` | AST traversal + parent tracking |

---

### parser/core

Standalone parsing library with JavaParser-inspired API.

**Key Components:**

| Component              | Purpose                      |
| ---------------------- | ---------------------------- |
| `StaticGroovyParser`   | Static parsing utility       |
| `GroovyParser`         | Configurable parser instance |
| `VoidVisitorAdapter`   | Type-safe visitor pattern    |
| `GroovySymbolResolver` | Symbol resolution            |
| `TypeSolver`           | Type resolution strategies   |

See [parser/README.md](../parser/README.md) for details.

---

### groovy-diagnostics

Diagnostic providers for error detection and linting.

**Architecture:**

```
groovy-diagnostics/
├── api/                           # Provider interface
│   └── DiagnosticProvider.kt      # Abstract provider
└── codenarc/                      # CodeNarc implementation
    ├── CodeNarcDiagnosticProvider.kt
    ├── RuleLoader.kt
    └── JenkinsCpsRules.kt
```

**Diagnostic Flow:**

```
Source Code
    │
    ▼
┌────────────────┐
│ Groovy Compiler│ ──► Syntax/Type Errors
└────────────────┘
    │
    ▼
┌────────────────┐
│   CodeNarc     │ ──► Style/Best Practice Warnings
└────────────────┘
    │
    ▼
┌────────────────┐
│ Custom Rules   │ ──► Domain-Specific Diagnostics
│ (Jenkins CPS)  │
└────────────────┘
    │
    ▼
publishDiagnostics
```

---

### groovy-jenkins

Jenkins Pipeline support with step completion and diagnostics.

**Key Components:**

| Component                       | Purpose                     |
| ------------------------------- | --------------------------- |
| `JenkinsFileDetector`           | Detects Jenkinsfiles        |
| `JenkinsContext`                | Pipeline context management |
| `JenkinsStepCompletionProvider` | Step/global var completion  |
| `StableStepDefinitions`         | Hardcoded core steps        |
| `GdslParser`                    | Parse Jenkins GDSL output   |
| `VarsGlobalVariableProvider`    | Shared library vars         |

**Metadata Hierarchy:**

```
Priority (high to low):
1. User Override (.groovy-lsp/jenkins.json)
2. Stable Step Definitions (hardcoded)
3. Versioned Metadata (per LTS)
4. Dynamic Classpath Scan
5. Bundled Metadata (fallback)
```

[→ Jenkins IntelliSense Architecture](JENKINS_INTELLISENSE_ARCHITECTURE.md)\
[→ Roadmap](roadmap/README.md)

---

### groovy-spock

Spock testing framework support.

**Key Components:**

| Component                 | Purpose                        |
| ------------------------- | ------------------------------ |
| `SpockSpecDetector`       | Detects Spock specifications   |
| `SpockBlockIndex`         | Indexes given/when/then blocks |
| `SpockCompletionProvider` | Spock-aware completions        |

[→ Spock Support Documentation](SPOCK_SUPPORT.md)

---

### groovy-gdsl

GDSL script execution for dynamic DSL support.

**Key Components:**

| Component         | Purpose                       |
| ----------------- | ----------------------------- |
| `GdslScript`      | Base class for GDSL scripts   |
| `GdslExecutor`    | Executes GDSL via GroovyShell |
| `GdslContributor` | Captures DSL contributions    |
| `GdslLoader`      | Loads bundled GDSL files      |

[→ GDSL Execution Engine Spec](roadmap/specs/GDSL_EXECUTION_ENGINE.md)

---

### groovy-build-tool

Build server integration for project configuration.

**Supported Protocols:**

| Protocol                    | Status | Use Case         |
| --------------------------- | ------ | ---------------- |
| BSP (Build Server Protocol) | ✅     | Bazel, sbt, Mill |
| Gradle Build Server         | ⏳     | Gradle projects  |

[→ BSP Integration](BSP_CLIENT_IMPLEMENTATION.md)

---

## Data Flow

### Completion Request Flow

```
Client                    LSP Server                  Providers
  │                           │                           │
  │  textDocument/completion  │                           │
  │─────────────────────────▶│                           │
  │                           │                           │
  │                           │  getDocumentAST()         │
  │                           │──────────────────────────▶│
  │                           │                           │
  │                           │  analyzeContext()         │
  │                           │──────────────────────────▶│
  │                           │                           │
  │                           │  getCompletions()         │
  │                           │──────────────────────────▶│
  │                           │                           │
  │                           │◀─ CompletionItem[]        │
  │◀─ CompletionList          │                           │
  │                           │                           │
```

### Document Change Flow

```
Client                    LSP Server                  Services
  │                           │                           │
  │  textDocument/didChange   │                           │
  │─────────────────────────▶│                           │
  │                           │                           │
  │                           │  updateDocument()         │
  │                           │──────────────────────────▶│
  │                           │                           │
  │                           │  recompileDocument()      │
  │                           │──────────────────────────▶│
  │                           │                           │
  │                           │  getDiagnostics()         │
  │                           │──────────────────────────▶│
  │                           │                           │
  │◀─ publishDiagnostics      │                           │
  │                           │                           │
```

---

## Configuration

### Server Configuration

```json
{
  "groovy": {
    "classpath": ["lib/*.jar"],
    "sourceDirectories": ["src/main/groovy"],
    "formatting": {
      "tabSize": 4,
      "insertSpaces": true
    },
    "diagnostics": {
      "codenarc": {
        "enabled": true,
        "rulesets": ["rulesets/basic.xml"]
      }
    },
    "jenkins": {
      "enabled": true,
      "filePatterns": ["Jenkinsfile", "*.jenkinsfile"],
      "sharedLibraries": []
    }
  }
}
```

### Jenkins Configuration

```json
// .groovy-lsp/jenkins.json
{
  "jenkinsVersion": "2.479.3",
  "gdslFile": ".jenkins/pipeline.gdsl",
  "plugins": ["workflow-aggregator", "git"],
  "metadataOverrides": {
    "customStep": {
      "plugin": "my-plugin",
      "parameters": {}
    }
  }
}
```

---

## Extension Points

### Custom Completion Providers

```kotlin
interface CompletionContributor {
    fun isApplicable(context: CompletionContext): Boolean
    fun contribute(context: CompletionContext): List<CompletionItem>
}

// Register via ServiceLoader
class MyCompletionContributor : CompletionContributor {
    override fun isApplicable(context: CompletionContext) = 
        context.isJenkinsFile && context.enclosingBlock == "steps"
    
    override fun contribute(context: CompletionContext) = 
        listOf(/* custom completions */)
}
```

### Custom Diagnostic Providers

```kotlin
interface DiagnosticProvider {
    fun getDiagnostics(document: Document): List<Diagnostic>
}

// Register via DiagnosticProviderRegistry
class MyDiagnosticProvider : DiagnosticProvider {
    override fun getDiagnostics(document: Document): List<Diagnostic> {
        // Custom diagnostic logic
    }
}
```

---

## Performance Considerations

### Caching Strategy

| Cache           | Scope        | Invalidation        |
| --------------- | ------------ | ------------------- |
| AST Cache       | Per-document | On document change  |
| Symbol Index    | Workspace    | On file change      |
| Classpath Types | Session      | On config change    |
| GDSL Results    | Session      | On GDSL file change |

### Lazy Loading

- AST parsing: On-demand per document
- Symbol indexing: Background on workspace open
- Classpath scanning: Deferred until needed

### Memory Management

- Soft references for AST cache
- LRU eviction for large caches
- Explicit cleanup on document close

---

## Testing Architecture

### Test Categories

| Category    | Location      | Purpose                 |
| ----------- | ------------- | ----------------------- |
| Unit Tests  | `*/src/test/` | Component isolation     |
| Integration | `tests/e2e/`  | LSP protocol compliance |
| E2E         | `tests/e2e/`  | Real editor scenarios   |
| Property    | `*/src/test/` | Edge case coverage      |

### Test Utilities

```kotlin
// tests/lsp-client/
class TestLspClient {
    fun initialize(workspacePath: Path): InitializeResult
    fun completion(uri: String, line: Int, character: Int): CompletionList
    fun hover(uri: String, line: Int, character: Int): Hover?
    fun definition(uri: String, line: Int, character: Int): List<Location>
}
```

---

## Future Architecture Goals

1. **Plugin System**: Load extensions at runtime
2. **Incremental Parsing**: Parse only changed regions
3. **Multi-Process**: Offload heavy operations
4. **Remote Development**: Support over SSH/containers

---

## References

- [LSP Specification 3.17](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/)
- [LSP4J Documentation](https://github.com/eclipse-lsp4j/lsp4j)
- [Groovy Documentation](https://groovy-lang.org/documentation.html)

---

_Last updated: December 21, 2025_
