<!-- SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# CI diagnostic accountability

A successful command is insufficient when it emits actionable diagnostics. Agents own the complete CI result for the
head they ship: failures, warnings, dependency advice, skips and tracked debt. Inspect the check results and generated
diagnostic summary before declaring work ready. Report counts, fixes, remaining issue links and validation limits in the
PR and completion message. Independent review must cover changes to diagnostic policy and exceptions.

## Preserve the requested CI attempt

Bind every historical audit to the repository, run ID, attempt, job ID and publication head SHA. Read the requested
attempt and job metadata before accepting their logs:

```bash
# Replace the example identities together for a different job.
repository=albertocavalcante/gvy
run_id=43237436692
attempt=1
job_id=131249849696
head_sha=1d1b0d72aeafcdbd276ca530219f15e4f83c3b9d

gh api "repos/$repository/actions/runs/$run_id/attempts/$attempt" \
    --jq '{repository: .repository.full_name, id, run_attempt, head_sha, status, conclusion}'
gh api "repos/$repository/actions/jobs/$job_id" \
    --jq '{id, run_id, run_attempt, head_sha, status, conclusion}'
```

The repository, run, attempt and head must match the requested tuple; the job must additionally match its requested ID.
Wait for applicable work to complete. Missing historical metadata, a mismatch or unavailable evidence blocks
certification. Do not fall back to the latest run or a same-name job.

For a readable view, select both the job and attempt explicitly:

```bash
gh run view "$run_id" --repo "$repository" --attempt "$attempt" --job "$job_id" --log
```

This output adds job/step prefixes and may contain `UNKNOWN STEP`. Label it **normalized CLI output**. Its digest, byte
count and NUL count do not describe the original log.

Retrieve original bytes from the selected job's API endpoint. Use a fresh private evidence directory; preserve the
metadata and exact request identity beside the capture. The following capture requires Bash and Node.js:

```bash
set -euo pipefail
mkdir -p .agent/out
evidence=".agent/out/ci-${run_id}-attempt-${attempt}-job-${job_id}"
mkdir -m 700 "$evidence" # Fails if this evidence directory already exists.
gh api "repos/$repository/actions/runs/$run_id/attempts/$attempt" > "$evidence/run.json"
gh api "repos/$repository/actions/jobs/$job_id" > "$evidence/job.json"
jq -e --arg repository "$repository" --arg head "$head_sha" \
    --argjson run "$run_id" --argjson attempt "$attempt" \
    '.repository.full_name == $repository and .id == $run and .run_attempt == $attempt and
     .head_sha == $head and .status == "completed"' "$evidence/run.json" > /dev/null
jq -e --arg head "$head_sha" --argjson run "$run_id" \
    --argjson attempt "$attempt" --argjson job "$job_id" \
    '.id == $job and .run_id == $run and .run_attempt == $attempt and
     .head_sha == $head and .status == "completed"' "$evidence/job.json" > /dev/null
gh api "repos/$repository/actions/jobs/$job_id/logs" --allow-escape-sequences > "$evidence/job.log.partial"
mv "$evidence/job.log.partial" "$evidence/job.log"
node --input-type=module - "$evidence/job.log" <<'JS'
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
const bytes = readFileSync(process.argv[2]);
console.log(JSON.stringify({
  sha256: createHash('sha256').update(bytes).digest('hex'),
  bytes: bytes.length,
  nulBytes: bytes.reduce((count, byte) => count + Number(byte === 0), 0),
}));
JS
```

Record the log endpoint, requested tuple, retrieval success/failure, SHA-256, byte count and NUL count in the audit
receipt. A failed retrieval leaves a partial capture; preserve it as incomplete evidence and stop certification. Keep
original bytes separate from decoded or normalized analysis. Use binary-safe readers such as `rg --text`; removing NULs
or escape sequences does not repair an incomplete producer capture.

Retain the original failed attempt and each retry separately. A later success does not replace the earlier result. A
matching command or test label proves presence in the log, not that checks executed: verify the actual report,
assertions, test count and skips against the selected source/artifact.

