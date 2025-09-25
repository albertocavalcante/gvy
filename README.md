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

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## License

Apache License 2.0 - See [LICENSE](LICENSE) for details.
