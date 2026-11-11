# Install and update gvy

Install the released extension for VS Code, Cursor, or VSCodium. To build your current checkout instead, use the
[contributor instructions](../editors/code/CONTRIBUTING.md#build-and-install-this-checkout).

Use VS Code 1.144 or newer, or an editor compatible with that VS Code API. Homebrew installs the Java runtime; it does
not install the editor.

## macOS and Linux with Homebrew

With [Homebrew](https://brew.sh/) installed, run:

```bash
brew install albertocavalcante/tap/gvy
gvy-install-extension
```

The formula installs Java 17, the standalone server, and a prebuilt VSIX from the same gvy release. Homebrew verifies
SHA-256 checksums for both files before installation. You do not need Git, Gradle, Node, pnpm, or a separate Groovy
installation.

The helper searches for editor commands on PATH in this order: `code`, `cursor`, `codium`, `code-insiders`,
`cursor-insiders`. It prints the selected editor before installing. To select an editor explicitly:

```bash
gvy-install-extension cursor
```

Use `codium` for VSCodium or `code-insiders` for VS Code Insiders. An explicit editor that is unavailable is an error;
the helper does not switch to another editor. It uses the released VSIX packaged by Homebrew and does not build from
source or fetch another version at runtime.

Open a `.groovy` file, `.gradle` file, or `Jenkinsfile` after installation. If your editor is already open, run
**Developer: Reload Window**.

### If the editor command is missing

In VS Code on macOS, run **Shell Command: Install 'code' command in PATH** from the Command Palette, then retry. Cursor
and VSCodium provide their own shell commands. You can also pass the absolute path to your editor's CLI.

For a manual installation, use **Extensions: Install from VSIX…** and select `gvy.vsix` from the formula's `libexec`
directory. To find it:

```bash
brew --prefix gvy
```

### If the editor asks for Java

Java is installed by the formula. Run `brew info albertocavalcante/tap/gvy` to see its JDK location, and select that
location in the editor's Java configuration. See [Java settings](../editors/code/README.md#java-settings-explained) if
you already use a different project JDK. The installer does not change your Java or editor settings.

## Windows or without Homebrew

1. Install Java 17 or newer if it is not already available.
2. [Download gvy v0.5.4](https://github.com/albertocavalcante/gvy/releases/download/v0.5.4/gvy-0.5.4.vsix), or choose
   `gvy-<version>.vsix` from [GitHub Releases](https://github.com/albertocavalcante/gvy/releases).
3. Run **Extensions: Install from VSIX…** in your editor's Command Palette and select the downloaded file.
4. Open a Groovy file. Reload an existing editor window after installing an update.

The server is bundled in the VSIX. For checksum or attestation verification and Java settings, see the
[editor guide](../editors/code/README.md#setup).

## Update

For Homebrew installations:

```bash
brew update
brew upgrade gvy
gvy-install-extension
```

The final command installs the formula's updated VSIX into your editor. Updating Homebrew alone does not replace an
extension already installed in an editor. If you used an explicit editor command, use it again when updating.

For manual installations, download the newer VSIX and install it the same way. Keep a previous VSIX if you need to roll
back; your editor may require confirmation to install an older version.

## Remove

Uninstall **gvy - Groovy** from your editor's Extensions view. To remove the Homebrew server and helper too, run:

```bash
brew uninstall gvy
```

Uninstalling the formula does not remove an extension already installed in an editor.

## Other editors

Configure an LSP client to launch `gvy` over standard input/output after a Homebrew installation. For a standalone JAR
or socket transport, follow the [language server guide](lsp/usage.md).
