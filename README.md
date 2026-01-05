# Groovy Devtools

Groovy Devtools (gvy) is a monorepo of Apache Groovy tooling. The Groovy Language Server (LSP) is the primary component
and is still a work in progress, but it already provides core editor features plus framework-aware support for Jenkins
pipelines and Spock tests.

## Status

- Core LSP: completion, hover, navigation, formatting, diagnostics, code actions, rename, folding, semantic tokens
- Jenkins: metadata-driven completion and diagnostics; deeper context awareness in progress
- Spock: spec detection and block awareness; richer DSL support in progress
- Editors: VS Code/Cursor/VSCodium extension in `editors/code/`

See `docs/roadmap.md` for the user-facing roadmap.

## Documentation

- `docs/README.md`
- `docs/overview.md`
- `docs/lsp/usage.md`
- `docs/lsp/feature-support.md`

## LSP Target

We target LSP 3.17 and use `lsp4j` 0.24.0. The full support matrix lives in `docs/lsp/feature-support.md`.

## Requirements

- Java 17 or higher
- Groovy 4.0+

## Building

```bash
./gradlew build
```

This creates a fat JAR under `build/libs/`.

## Running

### Stdio mode (default)

```bash
java -jar build/libs/groovy-lsp-<version>.jar
```

### Socket mode

```bash
java -jar build/libs/groovy-lsp-<version>.jar socket 8080
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

### GitHub issue XML prompt generator

Generate a fenced `xml` prompt for a GitHub issue using the Codex CLI:

```bash
./tools/github-issues/generate-xml-prompt.sh ISSUE_NUMBER [--comment] [--model MODEL]
```

Requirements: `gh` (authenticated for the repo), `codex` CLI, and `jq` for parsing issue metadata. The tool asks Codex
to return a pretty-printed XML block inside triple-backtick xml code fences; use `--comment` to post the output back to
the GitHub issue instead of printing it locally. The default model is `gpt-5-codex`.

### Monorepo layout

| Path                       | Description                         |
| -------------------------- | ----------------------------------- |
| `groovy-lsp/`              | Core language server                |
| `parser/`                  | Parsing libraries (api/native/core) |
| `groovy-common/`           | Shared utilities                    |
| `groovy-diagnostics/`      | Diagnostics (compiler + CodeNarc)   |
| `groovy-formatter/`        | OpenRewrite-based formatting        |
| `groovy-jenkins/`          | Jenkins pipeline support            |
| `groovy-spock/`            | Spock framework support             |
| `groovy-gdsl/`             | GDSL execution and metadata         |
| `groovy-testing/`          | Test discovery utilities            |
| `groovy-junit/`            | JUnit integration                   |
| `groovy-repl/`             | Groovy REPL                         |
| `groovy-build-tool/`       | BSP/Gradle integration              |
| `editors/code/`            | VS Code/Cursor/VSCodium extension   |
| `jupyter/`                 | Jupyter kernels (Groovy/Jenkins)    |
| `tools/jenkins-extractor/` | Jenkins metadata extractor          |
| `tests/`                   | End-to-end LSP scenarios            |

### VS Code Extension

The VS Code extension is located in `editors/code/`. See [editors/code/README.md](editors/code/README.md) for
extension-specific documentation.

A standalone mirror is maintained at [vscode-groovy](https://github.com/albertocavalcante/vscode-groovy) (synced via
Copybara).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## License

Apache License 2.0 - See [LICENSE](LICENSE) for details.
