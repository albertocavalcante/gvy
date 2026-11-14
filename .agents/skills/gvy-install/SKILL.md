---
name: gvy-install
description: Install or update the GVY language server and VS Code extension from an exact main commit or an explicitly selected local checkout.
metadata:
  author: albertocavalcante
  tags: gvy, installation, vscode, provenance
---

# Install GVY

Use the installed `gvy-install` command when available; otherwise run `node editors/code/tools/gvy-install.js` from a
gvy checkout containing this helper. Use the maintained installer instead of reconstructing GitHub downloads, ZIP
extraction, smoke checks, wrappers, or receipts in a shell or REPL. The equivalent direct entrypoint is
`node editors/code/tools/install-extension.js --from-ci`.

## Choose the requested source

- **Latest main or a remote commit:** use the CI artifact mode below. It resolves the ref once and uses the server and
  VSIX from the same successful Extension CI run at that full SHA.
- **This checkout, including local changes:** use `--from-source`; follow the
  [source-build instructions](../../../editors/code/CONTRIBUTING.md#build-and-install-this-checkout). Build in an
  isolated worktree and respect the host's build admission controls. Carry the requested HEAD and local changes into
  that worktree without modifying the shared checkout; a clean main build does not satisfy a dirty-checkout request.
- **Newest green commit:** use the separate
  [green-main workflow](../../../editors/code/CONTRIBUTING.md#run-the-latest-green-main). Its selection policy differs
  from exact HEAD.

## Install an exact main snapshot

```sh
gvy-install --ref main --wait 600 --json
```

Use `--java-home`, `--installer`, `--state-root`, and `--bin-dir` for explicit local configuration. Preserve an existing
command that the installer cannot establish it owns. Resolve a reported PATH conflict before claiming the standalone
command was updated.

Capture stderr in a private local log. Inspect the JSON result first; read only the failed stage's log when needed. The
receipt records the selected SHA, CI run and artifacts, verified hashes, installation stage, and retained files. For
pending or interrupted work, use its recorded `resumeArgs` with the direct Node entrypoint, keeping any custom
`--state-root` and local configuration. Resume only this helper's `ci-v1` receipts; older manual receipts document
history. This keeps the original SHA. Do not restart with `--ref main` merely because another commit landed. Never
substitute an older green build or a release for exact HEAD.

A failed or canceled producer is a failed result, not a pending installation. If GitHub reports a new run attempt,
select the receipt's same full SHA in a fresh invocation to verify the new producer; an existing attempt receipt is not
evidence for its rerun.

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
