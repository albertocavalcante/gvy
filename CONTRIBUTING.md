# Contributing to gvy

Use this guide to build gvy, validate changes, and open a pull request. To install gvy for everyday use, start with the
[README](README.md#get-started).

## Choose where to work

- [Repository overview](docs/overview.md): modules and their responsibilities.
- [Kotlin style and readability](docs/kotlin-style.md): readable conditions, control flow, naming, and idioms.
- [Quality Bar](docs/quality-bar.md): project goals and priorities.
- [Editor contributor guide](editors/code/CONTRIBUTING.md): TypeScript extension setup and testing.
- [Knowledge base](kb/README.md): architecture, research, and design records.

Use a feature branch in your own clone or an isolated worktree. Preserve other contributors' checkouts and uncommitted
work. Coding agents must also read [AGENTS.md](AGENTS.md) and the applicable instructions in the directories they edit.

## Set up the JVM build

- Install **JDK 21** for compilation. gvy targets a Java 17 runtime.
- Make **JDK 17** available for the default test runtime.
- Use the checked-in Gradle wrapper (`./gradlew`); a separate Kotlin or Gradle installation is not required.

Gradle can provision compilation and test toolchains through the configured Foojay resolver. If provisioning is
unavailable, install the required JDKs locally. The [version catalog](gradle/libs.versions.toml) and
[Gradle wrapper configuration](gradle/wrapper/gradle-wrapper.properties) define dependency and build-tool versions.

Run the commands below from the repository root. If you use direnv, review `.envrc` before allowing it in a new
worktree. On shared machines with `build-slot` installed, wrap Gradle invocations with `build-slot --` to queue
memory-intensive work. Run one Gradle build at a time per machine.

## Build and run the server

Build the standalone server JAR:

```bash
./gradlew :gls:shadowJar
```

The output is `gls/build/libs/gls-<version>-all.jar`. To run it, replace `<version>` with the version in the generated
filename:

```bash
java -Xmx2g -jar "gls/build/libs/gls-<version>-all.jar" --help
```

See the [server usage guide](docs/lsp/usage.md) for LSP transports and editor configuration, or
[build and install this checkout](editors/code/CONTRIBUTING.md#build-and-install-this-checkout) to try the extension
with your server changes.

To try the native command entry point, see the [Kotlin/Native launcher preview](launcher/README.md). It builds the same
JVM payload behind a native executable and currently targets macOS Apple silicon, Linux x64, and Windows x64.

For a full build, including tests:

```bash
./gradlew build
```

## Validate your changes

Match validation to the changed behavior. Documentation-only changes need formatting, link checks, and a review of the
instructions and claims. Code changes need relevant tests and lint checks; required CI checks must pass before merge.

### License metadata

Bootstrap the pinned uv version with
`python -m pip install --require-hashes --only-binary=:all: -r .reuse/bootstrap-requirements.txt`, then install the
locked tools with `uv sync --project .reuse --locked`. Run `make license-check` and `make license-test` offline. The
equivalent portable check is `uv run --offline --project .reuse --locked --no-dev python .reuse/check.py --root .`.

When updating uv, update the version and all wheel SHA-256 hashes in `.reuse/bootstrap-requirements.txt` from the
[uv release metadata](https://pypi.org/pypi/uv/0.13.7/json), then validate the Linux, macOS, and Windows tooling jobs.

Original maintained source files require leading SPDX copyright and Apache-2.0 license comments. Preserve existing
copyright notices and attributed holders; use `Alberto Cavalcante and the gvy contributors` for original gvy source
where a more specific holder is not known. This does not change the attribution or license of imported material. REUSE
metadata covers non-commentable files and byte-sensitive fixtures, but does not replace the required source headers.
Audit imported material and keep its provenance and license explicit. The checker validates the canonical Apache-2.0
text and REUSE coverage as well as the leading comments. Do not use bulk annotation on fixtures or scripts without
checking encodings and byte-sensitive behavior.

See [license metadata and source headers](docs/licensing.md) for file classification, imported material and source
export requirements. Gradle verification and publication tasks share the same offline check.

### JVM code

Run a focused test while iterating, then the relevant broader checks:

```bash
./gradlew :gls:test --tests '*HoverProviderTest'
./gradlew test
./gradlew lint
```

The root `test` task includes JVM unit tests and Kotlin Multiplatform JVM tests. Run protocol scenarios with
`./gradlew :tests:e2eTest`; see the [scenario guide](tests/e2e/resources/scenarios/README.md) to add or select a
scenario.

Tests use Java 17 by default. If only JDK 21 is available, select it explicitly:

```bash
./gradlew test -PtestJavaVersion=21
```

This provides an additional compatibility check; it does not validate the minimum Java 17 runtime. Changing the test
runtime leaves compilation toolchains and the Java 17 bytecode target unchanged.

`lint` runs Detekt, Spotless, and the formatter target regression. Use `./gradlew lintFix` to apply source fixes, then
inspect the diff. To check only Kotlin formatter target selection, run `./gradlew verifyKotlinFormattingTargets`. It
briefly creates generated Kotlin probes in selected projects' build directories, then removes them; existing probes
cause the check to fail.

### Documentation

Use dprint with the checked-in [configuration](dprint.json). For example, when editing the entry-point guides:

```bash
dprint fmt README.md CONTRIBUTING.md
dprint check README.md CONTRIBUTING.md
```

Pass the files you changed, check relative links and heading anchors, and verify commands against the current source.
The editor directory also uses [Prettier](editors/code/.prettierrc); follow its contributor guide for local checks.

Release maintainers must update the direct VSIX download links in `README.md` and `docs/installation.md` when a release
is published. Keep the Homebrew JAR and VSIX URL/digest pairs in sync through reviewed tap PRs.
[Distribution publishing](https://github.com/albertocavalcante/gvy/issues/1675) tracks automating those updates and
enabling editor registry publishing.

### Editor extension

Follow the [editor validation instructions](editors/code/CONTRIBUTING.md#testing). Use pnpm for `editors/code/`. Backend
integration tests must use a server built from the candidate checkout, as described there.

### Zed adapter

The experimental [Zed adapter](editors/zed/README.md) has a separate Rust toolchain and local-JAR development procedure.
Run `make zed-check` and `make zed-build` for adapter changes. These commands validate Rust and WASM; the adapter guide
also describes the required real-editor checks.

## Where documentation belongs

| Audience or purpose         | Destination                                                                                                       | Content                                                      |
| --------------------------- | ----------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------ |
| Someone evaluating gvy      | [README](README.md)                                                                                               | Purpose, maturity, installation, and links to next steps     |
| Someone using gvy           | [User documentation](docs/README.md#use-gvy) and [editor guide](editors/code/README.md)                           | Configuration, commands, support limits, and troubleshooting |
| Human contributors          | This guide, [editor contributor guide](editors/code/CONTRIBUTING.md), and [repository overview](docs/overview.md) | Setup, tests, module responsibilities, and PR expectations   |
| Coding agents               | [AGENTS.md](AGENTS.md) and `.agent/`                                                                              | Execution rules and automation workflows                     |
| Design and maintenance work | [Knowledge base](kb/README.md)                                                                                    | Architecture, RFCs, research, audits, and historical plans   |

Keep each procedure in one place and link to it from other entry points. Treat the [Quality Bar](docs/quality-bar.md) as
the single roadmap; track progress in its linked issues. Label goals and planned work so readers can distinguish them
from shipped behavior.

## Open a pull request

- Add a regression test for a bug fix and appropriate tests for new behavior.
- Keep the change focused and update the documentation affected by it.
- Use Conventional Commit subjects, such as `fix(lsp): handle unresolved symbols` or `docs: clarify server setup`.
- Explain the user impact, validation, and any remaining limits in the PR description.
- Address review findings and required checks. Maintainers use squash merges to keep history linear.

Review [CI diagnostic accountability](docs/ci-diagnostics.md) when evaluating build results. Report actionable warnings
and skipped validation alongside failures.

## Report bugs and dependency issues

Use [GitHub Issues](https://github.com/albertocavalcante/gvy/issues) for bugs, feature requests, and contribution
questions. Include a minimal example, reproduction steps, expected and actual behavior, and environment versions.

Dependencies can have bugs too. Track suspected and confirmed upstream defects in gvy's issue board with evidence,
affected versions, mitigations, and follow-up ownership. A local workaround does not resolve the upstream defect.

Follow the [dependency bug policy](docs/dependency-bugs.md) when investigating or preparing an upstream contribution.
Agents must keep upstream submissions local for human review until a maintainer explicitly approves the specific
material and target. Check the upstream project's contribution and AI policies before submitting.
