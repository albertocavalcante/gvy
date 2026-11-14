---
name: gvy-install
description: Install or update the GVY language server and VS Code extension from an exact main commit or an explicitly selected local checkout.
metadata:
  author: albertocavalcante
  tags: gvy, installation, vscode, provenance
---

# Install GVY

Run from the gvy repository root. Use the maintained installer instead of reconstructing GitHub downloads, ZIP
extraction, smoke checks, wrappers, or receipts in a shell or REPL.

## Choose the requested source

- **Latest main or a remote commit:** use the CI artifact mode below. It resolves the ref once and uses the server and
  VSIX from the same successful Extension CI run at that full SHA.
- **This checkout, including local changes:** use `--from-source`; follow the
  [source-build instructions](../../../editors/code/CONTRIBUTING.md#build-and-install-this-checkout). Build in an
  isolated worktree and respect the host's build admission controls.
- **Newest green commit:** use the separate
  [green-main workflow](../../../editors/code/CONTRIBUTING.md#run-the-latest-green-main). Its selection policy differs
  from exact HEAD.

## Install an exact main snapshot

```sh
node editors/code/tools/install-extension.js --from-ci --ref main --wait 600 --json
```

Use `--java-home`, `--installer`, `--state-root`, and `--bin-dir` for explicit local configuration. Preserve an existing
command that the installer cannot establish it owns. Resolve a reported PATH conflict before claiming the standalone
command was updated.

Capture stderr in a private local log. Inspect the JSON result first; read only the failed stage's log when needed. The
receipt records the selected SHA, CI run and artifacts, verified hashes, installation stage, and retained files. For
pending or interrupted work, run the installer with `--from-ci --resume <receipt> --wait 600 --json`, keeping any custom
`--state-root` and local configuration. This keeps the original SHA. Do not restart with `--ref main` merely because
another commit landed. Never substitute an older green build or a release for exact HEAD.

`--package-only` verifies and smoke-checks the pair without installing it. Use it for a verification request or an
isolated forward test. A packaged or pending receipt does not establish an installed result.

## Completion

Require a successful installed receipt and verification of the actual installed server, client, and standalone command.
The extension's package version alone does not identify the source commit. Preserve partial-install and failed-check
results accurately; the editor CLI operation is not an atomic transaction with the standalone command.

Report the full source selection, runtime version, actual validation, retained installer/receipt paths, and whether an
existing VS Code window needs **Developer: Reload Window**. Distinguish Extension CI from full project CI. Clean owned
temporary files and processes; keep active runtime files and identified recovery material.

See the [CI installation guide](../../../editors/code/CONTRIBUTING.md#install-an-exact-main-commit-from-ci) for
supported platforms, prerequisites, receipts, and recovery.
