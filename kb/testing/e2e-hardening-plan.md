# E2E harness hardening delivery plan

The harness must reject bad results, recognize corrected results, and release the resources it owns. This plan tracks
the concrete harness findings behind #1546, #1556 and #1557. Existing product bugs get faithful repros; a repro alone
does not mark the product bug fixed. Protocol coverage follows the repository's LSP 3.17 contract.

## Delivery rules

- Use small PRs with failing regression tests first, appropriate checks, and independent review of the final head.
- Coordinate owned files and build scheduling across agents. Run one Gradle build at a time on a machine.
- Preserve shared checkouts and unrelated work. Verify each squash merge and clean only owned worktrees/branches.
- Mark a row complete only with merged implementation and executable acceptance evidence. An issue or plan is tracking,
  not resolution. Record newly discovered relevant debt here until it is resolved.
- Every xfail must accept a known-correct response and reject the reported bad response. Validate its language mode,
  engine, capability profile and response view; expected failure alone is insufficient evidence.

## Ordered slices

| Order | Slice                                          | Acceptance gate                                                                                                           |
| ----- | ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| 1     | Strict assertions and desired-success controls | Unknown keys fail; JSON null is distinct from missing; correct responses pass; bad-response mutants fail.                 |
| 2     | Session ownership and execution boundaries     | Startup, success, failure and timeout release resources; launch configuration is honored; exit is verified.               |
| 3     | Event causality and a realistic client         | Waits identify the triggering action/version; applied edits change the right buffers/files; rejected edits preserve them. |
| 4     | Protocol controls and coverage                 | Client profiles, exact errors, cancellation/progress and every advertised/custom method have linked evidence.             |
| 5     | Measured speed and editor proof                | Fast harness tests avoid packaging; queue costs are bounded; named-build performance and packaged-editor checks run.      |

## Concrete finding ledger

The identifiers refer to the 2026-11-11 harness audit and issue cross-check. Status changes include the delivering PR
and validation, so another contributor can independently check completion.

