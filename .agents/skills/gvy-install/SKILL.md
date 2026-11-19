---
name: gvy-install
description: Install or update the GVY language server and VS Code extension from an exact main commit or an explicitly selected local checkout.
metadata:
  author: albertocavalcante
  tags: gvy, installation, vscode, provenance
---

# Install GVY

For CI installations, use the installed `gvy-install` command when available; otherwise run
`node editors/code/tools/gvy-install.js` from a gvy checkout containing this helper. That command selects CI mode. Use
the maintained installer instead of reconstructing GitHub downloads, ZIP extraction, smoke checks, wrappers, or receipts
in a shell or REPL.

## Choose the requested source

- **Latest main or an exact remote commit:** use the CI artifact mode below. `--ref` accepts `main` or a lowercase, full
  40-character commit SHA. It resolves `main` once and uses the server and VSIX from the same successful main-push
  Extension CI run at that SHA. Resolve another requested remote identifier to its full SHA before invoking the helper;
  preserve both the requested identifier and resolved SHA. A branch-only build does not qualify as a main-push producer.
- **This checkout, including local changes:** use the separate direct entrypoint
  `node editors/code/tools/install-extension.js --from-source --installer code --json`. Do not pass `--from-source` to
  the CI-only `gvy-install` command. Read [local source preparation](references/local-source.md) when preserving dirty
  or shared source, then follow the
  [source-build instructions](../../../editors/code/CONTRIBUTING.md#build-and-install-this-checkout).
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

Capture stderr in a private local log. Inspect the JSON result first; read the relevant failure log when needed. The
receipt records the selected SHA, CI run and artifacts, verified hashes, installation stage, and retained files. For
pending or interrupted work, call `gvy-install` with its recorded `resumeArgs` array as argv. The direct equivalent is
`node editors/code/tools/install-extension.js` followed by that array. Preserve custom `--state-root` and local
configuration. Resume only this helper's `ci-v1` receipts; older manual receipts document history. This keeps the
original SHA. Do not restart with `--ref main` merely because another commit landed. Never substitute an older green
build or a release for exact HEAD.

A failed or canceled producer is a failed result, not a pending installation. If GitHub reports a new run attempt,
select the receipt's same full SHA in a fresh invocation to verify the new producer; an existing attempt receipt is not
evidence for its rerun.

`--package-only` verifies and smoke-checks the pair without installing it. Use it for a verification request or an
isolated forward test. To install that verified selection later, resume its receipt without `--package-only` rather than
replaying the package-only `resumeArgs` unchanged. A packaged or pending receipt does not establish an installed result.

## Completion

Match completion evidence to the requested operation:

- **CI installation:** require a successful installed receipt and verification of the actual installed server, client,
  extension manifest, and standalone command.
- **Package-only verification:** require a successful packaged receipt with verified hashes and passing server smoke
  checks. Installation is outside this operation.
- **Local-source installation:** require the source-build receipt's verified package and smoke checks plus a successful
  editor CLI install. This route installs the extension and bundled server; it does not update the standalone command or
  independently rehash the installed extension. Report those limits accurately.

The extension's package version alone does not identify the source commit. Preserve partial-install and failed-check
results accurately; the editor CLI operation is not an atomic transaction with the standalone command. A failure after
activation or during receipt/cleanup work does not establish that the installation was rolled back.

Report the full source selection, runtime version, actual validation, retained installer/receipt paths, and whether an
existing VS Code window needs **Developer: Reload Window**. Verified files on disk do not prove which server an existing
window has loaded. Distinguish Extension CI from full project CI. Clean owned temporary files and processes; keep active
runtime files and identified recovery material.

See the [CI installation guide](../../../editors/code/CONTRIBUTING.md#install-an-exact-main-commit-from-ci) for
supported platforms, prerequisites, receipts, and recovery.
