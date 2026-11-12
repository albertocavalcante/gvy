# CI diagnostic accountability

A successful command is insufficient when it emits actionable diagnostics. Agents own the complete CI result for the
head they ship: failures, warnings, dependency advice, skips and tracked debt. Inspect the check results and generated
diagnostic summary before declaring work ready. Report counts, fixes, remaining issue links and validation limits in the
PR and completion message. Independent review must cover changes to diagnostic policy and exceptions.

## Required readiness

The required `Validation Check` evaluates the current workflow's dependency results. Applicable Gradle lint, unit/E2E
jobs, packaged-JAR smoke checks on Linux/macOS/Windows, the Native launcher workflow, Zed adapter checks and the
reusable Bazel workflow must succeed. Missing applicability output, a failed path check, an unexpected skip, a
cancellation, a failed runner check or pending runner provisioning fails readiness. Provisioning does not validate the
original revision: rerun that revision when the runner is available.

The applicability job also enforces temporary dependency ownership and expiry on every invocation, including
documentation-only changes. A dependency-maintenance failure therefore fails required readiness and is included in the
completed-job Actions observation; the separate maintenance workflow remains a daily and stacked-PR backstop.

Applicability comes from the exact event base/head revisions and the verified checkout, with a JSON artifact recording
both source and tested SHAs. Known narrative documentation is excluded from runtime suites. Zed component inputs require
their Rust checks; shared Makefile and main-workflow edits also require them. Gradle, Bazel and Zed applicability are
evaluated independently, including mixed changes and deletions. Unknown paths are conservatively tested; fixtures and
executable inputs remain applicable regardless of their extension. Release branch names do not bypass checks. The pure
state machine and Git-diff classifier have failure and negative-control tests.

