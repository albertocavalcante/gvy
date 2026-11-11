# gvy — Groovy Devtools

Language tools for Apache Groovy, with a language server and a VS Code extension for Groovy code, Jenkins pipelines, and
Spock tests.

[Get started](#get-started) · [Documentation](docs/README.md) ·
[Releases](https://github.com/albertocavalcante/gvy/releases) · [Contributing](CONTRIBUTING.md)

## What you can do

| Workflow           | Features                                                                                 |
| ------------------ | ---------------------------------------------------------------------------------------- |
| Write Groovy       | Completion, hover documentation, signature help, and compiler and CodeNarc diagnostics   |
| Navigate a project | Go to definition, find references, and browse document and workspace symbols             |
| Edit code          | Formatting, rename, and code actions                                                     |
| Work with Jenkins  | Pipeline step completion and diagnostics, shared-library configuration, and GDSL support |
| Work with Spock    | Spec detection, block awareness, and test run/debug code lenses                          |

gvy is in early development. Feature coverage and correctness vary by project; Jenkins and Spock support is still
evolving. The [feature matrix](docs/lsp/feature-support.md) describes current support, and the
[Quality Bar](docs/quality-bar.md) explains the goals and priorities.

## Get started

### VS Code and compatible editors

You need **Java 17 or newer**. The extension bundles the language server; you do not need to build gvy or install Groovy
separately.

1. Download the `gvy-<version>.vsix` asset from the
   [latest release](https://github.com/albertocavalcante/gvy/releases/latest).
2. In VS Code, run **Extensions: Install from VSIX…** from the Command Palette and select the downloaded file.
3. Open a `.groovy` file, `.gradle` file, or `Jenkinsfile` to start the language server.

The extension is distributed through GitHub Releases. It is not published to the VS Code Marketplace or Open VSX. For
Java settings, download verification, Cursor/VSCodium setup, and troubleshooting, see the
[editor guide](editors/code/README.md).

### Other editors

The server targets Language Server Protocol (LSP) 3.17 and runs over standard input/output or a local socket. Use the
standalone JAR with your editor's LSP client; see [running the language server](docs/lsp/usage.md) for setup.

## Documentation

| If you want to…               | Start here                                                           |
| ----------------------------- | -------------------------------------------------------------------- |
| Configure the extension       | [Editor settings and commands](editors/code/README.md#configuration) |
| Connect another editor        | [Standalone language server](docs/lsp/usage.md)                      |
| Check feature coverage        | [LSP feature support](docs/lsp/feature-support.md)                   |
| Understand project priorities | [Quality Bar and roadmap](docs/quality-bar.md)                       |
| Build or contribute           | [Contributor guide](CONTRIBUTING.md)                                 |

## Help and contributions

[Report a bug or request a feature](https://github.com/albertocavalcante/gvy/issues). For bugs, include a small Groovy
example, steps to reproduce, the gvy version, your editor, and your Java version.

Contributions are welcome. The [contributor guide](CONTRIBUTING.md) covers setup, validation, and pull requests.

## License

[Apache License 2.0](LICENSE).
