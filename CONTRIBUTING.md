# Contributing to Groovy Language Server

Thanks for your interest in contributing!

## How to Contribute

1. **Fork the repository**
2. **Create a feature branch** (`git checkout -b feature/amazing-feature`)
3. **Make your changes**
4. **Run tests** (`./gradlew test`)
5. **Commit your changes** (`git commit -m 'Add amazing feature'`)
6. **Push to your branch** (`git push origin feature/amazing-feature`)
7. **Open a Pull Request**

## Development Setup

### Requirements

- JDK 17 or higher
- Kotlin 2.0+

### Building

```bash
./gradlew build
```

### Running Tests

```bash
./gradlew test
```

The root `test` task includes JVM unit tests and KMP JVM tests. Run the protocol suite with `./gradlew :tests:e2eTest`.

Tests use Java 17 by default, so Gradle needs a Java 17 toolchain. It can use a locally installed JDK or download one
through the configured Foojay resolver when provisioning is available. If provisioning is unavailable, install JDK 17
locally.

If only JDK 21 is available, select it explicitly with the following command. This also provides an additional
compatibility check; it does not validate the minimum Java 17 runtime:

```bash
./gradlew test -PtestJavaVersion=21
```

Changing the test runtime leaves compilation toolchains and the Java 17 bytecode target unchanged.

Run `./gradlew lint` for Detekt, Spotless and the formatter target regression. To check only target selection, run
`./gradlew verifyKotlinFormattingTargets`. It checks tracked source layouts and briefly creates generated Kotlin probes
under each selected project's build directory, then removes them. Existing probe files cause the check to fail.

## Pull Request Guidelines

- Include tests for new functionality
- Update documentation as needed
- Follow existing code style
- Keep commits focused and atomic
- Write clear commit messages

## Reporting Issues

Please use GitHub Issues to report bugs or request features. Include:

- Clear description
- Steps to reproduce (for bugs)
- Expected behavior
- Actual behavior
- Environment details (OS, Java version, etc.)

## Questions?

Feel free to open an issue for discussion or questions about contributing.

## Developer commands

`Justfile` is the canonical command interface; Gradle and pnpm remain the build systems. Use Just 1.59.0. If it is not
installed, the repository bootstrap verifies the pinned release archive checksum before extracting or replacing the
binary:

```sh
node tools/setup-just.js --bin-dir "$HOME/.local/bin"
export PATH="$HOME/.local/bin:$PATH"
just
```

On Windows, use `node tools/setup-just.js --bin-dir "$env:LOCALAPPDATA/gvy/bin"` and add that directory to PATH. The
bootstrap needs Node and `tar` (included on supported Windows runners). CI uses the same bootstrap with `--github-path`.
Supported bootstrap targets are Linux, macOS and Windows on x64 and arm64.

Common commands:

| Command                                           | Behavior                                                                        |
| ------------------------------------------------- | ------------------------------------------------------------------------------- |
| `just doctor`                                     | Check source-build prerequisites                                                |
| `just install-vscode-source --json`               | Build, verify and install this checkout                                         |
| `just package-source --build-root /path/to/build` | Build and verify a VSIX without installing                                      |
| `just build`, `just test`, `just lint`            | Delegate to the corresponding Gradle tasks                                      |
| `just check-editor`                               | Compile, type-check, lint and unit-test the extension and tooling               |
| `just check-commands`                             | Check Just formatting and execute recipe contract tests                         |
| `just check`                                      | Formatting, JVM quality/coverage verification, tests, editor and command checks |

Recipes run from the repository root even when invoked from a subdirectory. From the parent directory, use
`just gvy/build`. Arguments are forwarded as an argument list, so quote paths containing spaces normally. Windows `.cmd`
launchers retain their explicit shell-metacharacter restrictions.

Existing Make targets remain compatibility delegates to Just. For example, `make test GRADLE_ARGS="--tests ExampleTest"`
still works. New automation should invoke Just or the underlying Gradle/Node/pnpm entry point. Direct Node source
installation remains available without Just.

The migration makes these behavior changes explicit:

- Editor install/package aliases now use this checkout's server and client.
- `ext-editors` refuses to install into several editors implicitly; choose one.
- `release-publish` requires a version argument (`make release-publish VERSION=vX.Y.Z`).
- `verify-builds` fails because the former command only printed filenames and did not prove artifact equivalence.
- Desktop installation retains its existing nested `viz/desktop/Makefile`; its packaging/install policy is outside this
  root-command migration.

The existing dev container does not provide Node or Just. Install those prerequisites before using the Node/Just
workflow there, or use `./gradlew` directly for JVM work.

### Known command limitations

These are observed gaps, rather than successful checks:

| Gap and impact                                                                                                                                                                                                                                                        | Responsible scope and next action                                                                                                                                                                                                                          | Acceptance criteria                                                                                                                                                                           |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `just fmt` / Gradle `lintFix` still fail on 16 existing findings in `viz/ast-model` (converter complexity/exception handling and serialization-test literals). The auto-correct task scans all `src`; normal Detekt currently reports no sources for this KMP module. | Visualization quality tooling: refactor the converters and fixture constants, then wire KMP source sets into normal lint. These behavior-sensitive changes are outside the command migration; adding suppressions or a new baseline would conceal the gap. | Both `./gradlew lintFix` and `./gradlew lint` pass, and normal lint demonstrably analyzes the KMP sources.                                                                                    |
| The dev container lacks Node and Just, so its image cannot run the documented command interface without extra setup.                                                                                                                                                  | Dev-container tooling: add Node, the package manager, and the pinned Just bootstrap to the image in a separately validated image update.                                                                                                                   | A fresh image runs `just --version`, `just check-commands`, and `just check-editor`.                                                                                                          |
| Runmux's live gvy pool admits `main` and the owner canary, but rejects PR events; current PR jobs still use hosted runners.                                                                                                                                           | Infrastructure [PR #1165](https://github.com/albertocavalcante/gvy/pull/1165): reconcile its routing with the active configuration and establish trusted PR admission. This migration must not broaden live runner policy.                                 | Required checks on the intended SHA complete on the intended pool with an `rmx-air-` runner name and Linux/ARM64/non-root evidence; retain explicit native Windows/macOS and x86-64 coverage. |
