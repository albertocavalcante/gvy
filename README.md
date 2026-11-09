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

- Java 17+ to run the server; JDK 21+ to build it
- Node 24+ and pnpm 12+ for the VS Code extension
- Just 1.59.0 for the developer command interface (direct Gradle/Node commands remain available)

## Build and install from source

With JDK 21+, Node 24+, pnpm 12+ and Just 1.59.0 installed:

```sh
just doctor
just install-vscode-source
```

This builds both the server and extension from the selected checkout, verifies the packaged artifacts, and installs the
VSIX into VS Code. See [developer commands](CONTRIBUTING.md#developer-commands) for bootstrap instructions, checks,
build-directory options and compatibility aliases.

## Building the server

```bash
./gradlew :gls:stageEditorServer
```

This builds the fat JAR and stages it at `gls/build/editor-server/gls.jar`.

## Running

### Stdio mode (default)

```bash
java -jar gls/build/editor-server/gls.jar
```

### Socket mode

```bash
java -jar gls/build/editor-server/gls.jar socket 8080
```

## Development

This project uses:

- Kotlin/JVM
- LSP4J for protocol implementation
- The checked-in Gradle wrapper for builds

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
| `gls/`                     | Core language server                |
| `parser/`                  | Parsing libraries (api/native/core) |
| `common/`                  | Shared utilities                    |
| `diagnostics/`             | Diagnostics (compiler + CodeNarc)   |
| `fmt/`                     | OpenRewrite-based formatting        |
| `jenkins/`                 | Jenkins pipeline support            |
| `ext/spock/`               | Spock framework support             |
| `dsl/gdsl/`                | GDSL execution and metadata         |
| `ext/testing/`             | Test discovery utilities            |
| `ext/junit/`               | JUnit integration                   |
| `repl/`                    | Groovy REPL                         |
| `build-tool/`              | BSP/Gradle integration              |
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
