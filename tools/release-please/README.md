# Release Please Configuration

This directory contains the configuration for [Release Please](https://github.com/googleapis/release-please), the tool that automates CHANGELOG generation, semantic versioning/tagging, and release creation.

## Files

- **`config.json`**: Defines the release rules (versioning strategy, tag formats, etc.).
- **`manifest.json`**: Tracks the current version of each component.

## Unified Release Model

We use a **Unified Release Model** where a single Git tag (`vX.Y.Z`) triggers the release of all components (LSP JAR and VS Code Extension).

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

By setting this to `false` (and managing versions in lockstep via the manifest), we ensure `release-please` supports our unified `v*` tagging strategy.

## Reference

- [Release Please Config Options](https://github.com/googleapis/release-please/blob/main/docs/manifest-releaser.md#configuration)
- [Release Please Repository](https://github.com/googleapis/release-please)
