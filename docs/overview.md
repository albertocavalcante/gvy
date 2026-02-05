# Groovy Devtools Overview

Groovy Devtools is a monorepo of developer tooling for Apache Groovy. The Groovy Language Server (LSP) is the main
component and remains a work in progress, while the surrounding modules provide parsing, diagnostics, formatting,
Jenkins metadata, test discovery, and editor integrations.

## Major components

- `../gls/`: Language Server (Kotlin/JVM)
- `../semantics/`: Semantic analysis based on [SemanticDB](https://scalameta.org/docs/semanticdb/guide.html)
  ([design](semantics/design.md))
- `../parser/`: Parsing libraries (`api`, `native`, `core`)
- `../diagnostics/`: Compiler + CodeNarc diagnostics
- `../fmt/`: OpenRewrite-based formatting
- `../jenkins/`: Jenkins pipeline metadata + completions
- `../ext/spock/`: Spock framework awareness
- `../ext/testing/`, `../ext/junit/`: Test discovery + adapters
- `../jupyter/`: Groovy and Jenkins Jupyter kernels
- `../editors/code/`: VS Code/Cursor/VSCodium extension
- `../tools/jenkins-extractor/`: Jenkins metadata extractor
- `../tests/`: End-to-end LSP scenarios

## Where to start

- Running the language server: `lsp/usage.md`
- LSP feature coverage: `lsp/feature-support.md`
- Semantics and type inference: `semantics/design.md`
- Roadmap and current priorities: `roadmap.md`
