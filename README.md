# Groovy Language Server

A Language Server Protocol (LSP) implementation for Apache Groovy.

## Status

⚠️ **Work in Progress** - This is an early development version with basic LSP functionality.

## Features

Currently implemented:

- Basic LSP lifecycle (initialize/shutdown)
- Simple completions (println, def, class)
- Hover support
- Diagnostics for TODO comments

Planned:

- Full Groovy AST integration
- Go-to definition
- Find references
- Refactoring support
- Gradle build file support

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

| Setting                                   | Type                                   | Default       | Description                                                                 |
| ----------------------------------------- | -------------------------------------- | ------------- | --------------------------------------------------------------------------- |
| `groovy.compilation.mode`                 | `"workspace"` \| `"single-file"`       | `"workspace"` | Controls how files are compiled. `workspace` enables cross-file resolution. |
| `groovy.compilation.incrementalThreshold` | number                                 | 50            | File count threshold for incremental compilation                            |
| `groovy.compilation.maxWorkspaceFiles`    | number                                 | 500           | Maximum files to compile together                                           |
| `groovy.server.maxNumberOfProblems`       | number                                 | 100           | Maximum diagnostics per file                                                |
| `groovy.java.home`                        | string                                 | `null`        | Path to Java home (requires Java 17+)                                       |
| `groovy.trace.server`                     | `"off"` \| `"messages"` \| `"verbose"` | `"off"`       | Trace communication with language server                                    |

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
  "groovy.trace.server": "messages"
}
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## License

Apache License 2.0 - See [LICENSE](LICENSE) for details.
