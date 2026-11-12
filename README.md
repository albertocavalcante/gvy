# gvy

**Groovy language tools for your editor.**

Completion, diagnostics, navigation, and formatting for Groovy code, Jenkins pipelines, and Spock tests. gvy includes a
language server and an extension for VS Code, Cursor, and VSCodium.

[Install](#get-started) · [Documentation](docs/README.md) ·
[Releases](https://github.com/albertocavalcante/gvy/releases) · [Contribute](CONTRIBUTING.md)

## Get started

The extension requires **VS Code API 1.144 or newer**. Cursor and VSCodium builds must provide a compatible VS Code API;
their own product version numbers differ.

### macOS and Linux with Homebrew

```bash
brew install albertocavalcante/tap/gvy
gvy-install-extension
```

Homebrew installs Java 17, the server, and the released extension, and verifies the downloads. No clone or source build
is required. The helper selects the first available editor: VS Code, Cursor, then VSCodium.

To choose an editor, run `gvy-install-extension cursor` or `gvy-install-extension codium` instead. Then open a `.groovy`
file, `.gradle` file, or `Jenkinsfile`. Reload an existing editor window after installation. Use
`gvy-install-extension --dry-run` to preview the selection without installing.

### Windows or without Homebrew

[**Download the VS Code extension — v0.5.4**](https://github.com/albertocavalcante/gvy/releases/download/v0.5.4/gvy-0.5.4.vsix)

Install **Java 17 or newer**, then run **Extensions: Install from VSIX…** in your editor's Command Palette and select
the downloaded file. The extension includes the server; a separate Groovy installation is not required.

The extension is distributed through GitHub Releases. Marketplace and Open VSX publishing are not enabled yet. See the
[installation guide](docs/installation.md) for editor commands, Java setup, updates, and troubleshooting.

For fixes before the next release, choose a
[prebuilt nightly or the latest green main](docs/installation.md#development-builds).

### Other LSP editors

With Homebrew, set your editor's server command to `gvy` (stdio). You can also use the standalone JAR;
[server setup](docs/lsp/usage.md) covers downloads and socket connections. The server targets LSP 3.17.

## In your editor

- **Write and understand code:** completion, hover documentation, signature help, and compiler and CodeNarc diagnostics.
- **Navigate projects:** definitions, references, and document and workspace symbols.
- **Make changes:** formatting, rename, and code actions.
- **Work with Jenkins:** pipeline step completion and diagnostics, shared libraries, and GDSL support.
- **Work with Spock:** spec detection, block awareness, and test run/debug code lenses.

**Early development:** coverage and correctness vary by project, and Jenkins and Spock support is still evolving. Check
the [feature matrix](docs/lsp/feature-support.md) for current limits and the [Quality Bar](docs/quality-bar.md) for
priorities.

## Find your next step

- [Install or update gvy](docs/installation.md)
- [Configure editor settings and commands](editors/code/README.md#configuration)
- [Connect another editor](docs/lsp/usage.md)
- [Build or contribute](CONTRIBUTING.md)

## Help and feedback

[Report an issue](https://github.com/albertocavalcante/gvy/issues) with a small Groovy example, steps to reproduce, and
your gvy, editor, and Java versions. Feature requests and contributions are welcome.

## License

[Apache License 2.0](LICENSE).
