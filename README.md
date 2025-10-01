# Groovy Language Server

A Language Server Protocol (LSP) implementation for Apache Groovy.

## Status

⚠️ **Work in Progress** - This is an early development version with basic LSP functionality.

## Features

### LSP Feature Matrix

| Feature               | Status | LSP Version | Description                              |
| --------------------- | ------ | ----------- | ---------------------------------------- |
| **Core Features**     |
| Lifecycle             | ✅     | 3.16        | Initialize, shutdown, capabilities       |
| Text Synchronization  | ✅     | 3.16        | Document sync, file watching             |
| Diagnostics           | ✅     | 3.16        | Static analysis via CodeNarc integration |
| **Navigation**        |
| Go to Definition      | ✅     | 3.16        | Navigate to symbol definitions           |
| Find References       | ✅     | 3.16        | Find all symbol references               |
| Document Symbols      | ✅     | 3.16        | Outline view for current file            |
| Workspace Symbols     | ✅     | 3.16        | Search symbols across workspace          |
| Type Definition       | ✅     | 3.16        | Navigate to type definitions             |
| **Code Intelligence** |
| Completion            | ✅     | 3.16        | Code completion with triggers            |
| Hover                 | ✅     | 3.16        | Documentation on hover                   |
| Signature Help        | ✅     | 3.16        | Parameter hints while typing             |
| **Refactoring**       |
| Rename                | ✅     | 3.16        | Symbol renaming                          |
| Code Actions          | ✅     | 3.16        | Quick fixes via CodeNarc                 |
| Document Formatting   | ✅     | 3.16        | Format entire document                   |
| Range Formatting      | ✅     | 3.16        | Format selected text                     |
| **Advanced Features** |
| Semantic Tokens       | ✅     | 3.16        | Enhanced syntax highlighting             |
| Folding Ranges        | ✅     | 3.16        | Code folding support                     |
| **Not Implemented**   |
| Code Lens             | ❌     | 3.16        | Inline actionable commands               |
| Selection Range       | ❌     | 3.16        | Smart text selection                     |
| Document Links        | ❌     | 3.16        | Clickable links in documents             |
| Call Hierarchy        | ❌     | 3.16.1      | Call hierarchy navigation                |
| Type Hierarchy        | ❌     | 3.17        | Type hierarchy navigation                |
| Inlay Hints           | ❌     | 3.17        | Inline type annotations                  |
| Pull Diagnostics      | ❌     | 3.17        | Pull-based diagnostic requests           |

### Additional Features

- **REPL Integration**: Interactive Groovy evaluation with workspace context
- **Gradle Integration**: Automatic dependency resolution from Gradle projects
- **CodeNarc Integration**: Static analysis with configurable rulesets and quick fixes
- **Workspace Compilation**: Cross-file type resolution and analysis
- **Configuration Management**: Runtime configuration updates without restart

## LSP 3.17 Compliance

### Implementation Summary

| Category          | Implemented | Total  | Coverage   |
| ----------------- | ----------- | ------ | ---------- |
| Core Features     | 3/3         | 3      | ✅ 100%    |
| Navigation        | 5/6         | 6      | ⚠️ 83%     |
| Code Intelligence | 3/3         | 3      | ✅ 100%    |
| Refactoring       | 4/4         | 4      | ✅ 100%    |
| Advanced Features | 2/9         | 9      | ❌ 22%     |
| LSP 3.17 Features | 0/4         | 4      | ❌ 0%      |
| **Overall**       | **17/29**   | **29** | **⚠️ 59%** |

### Missing Features by Priority

**High Priority** (Standard LSP features):

- Code Lens (3.16)
- Selection Range (3.16)
- Document Links (3.16)
- Call Hierarchy (3.16.1)

**Medium Priority** (LSP 3.17 features):

- Type Hierarchy
- Inlay Hints
- Pull Diagnostics

**Low Priority**:

- Inline Values (debugging)
- Moniker (cross-repository)
- Notebook Document Support

## Requirements

- Java 17 or higher
- Groovy 4.0+

