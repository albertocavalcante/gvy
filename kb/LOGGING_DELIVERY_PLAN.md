# Logging reliability delivery plan

Deliver logging that explains failures, respects the user's selected verbosity, stays observational, and has bounded
CPU, memory and output costs. Ship this as small independently reviewed PRs, using Terra implementation agents and a
coordinator who owns integration, validation and landing.

**Status:** planned; no implementation packet below is claimed complete. This is a delivery specification under the
[Quality Bar](../docs/quality-bar.md), especially Correct, Honest, Fast, Safe and the review-findings track. It is not a
second project roadmap. Existing issue acceptance criteria remain authoritative for their complete scope.

## Baseline and evidence

The logging audit examined tracked commit `677a4fbbacc466a0531109543101a2742a309d40`. The publication baseline was
rechecked at `1de77d083a6c1574be527eff46cd236abd5dd622`. Intervening #1707, #1722-#1725 and #1729 changes cover editor
installation documentation, Gradle JDK data, Linux E2E child ownership, status/configuration serialization and an MCP
research index entry. Reconcile their landed interfaces, especially subprocess test helpers; they do not establish the
logging acceptance below. Refresh references before every dispatch. Main has unrelated edits; preserve them.

| Evidence axis                 | State   | Consequence                                                                                                   |
| ----------------------------- | ------- | ------------------------------------------------------------------------------------------------------------- |
| Contract and correctness      | Partial | DEBUG evaluation can affect parsing; actual logger namespaces and BSP severity disagree with controls.        |
| Robustness and recovery       | Partial | Maven output, newline-free client records and REPL captures have unbounded paths.                             |
| Compatibility and conformance | Partial | Both Gradle/Bazel and supported editor runtimes need evidence; JUL and VS Code channel controls are distinct. |
| Real-consumer proof           | Partial | Router unit tests exist; packaged logging behavior still needs dedicated coverage.                            |
| Delivery repeatability        | Partial | Existing build/package/test paths are usable; logging-specific baseline and final evidence are missing.       |
| Security and operations       | Partial | Raw external values, repeated authentication warnings and exception rendering need consistent boundaries.     |
| Documentation and usability   | Partial | Users can select verbosity, but configured, running and visible states differ; support context is incomplete. |

Evidence supporting the work:

- [Logback configuration](../gls/src/main/resources/logback.xml) fixes the current application namespace at INFO;
  [LogLevelConfigurator](../gls/src/main/kotlin/com/github/albertocavalcante/gvy/gls/config/LogLevelConfigurator.kt)
  changes root and the old namespace. An isolated probe with repository-pinned Logback 1.5.25 and SLF4J 2.0.20 showed
  requested DEBUG/ERROR leaving the main server logger at INFO while parser loggers followed root.
- The same XML creates a synchronous ConsoleAppender with immediate flushing. An isolated 100-event probe observed 100
  Java stream writes and 100 flush calls on the emitting thread. This is not a syscall, disk-latency or production
  throughput benchmark. Neither probe replaced repository or packaged-editor tests.
- [RecursiveAstVisitor](../parser/native/src/main/kotlin/com/github/albertocavalcante/groovyparser/ast/visitor/RecursiveAstVisitor.kt)
  and
  [NodeRelationshipTracker](../parser/native/src/main/kotlin/com/github/albertocavalcante/groovyparser/ast/NodeRelationshipTracker.kt)
  evaluate class-hierarchy helpers for DEBUG messages. Kotlin Logging 7.0.14's message wrapper catches Exception, not
  NoClassDefFoundError; a lazy supplier does not make semantic inspection safe.
- [GroovyParserFacade](../parser/native/src/main/kotlin/com/github/albertocavalcante/groovyparser/GroovyParserFacade.kt),
  [SpockTestDetector](../ext/testing/src/main/kotlin/com/github/albertocavalcante/gvy/testing/spock/SpockTestDetector.kt)
  and
  [CodeNarcDiagnosticProvider](../diagnostics/codenarc/src/main/kotlin/com/github/albertocavalcante/gvy/diagnostics/codenarc/CodeNarcDiagnosticProvider.kt)
  emit routine analysis details at INFO. Parser class inventory preparation is outside its lazy message supplier.
- [BspClient](../build-tool/src/main/kotlin/com/github/albertocavalcante/gvy/build/bsp/BspClient.kt) ignores message
  severity, prints raw argv and promotes all child stderr to WARN.
  [MavenBuildTool](../build-tool/src/main/kotlin/com/github/albertocavalcante/gvy/build/maven/MavenBuildTool.kt) reads
  all child output before using or discarding it.
- [Client stderr routing](../editors/code/client/src/server/stderrRouter.ts) already preserves many severities and
  exception continuations. Its line buffering is unbounded. Other test-stream handling decodes arbitrary chunks.
