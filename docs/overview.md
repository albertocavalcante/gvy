# Groovy Devtools Overview

Groovy Devtools is a monorepo of developer tooling for Apache Groovy. The Groovy Language Server (LSP) is the main
component and remains a work in progress, while the surrounding modules provide parsing, diagnostics, formatting,
Jenkins metadata, test discovery, and editor integrations.

## Major components

- `../groovy-lsp/`: Language Server (Kotlin/JVM)
- `../parser/`: Parsing libraries (`api`, `native`, `core`)
- `../groovy-diagnostics/`: Compiler + CodeNarc diagnostics
- `../groovy-formatter/`: OpenRewrite-based formatting
- `../groovy-jenkins/`: Jenkins pipeline metadata + completions
- `../groovy-spock/`: Spock framework awareness
- `../groovy-testing/`, `../groovy-junit/`: Test discovery + adapters
- `../jupyter/`: Groovy and Jenkins Jupyter kernels
- `../editors/code/`: VS Code/Cursor/VSCodium extension
- `../tools/jenkins-extractor/`: Jenkins metadata extractor
- `../tests/`: End-to-end LSP scenarios

## Where to start

- Running the language server: `lsp/usage.md`
- LSP feature coverage: `lsp/feature-support.md`
- Roadmap and current priorities: `roadmap.md`
