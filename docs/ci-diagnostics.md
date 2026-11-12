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

Native launcher payload and Linux/macOS/Windows Native builds also use this wrapper, with separate stable producers and
failure artifacts. Their JDK 21/native-compiler processes are outside the Linux/JDK 17 numeric sampler contract; tool
diagnostics and original command/capture status remain required. The caller requires the reusable workflow to succeed.

The opt-in Native provisioning guard uses a fresh job-owned directory and records preflight, unapproved diagnostics,
verification and a final verdict. Its three host-specific Kotlin 2.3.0 overwrite warnings remain unexpected unless the
matching proof succeeds; their owned, expiring conditional debt remains visible even when absent. The isolated directory
contains provisioning writes but does not prove that only the installer's lock existed at the warning boundary or
establish full compiler integrity. Actual Native and packaged-payload tests remain required. See
[#1603](https://github.com/albertocavalcante/gvy/issues/1603).

Windows capture uses the explicit Git Bash path in `GVY_CI_GRADLE_BASH`. Before any evidence read, the
[launcher workflow](../.github/workflows/ci-launcher.yml) builds two small C helpers with native MinGW GCC 15 or newer
and sets `GVY_CI_EVIDENCE_READER` and `GVY_CI_PRIVATE_ROOT_HELPER` to their absolute paths. The reader checks the opened
Windows handle's type, attributes and byte limit; unavailable helpers fail closed. These variables configure the CI
capture tools.

The Windows guard creates a protected SID-only SDK root, checks known SDK directories/files for unsafe ACLs, reparse
points and hardlinks, and binds its full file identity across verification. Raw SIDs and file IDs stay in the held plan;
public receipts contain an identity digest. The same plan must survive preparation through final verification. Existing
workspace/temp/report directories are trusted CI inputs, and the guard does not protect against the same SID or
privileged actors. Synthetic controls and a local fresh-SDK experiment supplement the required integrated CI run.

First-party deprecated Kotlin DSL delegates and the old test-listener closure are migrated. Plugin deprecations have
exact source-attributed exceptions in `tools/ci/gradle-diagnostics.json`; inspected upstream versions, adoption
candidates and removal criteria remain tracked in [#1603](https://github.com/albertocavalcante/gvy/issues/1603). These
exceptions are debt, not proof that newer plugin releases have the same limitation.

Two Kover 0.9.11 visibility origins use `[emitter kover lazy configuration]` instead of a project name.
[Kover registers these configuration actions lazily](https://github.com/Kotlin/kotlinx-kover/blob/39fcc803490dbeecdcbc8d5535a165b1d472aaf9/kover-gradle-plugin/src/main/kotlin/kotlinx/kover/gradle/plugin/appliers/artifacts/AbstractVariantArtifacts.kt#L48-L68);
[Gradle executes registration actions when required](https://github.com/gradle/gradle/blob/8de7bcf76e785507ad92f0741ab9235a430c95e6/subprojects/core-api/src/main/java/org/gradle/api/NamedDomainObjectContainer.java#L104-L118).
The original cold trace emits both warnings after all projects were evaluated and `:tests:harnessTest` was selected, so
the last project header cannot establish their project ownership. The exact Gradle 9.8.2 message and full caller frames
remain part of each key; changed messages, Gradle versions, callers and first-party sources remain unexpected.

The [captured trace and provenance](../tools/ci/fixtures/gradle-kover-lazy-provenance.json) bind the two events to the
original run, tree, log hash and selected lines. Original context remains in the raw log; the existing string diagnostic
and strict report schema do not gain a separate project field. Only the two former `:viz:desktop` debt keys are
migrated; owners, expiry dates and the unresolved Kover retirement work in
[#1603](https://github.com/albertocavalcante/gvy/issues/1603) remain unchanged. A failed original command remains failed
after diagnostic attribution is corrected.

Linux/macOS Native integration adds fourteen exact upstream fingerprints from thirteen observed events per host. Windows
adds one exact bundle-notation fingerprint; its remaining plugin traces reuse existing entries. Three Native enum
consumer stacks retain distinct configuration-cache codec origins; Native download advice retains its verified bundle
emitter. These out-of-band messages must not inherit an unrelated project's last header. Unknown types, messages and
origins remain unexpected. For the originally inspected Linux/macOS traces, KGP 2.3.20 source fixes nine events, while
the Native enum limitation remains in inspected 2.3.20 and 2.4.21; source findings alone are not adoption or integration
proof.

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

The validation job also inspects Actions notice/warning/failure annotations from completed jobs in the exact run
attempt. Read-only API errors, stale identity and incomplete annotation pagination fail. It retains provenance and lists
skipped, unfinished and observer-excluded jobs; its snapshot is not a claim that its own later upload/post-job output or
another workflow was inspected. New runner/action diagnostics require the same exact, owned, expiring policy. Maintained
main-CI actions use verified Node 24 versions; removing old annotations from logs alone would not satisfy this check.

Notices remain informational and nonfatal by default. The snapshot's separate `notices` array preserves their original
severity, title, message and location with the repository, run, attempt, job, check, source and tested-checkout
identity. The summary shows their count and escaped contents even when the observation fails. Notices do not enter the
exact warning/failure diagnostic policy or its exception ledger; a notice alongside a new warning cannot make that
warning pass.

The retained notice array has a 256 KiB compact JSON UTF-8 budget, including its brackets, commas and provenance. Each
notice is validated and checked against that shared budget while its API page is processed; the observer never truncates
a notice. Exceeding the budget, malformed notice schema or failed/incomplete pagination fails the observation. Already
retained notices remain explicitly partial evidence, with `observationComplete: false`; they cannot establish readiness.
The report records the retained byte count and limit, including on observation errors. This budget applies to the
compact notice-array payload, not the entire report or formatted summary.

The whole rendered summary has a separate 512 KiB UTF-8 limit, below GitHub's
[1 MiB per-step limit](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-commands#step-isolation-and-limits).
This includes escaping expansion and all warning, error, job and debt text. Overflow sets `ok: false`, adds a stable
error and replaces the rendered output with a small failure report pointing to the complete `diagnostics.json` artifact.
Captured notice JSON remains intact; `observationComplete` continues to describe API capture, while failed rendering
blocks readiness.

Before appending to `GITHUB_STEP_SUMMARY`, the observer checks the existing prefix and new text against the same 512 KiB
limit. If the full report cannot fit, it fails and attempts the small failure report. If that also cannot fit, the
prefix is preserved and no text is appended; stderr, the JSON verdict and artifact `summary.md` still report the
failure. A summary-file write error also fails the verdict. Summary publication checks run before the final JSON verdict
is written, so these failures cannot become successful readiness evidence.

Notice title/message/location and identity are published in the existing diagnostic JSON artifact and escaped summary.
Notice `raw_details` and unknown API response fields are omitted. These are text fields from the repository's public
Checks API, not a private-log projection; never place credentials in annotations. Warning/failure diagnostic keys and
their existing raw-detail handling remain unchanged.

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