The decisive counterexample is [#1883](https://github.com/albertocavalcante/gvy/issues/1883). With GitHub CLI 2.103.3,
the default view of attempt-1 job `131249849696` returned the same normalized log as successful attempt-2 job
`131252364808`. Explicit `--attempt 1` recovered the failure; direct job API logs had different hashes and lengths. The
[CLI manual](https://cli.github.com/manual/gh_run_view) documents a separate attempt selector and log-association
limitations. This is evidence for explicit selection in gvy's procedure, not an established upstream defect. Executable
retrieval/identity regression protection remains tracked in #1883; these instructions alone do not enforce it.

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

## VS Code provisioning

Extension CI's Linux/macOS/Windows integration and packaged-extension jobs, plus Nightly's Linux integration job, use
`tools/ci/provision-vscode.mjs`. Provisioning, test-runner invocation and the post-test evidence audit are separate
steps. The wrapper invokes the existing pinned test CLI once, after unit tests and harness compilation; the packaged
lane preserves its implementation-navigation selection. An integration-runner marker records that exact invocation,
including a failed spawn, rather than claiming the editor launched or an assertion executed. The marker is claimed
atomically before profile preparation and updated through its held descriptor. A preparation failure remains explicitly
unexecuted, authorizes no editor-log upload and fails readiness; an existing claim stops before profile side effects.

The supported contract is `@vscode/test-electron` 3.1.0 with the declared pnpm patch, `@vscode/test-cli` 0.0.15, its
SDK-declared `https-proxy-agent` 7.0.6 and VS Code 1.144.0. The shared configuration verifies the manifest floor and
guards both repository and packaged manifests against extension dependencies, installation options and CLI argument
drift. The runner passes the exact guarded `.vscode-test.mjs` with the
[supported `--config` option](https://github.com/microsoft/vscode-test-cli/blob/88ade1d567669de60a2edaf3753fae31af0d727c/src/cli/args.mts#L16-L21),
so another discovered configuration cannot replace it. This matters because the
[CLI dependency-install branch downloads again even with an installed path](https://github.com/microsoft/vscode-test-cli/blob/88ade1d567669de60a2edaf3753fae31af0d727c/src/cli/platform/desktop.mts#L128-L164).
Tool or configuration changes must update these controls through review.

The declared SDK patch changes only cold exact stable-version validation from the broad release list to the official
exact-version/platform/stable endpoint. It retains literal version 1.144.0, the SDK's stable archive URL, reporter,
archive retry loop and independent transfer checksum. Stable/latest aliases, unreleased and Insiders resolution keep
their original behavior. A commit selector is unsuitable because the pinned SDK routes it to Insiders. The new request
uses the SDK's private proxy routing and inherited CA configuration, requires a complete 200 response, and bounds raw
bytes to 64 KiB with strict UTF-8/JSON and release-identity fields. Its independent absolute deadline cannot be extended
by incoming data; that deadline, malformed metadata and teardown failures do not qualify for the idle-timeout retry. The
worker verifies the actual installed SDK source hash before any provisioning network request; the public result
identifies the normalized pnpm patch hash. Post-install controls exercise the actual patched package on all native
platforms and Nightly. Both pnpm and Bazel locks require generated, reviewed consistency evidence; the supported
rules_js 3.1.0 / rules_ts 3.8.3 / rules_nodejs 6.7.3 graph handles pnpm's declared patch format without a second
compatibility patch.

Only the pinned SDK's exact `TimeoutError` constructor/message before its first `resolvedVersion` event qualifies for
one outer retry. The
[fixed-version metadata request precedes that event and the SDK's archive retry loop](https://github.com/microsoft/vscode-test/blob/2a4b25c3a48411bdd001af1cae136a308a0c7e32/lib/download.ts#L461-L579).
DNS, unknown errors/phases, archive/checksum failures, verification errors, incomplete evidence and assertion failures
never qualify. SDK archive attempts remain inside their original loop and are not retried by this controller.

The provisioning orchestration has a shared 180-second monotonic deadline, covering worker startup, metadata, archive
attempts, verification, one five-second backoff and owned-child-tree cleanup. Each worker receives at most 80 seconds,
with at most ten seconds of its remaining budget shared by exact-release metadata and the CLI probe, and two seconds
reserved for termination. Accepted cold hosted provisioning previously took about 10–20 seconds; these ceilings allow
headroom while bounding slow or dripping responses. The SDK's 15-second idle timeout remains unchanged and
[resets on incoming data](https://github.com/microsoft/vscode-test/blob/2a4b25c3a48411bdd001af1cae136a308a0c7e32/lib/request.ts#L21-L84).
Neither that timeout nor Mocha's existing 30-second assertion timeout is raised. POSIX termination addresses only the
new owned process group and probes its disappearance even after an unexpected worker exit. An unproven cleanup fails.
Windows uses the live owned worker PID's tree with bounded `taskkill /T /F`. If that worker exits unexpectedly first,
the controller cannot prove descendant cleanup: it reports `cleanup-incomplete`, forbids retry and blocks integration.
The same uncertainty applies when a failed synchronous Windows probe loses its intermediate command wrapper:
[Node can terminate that child on timeout or buffer overflow](https://nodejs.org/docs/latest-v24.x/api/child_process.html#child_processspawnsynccommand-args-options),
and
[Windows parent termination does not terminate its children](https://learn.microsoft.com/en-us/windows/win32/procthread/terminating-a-process).
A separate worker scope proof remains false and cannot be upgraded by successful parent `taskkill`; a runtime-probe
phase without a valid final proof also fails closed. Capture completeness is a separate field. The adapter does not
claim Windows job-object containment. These limits remain owned in #1882 and have native wrapper/grandchild controls.

Every attempt has a fresh owned cache; an unexpected cache hit fails. Expected build identity comes from the fixed
official exact-version/platform/stable metadata endpoint. The
[official VS Code CLI maps that response's `version` to `Release.commit`](https://github.com/microsoft/vscode/blob/2c33b50b16c472efe58357d1d2a8a5414bc23fbf/cli/src/update_service.rs#L75-L109).
The metadata projection requires matching `name` and `productVersion`, a commit and advertised archive SHA-256. The
installed CLI must report that exact version, commit and host architecture;
[its output uses `product.version` and `product.commit`](https://github.com/microsoft/vscode/blob/2c33b50b16c472efe58357d1d2a8a5414bc23fbf/src/vs/code/node/cli.ts#L104-L106).
The
[supported executable-to-CLI resolver](https://github.com/microsoft/vscode-test/blob/2a4b25c3a48411bdd001af1cae136a308a0c7e32/lib/util.ts#L332-L394)
handles platform layout; the verifier does not guess macOS executable names or Windows product directories. This
verifies runtime identity against official release metadata. The SDK's separate transfer validation does not expose its
actual checksum through its public reporter, so the advertised metadata digest is not proof of equality to this SDK
transfer or an independent build-integrity attestation.

The SDK's `newInstallComplete.commitHash` instead reads the
[`x-source-commit` response header](https://github.com/microsoft/vscode-test/blob/2a4b25c3a48411bdd001af1cae136a308a0c7e32/lib/download.ts#L335-L341).
It remains informational and cannot approve runtime identity. Candidate `0cbad583` failed verification before
integration in [Extension Validate](https://github.com/albertocavalcante/gvy/actions/runs/43265880210/job/131349988335)
and [Nightly](https://github.com/albertocavalcante/gvy/actions/runs/43265879993/job/131349349730). That candidate did
not retain the compared commit/architecture values; later API observations cannot reconstruct them. Future reports
retain safe typed expected/observed identities separately from approved delivery. Candidate `33dc06444` subsequently
exhausted both allowed idle timeouts before resolution in Extension Validate while its independent Nightly job resolved
in 134 ms, provisioned in 8.052 seconds without retry and passed integration. These are distinct retained outcomes;
neither a global outage nor universal availability is established.

The extra metadata request has a 64 KiB raw-body bound, strict UTF-8/JSON projection, fixed origin and no redirects or
vendor-provided URL fetch. Its deadline includes DNS/proxy/body activity, including dripping responses, within the
shared verification budget. Metadata errors have a distinct safe class and never qualify for the SDK retry. Private
`npm_config_https_proxy` overrides `npm_config_proxy`, matching the SDK; the adapter resolves and verifies the SDK's
declared public proxy-agent dependency. Invalid or unavailable configured routing fails before a direct request.
`NODE_EXTRA_CA_CERTS` remains inherited at worker startup, with no TLS CA override. Requests, responses and owned agents
are destroyed on every terminal path; proxy credentials, raw URLs and arbitrary response fields are never projected.

The public artifact contains one audited allowlisted JSON projection: source/tested SHA, repository/workflow/job/run and
attempt/matrix identity, invocation UUID, exact tool/editor identity, phases, numeric timing, outcomes, cleanup and
capture completeness, and private stream/error byte counts and SHA-256 hashes. Compact report JSON is limited to 32 KiB.
Unknown fields, stale identity, noncanonical receipts, malformed phases, links and unexpected publication files block
upload. Windows uses the existing compiled no-follow evidence reader before provisioning controls and the post-test
audit. Its native link/replacement negative controls run on the hosted Windows lane.

Raw SDK errors and probe output stay outside the artifact directory, with at most 128 KiB combined per attempt. Capture
overflow stops the worker and fails explicitly; unreported output, including unknown warnings, also fails. The original
bytes and each recorded error hash remain available in private job captures; they are never substituted for the audited
artifact. Ordinary SDK errors and integration output still stream to GitHub console logs under runner masking, which
does not make arbitrary output safe. Never dump environment variables or place credentials in process arguments.
Artifact projection does not suppress runtime diagnostics.

A failed preflight records integration as unexecuted; editor logs are not expected in that phase. Once the integration
runner is invoked, the audit requires editor log files even when assertions fail. Immediately before invocation, a
bounded no-link inventory quarantines any previous log directory into the invocation-private root without copying its
bytes, then creates a fresh empty log directory. Rename, permission and cross-volume failures stop before invocation.
The context-bound marker and public result record that preparation and the prior-file count, so probe or earlier logs
cannot satisfy the current invocation. The post-test audit again requires a canonical directory and parent, rejecting
regular-file or linked replacements without reading their bytes. Extension CI's existing failed-editor log upload also
requires the audit's positive nonempty inventory flag; a rejected replacement cannot select a raw upload path. Missing
required logs still fail the audit. The audit fails closed on missing or stale invocation evidence and never changes an
original integration failure into success. All three native platforms, the packaged test lane and Nightly remain
required applicable hosted checks. Original timeout failures and ownership remain in
[#1882](https://github.com/albertocavalcante/gvy/issues/1882); successful recovery does not retire the debt.

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
