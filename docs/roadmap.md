# Roadmap

Groovy Devtools is under active development. The language server is usable for core Groovy projects today, while the
broader monorepo is expanding to cover Jenkins pipelines, Spock, testing, and notebooks.

## Current status (snapshot)

- Core LSP: completion, hover, navigation, formatting, diagnostics, code actions, rename, semantic tokens (Jenkins)
- Jenkins pipelines: metadata-driven completion and diagnostics, deeper context support in progress
- Spock: spec detection and block awareness, richer DSL support in progress
- Testing: CodeLens run/debug for tests, shared test discovery utilities
- Jupyter: early kernel prototypes for Groovy and Jenkins

## Near-term focus

- Stability and performance for indexing and compilation
- Workspace-scale features (config refresh, file watching, symbol accuracy)
- Editor polish in VS Code and other clients

Internal design notes and detailed plans live in `../kb/roadmap/README.md`.