| ID  | Finding / owner scope                                                | Status      | Acceptance                                                                                                        |
| --- | -------------------------------------------------------------------- | ----------- | ----------------------------------------------------------------------------------------------------------------- |
| F1  | Ignored YAML keys weaken assertions; parser (#1556)                  | In progress | All catalog keys strict; wrong discovery name/count/text/line fail; explicit null remains representable.          |
| F2  | Null containment is a wildcard; assertions                           | In progress | Null matches only null; root null exists; missing child remains missing.                                          |
| F3  | Historical diagnostics satisfy later edits; event client             | Pending     | A previous version or incomplete provider publication cannot establish the final result of a new action.          |
| F4  | Client acknowledges edits/configuration without state; client/editor | Pending     | Scripted request outcomes preserve order and active state; successful edits are applied and verified.             |
| F5  | In-process listener ownership leaks; session                         | In progress | Both actual listeners, pipes, owned executors and server cleanup finish on every exit path.                       |
| F6  | Exit/crash/forced termination look successful; session/executor      | In progress | Explicit exit checks code and bounded completion; cleanup does not disguise a failed lifecycle assertion.         |
| F7  | Server args/env ignored; launcher                                    | In progress | Subprocess observes configured arguments/environment; unsupported in-process settings fail explicitly.            |
| F8  | Kotlin opens fixture as empty text; DSL                              | In progress | Omitted text matches YAML fixture opening; explicit empty remains empty; builder results are immutable snapshots. |
| F9  | Binary goldens decode UTF-8 and text mode trims; golden IO           | In progress | Equal arbitrary bytes pass; differing bytes fail as assertions; exact text preserves whitespace.                  |
| F10 | Partial acquisition and CLI outputs leak; workspace/executor         | In progress | Failed materialization/launch and all CLI outcomes release owned paths and streams.                               |
| F11 | Dynamic scenarios lack a whole-run deadline; execution               | Pending     | A blocked scenario expires, cancels pending work and cleans up without consuming the shard timeout.               |
| F12 | Formatting test requires recorded #393 bug; repro                    | Pending     | Desired output/no-op/idempotence is pinned; any remaining known failure is scoped to the intended assertion.      |
| F13 | Requests cannot express errors/overlap/cancellation; protocol        | Pending     | Exact result/error envelopes, handles, cancellation and response ordering have deterministic tests.               |
| F14 | Normal initialize lacks client profiles; protocol                    | Pending     | Capabilities, folders, encodings and explicit URI interpolation work through one state-capturing operation.       |
| F15 | Wait diagnostics skip new arrivals; event client                     | Pending     | Old mismatches never move the arrival cursor; failure artifacts include all relevant new events.                  |
| N1  | #431 workspace-symbol xfail uses wrong envelope/kind; repro          | Pending     | A correct symbol response passes, with the correct path/cardinality and representation.                           |
| N2  | #116/#1597 import checks reject correct array/text results; repro    | Pending     | Correct edit count and import text pass; missing/incorrect imports fail.                                          |
| N3  | #1597 requires an import already provided by Groovy; oracle          | Pending     | Use a genuinely non-default-import class and prove both language controls.                                        |
| N4  | #1606 compares full URI to relative path; repro                      | Pending     | All six repros accept exact correct target URIs and name ranges.                                                  |
| N5  | Outline scenario pins #1538 duplicates/parameters; repro             | Pending     | Negotiated tree/kinds/ranges and omission rules express the intended outline contract.                            |
| N6  | #981 asserts before readiness and filters out compiler errors; repro | Pending     | Dependency completion precedes assertions; a missing dependency cannot pass the diagnostic check.                 |
| N7  | #1587 claims every replacement but checks membership; repro          | Pending     | Every edit's new text/range and the resulting workspace contents are correct.                                     |

## Related required gates

| Gate                              | Existing tracking / next action                                                                                                | Status        |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ | ------------- |
| Harness lint                      | #1557: gate the actual harness sources and retire actionable findings.                                                         | In progress   |
| Configuration intent              | #1465: derive option validation from the readers and test observed configuration.                                              | Pending       |
| Valid language/engine contracts   | Reconcile #432/#433 and preserve the Core/Native distinction.                                                                  | Pending       |
| Pure assertion/typed step core    | Consolidate duplicate #804/#808 ownership, remove unchecked dispatch and fill #809 helper coverage.                            | Pending       |
| Fast tests and CLI-only execution | Separate harness tests from server packaging; avoid unused LSP launch for CLI-only cases.                                      | Pending       |
| Notification cost                 | Evaluate each candidate once, use sequence-indexed queues and bounded evidence storage; prove operation counts.                | Pending       |
| In-process global isolation       | Verify repeated sessions against shared GradleConnectionPool shutdown/reopen behavior; fix per-session ownership where needed. | Investigating |
| Coverage inventory                | Pin LSP 3.17 plus gvy extensions; classify every method/capability and gate newly advertised behavior.                         | Pending       |
| Raw protocol evidence             | Preserve original envelopes alongside normalized views, including missing/null, unions and IDs.                                | Pending       |
| Issue-linked repros               | #1523-#1543: retain exact issue/variant linkage; integrate pending repro PRs only after successful-response controls.          | Pending       |
| Expectation-free checks           | #1546/#1548: incremental/fresh equivalence, compiler agreement, token/range invariants and corpus diffs.                       | Pending       |
| Performance evidence              | #1564/#1558: named-build long sessions and deterministic cost tests; measure startup/analysis/request/teardown separately.     | Pending       |
| Packaged editor                   | #1272/#1584: actual middleware results, log/task behavior and restoration synchronized by document version.                    | Pending       |
| Diagnostic evidence               | #1603: unexpected server diagnostics fail the run; deliberate negative-test output is explicitly asserted.                     | Pending       |

## Validation and publication records

Each delivering PR records its reviewed head, fail-first proof, applicable checks, merge SHA and remaining limits in its
description. This section links those PRs as they land. No row is complete yet.

## References

- [Harness design](e2e-harness.md)
- [Quality Bar](../../docs/quality-bar.md)
- [Quality harness epic](https://github.com/albertocavalcante/gvy/issues/1546)
- [Conformance program](https://github.com/albertocavalcante/gvy/issues/1548)
- [Ignored scenario keys](https://github.com/albertocavalcante/gvy/issues/1556)
- [Harness lint](https://github.com/albertocavalcante/gvy/issues/1557)