## Building

```bash
./gradlew build
```

This creates a fat JAR at `build/libs/groovy-lsp-0.1.0-SNAPSHOT.jar`

## Running

### Stdio mode (default)

```bash
java -jar build/libs/groovy-lsp-0.1.0-SNAPSHOT.jar
```

### Socket mode

```bash
java -jar build/libs/groovy-lsp-0.1.0-SNAPSHOT.jar socket 8080
```

## Development

This project uses:

- Kotlin 2.0
- LSP4J for protocol implementation
- Gradle 9.1 for builds

### Running tests

```bash
./gradlew test
```

### Project Structure

```
src/
├── main/kotlin/         # LSP server implementation
├── test/kotlin/         # Unit tests
.spec/                   # Technical specifications
.github/workflows/       # CI/CD configuration
```

## Configuration

The language server supports the following configuration options that can be set in your editor:

### Core Configuration

| Setting                                   | Type                                   | Default       | Description                                                                 |
| ----------------------------------------- | -------------------------------------- | ------------- | --------------------------------------------------------------------------- |
| `groovy.compilation.mode`                 | `"workspace"` \| `"single-file"`       | `"workspace"` | Controls how files are compiled. `workspace` enables cross-file resolution. |
| `groovy.compilation.incrementalThreshold` | number                                 | 50            | File count threshold for incremental compilation                            |
| `groovy.compilation.maxWorkspaceFiles`    | number                                 | 500           | Maximum files to compile together                                           |
| `groovy.server.maxNumberOfProblems`       | number                                 | 100           | Maximum diagnostics per file                                                |
| `groovy.java.home`                        | string                                 | `null`        | Path to Java home (requires Java 17+)                                       |
| `groovy.trace.server`                     | `"off"` \| `"messages"` \| `"verbose"` | `"off"`       | Trace communication with language server                                    |

### REPL Configuration

| Setting                             | Type    | Default | Description                      |
| ----------------------------------- | ------- | ------- | -------------------------------- |
| `groovy.repl.enabled`               | boolean | `true`  | Enable REPL functionality        |
| `groovy.repl.maxSessions`           | number  | 10      | Maximum concurrent REPL sessions |
| `groovy.repl.sessionTimeoutMinutes` | number  | 60      | Session timeout in minutes       |

### CodeNarc Configuration

| Setting                          | Type    | Default | Description                              |
| -------------------------------- | ------- | ------- | ---------------------------------------- |
| `groovy.codenarc.enabled`        | boolean | `true`  | Enable CodeNarc static analysis          |
| `groovy.codenarc.propertiesFile` | string  | `null`  | Path to custom CodeNarc properties file  |
| `groovy.codenarc.autoDetect`     | boolean | `true`  | Auto-detect CodeNarc configuration files |

### Compilation Modes

#### Workspace Mode (Default)

Compiles all Groovy files together, enabling:

- ✅ Cross-file class resolution (classes in same package)
- ✅ Better type inference across files
- ✅ More accurate go-to-definition
- ⚠️ Slower for large workspaces (>500 files)

#### Single-File Mode

Compiles each file independently:

- ✅ Faster compilation
- ✅ Lower memory usage
- ✅ Suitable for very large codebases
- ❌ No cross-file resolution

### Incremental Compilation

For workspaces with fewer than 50 files, full recompilation is performed (simple and fast). For larger workspaces,
incremental compilation is used to improve performance.

The threshold can be adjusted with `groovy.compilation.incrementalThreshold`.

### Example VS Code Settings

```json
{
  "groovy.compilation.mode": "workspace",
  "groovy.compilation.incrementalThreshold": 100,
  "groovy.java.home": "/usr/lib/jvm/java-17-openjdk",
  "groovy.trace.server": "messages",
  "groovy.repl.enabled": true,
  "groovy.codenarc.enabled": true
}
```

## Architecture

### Provider-Based Implementation

The LSP server uses a provider-based architecture with separate implementations for each LSP feature:

| Provider                  | Purpose             | File Location               |
| ------------------------- | ------------------- | --------------------------- |
| `CompletionProvider`      | Code completion     | `providers/completion/`     |
| `HoverProvider`           | Hover information   | `providers/hover/`          |
| `DefinitionProvider`      | Go-to-definition    | `providers/definition/`     |
| `ReferenceProvider`       | Find references     | `providers/references/`     |
| `DocumentSymbolProvider`  | Document outline    | `providers/symbols/`        |
| `WorkspaceSymbolProvider` | Workspace search    | `providers/symbols/`        |
| `RenameProvider`          | Symbol renaming     | `providers/rename/`         |
| `CodeActionProvider`      | Quick fixes         | `providers/codeactions/`    |
| `FormattingProvider`      | Code formatting     | `providers/formatting/`     |
| `FoldingRangeProvider`    | Code folding        | `providers/folding/`        |
| `SemanticTokenProvider`   | Syntax highlighting | `providers/semantictokens/` |
| `SignatureHelpProvider`   | Parameter hints     | `providers/signature/`      |
| `TypeDefinitionProvider`  | Type navigation     | `providers/typedefinition/` |

### Core Services

| Service                       | Purpose                     |
| ----------------------------- | --------------------------- |
| `GroovyLanguageServer`        | Main LSP implementation     |
| `GroovyTextDocumentService`   | Text document operations    |
| `GroovyWorkspaceService`      | Workspace-level operations  |
| `GroovyCompilationService`    | Groovy AST compilation      |
| `WorkspaceCompilationService` | Cross-file compilation      |
| `CodeNarcService`             | Static analysis integration |
| `ReplSessionManager`          | REPL functionality          |

### Dependency Management

| Component                      | Purpose                        |
| ------------------------------ | ------------------------------ |
| `CentralizedDependencyManager` | Classpath management           |
| `SimpleDependencyResolver`     | Gradle dependency resolution   |
| `GradleConnectionPool`         | Gradle Tooling API integration |

## Implementation Status

### Known Limitations

- **Single-file mode**: Limited cross-file type resolution
- **Large workspaces**: Performance may degrade with >500 files
- **Gradle integration**: Requires Gradle Wrapper or local Gradle installation
- **CodeNarc rules**: Limited to built-in rulesets (custom rules not yet supported)

### Stability

| Feature Category      | Status          | Notes                                    |
| --------------------- | --------------- | ---------------------------------------- |
| Core LSP Features     | ✅ Stable       | Lifecycle, text sync, basic operations   |
| Navigation            | ✅ Stable       | Definition, references, symbols          |
| Code Intelligence     | ✅ Stable       | Completion, hover, signatures            |
| Diagnostics           | ✅ Stable       | CodeNarc integration working             |
| Refactoring           | ⚠️ Basic        | Rename and basic code actions            |
| REPL                  | ⚠️ Experimental | Limited to simple expressions            |
| Workspace Compilation | ⚠️ Beta         | May impact performance on large projects |

### Performance Characteristics

| Workspace Size | Compilation Mode        | Expected Performance     |
| -------------- | ----------------------- | ------------------------ |
| < 50 files     | Full compilation        | Fast (< 2s startup)      |
| 50-500 files   | Incremental             | Moderate (2-10s startup) |
| > 500 files    | Single-file recommended | Variable                 |

## Issue Tracking

This project uses a comprehensive label system for issue organization. See
[Issue #31](https://github.com/albertocavalcante/groovy-lsp/issues/31) for the complete label system covering:

- **LSP Protocol Areas**: `lsp/completion`, `lsp/navigation`, `lsp/diagnostics`, etc.
- **Priority Levels**: `P0-critical`, `P1-must`, `P2-should`, `P3-nice`
- **Effort Sizing**: `size/XS`, `size/S`, `size/M`, `size/L`, `size/XL`
- **Issue Types**: `bug`, `enhancement`, `documentation`, `architecture`, `tech-debt`

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## License

Apache License 2.0 - See [LICENSE](LICENSE) for details.