The Zed gate covers pinned Rust formatting, host tests, host/WASM Clippy and the WASM build. Editor runtime, Java/JAR
launch, platform and lifecycle validation remain tracked in
[#1175](https://github.com/albertocavalcante/gvy/issues/1175).

## Bazel gate and build observability

`CI (Bazel)` is called by main CI for applicable changes and also runs daily on main or manually. Its build, test,
evidence and Buildifier results feed required readiness. Missing direct Kotlin dependencies and duplicate Maven versions
fail natively. The wrapper preserves original command/capture failures and rejects incomplete or mismatched BEP. Build
and test get unique invocation IDs, local BEP files, JSON profiles and receipts bound to the source SHA, tested
checkout, run ID and attempt. Remote upload stays disabled. Configuration, resource bounds and artifact contracts are
documented in [build observability](build-observability.md).

CI bootstraps the pinned executable with exactly `bazel --version`. Bazel 9 handles this in its native client before
reading rc files or starting a JVM; Bazelisk still resolves and caches the executable. Actual build/test commands then
run through the captured minimal environment. The pinned launcher's deprecated `-Xverify:none` server option remains
upstream retirement debt in [#1603](https://github.com/albertocavalcante/gvy/issues/1603); avoiding an unnecessary batch
JVM does not remove that underlying option.

A target reported as `SKIPPED` fails the evidence gate even if Bazel exits zero. The report retains its skip count and
stream completeness; an intentional platform incompatibility requires a reviewed disposition.

The diagnostic gate detects compiler/tool warnings, dependency add/remove advice, duplicate artifact versions and
repository reproducibility advice, even after a command fails. Routine progress and generated-file listings are
informational. It reads private console captures and publishes the warning/debt report to the job summary. Known
exceptions absent from a warm cache remain visible; new formats need regression coverage.

This repository is public. CI uploads only audited, versioned BEP/profile projections and invocation summaries, retained
for 14 days. Command/environment payloads, file/URL references, arbitrary text and raw captures are excluded. Graph IDs
are opaque; true outcomes, identity, timing and numeric metrics remain. Public traces use generic names and counter
keys; full labels and commands remain in private captures. Unknown, stale, symlinked, malformed or tampered publication
files block the upload. A failed projection never falls back to publishing raw evidence.

Ordinary Bazel console output is still streamed to GitHub job logs, subject to runner masking; the projection does not
make arbitrary program output safe to print. Never put credentials in command arguments or dump environments. Raw
private captures and diagnostic reports must stay outside the artifact directory and version control. This text
classifier is not proof that every future diagnostic syntax is understood.

Use a fresh publication directory for each local build/test pair, with the shared-machine build slot:

```bash
node --test tools/ci/*.test.mjs
GVY_EVIDENCE_ROOT="$HOME/.local/state/gvy/build-evidence/local-001"
build-slot --slots 1 -- node tools/ci/run-bazel.mjs \
  --report-root "$GVY_EVIDENCE_ROOT/public" --private-report-root "$GVY_EVIDENCE_ROOT/private" \
  --producer bazel-build build //...
build-slot --slots 1 -- node tools/ci/run-bazel.mjs \
  --report-root "$GVY_EVIDENCE_ROOT/public" --private-report-root "$GVY_EVIDENCE_ROOT/private" \
  --producer bazel-test test //...
node tools/ci/audit-public-bazel-evidence.mjs --report-root "$GVY_EVIDENCE_ROOT/public"
```

The private invocation directories share the public directories' UUID basenames. Check their console files without
placing the resulting reports in the public directory:

```bash
node tools/ci/bazel-diagnostics.mjs --baseline tools/ci/bazel-diagnostics.json \
  --report "$GVY_EVIDENCE_ROOT/diagnostics-private" "$GVY_EVIDENCE_ROOT/private/"*/console.log
```

## Gradle tool diagnostics

Required lint, unit, E2E and packaging invocations use `tools/ci/run-gradle.mjs`. It captures both streams, forces plain
console output and all warnings, and retains deprecation origin traces. A successful process with a new diagnostic fails
the step. Missing, empty, truncated or contradictory completion evidence and capture failures also fail. Each invocation
retains the original exit status, raw log/hash, exact diagnostics, owned exceptions and source/run context in a unique
evidence directory. CI uploads that directory even after failure.

Nightly lint and the server test/E2E/staging graph run in separate read-only Linux jobs, each with required numeric
observations and the same diagnostic gate. Their daemon lifetimes are isolated by job, while the 3 GiB heap, 2 GiB
metaspace and two-worker limits remain unchanged. Both jobs must succeed before the publishing job can run. Diagnostic
evidence stays separate from the verified JAR/VSIX pair.

Native launcher payload and Linux/macOS Native builds also use this wrapper, with separate stable producers and failure
artifacts. Their JDK 21/native-compiler processes are outside the Linux/JDK 17 numeric sampler contract; tool
diagnostics and original command/capture status remain required. The caller requires the reusable workflow to succeed.

First-party deprecated Kotlin DSL delegates and the old test-listener closure are migrated. Plugin deprecations have
exact source-attributed exceptions in `tools/ci/gradle-diagnostics.json`; inspected upstream versions, adoption
candidates and removal criteria remain tracked in [#1603](https://github.com/albertocavalcante/gvy/issues/1603). These
exceptions are debt, not proof that newer plugin releases have the same limitation.

Native integration adds fourteen exact upstream fingerprints from thirteen observed events per host. Three Native enum
consumer stacks retain distinct configuration-cache codec origins; Native download advice retains its verified bundle
emitter. These out-of-band messages must not inherit an unrelated project's last header. Unknown types, messages and
origins remain unexpected. Inspected KGP 2.3.20 source fixes nine of these events, while the Native enum limitation
remains in inspected 2.3.20 and 2.4.21; source findings alone are not adoption or integration proof.

Spotless 8.11.0 guards its obsolete configuration-visibility call on Gradle 9. Cold formatter checks retain pinned
ktlint 1.7.1 and existing output. The retired Spotless exception is removed; its original trace remains a negative
regression fixture.

Five exact generated SCIP compiler warnings also remain tracked in
[#1599](https://github.com/albertocavalcante/gvy/issues/1599). Gradle uses Wire 5.5.1, while Bazel's generator uses
5.4.0; matching warnings do not establish version alignment. The Gradle exceptions bind to the compilation task,
generated file, line, column and message. Other generated warnings and first-party warnings still fail. Retiring these
exceptions requires fresh generation and warning-free compilation plus protocol compatibility checks in both build
systems.

Unit/E2E jobs also require JUnit report files and preserve their HTML/XML reports as artifacts. Test jobs have read-only
tokens, including fork PRs; optional check-publishing actions are unnecessary for the required producer verdict. The
build explicitly executes both protobuf generators with their 512 MiB JVM bounds before compilation; cached task outputs
cannot substitute for this generator acceptance check.

Forked Gradle test JVMs explicitly disable CDS archive reuse because test instrumentation such as MockK appends
bootstrap classes. Instrumentation, coverage and warning reporting remain enabled. This setting applies to test workers;
separately launched server JVMs keep their own configuration.

The default Gradle daemon divides its 5 GiB heap/metaspace cap into a 3 GiB build heap and 2 GiB for loaded classes.
Cold compilation previously triggered daemon health advice with both the earlier 1 GiB and current 2 GiB metaspace
limits. Daemon expiration and GC-thrashing advice are diagnostics too; successful commands must not silently accept
them. Required CI Gradle invocations explicitly cap workers at two to bound concurrent compilation and test work while
retaining the 3+2 GiB daemon cap. Test workers have separate heaps; neither this cap nor the worker limit is a total-RSS
bound. Shared hosts still require the build slot and bounded local overrides.

Hosted Linux main-build producers also require bounded numeric daemon memory observations in each invocation's
memory.json. The sampler records heap/metaspace usage, GC and loaded/unloaded class counters without changing the
configured heap or metaspace limits. Its sampled maxima are the highest observations, not guaranteed peaks. Missing,
ambiguous, unsupported or failed sampling is incomplete evidence and fails the opted-in producer while preserving the
original Gradle exit.

The sampler identifies the current job's daemon with one exact immutable ErrorFile argument, current user and pinned
PID/start time. The argument is stable across protobuf, unit and JAR producers so their daemon remains reusable. Its
owner-only crash directory is outside the workspace and artifact root. Reports retain neither the argument/private path
nor command lines, environment values or class names. Only the sampler's own tool processes can be terminated; the probe
does not stop daemons or force GC. HotSpot can fall back to other crash-log destinations if its configured file cannot
open, so this does not make arbitrary fatal console output safe. Crash/core/replay files remain outside the explicit
artifact inputs.

The default Main observation profile uses five-second intervals, 30 minutes, 360 sample pairs and a 256 KiB report.
Nightly explicitly sets `GVY_GRADLE_MEMORY_PROFILE=nightly` alongside `GVY_GRADLE_MEMORY_PROBE=true`: its fixed profile
uses ten-second intervals and 60 minutes, retaining the same sample and byte caps. Unknown profiles and profiles without
probe opt-in fail before Gradle starts. Resolved numeric limits appear in the existing report shape. Each jstat command
has an explicit 64 MiB heap, a two-second timeout and a 32 KiB output limit. Use the complete context-bound observations
to evaluate cold compilation pressure before changing a resource profile; the memory-health warning remains a gate.

Gradle's indented captured test output is outside the tool classifier. Runtime warnings need scenario/test identity and
executed negative-test assertions; they cannot be safely accepted by baselining a whole console stream.

## Resolve or explicitly account for debt

Fix first-party warnings and dependency declarations. Preserve necessary runtime dependencies when removing compile
dependencies. Never mute an entire warning category, turn off dependency analysis, rewrite a baseline automatically or
mark a failed check successful to obtain a green result.

For an unavoidable upstream limitation, add an exact diagnostic to the corresponding `tools/ci/*-diagnostics.json`
ledger only after independent review. Every entry requires an owner, repository issue, impact/reason, next action,
acceptance criteria and expiry. A different file, target or diagnostic fails; there are no regular-expression
exceptions. Expired or malformed exceptions fail even when cached actions emit nothing. Renewing an exception requires
fresh evidence and review; remove obsolete entries when their fix is verified with a fresh build. Repeated Kotlin worker
output is deduplicated, and generated-source temporary roots are normalized to `generated/`.

Current tooling limits are tracked in [Wire generation](https://github.com/albertocavalcante/gvy/issues/1599) and the
exception ledger. Narrow deprecated-API compatibility paths are tracked in
[recipe and Maven migration](https://github.com/albertocavalcante/gvy/issues/1600); suppressions document their purpose
at the compatibility boundary. These items remain debt until their acceptance criteria are met.

Timeout advice is emitted per target with `--test_verbose_timeout_warnings`; the unqualified summary also fails if
encountered. Only elapsed seconds within that advice are normalized. Fast module tests have explicit short timeouts
while retaining their medium memory sizing. The portable build-tool budget and its exact shorter-timeout advisory are
tracked in [#1607](https://github.com/albertocavalcante/gvy/issues/1607); a new target or a recommendation to lengthen a
timeout still fails.

## Actions annotations and coverage limits

The validation job also inspects Actions warning/failure annotations from completed jobs in the exact run attempt.
Read-only API errors, stale identity and incomplete annotation pagination fail. It retains provenance and lists skipped,
unfinished and observer-excluded jobs; its snapshot is not a claim that its own later upload/post-job output or another
workflow was inspected. New runner/action diagnostics require the same exact, owned, expiring policy. Maintained main-CI
actions use verified Node 24 versions; removing old annotations from logs alone would not satisfy this check.

GitHub selective reruns can copy earlier successful job outputs into a new attempt while omitting their annotations. The
snapshot retains the trusted attempt start and each job's actual execution start. Required readiness demands one
successful, annotation-observed `check-paths` execution that started in the current attempt; that job is an ancestor of
the required producer graph. Other completed, non-skipped jobs in the snapshot must also have fresh observed execution,
including independent advisory jobs. Skipped, unfinished and observer records retain the declared snapshot boundary.
Retry CI with **Re-run all jobs** so policy, applicability and producer evidence share the attempt. A validation-only or
partial downstream rerun cannot clear earlier warnings through copied success records.

Passing-test server output remains outside these tool gates. The runtime producer/consumer contract is coordinated with
the E2E harness work and tracked in [#1603](https://github.com/albertocavalcante/gvy/issues/1603). It must preserve
complete server logs from passing tests, bind them to actual execution/assertion IDs and reject unexpected warnings
without blanket negative-test exceptions. Until both producer and consumer are implemented and validated, shipping
agents must report this coverage gap explicitly. Semantic LSP diagnostics remain protocol assertions.

Other workflows, advisory Sonar/dependency-update commands and the validation job's own post-job output still need agent
review. This implementation does not claim repository-wide warning coverage. Every newly observed uncovered diagnostic
must be fixed or recorded with the same ownership and acceptance criteria.

Inspect complete job logs with binary-safe readers, such as `rg --text`, because legitimate NUL-delimited Git output can
make a default text search omit later warnings. Preserve the original bytes and hash. The Gradle producer separately
rejects NUL-containing captures as incomplete evidence; stripping those bytes does not repair a failed capture.

Optional HotSpot test diagnostics use per-process `test-jvm-%p.log` files. Parallel forks must not share one truncating
log path. These secondary XML logs are separate from the required plain Gradle captures and numeric reports; retain
malformed bytes for investigation rather than stripping NULs or claiming the file is complete.

Bazel module-lock drift fails CI. Full Maven resolution reproducibility remains tracked in
[#1029](https://github.com/albertocavalcante/gvy/issues/1029): the Maven lock remains disabled after historical GMM
variant/checksum mismatches. Current stable-resolver attribution and fresh cross-environment agreement still require
verification.

Temporary forks, patches, custom dependency downloads and resolver escapes also have a separate lightweight
[maintenance inventory and review-date gate](dependency-maintenance.md). It checks declarations on every PR and daily,
including sources absent from warm-cache logs; passing diagnostic output does not renew dependency retirement debt.
