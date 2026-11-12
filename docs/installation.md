# Install and update gvy

Install the released extension for VS Code, Cursor, or VSCodium. To build your current checkout instead, use the
[contributor instructions](../editors/code/CONTRIBUTING.md#build-and-install-this-checkout).

| Choose              | What you get                                                              | Install                                 |
| ------------------- | ------------------------------------------------------------------------- | --------------------------------------- |
| Stable              | A tagged release; see the [v0.5.4 limitation](#stable-release-limitation) | Homebrew or the released VSIX below     |
| Nightly             | A prebuilt, tested snapshot of `main`                                     | [Nightly VSIX](#nightly-download)       |
| Latest green `main` | The newest commit with passing required CI, built locally                 | [`make ext-latest`](#latest-green-main) |

Use VS Code 1.144 or newer, or an editor compatible with that VS Code API. Homebrew installs the Java runtime; it does
not install the editor. Cursor and VSCodium have separate product version numbers. Some builds use an older VS Code API
and cannot install this release. The installer preserves the editor's compatibility error; it never forces an
incompatible installation. [Broader editor compatibility](https://github.com/albertocavalcante/gvy/issues/1706) remains
in progress.

## Stable release limitation

The published **v0.5.4 VSIX bundles the v0.4.8 server** (`0.4.8-SNAPSHOT` at runtime), including the VSIX installed by
Homebrew. The standalone `gls-0.5.4.jar` and Homebrew's `gvy` command run the newer server. Track the correction in
[#1751](https://github.com/albertocavalcante/gvy/issues/1751).

For newer server fixes in your editor, use a [paired nightly](#nightly-download) when available or
[build the latest green main](#latest-green-main). This limitation remains until a corrected stable release and updated
distribution pins are published.

## macOS and Linux with Homebrew

With [Homebrew](https://brew.sh/) installed, run:

```bash
brew install albertocavalcante/tap/gvy
gvy-install-extension
```

The formula installs Java 17, the standalone server, and a prebuilt VSIX from the same gvy release. Homebrew verifies
SHA-256 checksums for both files before installation. You do not need a gvy checkout, Gradle, Node, pnpm, or a separate
Groovy installation.

The helper searches in this order: `code`, `cursor`, `codium`, `code-insiders`, `cursor-insiders`. It uses PATH and
standard macOS app locations under `/Applications` and `~/Applications`, and prints the selected editor before
installing. To select an editor explicitly:

```bash
gvy-install-extension cursor
```

Use `codium` for VSCodium or `code-insiders` for VS Code Insiders. An explicit editor that is unavailable is an error;
the helper does not switch to another editor. It uses the released VSIX packaged by Homebrew and does not build from
source or fetch another version at runtime.

Preview the selection without installing:

```bash
gvy-install-extension --dry-run
```

The preview shows the editor command, bundled VSIX path, and required editor API. The editor checks compatibility during
installation.

Open a `.groovy` file, `.gradle` file, or `Jenkinsfile` after installation. If your editor is already open, run
**Developer: Reload Window**.

### If the editor command is missing

Standard macOS app installations are found automatically. For other locations, pass the absolute path to your editor's
CLI or enable its shell command. In VS Code on macOS, use **Shell Command: Install 'code' command in PATH** from the
Command Palette. Cursor and VSCodium provide their own shell commands.

For a manual installation, use **Extensions: Install from VSIX…** and select `gvy.vsix` from the formula's `libexec`
directory. To find it:

```bash
brew --prefix gvy
```

### If the editor asks for Java

Java is installed by the formula. Run `brew info albertocavalcante/tap/gvy` to see its JDK location, and select that
location in the editor's Java configuration. See [Java settings](../editors/code/README.md#java-settings-explained) if
you already use a different project JDK. The installer does not change your Java or editor settings. The standalone
`gvy` command uses its packaged Java 17 even if your project has an older `JAVA_HOME`.

## Windows or without Homebrew

1. Install Java 17 or newer if it is not already available.
2. [Download gvy v0.5.4](https://github.com/albertocavalcante/gvy/releases/download/v0.5.4/gvy-0.5.4.vsix), or choose
   `gvy-<version>.vsix` from [GitHub Releases](https://github.com/albertocavalcante/gvy/releases).
3. Run **Extensions: Install from VSIX…** in your editor's Command Palette and select the downloaded file.
4. Open a Groovy file. Reload an existing editor window after installing an update.

The server is bundled in the VSIX. For checksum or attestation verification and Java settings, see the
[editor guide](../editors/code/README.md#setup).

## Development builds

### Nightly download

Use a nightly to try changes from `main` without installing a build toolchain:

1. Open [GitHub Releases](https://github.com/albertocavalcante/gvy/releases) and select the newest **Nightly**
   prerelease that includes **gvy-nightly.vsix** and **nightly.json**.
2. Download **gvy-nightly.vsix**, then run **Extensions: Install from VSIX…** in your editor and select it.
3. Reload the editor window. Keep the downloaded file if you want to return to this build later.

The daily workflow is scheduled for 03:00 UTC and publishes only after server tests, editor tests, packaging, and an LSP
startup and shutdown check pass. A failed run publishes nothing; check the release date and commit when choosing a
build. Both the extension and its bundled server come from that commit. **nightly.json** records the source commit,
actual server version, editor API requirement, and hashes; **checksums.txt** provides SHA-256 digests.

Java 17+ and a compatible editor are still required. The standalone **gls-nightly.jar** is available in the same release
for other LSP clients. Nightlies use separate dated tags and do not replace the latest stable release.

To update a nightly, install the newer nightly VSIX. For a downgrade to stable or an older nightly, first uninstall gvy
from the editor's Extensions view, then use **Extensions: Install from VSIX…** with the chosen file. Homebrew users can
select the stable `libexec/gvy.vsix` under `brew --prefix gvy` instead of downloading it again. The command-line helper
does not request downgrades. Installing a nightly VSIX replaces gvy in that editor; it does not change the Homebrew
server.

Homebrew's `gvy` formula tracks stable releases. There is no `gvy-nightly` formula or `brew install --HEAD` support yet.
Nightly downloads are manual installs; the extension does not automatically follow the nightly channel. Automatic
updates and rollback are tracked in [#1736](https://github.com/albertocavalcante/gvy/issues/1736).

### Latest green main

A nightly is a daily snapshot. For the newest `origin/main` commit whose required CI passed, use an existing gvy
checkout with the [JVM build setup](../CONTRIBUTING.md#set-up-the-jvm-build) and green-main prerequisites below
installed:

```bash
make ext-latest DRY_RUN=1
make ext-latest
```

This builds both components from the selected commit and keeps previous VSIX files for `make ext-rollback`. It needs
authenticated `gh`, `jq`, Java, `pnpm`, and `unzip`. See the
[green-main contributor guide](../editors/code/CONTRIBUTING.md#run-the-latest-green-main) for editor selection,
provenance, rollback, and build-directory controls.

## Update

For stable Homebrew installations:

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
