# gvy documentation

Start with the guide for your task. For a short introduction and installation steps, see the
[project README](../README.md).

## Use gvy

- [Install and update gvy](installation.md): Homebrew, direct VSIX installation, Java setup, and updates.
- [Editor guide](../editors/code/README.md): install the extension, configure Java and language features, and
  troubleshoot.
- [Standalone language server](lsp/usage.md): connect another editor over standard input/output or a socket.
- [LSP feature support](lsp/feature-support.md): supported protocol methods and current limits.
- [Quality Bar and roadmap](quality-bar.md): project goals, quality targets, and priorities.

## Contribute

- [Contributor guide](../CONTRIBUTING.md): build, test, and prepare a pull request.
- [Kotlin style and readability](kotlin-style.md): write and review clear, idiomatic Kotlin.
- [License metadata and source headers](licensing.md)
- [Editor contributor guide](../editors/code/CONTRIBUTING.md): develop and test the VS Code extension.
- [Repository overview](overview.md): find the module responsible for a change.
- [Semantics and type inference](semantics/design.md): understand the semantic model.
- [Dependency bug policy](dependency-bugs.md): investigate dependency defects and prepare upstream contributions.
- [CI diagnostic accountability](ci-diagnostics.md): evaluate warnings, failures, and validation limits.

Documentation placement is defined in the [contributor guide](../CONTRIBUTING.md#where-documentation-belongs). Coding
agents start with [AGENTS.md](../AGENTS.md).

## Design and history

- [Knowledge base](../kb/README.md): architecture, RFCs, research, audits, and plans.
- [Gradle-to-Bazel migration summary](bazel_migration_summary.md): historical migration patterns, decisions, and
  troubleshooting.
