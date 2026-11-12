# Desktop development

## Test the desktop

From the repository root, run the focused suite and quality gate:

```sh
./gradlew :viz:desktop:test
./gradlew :viz:desktop:lintFix
./gradlew :viz:desktop:lint :viz:desktop:check
```

Follow the [contributor guide](../../CONTRIBUTING.md#validate-your-changes) for toolchains and shared-host build limits.
`check` includes the existing coverage rule. Coverage does not replace behavioral assertions.

Use the narrowest test boundary that proves the behavior:

| Behavior                                                            | Test boundary                                                                                                                                                               |
| ------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| DTO ownership, half-open hits, smallest span/depth ties             | `AstNavigation` with immutable source/DTO fixtures; assert original instances and invalid/stale references                                                                  |
| UTF-16 positions, CR/LF/CRLF and diagnostic spans                   | `SourceCoordinates` and `errorSpans` directly; no view model, parser or reflection                                                                                          |
| Captured JSON/DOT output                                            | `encodeExport` directly, retaining real encoder/parser smoke fixtures                                                                                                       |
| Debounce, generations, encoding failure and parser/export exclusion | Explicit test dispatchers, injected parser/encoder, deferred barriers and fake file operations                                                                              |
| Noncooperative parser/file work                                     | Bounded real executor tests with latches; release barriers and close executors in cleanup                                                                                   |
| Editor/tree and app wiring                                          | Headless Compose interactions; real parser fixtures for navigation and fake dialogs/files for orchestration ([#1702](https://github.com/albertocavalcante/gvy/issues/1702)) |
| File publication and package installation                           | Real isolated temporary filesystem fixtures and native failure tests; never a user's application or export                                                                  |

Internal `AstViewModel` construction requires an explicit `FileEnvironment`. Tests supply in-memory preferences and
choose their file operations and dispatchers. Only production construction creates live preferences. Always close the
model or dispose its owning app, and release gates in `finally` so a failed assertion cannot strand a worker.

Keep real integration tests alongside fakes: fake dispatchers do not prove blocking-worker limits, and fake dialogs do
not prove native picker behavior. Headless Compose tests do not measure rendered frame latency. Changes to platform
adapters still need native validation for every claimed OS/filesystem. Export replacement and Unicode normalization
remain tracked in [#1690](https://github.com/albertocavalcante/gvy/issues/1690) and
[#1190](https://github.com/albertocavalcante/gvy/issues/1190).

## Package and install

On macOS, `make -C viz/desktop install` builds the application image and installs the app into `/Applications` by
default. The destination must already exist and be writable by the current user. Set `INSTALL_DIR` to choose a different
existing writable directory, for example `make -C viz/desktop install INSTALL_DIR="$HOME/Applications"`.

Installation copies the complete app into a staging directory on the destination filesystem before replacing an existing
app. Backup, promotion, and rollback use macOS `renamex_np` with `RENAME_EXCL` through the system `osascript` bridge. If
the filesystem does not support exclusive renames, installation fails rather than overwriting another destination.

If rollback cannot restore the previous app, the error reports the backup path. An uncatchable interruption can also
leave a `.gvy-visualizer-install.lock` directory and a `.gvy-visualizer-stage.*` directory without printing the backup
path. Before clearing a stale lock, confirm that no installation is running, inspect the staged `previous` directory,
and recover any prior app without overwriting another installation. The installer does not infer process state or remove
stale locks automatically.

Run `make -C viz/desktop test-install` on macOS for isolated installation and native exclusive-rename tests. The native
primitive tests have been run on macOS 26.7.1 (25G241) on APFS; other macOS versions and filesystems require their own
validation. This test does not install or change an app in `/Applications`.
