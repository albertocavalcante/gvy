# Release Please Configuration

This directory contains the configuration for [Release Please](https://github.com/googleapis/release-please), the tool
that automates CHANGELOG generation, semantic versioning/tagging, and release creation.

## Files

- **`config.json`**: Defines the release rules (versioning strategy, tag formats, etc.).
- **`manifest.json`**: Tracks the current version of each component.
- **[`version.txt`](../../version.txt)**: The root `simple` strategy's plain version state. Release Please requires the
  file to exist and updates it alongside the manifest, Gradle base version and extension package version.

## Unified Release Model

We use a **Unified Release Model** where a single Git tag (`vX.Y.Z`) triggers the release of all components (LSP JAR and
VS Code Extension).

### Critical Configuration Options

#### `include-component-in-tag: false`

In `config.json`, the root package owns the release and updates both distribution versions through `extra-files`:

```json
".": {
    "release-type": "simple",
    "include-component-in-tag": false,
    "extra-files": ["build.gradle.kts", "editors/code/package.json"]
}
```

- **`true` (Default)**: Generates tags like `vscode-groovy-v1.2.3`.
- **`false`**: Generates tags like `v1.2.3` (using the version from the manifest).

Setting this to `false` preserves the unified `v*` tagging strategy. `version.txt` contains only the manifest version
and a final newline; keep its license metadata in the adjacent `.license` file because the updater replaces its
contents.

Validate the checked-in release state without a JVM build:

```bash
node --test tools/ci/release-state.test.mjs
```

CI's policy self-test runs this control on every PR. It checks the version file selected by the actual root
configuration, the extra-file declarations and agreement with the manifest, Gradle and extension versions.

### Nightly release discovery

The stable release and paired-nightly channels share this repository. Release Please 17.6.0 scans GitHub releases and
warns about the component parsed from the already published
[`nightly-0.5.4-nightly.20261112112348.43205148552.1-publish.1`](https://github.com/albertocavalcante/gvy/releases/tag/nightly-0.5.4-nightly.20261112112348.43205148552.1-publish.1)
tag before finding the stable release. The exact advisory and supported channel interoperability follow-up are owned
under [#1603](https://github.com/albertocavalcante/gvy/issues/1603), with a review due on 2026-11-19 and exact source
fingerprints in the dependency-maintenance ledger. Published history remains immutable. This disposition does not
suppress warnings or establish that future nightlies or stable releases work; fresh action evidence remains required.

### PR Title Pattern

The `pull-request-title-pattern` option customizes the title of release PRs.

```json
"pull-request-title-pattern": "chore: release v${version}"
```

#### Available Variables

| Variable       | Description                                  | Example         |
| -------------- | -------------------------------------------- | --------------- |
| `${version}`   | The computed semantic version                | `v0.4.8`        |
| `${component}` | The package/component name (for monorepos)   | `vscode-groovy` |
| `${scope}`     | Conventional commit scope (adds parentheses) | `(api)`         |

#### Default Pattern

The default pattern is: `chore${scope}: release${component} ${version}`

This produces titles like:

- Single package: `chore: release v0.4.8`
- With component: `chore: release vscode-groovy v0.4.8`
- With scope: `chore(api): release v0.4.8`

## Validate release packages

Run the packaging regression controls without building the JVM project:

```bash
node --test tools/ci/release-artifacts.test.mjs
```

CI also runs the paired-nightly controls to check the shared ZIP and server-provenance readers.

The tagged-release builder runs `test` and `:gls:stageEditorServer` before packaging the extension. It passes the exact
staged JAR through pnpm preparation and VSCE prepublish, with pinned fallback disabled. The verifier checks the source
commit, clean server provenance, extension version, and byte equality between the standalone and embedded JARs.
`dist/release.json` records the verified pair's provenance and SHA-256 digests.

For a prerelease such as `v0.5.4-rc.1`, Gradle can report the server's source base version as `0.5.4`. Verification
accepts that base version while requiring the extension's full prerelease version and the same clean source commit. The
receipt retains the server's actual version. `tools/release.sh checksums` can regenerate the checksum manifest; it
excludes its own output and replaces the previous manifest only after hashing succeeds.

Use Node.js 24, as in release CI. Tagged artifact builds require Git 2.44 or newer for
[`git show-ref --exists`](https://github.com/git/git/blob/v2.44.0/Documentation/git-show-ref.txt). Use a clean checkout
whose base version matches the requested release, and provide the tag explicitly:

```bash
make release-build RELEASE_VERSION=vX.Y.Z    # preview build steps and output paths
make release-publish RELEASE_VERSION=vX.Y.Z  # build verified local release artifacts
```

The preview validates the version and Git refname, leaving existing artifacts and checksums unchanged. It invokes
neither Gradle nor pnpm and creates no placeholder files. Other commands reject `--dry-run`; unsupported options and
extra arguments fail before any build action. The `release-publish` target requires `RELEASE_VERSION`; it does not parse
human-readable Gradle version output. The tagged GitHub workflow publishes the resulting artifacts.

The direct build command is `tools/release.sh build --version vX.Y.Z`; add `--dry-run` for the preview. The builder
restores the original `package.json` contents and permissions after either successful packaging or an error. Artifact
paths resolve inside the script's own checkout, including when it is invoked from another directory. Version and
checkout preflight checks run before a real build resets `dist/`. If the requested release tag exists locally, it must
resolve to the checkout's HEAD commit; lightweight and annotated tags are supported. Fetch the tag before reproducing a
published release, for example `git fetch origin tag vX.Y.Z`. Builds before the tag exists locally remain supported.
Invalid tags and tag-read errors fail before artifact cleanup.

Published v0.5.4 still contains the mismatched server tracked in
[#1751](https://github.com/albertocavalcante/gvy/issues/1751). A source fix does not replace that release; publish and
test a corrected tagged release before updating the Homebrew and direct-download pins.

## Reference

- [Release Please Config Options](https://github.com/googleapis/release-please/blob/main/docs/manifest-releaser.md#configuration)
- [Release Please Repository](https://github.com/googleapis/release-please)
