# CLI first agent tooling and self contained runtime distribution

Date: 2026-11-11. Status: research proposal, tracked in [#1741](https://github.com/albertocavalcante/gvy/issues/1741).
No launcher, runtime image or performance improvement is delivered by this record.

## Decision direction

The owner prefers CLI workflows. Make gvy useful directly from a shell and agent harness, with LSP and optional MCP as
other adapters over shared analysis services. A useful CLI must not depend on an MCP connection or an editor session.

The recommended packaging experiment is a clean launcher plus a tested JVM runtime image, with a matching `gvy_nojdk`
variant. For single-file delivery, evaluate a native executable carrying the application and runtime payload and
extracting it to a private user cache. This retains a real JVM and dynamic project loading. AOT compilation remains a
separate compatibility experiment.

The smallest acceptable runtime is the smallest one that preserves supported analysis behavior. In gvy today, host
runtime modules also supply some of the JDK types visible to analyzed projects; a successful startup is therefore
insufficient evidence of a correct trimmed image.

This record complements [MCP research](MCP_RESEARCH.md), the [Quality Bar](../docs/quality-bar.md) and
[#1548](https://github.com/albertocavalcante/gvy/issues/1548). It studies the agent CLI, launcher and runtime
independently. No artifact sizes, startup savings, Native Image parity or minimum module list were measured.

## Existing CLI and distribution

Source snapshot: gvy `ce45ade0bd665ad1970d74482f8ed28aec1c628d` [S01].

| Existing behavior                                        | Implication                                                                                                                                                                   |
| -------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `check` accepts files/workspace and TEXT or SARIF output | Useful groundwork; truthful handling of incomplete analysis is owned by #1710.                                                                                                |
| `format` prints content to stdout                        | The command has no stdin/write/check/diff modes; several files concatenate output. A refused format currently prints unchanged content, warns on stderr and returns normally. |
| `index` writes SCIP/LSIF                                 | The exporter has a separate lightweight parser/definition model; do not silently use it as the full semantic-query engine.                                                    |
| `version --json` emits provenance                        | Reuse and extend the existing machine-readable convention.                                                                                                                    |
| `execute` wraps executeCommand                           | Only `groovy.version` is advertised; this is not a general semantic-query CLI.                                                                                                |
| `jenkins extract/validate` provides task commands        | Extraction progress/output and all-download-failure outcomes need explicit agent contracts.                                                                                   |
| No arguments start LSP stdio                             | Preserve existing editor/JAR compatibility while designing a discoverable CLI entry point.                                                                                    |

Homebrew already provides a clean `gvy` command. At tap revision `25f05839742ec2771a9e733844aed162d7ff0f9d`, the formula
depends on `openjdk@17` and writes a shim using that Java, a bounded heap, the installed JAR and unchanged arguments. It
preserves project `JAVA_HOME`, but has no BYO-Java override [S02]. This is package-manager-supplied Java, rather than a
runtime embedded inside the release executable.

Standalone release assembly still publishes the shaded JAR, VSIX and checksums. The build uses JDK 21 while emitted
bytecode targets Java 17; the documented runtime floor is Java 17. Neither a bundled runtime nor a standalone `_nojdk`
pipeline was found in the inspected release source. Existing runtime-dependency/JAR-size reporting tasks can support
later measurement; optional minimization configuration is not evidence of safe size savings [S01].

## Agent CLI contract

CLI is a first-class product surface. Reuse compilation, documents, classpaths, diagnostics and providers through shared
services; keep semantic logic out of command parsing and transport adapters. Existing composition lives in private
fields of `GroovyLanguageServer`, so extracting that boundary is real work [S01].

Proposed task families, not promises of existing commands:

| Task family      | Useful operations                         | Required result                                                                           |
| ---------------- | ----------------------------------------- | ----------------------------------------------------------------------------------------- |
| Readiness        | Runtime/project doctor, workspace state   | Selected runtime, module availability, project JDK/classpath/index readiness and failures |
| Semantic queries | Symbols, declaration details, references  | Bounded candidates and results, source identity, coverage and ambiguity                   |
| Verification     | Check edited sources, compare diagnostics | Exact source versions, complete/incomplete status, provider failures and findings         |
| Safe changes     | Format check/diff, rename preview         | Reviewable output, explicit refusal, version validation and no surprise writes            |

Use a versioned JSON contract for agent results, retaining appropriate text and SARIF output. Keep stdout limited to the
selected data/protocol format and send progress to stderr. Distinguish findings, invalid inputs, incomplete analysis,
execution failures and cancellation through documented outcomes and exit semantics. Do not turn unsupported or failed
analysis into successful empty results.

Use explicit workspace and file/symbol selection, source hashes/versions, output bounds and cancellation. A future
batch/JSONL mode can amortize setup across requests. First measure one-shot and batch behavior; an optional workspace
session may later retain analysis, with explicit identity, bounded memory, idle expiry and shutdown. Current socket
connections create separate servers and do not already share an editor's state [S01]. Reuse #1709/#1713 rather than
inventing a second analysis authority.

## What Bazel demonstrates

Bazel 9.3.2 publishes both ordinary and `bazel_nojdk` executable variants on its supported platform/CPU combinations
[S03]. Its pinned packaging rule concatenates a compiled native client and ZIP payload; the release graph selects a
minimized embedded runtime or excludes it [S04]. Thus the user's analogy is technically applicable without requiring gvy
to become an AOT-compiled application.

Bazel's launcher chooses an explicit Java override before bundled Java, then optionally discovers system Java [S05]. Its
long-lived server lets multiple CLI operations share cached work, with workspace-specific server identity [S06]. gvy can
adopt the packaging and identity ideas while measuring whether a persistent process earns its resource cost.

Bazel also demonstrates why static dependency analysis is insufficient: its runtime-minimization script explicitly adds
a TLS module missed by `jdeps` [S04]. Derive gvy's module and provider closure from its own behavior, rather than
copying Bazel's list or implementation.

## Packaging choices

| Option                                     | User experience                                          | Boundary                                                                                           |
| ------------------------------------------ | -------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| Script shim around the JAR                 | A normal `gvy` command                                   | Lowest-complexity bridge; Java must be supplied or installed by a package manager.                 |
| Native launcher plus runtime/app directory | A normal executable with private supporting files        | Can preserve JVM behavior and explicit runtime selection.                                          |
| `jpackage` app image or installer          | Native launcher and self-contained installed application | Supports nonmodular JARs and a supplied runtime image; the application remains a directory/bundle. |
| Native executable with compressed payload  | One runnable download, then private extracted files      | Adds cache identity, extraction, update and recovery responsibilities.                             |
| Native Image / Crema                       | A different executable/runtime model                     | Separate dynamic-loading, metadata, lifecycle and feature-parity gates.                            |

`jpackage` accepts a nonmodular main JAR and an existing `--runtime-image`, so a JPMS migration is not required. Native
packaging must be produced for the target platform [S07]. It does not by itself produce a literal one-file installed
application.

For single-file delivery, propose one binary per OS/CPU containing the launcher, application and selected runtime. On
first use, extract atomically to a version/content-specific user cache, validate the selected payload and launch it.
Test concurrent launches, interruption, corruption, read-only installation paths, cache relocation and coexistence of
versions. Preserve signatures/notices and verify payload provenance. Keep stdout clean during extraction, particularly
for LSP/MCP stdio.

The `gvy_nojdk` flavor should carry the same application payload and launcher behavior while omitting Java. Specify an
explicit runtime override before bundled-runtime selection, then documented system discovery for the BYO case. An
invalid explicit override must fail visibly. Report which runtime was selected and keep the gvy host runtime separate
from project Gradle/toolchain selection. Do not silently download a runtime during an ordinary command.

Choose launcher implementation language based on platform support, process handling, maintenance and packaging evidence.
The native layer needs runtime/payload selection and process lifecycle, not another semantic engine. Correct argument
forwarding, Unicode paths, exit status and cancellation matter more than matching Bazel's implementation language.

## Minimum JVM correctness gate

`jlink` creates a runtime from selected modules and their transitive dependencies. Java 17 offers debug/header/manual
stripping and compression; service binding can expand the closure. `jdeps --print-module-deps` can seed the module list,
but static analysis misses reflection and cannot establish all optional/provider behavior [S08, S09]. Keep Groovy/Kotlin
application libraries on the classpath initially.

The gvy-specific risk is broader than server dependencies. `ClasspathService` uses the system loader, parents project
JAR loaders to it and installs a reflection solver. Missing classes can become empty member lists or unresolved symbols.
Native compilation also uses the host platform loader; configured `javaHome` currently reaches Gradle tooling rather
than an independent target-JDK symbol image [S10].

Consequently, dropping `java.sql` or `java.desktop` could change answers about `java.sql.Connection` or `java.awt.Color`
even if gvy initializes successfully. This is a source-derived risk; no reduced-runtime reproduction was executed.
Preserve the supported JDK API universe or first design an explicit target-JDK metadata/resolution boundary.

Derive and version the module manifest against the complete final application payload, including services, optional
paths, TLS/resources, diagnostics, formatting and project loading. Test SQL, desktop and XML types, previously
unseen/replaced dependency JARs and any required compiler tools. No direct production `ToolProvider` or
joint-compilation call was found, but dependency internals were not exhaustively audited; this does not justify dropping
`jdk.compiler` [S01].

Use a maintained runtime line compatible with the supported floor as the initial baseline; Temurin 17 is one concrete
candidate while retaining JDK 21 for the build. Preserve its supplied licenses/notices and exact vendor/build
provenance. Bundling Java creates an ongoing runtime update/rebuild obligation; Temurin documents its licensing and
quarterly update policy [S11]. A newer JVM can be compared separately without confusing runtime upgrades with packaging
savings.

## Native compilation is a separate experiment

Default Native Image has closed-world and reachability requirements. Parsing previously unseen source as data differs
from loading previously unseen dependency bytecode. gvy's Groovy compiler, reflection, CodeNarc and project JAR loading
need exact-stack tests [S01, S12].

Avoid a blanket claim that native compilation cannot load classes: released Crema documentation supports runtime class
definition with additional options, but may need a JDK module payload and cannot recover every member eliminated during
image construction. The reviewed implementation also states runtime-loaded classes cannot currently unload, which
matters for dependency and ruleset churn [S13]. Groovy 6's incubating Native Image work does not certify gvy's Groovy
4.0.33 stack [S14].

A native launcher with a bundled JVM already addresses JAR invocation and Java installation ceremony. Evaluate AOT/Crema
through the existing compatibility/profiling work when its benefits justify the additional runtime constraints.

## Evidence and delivery sequence

1. Reuse #1710/#1543 to make current CLI outcomes and stdout trustworthy, then specify useful semantic-query JSON and
   batch contracts.
2. Standardize the launcher interface across distribution channels, preserving current editor entry points and project
   toolchain selection.
3. Establish full-runtime parity, then test a versioned `jlink` candidate and a matching BYO-Java flavor.
4. Measure one-file packaging independently: download size, installed/cache size, cold extraction, first useful answer,
   repeated calls and process memory.
5. Decide whether persistent sessions or AOT options improve the measured workload without weakening semantic
   correctness.

All steps require exact application/runtime/platform identities and original behavior fixtures. Compare findings and
completeness, not only successful process exit. Keep one-shot, first-use and warm measurements separate. No minimum
image size or speedup is established here.

Reuse #1675 for release-channel synchronization, #1736 for explicit update/rollback, #1562/#1564 for metrics and session
budgets, and #1709/#1713 for verification/session ownership. Track launcher/runtime acceptance in #1741 and keep #1727
CLI-first. These are dependencies and ownership links, not claims that their work is complete.

## Initial implementation decision

The owner selected Kotlin/Native for the first launcher so it can be dogfooded in a real project. Learning its practical
strengths and shortcomings is an explicit objective; a Go comparison is not a prerequisite. Start with the actual JVM
artifact and preserve its commands, arguments, streams, environment, cancellation and exits. Keep semantic operations in
the JVM and the launcher replaceable through that process boundary.

The [source preview](../launcher/README.md) implements the first external-Java slice for macOS Apple silicon and Linux
x64. This does not establish a smaller runtime, a faster startup, single-file delivery or complete platform coverage.
Bundled/BYO runtime parity, Windows process behavior and the remaining packaging decisions stay in #1741.

## Claims and open decisions

| Claim                                                  | Status                                          | Evidence or decision gate                                       |
| ------------------------------------------------------ | ----------------------------------------------- | --------------------------------------------------------------- |
| gvy already has CLI commands and a Homebrew shim       | Confirmed at inspected revisions                | S01, S02; does not establish standalone bundled-runtime support |
| Bazel ships bundled and no-JDK executable variants     | Confirmed for 9.3.2                             | S03–S05                                                         |
| A native launcher can carry a JVM/application payload  | Confirmed prior-art mechanism; proposed for gvy | S04; requires gvy's own extraction and platform tests           |
| A `jdeps`-minimal server image preserves gvy semantics | Not established                                 | S09–S10; needs JDK-type and feature parity                      |
| CLI should be the primary agent surface                | Owner requirement                               | Shared core and optional LSP/MCP adapters                       |
| A warm session or native compilation improves gvy      | Not established                                 | Correctness-first cold/warm/resource measurements               |

Open decisions include the supported project-JDK API universe, target-JDK separation, exact runtime module/provider
closure, launcher implementation, selected OS/CPU/libc matrix and whether extraction or persistent sessions earn their
maintenance cost. #1741 owns recording these decisions and splitting any approved implementation into bounded changes.
Existing correctness debt remains with its linked owners.

## Sources and refresh

Primary sources were checked on 2026-11-11. Mutable manuals are scoped to the named JDK version; code references are
pinned.

- **S01** [gvy source snapshot](https://github.com/albertocavalcante/gvy/tree/ce45ade0bd665ad1970d74482f8ed28aec1c628d):
  `gls/.../cli/{GlsCommand,CheckCommand,FormatCommand,VersionCommand}.kt`;
  `services/{IndexExportService,GroovyWorkspaceService}.kt`; `gls/.../GroovyLanguageServer.kt`; `tools/release.sh`;
  `gls/build.gradle.kts`; root build and dependency catalog. Source inspection, no runtime experiment.
- **S02**
  [Homebrew gvy formula](https://github.com/albertocavalcante/homebrew-tap/blob/25f05839742ec2771a9e733844aed162d7ff0f9d/Formula/gvy.rb#L13):
  dependency and launcher contract.
- **S03** [Bazel 9.3.2 release](https://github.com/bazelbuild/bazel/releases/tag/9.3.2): executable variants verified
  through release metadata; no asset downloaded.
- **S04** Bazel release source `5951e93c05876016377063a150f8b56f38276461`:
  [binary packaging](https://github.com/bazelbuild/bazel/blob/5951e93c05876016377063a150f8b56f38276461/src/bazel_binary.bzl),
  [release graph](https://github.com/bazelbuild/bazel/blob/5951e93c05876016377063a150f8b56f38276461/src/BUILD) and
  [runtime minimization](https://github.com/bazelbuild/bazel/blob/5951e93c05876016377063a150f8b56f38276461/src/minimize_jdk.sh).
- **S05**
  [Bazel Java selection](https://github.com/bazelbuild/bazel/blob/5951e93c05876016377063a150f8b56f38276461/src/main/cpp/startup_options.cc#L459):
  explicit, bundled and discovered runtime precedence.
- **S06** [Bazel client/server model](https://bazel.build/run/client-server): caches and workspace identity; not a gvy
  performance measurement.
- **S07** [JDK 17 jpackage](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jpackage.html): nonmodular JAR
  input, runtime images and platform packaging.
- **S08** [JDK 17 jlink](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jlink.html): module closure,
  stripping, compression, services and image maintenance.
- **S09** [JDK 17 jdeps](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jdeps.html) and
  [Oracle migration analysis limitations](https://docs.oracle.com/en/java/javase/25/migrate/preparing-migration.html):
  static dependency analysis does not prove reflective closure.
- **S10** gvy pinned host-runtime boundaries:
  [ClasspathService](https://github.com/albertocavalcante/gvy/blob/ce45ade0bd665ad1970d74482f8ed28aec1c628d/gls/src/main/kotlin/com/github/albertocavalcante/gvy/gls/services/ClasspathService.kt#L26),
  [ClasspathReflection](https://github.com/albertocavalcante/gvy/blob/ce45ade0bd665ad1970d74482f8ed28aec1c628d/gls/src/main/kotlin/com/github/albertocavalcante/gvy/gls/services/ClasspathReflection.kt#L27)
  and
  [ReflectionTypeSolver](https://github.com/albertocavalcante/gvy/blob/ce45ade0bd665ad1970d74482f8ed28aec1c628d/parser/core/src/main/kotlin/com/github/albertocavalcante/groovyparser/resolution/typesolvers/ReflectionTypeSolver.kt#L42).
- **S11** [Temurin FAQ/licensing](https://adoptium.net/docs/faq) and
  [support/update policy](https://adoptium.net/support/): candidate runtime obligations, not a vendor selection already
  implemented by gvy.
- **S12** [Native Image reachability metadata](https://www.graalvm.org/latest/reference-manual/native-image/metadata/):
  ordinary AOT compatibility model.
- **S13** Crema at `e3cb74fceaf6ee4f50366d5ab2bbf602e464475d`:
  [runtime-loading contract](https://github.com/oracle/graal/blob/e3cb74fceaf6ee4f50366d5ab2bbf602e464475d/substratevm/docs/runtime-class-loading.md)
  and
  [class lifetime restriction](https://github.com/oracle/graal/blob/e3cb74fceaf6ee4f50366d5ab2bbf602e464475d/substratevm/src/com.oracle.svm.interpreter.metadata/src/com/oracle/svm/interpreter/metadata/RuntimeLoadedClassHierarchy.java#L53).
- **S14**
  [Groovy 6 release notes](https://www.groovy-lang.org/releasenotes/groovy-6.0.html#_dynamic_groovy_in_graalvm_native_images_incubating):
  separate incubating compatibility lane.

Before implementation, refresh the gvy CLI and loader paths, tap shim, linked issue ownership, Bazel release mechanism
and chosen JDK/SDK contracts. Record artifact provenance, selected modules, platform and commands with each
compatibility run. Reopen the packaging decision if target-JDK separation, dynamic-loading support or the measured
size/startup tradeoff changes.
