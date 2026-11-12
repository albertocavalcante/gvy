# Release Please Configuration

This directory contains the configuration for [Release Please](https://github.com/googleapis/release-please), the tool
that automates CHANGELOG generation, semantic versioning/tagging, and release creation.

## Files

- **`config.json`**: Defines the release rules (versioning strategy, tag formats, etc.).
- **`manifest.json`**: Tracks the current version of each component.

## Unified Release Model

We use a **Unified Release Model** where a single Git tag (`vX.Y.Z`) triggers the release of all components (LSP JAR and
VS Code Extension).

### Critical Configuration Options

#### `include-component-in-tag: false`

In `config.json`, this setting is crucial for the VS Code extension (`editors/code`).

```json
"editors/code": {
    "component": "vscode-groovy",
    "include-component-in-tag": false
}
```

- **`true` (Default)**: Generates tags like `vscode-groovy-v1.2.3`.
- **`false`**: Generates tags like `v1.2.3` (using the version from the manifest).

By setting this to `false` (and managing versions in lockstep via the manifest), we ensure `release-please` supports our
unified `v*` tagging strategy.

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

The tagged-release builder runs `test` and `:gls:stageEditorServer` before packaging the extension. It passes the exact
staged JAR through pnpm preparation and VSCE prepublish, with pinned fallback disabled. The verifier checks the source
commit, clean server provenance, extension version, and byte equality between the standalone and embedded JARs.
`dist/release.json` records the verified pair's provenance and SHA-256 digests.

Use a clean checkout whose base version matches the requested release. `tools/release.sh build --version vX.Y.Z`
produces the verified artifacts; `--dry-run` creates placeholders without invoking Gradle or pnpm. The builder restores
the original `package.json` after either successful packaging or an error.

Published v0.5.4 still contains the mismatched server tracked in
[#1751](https://github.com/albertocavalcante/gvy/issues/1751). A source fix does not replace that release; publish and
test a corrected tagged release before updating the Homebrew and direct-download pins.

## Reference

- [Release Please Config Options](https://github.com/googleapis/release-please/blob/main/docs/manifest-releaser.md#configuration)
- [Release Please Repository](https://github.com/googleapis/release-please)
