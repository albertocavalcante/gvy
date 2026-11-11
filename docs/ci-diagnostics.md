# CI diagnostic accountability

A successful command is insufficient when it emits actionable diagnostics. Agents own the complete CI result for the
head they ship: failures, warnings, dependency advice, skips and tracked debt. Inspect the check results and generated
diagnostic summary before declaring work ready. Report counts, fixes, remaining issue links and validation limits in the
PR and completion message. Independent review must cover changes to diagnostic policy and exceptions.

## Required readiness

The required `Validation Check` evaluates the current workflow's dependency results. Applicable Gradle lint, unit/E2E
jobs, packaged-JAR smoke checks on Linux/macOS/Windows and the reusable Bazel workflow must succeed. Missing
applicability output, a failed path check, an unexpected skip, a cancellation, a failed runner check or pending runner
provisioning fails readiness. Provisioning does not validate the original revision: rerun that revision when the runner
is available.

The applicability job also enforces temporary dependency ownership and expiry on every invocation, including
documentation-only changes. A dependency-maintenance failure therefore fails required readiness and is included in the
completed-job Actions observation; the separate maintenance workflow remains a daily and stacked-PR backstop.

Applicability comes from the exact event base/head revisions and the verified checkout, with a JSON artifact recording
both source and tested SHAs. Only known narrative documentation is excluded from runtime suites. Unknown paths are
conservatively tested; fixtures and executable inputs remain applicable regardless of their extension. Release branch
names do not bypass checks. The pure state machine and Git-diff classifier have failure and negative-control tests.

## Bazel gate and build observability

`CI (Bazel)` is called by main CI for applicable changes and also runs daily on main or manually. Its build, test,
evidence and Buildifier results feed required readiness. Missing direct Kotlin dependencies and duplicate Maven versions
fail natively. The wrapper preserves original command/capture failures and rejects incomplete or mismatched BEP. Build
and test get unique invocation IDs, local BEP files, JSON profiles and receipts bound to the source SHA, tested
checkout, run ID and attempt. Remote upload stays disabled. Configuration, resource bounds and artifact contracts are
documented in [build observability](build-observability.md).

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

First-party deprecated Kotlin DSL delegates and the old test-listener closure are migrated. Plugin deprecations have
exact source-attributed exceptions in `tools/ci/gradle-diagnostics.json`; inspected upstream versions, adoption
candidates and removal criteria remain tracked in [#1603](https://github.com/albertocavalcante/gvy/issues/1603). These
exceptions are debt, not proof that newer plugin releases have the same limitation.

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

Bazel module-lock drift fails CI. Full Maven resolution reproducibility remains tracked in
[#1029](https://github.com/albertocavalcante/gvy/issues/1029): the Maven lock remains disabled after historical GMM
variant/checksum mismatches. Current stable-resolver attribution and fresh cross-environment agreement still require
verification.

Temporary forks, patches, custom dependency downloads and resolver escapes also have a separate lightweight
[maintenance inventory and review-date gate](dependency-maintenance.md). It checks declarations on every PR and daily,
including sources absent from warm-cache logs; passing diagnostic output does not renew dependency retirement debt.
