# Groovy Language Server

A Language Server Protocol (LSP) implementation for Apache Groovy.

## Status

⚠️ **Work in Progress** - This is an early development version with basic LSP functionality.

## LSP Feature Support

We target **LSP Specification 3.17** and use `lsp4j` 0.24.0.

| LSP Method | Feature | Status | Notes |
| :--- | :--- | :---: | :--- |
| **Lifecycle** | | | |
| `initialize` | Initialize | ✅ | |
| `shutdown` | Shutdown | ✅ | |
| `exit` | Exit | ✅ | |
| **Text Synchronization** | | | |
| `textDocument/didOpen` | Open | ✅ | Triggers compilation & diagnostics |
| `textDocument/didChange` | Change | ✅ | Full sync; triggers re-compilation |
| `textDocument/didSave` | Save | ✅ | |
| `textDocument/didClose` | Close | ✅ | Clears diagnostics |
| **Language Features** | | | |
| `textDocument/completion` | Completion | ✅ | Keywords + AST-based variables/methods |
| `textDocument/hover` | Hover | ✅ | Type info & documentation |
| `textDocument/signatureHelp` | Signature Help | ✅ | Method parameter hints |
| `textDocument/formatting` | Formatting | ✅ | Via OpenRewrite |
| `textDocument/publishDiagnostics` | Diagnostics | ✅ | Groovy compiler errors |
| `textDocument/codeAction` | Code Actions | ⏳ | Planned (Quick fixes) |
| `textDocument/rename` | Rename | ⏳ | Planned |
| `textDocument/semanticTokens` | Semantic Tokens | ⏳ | Planned (Syntax highlighting) |
| `textDocument/inlayHint` | Inlay Hints | ⏳ | Planned (LSP 3.17) |
| **Navigation** | | | |
| `textDocument/definition` | Go to Definition | ✅ | Symbols & Types |
| `textDocument/typeDefinition` | Type Definition | ✅ | |
| `textDocument/references` | Find References | ✅ | |
| `textDocument/documentSymbol` | Document Symbols | ✅ | Outline view |
| `workspace/symbol` | Workspace Symbols | ✅ | Global search |
| `textDocument/foldingRange` | Folding | ⏳ | Planned |
| `textDocument/implementation` | Implementation | ⏳ | Planned |

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

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## License

Apache License 2.0 - See [LICENSE](LICENSE) for details.