- [Log level commands](../editors/code/client/src/commands/logLevel.ts) change the restart-required server setting. The
  [public LogOutputChannel API](https://code.visualstudio.com/api/references/vscode-api#LogOutputChannel) exposes the
  channel threshold and change event, but no public setter. Do not promise automatic threshold synchronization.
- [Test execution](../editors/code/client/src/features/testing/LSPTestExecutionService.ts) warns when aliases identify
  the same result, logs raw launch details, and has inconsistent stdout/stderr framing.
- [Report Issue](../editors/code/client/src/commands/index.ts) currently supplies versions and OS only. Its
  [log extraction helper](../editors/code/client/src/utils/reportIssue.ts) is uncalled. The command opens an editable
  form; it does not automatically submit an issue or upload logs.
- [GroovyExecutor](../repl/src/main/kotlin/com/github/albertocavalcante/gvy/repl/GroovyExecutor.kt) redirects global
  streams under an instance lock and captures unbounded bytes. Direct System.err capture is covered by current tests;
  replacing it with Groovy's `out` binding alone would change behavior.

Additional visualizer evidence was independently checked during planning: each Native responsiveness probe log at source
heads `2226b33ce8363baa3d0e0d5e6f4a1e43e0c23dd4` and `4c49f62b85092e75bbfe1fe7e9b7f0a11727fbae` contains 15,420 DEBUG
lines. The synthetic fixtures have 10/1,000 methods (188/20,798 UTF-8 bytes), each with five warmups and ten samples.
The probes use the desktop runtime classpath; desktop and Jupyter applications carry runtime Logback dependencies
without their own configuration resources in the audited source. Baseline logs ran on the EDT and async logs in
background work, so the timing difference is not a parser-only speedup. Private raw evidence stays outside Git; L00
controls logging configuration explicitly and L19 verifies the actual distributions. Coordinate measurement controls
with [#604](https://github.com/albertocavalcante/gvy/issues/604).

### Existing work to reuse

Issue states below were checked while preparing this plan. Recheck issue bodies, PRs and task ownership at dispatch.

| Existing item                                                 | This plan's contribution                       | Closure boundary                                                                                                              |
| ------------------------------------------------------------- | ---------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| [#1409](https://github.com/albertocavalcante/gvy/issues/1409) | L01: DEBUG-safe AST logging                    | Do not close on a helper rename; exercise incomplete classpaths and actual traversal.                                         |
| [#1270](https://github.com/albertocavalcante/gvy/issues/1270) | L02/L03: typing volume and real namespaces     | Its historical XML description has drifted; current configurator remains wrong. Close only after volume and routing evidence. |
| [#1559](https://github.com/albertocavalcante/gvy/issues/1559) | L04: JUL bridge                                | Both builds and packaged startup must be verified.                                                                            |
| [#1272](https://github.com/albertocavalcante/gvy/issues/1272) | L18: packaged logging proof                    | Also contains Gradle task discovery; logging coverage alone does not close the whole issue.                                   |
| [#1257](https://github.com/albertocavalcante/gvy/issues/1257) | L12: signature-warning amplification           | Replay and WireMessage.copy work remain separate security debt.                                                               |
| [#454](https://github.com/albertocavalcante/gvy/issues/454)   | L16: bounded provider timing/failure summaries | No remote metrics service is required.                                                                                        |
| [#456](https://github.com/albertocavalcante/gvy/issues/456)   | L16: honest provider failure visibility        | Reuse current terminal outcomes; verify behavior for standard clients too.                                                    |
| [#1686](https://github.com/albertocavalcante/gvy/issues/1686) | L14: lower formatter failure fidelity          | Active formatter work owns this slice until explicitly handed off.                                                            |
| [#1603](https://github.com/albertocavalcante/gvy/issues/1603) | Reuse relevant runtime-log evidence            | Do not duplicate or replace the broader CI diagnostic program.                                                                |
| [#1268](https://github.com/albertocavalcante/gvy/issues/1268) | Preserve completed severity routing            | Already closed; do not recreate that original bug or regress its tests.                                                       |

During planning, [#1722](https://github.com/albertocavalcante/gvy/pull/1722) landed build-tool serialization and
[#1723](https://github.com/albertocavalcante/gvy/pull/1723) landed E2E child ownership. Reuse their current interfaces.
Status/configuration serialization also landed through [#1724](https://github.com/albertocavalcante/gvy/pull/1724) and
[#1725](https://github.com/albertocavalcante/gvy/pull/1725). L03/L10/L16 must reuse the current ConfigurationPayload,
StatusNotificationCodec and StatusNotificationAdapter boundaries. Open
[#1646](https://github.com/albertocavalcante/gvy/pull/1646) changes CI evidence. Separate active worktrees cover AST
position queries, formatter outcomes, editor boundaries and follow-on serialization work. Reconcile affected files with
their owners; a clean checkout does not imply that it is available. Existing acceptable work can satisfy a packet
without creating a duplicate PR.

## Shared behavioral contract

### Levels and value

| Level | Meaning                                                                           | Volume rule                                                           |
| ----- | --------------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| ERROR | An unexpected operation failed; identify the operation and preserve its cause.    | One owner reports a failure; avoid duplicate stacks from every layer. |
| WARN  | An actionable recoverable condition or degraded state needs attention.            | Emit on state transition; aggregate repeated equivalent events.       |
| INFO  | Startup/readiness/shutdown and meaningful workspace or configuration transitions. | No per-keystroke parser, detector, provider or protocol progress.     |
| DEBUG | Bounded operation summaries, relevant decisions, timing and results.              | Prefer one summary per operation over AST-node and collection dumps.  |
| TRACE | Explicit diagnostic detail and inventories when necessary.                        | Opt-in, bounded, with sensitive protocol payloads kept separate.      |

Expected cancellation, stale work and incomplete source while typing are not application errors. Retain existing useful
state-change logging, such as deduplicated degraded compilation. Logging must never perform semantic work, change
cancellation/fatal-error behavior, run user code or determine a feature result.

Keep Kotlin Logging and SLF4J interoperable. Prefer lazy Kotlin messages and parameterized SLF4J messages; do not
mechanically replace every API or invent another logging framework. Helpers in `common` own reusable pure text/bounds
logic, while process draining, Logback configuration and editor presentation remain at their existing IO boundaries.

### Context and privacy

Use consistent names where relevant: operation/method, opaque session/run/attempt identifier, safe document identity,
document version, provider/build target, duration, result count and outcome. Use existing captured run/version state;
never read a newer mutable document merely to enrich an older operation's log. Do not claim a local run identifier is
the JSON-RPC request ID. Do not rely on thread names for coroutine correlation.

Default structured support capture/export uses allowlisted fields and excludes source text, authorization headers,
secret argument values, URL userinfo/query secrets, raw environment values and unknown message/Throwable text at the
ring's ingestion boundary. Do not ingest raw channel strings first and attempt to clean them only during export.

Useful human-readable local failure logs can retain bounded exception/child diagnostic details, with known-pattern
redaction and path normalization. They may contain sensitive text: arbitrary child output, exception messages and
external strings cannot be proved harmless by a secret detector. Do not promise complete secret removal or require a
consent prompt merely to see a normal operational failure. Full raw child dumps/protocol payload capture stays a
separate explicit session choice. Existing user-requested build/test output remains available in its own channel and is
not implicitly exportable.

Exercise bounded rendering of causes and suppressed exceptions as well as message text. Preserve the original cause
inside the operation, useful safe type/frame/operation context in local logs, and a distinct allowlisted support event.
Adding a raw Throwable to a sanitized message must not bypass limits or silently feed raw text into the support ring.
Synthetic-secret tests verify the specified examples and capture boundary, not arbitrary-secret recognition.

Protocol traces remain separately enabled and may contain source or secrets. Do not feed them into normal diagnostics
capture or exports by default. Exports are local, explicit and previewable; no new telemetry or automatic upload.
Redaction has stated limits and is not a promise to recognize every possible secret.

### Bounds and cost

L00 records representative fixtures and reviews concrete byte limits before consumers merge. Raw-detail opt-in is
separate from the capacity policy: a bounded buffer does not make its contents safe to collect or share. Initial
candidates are 64 KiB per diagnostic record, 256 KiB retained child-output tail, 256 KiB/512 events for an in-memory
diagnostic ring (whichever binds first), and 64 KiB for a local diagnostics export. These are proposed limits, not
measured production budgets. Record final values, units, overflow policy and rationale in the owning
tests/documentation. Count UTF-8 bytes, not Java/JavaScript string length, when claiming byte bounds.

Every bounded consumer must continue draining/discarding excess input, emit explicit bounded truncation information, and
recover at the next complete record. Limit stderr/stdout capture, exception depth/frames and exported aggregates. Use
fixed-size/count state for repeated-event suppression; no attacker-controlled map of unlimited identities.

Retain protocol stdout discipline. A diagnostic line must never enter JSON-RPC stdout. Do not blindly disable flush, add
duplicate file sinks or replace a bounded synchronous path with an unbounded asynchronous queue. Reduced event volume,
lazy construction and bounded retention are the first optimizations. Async delivery requires a measured bottleneck and a
separately reviewed queue, saturation, failure-preservation and shutdown contract.

## Five phases and dispatch order

| Phase                   | Outcome                                                                               | Packets           | Completion gate                                                                                                                              |
| ----------------------- | ------------------------------------------------------------------------------------- | ----------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| A: contract and safety  | Diagnostics cannot change parsing; baseline and limits are explicit.                  | L00, L01          | Failing controls reproduce unsafe DEBUG behavior; repaired behavior matches quiet modes; reproducible baseline recorded.                     |
| B: trustworthy controls | Effective namespaces, JUL, BSP and editor state agree with their documented controls. | L02-L06, L10, L19 | Actual emissions pass level/severity tests; no protocol pollution; configured/running/visible state is honest.                               |
| C: bounded cost         | External output, capture, routine events and repeated warnings remain bounded.        | L07-L09, L11-L13  | Large/no-newline/Unicode/burst/cancellation tests pass; default typing is quiet; bounded-state evidence recorded.                            |
| D: useful failures      | Causes, outcomes, correlation, metrics and local support context are available.       | L14-L17           | Failed operations remain distinguishable from healthy no-ops; concurrent summaries identify the correct run; export is safe and previewable. |
| E: consumer proof       | The integrated JAR and VSIX demonstrate useful logging with measured costs.           | L18               | Packaged scenarios, applicable platform/CI checks and baseline-versus-final evidence pass at recorded SHAs.                                  |

Phases describe outcome gates, not barriers against disjoint work. L01 and L08, for example, can proceed independently.
L19 was added when desktop runtime evidence exposed a separate configuration gap; its ID remains stable even though it
lands before final verification L18. The packet dependency column controls dispatch. Every implementation PR starts from
fresh main after its prerequisites land unless the coordinator explicitly approves a small linear stack. No merge
commits.

| Packet | Proposed PR subject                                              | Hard prerequisites                      | Primary ownership                                                    |
| ------ | ---------------------------------------------------------------- | --------------------------------------- | -------------------------------------------------------------------- |
| L00    | `test(logging): establish behavioral fixtures and cost baseline` | None                                    | Existing test utilities, logging fixtures, contributor evidence      |
| L01    | `fix(parser): keep debug logging free of hierarchy resolution`   | None; reconcile L00 fixtures            | Native visitor/tracker diagnostics                                   |
| L02    | `perf(logging): quiet routine analysis during editing`           | L00, L01                                | Native parser, Spock detector, CodeNarc progress                     |
| L03    | `fix(logging): apply verbosity to actual application loggers`    | L01, L02                                | GLS configurator, Logback XML, level tests                           |
| L19    | `fix(logging): set explicit defaults for standalone runtimes`    | L01, L02, L03, L05                      | Desktop/kernel/BSP executable configuration and packaged smoke tests |
| L04    | `fix(logging): route JUL records through Logback once`           | L03                                     | Executable bootstrap and dependency declarations                     |
| L05    | `feat(logging): add bounded safe diagnostic rendering`           | L00                                     | Common pure helpers and default JVM rendering boundary               |
| L06    | `fix(bsp): preserve message severity and safe launch context`    | L05                                     | Active BSP client and bsp-core equivalents                           |
| L07    | `fix(build): bound Maven output and subprocess lifetime`         | L05                                     | Maven CLI capture, repository metadata, lifecycle tests              |
| L08    | `fix(vscode): bound and decode diagnostic process streams`       | L00                                     | Client stream decoder/router and process consumers                   |
| L09    | `fix(vscode): route actionable failures to shared output`        | L05 contract, L08                       | Client logger, lifecycle/feature errors, safe test metadata          |
| L10    | `fix(vscode): show active logging and channel visibility`        | L03, L04, L09                           | Log commands, configuration watcher, status UI, descriptions         |
| L11    | `fix(vscode): avoid false test result collision warnings`        | L08, L09                                | Test result aliasing and routine test progress                       |
| L12    | `fix(jupyter): aggregate repeated signature rejection logs`      | L00                                     | Kernel warning limiter and request logging                           |
| L13    | `fix(repl): bound captures and serialize global stream changes`  | L05                                     | REPL capture guard, output limits and consumers                      |
| L14    | `fix(fmt): report pipeline failures without hiding cancellation` | Existing #1686 owner; L05 for rendering | Formatter outcomes and affected consumers                            |
| L15    | `fix(logging): retain operational causes at failure boundaries`  | L05-L07, L09, L14                       | Remaining GLS/parser/DSL/build exception sites                       |
| L16    | `feat(diagnostics): add correlated bounded operation summaries`  | L02, L03, L05, L14, L15                 | Diagnostic runs, provider summaries and local counters               |
| L17    | `feat(vscode): add previewable local diagnostic export`          | L08-L10, L15, L16                       | Bounded client capture, support commands and issue flow              |
| L18    | `test(logging): verify packaged behavior and logging costs`      | L01-L17 and L19 complete                | Integration scenarios, measurements and user/contributor docs        |

These are work packet IDs, not pre-created PR numbers. Keep a packet focused on its behavior. If a packet needs two PRs
to retain reviewability, give them suffixes, retain the dependencies and do not mark the parent complete early. L00 must
not merge intentionally failing tests: fail-first reproductions belong in the associated fix, or use the repository's
existing explicit expected-failure mechanism where appropriate.

## Terra implementation packets

### L00: Establish the baseline and executable contract

Own the minimum reusable test/event-capture fixtures and evidence instructions. Reuse existing ListAppender, process
fixtures, E2E scenarios and native parser JMH infrastructure where appropriate. Do not add a benchmark framework or new
CI service. Freeze representative small/large Groovy and Spock workspaces and synthetic child-output fixtures.

Record startup and warmed typing/hover/completion/diagnostics workloads at INFO, DEBUG and TRACE; record JVM/editor
versions, candidate SHA and JAR hash, fixture, warmup, repetition count and configuration. Measure emitted records and
UTF-8 bytes, stream write/flush counts, bounded retained bytes, allocation/memory where supported, request p50/p95 and
shutdown behavior. Keep raw private outputs outside Git; commit sanitized summaries and reproducible commands only.

Gate: repeatable measurements distinguish event-generation cost, pipe transport and editor presentation. Review final
limits and noise/latency acceptance with evidence; hardware-sensitive numbers are not flaky CI thresholds. Rollback:
remove isolated fixtures/instrumentation without changing production logging.

### L01: Make diagnostic evaluation observational

Own `parser/native` RecursiveAstVisitor and NodeRelationshipTracker debug arguments and their focused tests. Remove
`isScriptSafe`/supertype resolution from diagnostic preparation; log cheap existing identifiers/coordinates only. Do not
hide fatal errors raised by actual semantic work under a broad logging catch.

Fail first with a ClassNode/decompiled-superclass fixture whose hierarchy access fails, then exercise the actual visitor
and class-enumeration paths at INFO/DEBUG/TRACE, including cyclic hierarchies and the #1409 incomplete-classpath case.
Assert equivalent traversal/index/degraded results and no logging-only hierarchy accesses. Restore process-wide logger
state in tests. Gate: #1409 acceptance is demonstrated before L03 makes DEBUG effective more broadly. Rollback: an
isolated revert restores the former diagnostic details; no model/schema change is involved.

### L02: Remove routine analysis chatter and eager work

Own GroovyParserFacade, SpockTestDetector, CodeNarcDiagnosticProvider and only additional resolver/detector sites proven
to repeat during editing. Move routine retry branches/detection/analysis-completed events to bounded DEBUG summaries;
full class/node inventories belong at TRACE and inside lazy suppliers. Preserve real failure, readiness and meaningful
state-transition events. Avoid demoting unrelated startup/reload messages by text matching alone.

Capture actual events for repeated edits and a large class inventory; INFO contains no routine per-edit progress, DEBUG
remains useful and bounded, disabled levels do not evaluate diagnostic suppliers, and parse/diagnostic/test results are
unchanged. Compare event/byte totals with L00. Gate: #1270 volume criteria; namespace closure waits for L03. Rollback:
message levels/content can be reverted independently of language behavior.

### L03: Repair level application and its tests

Own LogLevelConfigurator, logback.xml, relevant startup/configuration wiring and tests. Inventory actual logger names,
including native/core parser, reports/BSP and named loggers. Define one explicit application-namespace policy; preserve
intentional dependency overrides instead of enabling every dependency through root. Test ERROR/WARN/INFO/DEBUG/TRACE
against representative real loggers and emitted events, not only assigned Level fields or obsolete packages.

Exercise initialization and repeat configuration, non-Logback behavior, test-state restoration and dependency controls.
Define process-level behavior for socket mode's multiple server instances: configuration must not silently claim
per-client isolation that a shared backend cannot provide. The extension can retain restart-required semantics;
non-editor clients receive documented truthful behavior. Remove INFO configuration-parsing chatter that defeats quiet
startup expectations. Gate: selected level matches emitted application records; stdout remains protocol-only. Rollback:
revert namespace/wiring changes together and retain the regression tests as evidence of the reopened defect.

### L19: Give every runnable artifact explicit logging defaults

Own only executable-boundary logging resources/bootstrap and focused distribution smoke tests for desktop, Jupyter
kernels, Maven BSP and other runnable tools discovered by an entry-point inventory. Verify each resolved runtime
classpath before prescribing the resource change. Libraries such as parser/common/REPL must not install appender
resources that override an embedding application's policy; assess runtime backend leakage separately from executable
configuration. Coordinate desktop edits with the visualizer owner; do not change parsing/UI scheduling here.

Specify quiet useful default levels and stderr routing for each executable, explicit supported DEBUG/TRACE overrides,
and application namespace parity with L03. A shared policy implementation is acceptable; duplicate logback.xml files
that drift are not. Do not assume GLS resources appear in a desktop/kernel distribution. Default stderr must preserve
protocol/CLI data streams wherever those exist.

Fail first by launching the actual runtime classpath/distribution with no external logging config and measuring a
representative Native parse: no accidental fallback DEBUG inventory at default levels, failures remain visible, and
explicit debugging produces bounded useful detail. Exercise duplicate/config-resource precedence, embedding with a
host-supplied logger, packaged startup, and desktop/kernel stderr behavior. Gate: every runnable artifact has a named
owner, tested default/effective logging policy and reproducible launch proof. L18 includes final cost evidence for these
runtimes. Rollback: revert only executable resources/bootstrap as a unit; never place an application config into a
reusable library to fix a missing distribution resource.

### L04: Bridge JUL at the executable boundary

Own executable startup, Logback/JUL bridge lifecycle and Gradle/Bazel dependency parity. Install once before relevant
LSP4J logging; do not replace handlers from reusable library constructors, duplicate records across restarts, or create
a JUL-to-SLF4J-to-JUL recursion. Preserve embedding boundaries and explain process-global effects.

Fail first with real JUL records at multiple levels. Verify one correctly formatted record, WARN dependency filtering,
repeated startup, non-English JVM locale and no stdout corruption. Test the staged/shadow JAR and Bazel distribution,
not only a test classpath. Gate: full #1559 acceptance, including both dependency graphs. Rollback: revert bridge and
dependency declarations as one unit; the client compatibility router remains intact.

### L05: Establish safe bounded rendering

Own small pure helpers in `common` and the default JVM rendering boundary, with no new runtime framework. Finalize the
L00 limits contract: UTF-8 truncation, bounded collection/throwable rendering, metadata allowlists, path normalization,
URL userinfo/query handling, control-character escaping and truncation counts. Keep useful bounded local message/cause
detail with best-effort redaction, and define a separate strict allowlisted support-event representation. Unknown text
must not enter that support representation merely because a regex redactor ran. Share behavioral fixtures with
TypeScript rather than importing JVM code there.

Own the early raw-detail session policy consumed by L06/L07: full child dumps are off by default; explicitly requested
detail is bounded, labelled potentially sensitive, scoped to a session and excluded from support capture/export unless
separately selected. Define the control/propagation/lifecycle seam here so L06/L07 do not depend on the later export UI.
No new opt-in gate is required for ordinary useful per-record local failure messages.

Fail first with synthetic credentials in argv, malformed/authenticated URLs, nested/suppressed/cyclic exceptions,
multiline/control text and multibyte cap boundaries. Verify default Logback message/throwable limits together; error
rendering must not bypass caps or support allowlisting. Keep useful operation/type/context and prove bounded CPU/state
for oversized input. Gate: downstream owners can adopt tested helpers without retaining whole unbounded inputs or
claiming all local text secret-free. Rollback: revert consumer adoption before removing shared helpers; keep emitted
record format compatible with routing.

### L06: Preserve BSP severity and useful safe context

Own build-tool BspClient, corresponding bsp-core handlers/transport logging and tests. Preserve ERROR/WARNING/INFO/LOG
notification types with a documented null/unknown fallback. stderr alone does not prove an error or warning. Child
failures retain useful bounded local diagnostic context with best-effort redaction and safe identity/exit/count
metadata; full raw tails require session opt-in and stay out of default support capture. Keep draining when an event is
filtered out.

Replace every active unbounded BSP stderr readLine/useLines path before rendering, including build-tool BspClient,
bsp-core BspConnector, ErrorStreamLogger and relevant transport variants. Use byte-bounded streaming framing with
UTF-8-safe truncation/recovery, continue draining past the cap, and give each retained tail an explicit connection owner
and close/cancellation lifecycle. Apply L05 to launch metadata without changing actual argv.

Test more-than-pipe-capacity output, newline-free records, split Unicode, delimiter recovery, close/cancel, all message
types at INFO/WARN thresholds, genuine errors without DEBUG, repeated progress, multiline messages, unknown types and
stderr-only process failure. Cover both implementations while preserving their public contracts; this packet does not
consolidate BSP architecture. Gate: severity does not depend on transport and memory remains bounded before rendering.
Rollback: revert consumers together where the framing interface changes; severity/metadata changes remain independently
reversible.

### L07: Bound Maven capture and its lifecycle

Own MavenBuildTool CLI capture and RepositoryProvider metadata. Replace readText with continuous draining into a
byte-bounded failure tail; successful output never builds a whole string. Retain useful final local diagnostic lines
with known-pattern redaction, safe exit/operation/count metadata and explicit truncation. Full raw-dump mode is a
separate session choice. Neither ordinary excerpts nor full dumps automatically enter the support ring/export;
repository metadata there is allowlisted. Describe external local text as potentially sensitive without claiming a
secret detector can prove it safe.

The current capture/wait path has no invocation deadline. Introduce and document a reviewed default deadline and
configuration policy without inventing an existing contract. Handle caller cancellation and timeout explicitly. Bound
wait/drain completion, close streams even when descendant-held descriptors prevent EOF, destroy and reap owned
processes, preserve interruption and avoid leaving a reader behind. Reuse established process-ownership primitives where
applicable; never kill an unproven unrelated process.

Use fake children that emit more than pipe capacity, no newline and split Unicode, exit success/failure, hang, retain
pipe descriptors in descendants or ignore normal termination. Assert bounded retention, completion, useful final local
lines, safe support metadata, no deadlock and unchanged successful classpath parsing. Gate: output memory and failure
log size are bounded independent of child verbosity. Rollback: isolate the process runner change; do not revert
independently while a consumer assumes its new lifecycle contract.

### L08: Bound and correctly frame client process streams

Own stderrRouter and shared client decoder/framing helper, plus server extra-output and test process consumers.
Coordinate edits to client.ts/LSPTestExecutionService with L09-L11. Use a UTF-8 streaming decoder and byte-aware record
bounds; discard excess until the next delimiter and mark truncation without reprinting discarded data.

Fail first on split Unicode, split CRLF/newline, multiple records per chunk, final unterminated record, close/error,
huge newline-free records, cancellation and duplicate flush. Preserve Logback/JUL/exception severity and fatal fallback.
A truncated structured test-event record must not be parsed as complete; recover at the next delimiter and report that
loss. Detect Maven HTTP-blocker text across chunks. Never intercept protocol stdout as if it were diagnostic text. Gate:
fixed maximum buffered state, correct recovery and no malformed Unicode across all consumers. Rollback: framing can
revert separately, with consumers updated together where interfaces changed.

### L09: Put client failures in the channel users inspect

Own a narrow injected client log sink using existing channels, affected console.* sites, lifecycle hooks and safe test
launch metadata. Inventory each site: preserve ordinary test output in Groovy Tests, route extension operational
failures to the documented server/client diagnostics channel, preserve original Error/cause internally and assign one
layer ownership. Cover pre-initialization/disposal without throwing or recursively logging logger failures.

Test missing Java/JAR, timeout, async listener, feature command and shutdown failures; each leaves one useful bounded
local record with best-effort redaction in the documented channel. Separately emit only allowlisted fields to any
support-capture sink; never forward a rendered console string or arbitrary Error text there. Remove raw argv/cwd/env
logging by default; retain build tool, operation and other reviewed metadata. Follow L05's cross-language fixtures for
Error/cause rendering. Gate: users need no developer console to recover the actionable context covered by this packet.
Rollback: injected sink changes remain independent of LSP protocol and feature intelligence.

### L10: Make verbosity controls honest and usable

Own log commands, relevant configuration watcher/status UI, channel threshold subscriptions and setting descriptions.
Track configured value, last successfully started server value and current channel threshold separately. Pending or
failed restart is visible; unknown effective state remains unknown. Dispose subscriptions correctly across restarts.

Use the supported Output UI instructions/action for channel verbosity. Validate any invoked command in supported
runtimes; do not depend on an invented public setter or silently change the user's global editor level. Explain the
separate protocol Trace channel and its sensitive payloads. Test no restart, declined restart, successful/failed
restart, channel level changes and settings scopes. Gate: from default INFO, a user can enable DEBUG and see a real
DEBUG record using the documented flow; ERROR remains quiet. Rollback: controls remain compatible with the existing
server setting.

### L11: Restore trust in test logging

Own test result-map aliases and remaining routine test/suite progress. Warn only when a key refers to a different
result; deduplicate equal aliases while preserving current matching precedence and genuine collision behavior. Test one
ordinary result with identical exact/class/normalized keys, duplicate-but-distinct records, space normalization and
cross-suite ambiguity. Unique results produce zero collision warnings.

Keep user-requested build/test output available, but avoid duplicating every Testing UI transition as unconditional
channel chatter. Compare representative suite event counts before/after; retain real failures and summary counts. Gate:
no false warning for a successful unique result and no loss of result matching. Rollback: alias and progress changes can
be reverted separately if required.

### L12: Aggregate Jupyter warning floods

Own KernelServer signature rejection/request logs and a small fixed-state limiter with an injectable monotonic clock.
Every invalid message is still rejected before dispatch. Emit the first relevant warning and bounded periodic
suppressed-count summaries; define final/next-event summary behavior without a timer that outlives the kernel. Use fixed
cardinality per known socket or globally, not attacker-controlled message IDs. Never include signatures, keys or
payloads. Ordinary request traffic belongs at DEBUG with useful operation outcomes.

Test bursts, separate intervals, concurrency, clock boundary behavior, shutdown and valid delivery. Gate: N invalid
messages produce bounded warnings per interval and accurate counts without changing authentication. Close only the
logging portion of #1257. Rollback: limiter/request-level changes do not modify authentication or replay semantics.

### L13: Make REPL capture bounded and stop cross-executor stream corruption

Own GroovyExecutor and direct consumers/tests. First characterize existing println, direct System.out/System.err,
exception, session binding and Jupyter output contracts. Keep a per-instance lock for shell state, and use one
process-wide guard for the complete global redirect/execute/restore interval across executor instances. Specify lock
order; restore exact original streams in finally, including exceptional paths. Bound stdout/stderr retention with
explicit UTF-8-safe truncation while continuing to accept/discard writes.

Test deterministically overlapping executor instances, nested/reentrant policy, thrown failure, large output, multibyte
boundaries and restoration. Do not redirect server protocol streams or claim that the guard isolates unrelated threads
writing global System streams. The current tracked consumer is Jupyter; do not add an embedded LSP execution path as
part of logging work.

Gate: executor instances cannot overwrite each other's restoration, outputs and retained memory are bounded, existing
System.err capture stays compatible, and public docs precisely state the remaining process-global limitation. Rollback:
bounds/guard changes revert together if output contracts differ. Full process isolation remains explicit owned debt in
the ledger below; a global lock does not resolve that separate limitation.

### L14: Integrate the formatter owner's honest-outcome fix

Coordinate with the existing #1686/formatter-pipeline-outcomes owner before changing fmt. Reuse or review that work
instead of competing with it. Own only missing acceptance: parser/checker/recipe/print/callback faults stay distinct
from healthy NO_OP, cancellation and fatal errors propagate appropriately, ordinary failures retain original causes, and
no unsafe edits are returned. Keep error state per operation; concurrent/subsequent calls cannot inherit it.

Use phase-specific injection controls through direct formatter, LSP telemetry/result, CLI and code-action call paths.
Preserve valid unchanged source, syntax-invalid source behavior, token spelling and formatting refusal controls. Use L05
when reporting failure and one boundary for the stack. Gate: complete #1686 criteria, not a log-message-only fix.
Rollback: follow the formatter owner's outcome compatibility plan; do not roll back safety guards to silence errors.

### L15: Close remaining exception and failure-reporting gaps

After earlier owners land, inventory remaining message-only unexpected/operational failures in gls, parser/core,
DSL/GDSL, build integration and auxiliary runtimes. Start with strategy initialization, watcher registration, source
extraction, type resolution and GDSL's duplicate logging. Assign each remaining site to a named boundary; keep expected
parse misses/cancellation quiet and do not add a stack at every propagation layer.

Use L05 bounded local rendering and retain Throwable/type/operation context; support events receive only the separate
allowlisted representation. For each changed failure family, inject the original cause and capture actual emitted
events, verifying it survives without leaking synthetic credentials. Check suppression and result/fallback behavior.
Gate: every audit-relevant site is fixed or appears in the owned debt ledger with evidence; no generic catch, lost
cause, blanket suppression or diagnostic baseline expansion obtains a green result. Split this packet by module if
needed. Rollback: each boundary-specific change is independently reversible.

### L16: Correlate operation summaries and expose bounded provider health

Own DiagnosticsService/DiagnosticsOrchestrator and the smallest shared operation-summary seam. Reuse existing captured
run/document/attempt IDs and formatter outcomes. Define bounded fields, monotonic duration, outcome and provider count.
Thread-local MDC alone is insufficient across coroutines/executors; prefer explicit captured context unless propagation
is proved with the repository's existing context mechanism. No global mutable current-request slot.

Aggregate provider durations/failures/cancellations in fixed-size counters/histograms or another reviewed bounded local
representation. Return summaries through an existing appropriate local diagnostics/status surface with compatible schema
evolution; no remote backend. Avoid URI/request identifiers as unbounded metric labels. Reuse terminal outcome failures
to make partial/degraded diagnostics clear; standard clients need an honest supported fallback that does not mislabel a
provider failure as an error in the user's source. Do not erase useful diagnostics from healthy providers.

Test interleaved operations on one URI and multiple URIs, newer document arrival, cancellation, exception, success,
provider partial failure and cleanup. Verify the reported identity/version is the captured one and duration is recorded
once at terminal completion. Gate: #454's useful aggregate evidence and #456's agreed failure visibility are
demonstrated, with no high-volume INFO stream. A universal JSON-RPC interceptor is not a prerequisite; add it only if a
concrete consumer needs it and its propagation is tested. Rollback: preserve old compatible status/outcome consumers.

### L17: Provide a bounded local support workflow

Own a lifecycle-managed in-memory diagnostics ring at existing safe logging boundaries, reportIssue/commands and support
documentation. A LogOutputChannel cannot be read back through the public API: capture explicitly at emission from
allowlisted structured fields within the reviewed byte/event limits. Capture safe startup failure context even if
initialization never finishes. No default capture of arbitrary child text, exception messages or verbose protocol
traces. If raw diagnostic detail is needed, require session opt-in before collecting it and a separate inclusion choice
at preview; label best-effort redaction limits. Discard old session state predictably on restart/disposal.

Add Copy/Save Diagnostics with preview and cancel, including versions, candidate/build identity, configured/effective
levels, channel threshold, safe launch context, current failure and bounded recent safe summaries. Default
preview/export excludes raw-detail buffers. Raw-detail inclusion stays explicit and separate; never submit/attach
remotely. Preserve the editable issue-form flow and keep its URL short; a local artifact is preferable to putting a
large diagnostic payload in a query string. Do not add file logging on the normal hot path.

Test synthetic secrets in messages/causes/URLs, home/workspace paths, Unicode, multiline/control data, ring eviction,
oversize export, canceled preview, absent logs and early failure. Verify default capture/export contains only
allowlisted fields and no synthetic external secret, and raw-detail mode cannot start or export without its explicit
choices. Keep preview and saved/copied bytes identical after selection and rendering. Gate: a user can produce useful
bounded diagnostics after failed startup without exposing the synthetic secrets in default diagnostics or using
unsupported editor APIs. Rollback: remove the opt-in export without breaking normal issue creation.

### L18: Verify integrated behavior, costs and documentation

Own dedicated real Extension Host/packaged-VSIX logging scenarios, final measurements and canonical docs updates. Use a
JAR built from the candidate checkout and prove the packaged JAR/client hashes match. Exercise default startup, typing,
real failure, DEBUG/ERROR selection, independent channel filtering, pending/completed restart, JUL locale,
protocol-trace opt-in, output truncation, provider failure and diagnostic export. Preserve existing router regressions.

Repeat L00's warmed workloads against the integrated candidate with the same hardware/configuration/fixtures. Report
records/bytes, write/flush counts, retained memory and request latency separately. Verify slow-consumer/backpressure,
shutdown and missing-final-newline behavior. Approve quantitative latency/volume budgets from baseline evidence; do not
claim a percentage improvement from synthetic probe counts. If an unresolved measured bottleneck needs async delivery,
create a narrowly reviewed follow-up with a bounded queue, overload policy, severe-failure delivery and shutdown tests;
the milestone stays incomplete until its relevant acceptance is satisfied.

Update user logging/troubleshooting guidance in the editor README and appropriate docs, contributor reproducibility
instructions in CONTRIBUTING/testing notes, and status/evidence here. The Quality Bar remains the roadmap. Gate: all
packets have verified publication/merge SHAs, required CI and independent review; applicable packaged/platform tests
pass; remaining limits/debt are explicit. Logging work alone does not close unrelated halves of composite issues.
Rollback: retain the previous supported VSIX/JAR and documented rollback path; any production regression is fixed or
reverted through another reviewed PR.

## Validation and merge protocol

Read [AGENTS.md](../AGENTS.md), [Contributing](../CONTRIBUTING.md), the relevant subdirectory guidance,
[git workflow](../.agent/rules/git-workflow.md), [code quality](../.agent/rules/code-quality.md) and
[CI diagnostic accountability](../docs/ci-diagnostics.md). Follow the repository's current commands rather than freezing
stale tool invocations in a packet. These verified entry points are a starting map:

| Scope                           | Focused Gradle tests                                                     | Existing Bazel target where present                                  |
| ------------------------------- | ------------------------------------------------------------------------ | -------------------------------------------------------------------- |
| Native parser                   | `:parser:native:test`                                                    | `//parser/native:native_test`                                        |
| Core parser                     | `:parser:core:test`                                                      | Inspect current `parser/core/BUILD.bazel`                            |
| Common helpers                  | `:common:test`                                                           | Inspect current `common/BUILD.bazel`                                 |
| GLS configuration/services      | `:gls:test --tests '<focused class>'`                                    | Discover actual target; do not invent a GLS test label               |
| CodeNarc / Spock test detection | `:diagnostics:codenarc:test :ext:testing:test`                           | `//diagnostics/codenarc:codenarc_test`, `//ext/testing:testing_test` |
| Build / BSP                     | `:build-tool:test :bsp:bsp-core:test`                                    | `//build-tool:build-tool_test`; inspect bsp-core test coverage       |
| Jupyter / REPL                  | `:jupyter:kernel-core:test :repl:test`                                   | `//jupyter/kernel-core:kernel-core_test`, `//repl:groovy-repl_test`  |
| Formatter                       | `:fmt:test` and affected focused GLS tests                               | Inspect current fmt test target                                      |
| Extension                       | `pnpm test`, `pnpm run lint`, `pnpm run check-types`, `pnpm run compile` | Current extension CI/type/bundle checks                              |

Run Gradle/Bazel through `build-slot --` on the shared Mac; only one Gradle build at a time. Initialize the isolated
worktree environment with reviewed direnv configuration. Give JVMs explicit heap limits and keep reusable default Gradle
home. No tests/builds in locked main. Do not start a full multi-module build just to prove a narrative plan.

For integrated editor tests, from the candidate worktree root:

```bash
build-slot -- ./gradlew :gls:stageEditorServer
cd editors/code
gvy_test_jar="$(pwd)/../../gls/build/editor-server/gls.jar"
GLS_LOCAL_JAR="$gvy_test_jar" FORCE_DOWNLOAD=true pnpm install --frozen-lockfile
GVY_TEST_SERVER_JAR="$gvy_test_jar" pnpm run test:all
```

Use [the editor contributor guide](../editors/code/CONTRIBUTING.md#testing) for unpacked-VSIX tests and existing
`GVY_TEST_EXTENSION_ROOT` handling; add a logging-specific integration scenario rather than claiming the implementation
navigation scenario proves logging. Verify Linux/macOS/Windows where the current CI supports them and report unavailable
native coverage explicitly. Do not install or replace the user's running editor for routine QA.

Every implementation packet must:

1. Demonstrate a relevant failing control on its actual base, implement the smallest fix, then pass focused behavior
   checks, required lint and applicable broader CI. Avoid tests that only mirror private fields.
2. Run required `lintFix` in the owned worktree, inspect the resulting diff and retain only in-scope edits. Shell
   changes require ShellCheck/shfmt; Python changes require the owner's pinned typing/format/lint checks. Prefer
   existing Kotlin/TypeScript/Node fixtures over adding an unnecessary scripting language.
3. Review the complete final diff and affected call paths independently. Record the exact publication head, findings,
   fixes, follow-up review, commands/results, warnings/skips and remaining limits. Review changes to safety contracts,
   redaction, overflow and cleanup with the same care as ordinary feature behavior.
4. Inspect complete CI diagnostics for that head. No skipped/failed required check, widened baseline, blanket
   suppression or unreviewed exception counts as passing. Docs-only validation uses formatting/link checks and review;
   it does not wait for unrelated runtime suites unless enforced policy requires them.
5. Squash merge using a handcrafted Conventional Commit subject of at most 72 characters and meaningful body. Verify PR
   state, reviewed head, resulting main commit and actual containment before cleanup. Delete only the proven merged
   owned branch and clean/unlocked task worktree, prune tracking refs and report deliberately retained state.

## Delegation and coordination

The coordinator owns the dependency ledger, source/interface contracts, shared-file reservations, independent review, CI
evaluation, merge and cleanup verification. Terra authors own one bounded packet and its tests/docs. Use the smallest
team that keeps progress moving: at most two implementation authors plus one independent reviewer alongside the
coordinator within the currently available four-agent capacity. Reduce parallelism when tests or shared files contend.
Reviewers do not review their own authored packet; independently inspect evidence before discussing conclusions.

Select `gpt-6.1-terra` explicitly when creating the requested Terra delegates. For this harness, model overrides require
`fork_turns="none"`, so include the packet, revision, file ownership, dependencies and governing paths in the task text.
Do not assume delegates inherited this conversation. Reuse agents for later packets only when their context remains
relevant; refresh source SHAs after every merge. The coordinator, not arbitrary recursive spawning, assigns slots.

Example author dispatch, with placeholders filled from current evidence:

```text
Task: implement gvy logging packet Lxx from kb/LOGGING_DELIVERY_PLAN.md.
Model: gpt-6.1-terra. Base: <fresh origin/main SHA>. Prerequisites: <merged PRs/SHAs>.
Owned worktree/branch: <claimed available slot and branch>; verify before edits.
Owned files/interfaces: <exact reservation>. Peers: <affected author/reviewer names>.
Read AGENTS.md, relevant subdirectory guidance, git workflow, code quality,
CI diagnostics, and packet Lxx. Preserve all other checkouts and unrelated edits.
Reproduce the bug with a failing behavior test; implement only Lxx and its docs.
Validate <focused commands> and required checks, using build-slot for heavy builds.
Do not change shared files outside the reservation; message the coordinator with
new interface evidence or conflicts. Do not submit anything to upstream projects.
Open/update one focused PR and report the actual publication SHA, complete diff,
fail-first/pass evidence, CI diagnostics, rollback and remaining debt. Do not merge
until the independent reviewer and coordinator finish the final-head gates.
```

Example reviewer dispatch:

```text
Independently review gvy packet Lxx, PR <number>, base <SHA>, head <publication SHA>.
Read the complete final diff, affected call paths and tests. Verify the packet's
behavior, cancellation/fatal-error handling, redaction/throwable paths, bounds,
stream drain/recovery, global state restoration, protocol stdout and rollback.
Check that the regression fails on the base and that supplied CI evidence belongs
to this head. Report actionable findings with file/line, trigger and impact;
otherwise state coverage and limits. Do not edit or send external reviews/comments.
After fixes, review the changed head again before recommending readiness.
```

Suggested reservations and concurrency:

- First disjoint pair: L01 native safety and L08 TypeScript framing after L00's bounds contract. L12 can fill a slot
  while server/client prerequisites are reviewed. Do not occupy a slot merely to wait on prerequisites.
- Serialize L02 then L03 in server logging, and L03 then L04 where XML/bootstrap/build declarations overlap.
- L05's shared helpers/rendering contract lands before L06/L07/L15 adoption. L06 and L07 may run together only after
  their build-tool files and dependency declarations are reserved separately.
- L08-L11 and L17 share client.ts, lifecycle/status hooks and LSPTestExecutionService. Land in dependency order or
  explicitly divide non-overlapping files; do not rely on parallel agents resolving broad conflicts later.
- L14 is reserved to existing formatter work until its owner hands it off. L16 waits for relevant diagnostics/status
  serialization owners and consumes their landed interfaces rather than creating a competing protocol model.
- Broad catalogs, BUILD declarations, Logback XML, schemas, CONTRIBUTING and this status ledger each have one active
  writer. The coordinator queues changes and tells affected peers when the contract changes.

At preparation time all ten gvy worktree pool slots were occupied. Use `wt new` only when a slot is actually available;
never release another author's clean/detached checkout or create a forbidden pool-bypass worktree. Until capacity is
available, do read-only design/review or publish documentation through an owned remote branch. This is a scheduling
constraint, not authorization to interrupt other work. All persistent checkouts must also follow the owner's location
policy. No borrowed dirty checkout, stash/reset, arbitrary cleanup or tests in main.

## Deferred debt and boundaries

The following items remain unresolved by the logging milestone unless their acceptance is separately completed. This
ledger names impact, reason, owner scope, next action and acceptance; writing it is not resolution.

| Item                                                                                              | Impact / reason outside this delivery slice                                                                                                   | Responsible scope and next action                                                                                                                                                                                  | Acceptance                                                                                                                                                                                  |
| ------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Jupyter replay and WireMessage.copy portions of #1257                                             | Authentication integrity, separate from warning amplification.                                                                                | Jupyter/security owner implements the remaining issue criteria after L12.                                                                                                                                          | Replays do not execute twice, modified copies cannot verify stale signed bytes, history is bounded.                                                                                         |
| Universal provider deadlines, [#455](https://github.com/albertocavalcante/gvy/issues/455)         | A provider may hang; timing logs alone do not prevent it. Timeout semantics span cancellation and diagnostics publication.                    | Diagnostics owner adds tested per-provider deadline policy; L16 exposes outcomes without claiming this fixed.                                                                                                      | Hung provider times out, healthy output survives, cancellation/terminal status stays correct.                                                                                               |
| Complete REPL isolation from unrelated JVM writers                                                | L13 fixes executor-instance corruption and bounds capture; a process-wide lock cannot stop unrelated writers from entering a captured stream. | REPL/Jupyter owner designs execution-owned output and, where direct System stream capture must remain compatible, evaluates a dedicated session worker. Do not add embedded LSP execution before this is resolved. | Concurrent unrelated logging never enters user execution output; direct System.out/System.err capture and session-state contracts remain compatible, with bounded process lifecycle/output. |
| Gradle task-discovery portion of #1272                                                            | Unrelated task capability behavior shares a packaged-test issue.                                                                              | Extension/build owner finishes that issue's task scenario; L18 contributes logging proof only.                                                                                                                     | Both task discovery and logging packaged criteria pass before whole-issue closure.                                                                                                          |
| Kotlin-logging resolver workaround, [#1620](https://github.com/albertocavalcante/gvy/issues/1620) | Build/dependency reproducibility is separate from runtime message value.                                                                      | Dependency owner retires the workaround using verified Gradle/Bazel resolver evidence.                                                                                                                             | Normal resolution replaces the workaround without graph/version/packaging regressions.                                                                                                      |
| Asynchronous logging / external telemetry                                                         | No production bottleneck or consumer requirement currently justifies queue/service complexity.                                                | Coordinator evaluates L18 measurements; dispatch a separate bounded-delivery packet only when justified.                                                                                                           | Measured need, bounded saturation/shutdown policy, severe-event preservation and representative improvement proof.                                                                          |

Do not expand this work into parser-engine consolidation, a logging API rewrite, a serialization migration, CI/runner
migration, a new telemetry backend or an upstream submission. Relevant existing changes can be prerequisites or satisfy
acceptance, but they retain their own owner and review.

## Completion ledger

At dispatch, append the actual branch, author, base SHA, prerequisite merges, reviewer, PR, publication head, final
validation/diagnostics evidence, merge SHA and cleanup proof for the packet. Refresh rather than copy stale evidence.
The source contract is the packet text; GitHub issues/PRs carry live progress. Avoid rewriting the whole plan for every
status tick.

The milestone is complete only when L00-L19 are satisfied, actual user-visible failures and volume are verified in the
packaged client, baseline/final evidence is reviewable, and remaining limitations have the explicit ownership above. Any
failed or missing applicable gate remains a blocker. A plan PR, passing unit tests, a setting value or a green unrelated
CI run is not proof that runtime logging work shipped.
