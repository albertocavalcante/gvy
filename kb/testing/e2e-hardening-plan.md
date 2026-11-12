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

| ID  | Finding / owner scope                                                 | Status                                      | Acceptance                                                                                                                   |
| --- | --------------------------------------------------------------------- | ------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| F1  | Ignored YAML keys weaken assertions; parser (#1556)                   | Done: #1619                                 | All catalog keys strict; wrong discovery name/count/text/line fail; explicit null remains representable.                     |
| F2  | Null containment is a wildcard; assertions                            | Done: #1641                                 | Null matches only null; root null exists; missing child remains missing.                                                     |
| F3  | Historical diagnostics satisfy later edits; event client              | In progress: #1657                          | A previous version or incomplete provider publication cannot establish the final result of a new action.                     |
| F4  | Client acknowledges edits/configuration without state; client/editor  | In progress: #1654                          | Scripted request outcomes preserve order and active state; successful edits are applied and verified.                        |
| F5  | In-process listener ownership leaks; session                          | Done: #1649 and #1661                       | Both actual listeners, pipes, owned executors and server cleanup finish on every exit path.                                  |
| F6  | Exit/crash/forced termination look successful; session/executor       | Done: #1649                                 | Explicit exit checks code and bounded completion; cleanup does not disguise a failed lifecycle assertion.                    |
| F7  | Server args/env ignored; launcher                                     | Done: #1649                                 | Subprocess observes configured arguments/environment; unsupported in-process settings fail explicitly.                       |
| F8  | Kotlin opens fixture as empty text; DSL                               | Done: #1641                                 | Omitted text matches YAML fixture opening; explicit empty remains empty; builder results are immutable snapshots.            |
| F9  | Binary goldens decode UTF-8 and text mode trims; golden IO            | Done: #1622 and #1645                       | Equal arbitrary bytes pass; differing bytes fail as assertions; exact text preserves whitespace.                             |
| F10 | Partial acquisition and CLI outputs leak; workspace/executor          | Done: #1649 and #1673                       | Failed materialization/launch and all CLI outcomes release owned paths and streams.                                          |
| F11 | Dynamic scenarios lack a whole-run deadline; execution                | Done: #1673                                 | A blocked scenario expires, cancels pending work and cleans up without consuming the shard timeout.                          |
| F12 | Formatting test requires recorded #393 bug; repro                     | Pending                                     | Desired output/no-op/idempotence is pinned; any remaining known failure is scoped to the intended assertion.                 |
| F13 | Requests cannot express errors/overlap/cancellation; protocol         | Pending                                     | Exact result/error envelopes, handles, cancellation and response ordering have deterministic tests.                          |
| F14 | Normal initialize lacks client profiles; protocol                     | In progress: harness/protocol               | Capabilities, folders, encodings and explicit URI interpolation work through one state-capturing operation.                  |
| F15 | Wait diagnostics skip new arrivals; event client                      | Done: #1652                                 | Old mismatches never move the arrival cursor; failure artifacts include all relevant new events.                             |
| F16 | Default change versions discard later edits; document client          | Done: #1663                                 | YAML/Kotlin auto versions advance per server URI identity; explicit stale values remain testable; close/reopen resets state. |
| F17 | Unbounded CLI capture can exhaust memory; harness/runtime             | Done: #1685                                 | Concurrent readers retain bounded bytes per stream; overflow fails before assertions and releases owned resources.           |
| F18 | Teardown snapshots miss orphaned CLI descendants; harness/runtime     | Open: #1670                                 | Every launched descendant is contained or explicitly accounted for; cleanup verifies owned process identities and exit.      |
| F19 | Raw JSON shapes change on wire or observation; protocol/client        | Done: #1696                                 | Preserve missing/null object fields and authored scalar/array parameters; typed SDK defaults remain explicit.                |
| F20 | YAML and numeric assertions lose precision; parser/assertions         | In progress: #1695                          | Large integers and decimal values survive loading, dispatch and numeric comparisons without Double rounding.                 |
| F21 | Final-newline warnings fail or use the previous line; diagnostics     | In progress: #1708                          | Normalize the known producer convention; ordinary invalid positions still fail and real LF/CRLF controls pass.               |
| F22 | CodeNarc swallows rule crashes and returns empty success; diagnostics | In progress: #1740                          | Actual SDK rule failures become explicit provider failures; healthy empty input and legitimate exclusions remain supported.  |
| N1  | #431 workspace-symbol xfail uses wrong envelope/kind; repro           | Done: #1656                                 | A correct symbol response passes, with the correct path/cardinality and representation.                                      |
| N2  | #116/#1597 import checks reject correct array/text results; repro     | Done: #1656                                 | Correct edit count and import text pass; missing/incorrect imports fail.                                                     |
| N3  | #1597 requires an import already provided by Groovy; oracle           | Done: #1656                                 | Use a genuinely non-default-import class and prove both language controls.                                                   |
| N4  | #1606 compares full URI to relative path; repro                       | Done: #1656                                 | All six repros accept correct target URIs and declaration ranges; targetSelectionRange pins the identifier when returned.    |
| N5  | Outline scenario pins #1538 duplicates/parameters; repro              | Pending                                     | Negotiated tree/kinds/ranges and omission rules express the intended outline contract.                                       |
| N6  | #981 asserts before readiness and filters out compiler errors; repro  | Partial: #1677; fence #1657 pending         | Dependency completion precedes assertions; a missing dependency cannot pass the diagnostic check.                            |
| N7  | #1587 claims every replacement but checks membership; repro           | Partial: #1656; applied state #1654 pending | Every edit's new text/range and the resulting workspace contents are correct.                                                |

## Related required gates

| Gate                              | Existing tracking / next action                                                                                                | Status        |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ | ------------- |
| Harness lint                      | #1557/#1687: restore compatible typed analysis, gate actual harness sources, then retire verified findings.                    | In progress   |
| Configuration intent              | #1653 fixes reader-key drift (#1465); observed configuration effects still need coverage.                                      | Partial       |
| Valid language/engine contracts   | Reconcile #432/#433 and preserve the Core/Native distinction.                                                                  | Pending       |
| Pure assertion/typed step core    | Consolidate duplicate #804/#808 ownership, remove unchecked dispatch and fill #809 helper coverage.                            | Pending       |
| Fast tests and CLI-only execution | #1673 avoids unused LSP launch for CLI-only cases; separating harness tests from server packaging remains pending.             | Partial       |
| Notification cost                 | #1652 evaluates each candidate once and adds checkpoints; bounded storage remains pending.                                     | Partial       |
| In-process global isolation       | Verify repeated sessions against shared GradleConnectionPool shutdown/reopen behavior; fix per-session ownership where needed. | Investigating |
| Coverage inventory                | Official 3.17 model pinned; classify method/capability evidence and gate newly advertised behavior.                            | In progress   |
| Raw protocol evidence             | Preserve original envelopes alongside normalized views, including missing/null, unions, IDs and outer member omission.         | Pending       |
| Issue-linked repros               | #1523-#1543: retain exact issue/variant linkage; integrate pending repro PRs only after successful-response controls.          | Pending       |
| Expectation-free checks           | #1546/#1548: incremental/fresh equivalence, compiler agreement, token/range invariants and corpus diffs.                       | Pending       |
| Performance evidence              | #1564/#1558: named-build long sessions and deterministic cost tests; measure startup/analysis/request/teardown separately.     | Pending       |
| Packaged editor                   | #1272/#1584: actual middleware results, log/task behavior and restoration synchronized by document version.                    | Pending       |
| Diagnostic evidence               | #1603: unexpected server diagnostics fail the run; deliberate negative-test output is explicitly asserted.                     | Pending       |

## Validation and publication records

Each delivering PR records its reviewed head, fail-first proof, applicable checks and remaining limits in its
description. GitHub merge records identify the squash commit; publication and cleanup receipts retain verification of
the resulting target and owned-resource cleanup. The following implementation slices have landed; remaining rows stay
open until their own gates pass.

| PR                                                          | Result and validation                                                                                                                                                       |
| ----------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [#1619](https://github.com/albertocavalcante/gvy/pull/1619) | Strict schema and discovery controls; 116 native tests and applicable CI.                                                                                                   |
| [#1622](https://github.com/albertocavalcante/gvy/pull/1622) | Exact binary/text goldens and text update round-trip; seven native tests and applicable CI.                                                                                 |
| [#1637](https://github.com/albertocavalcante/gvy/pull/1637) | Xfail outcome boundary and nine retired lint findings; native tests and applicable CI.                                                                                      |
| [#1641](https://github.com/albertocavalcante/gvy/pull/1641) | Null presence/matching, fixture defaults and builder snapshots; 43 native unit controls plus two real stdio scenarios.                                                      |
| [#1643](https://github.com/albertocavalcante/gvy/pull/1643) | Unsupported JSON conversion fails explicitly; 39 native tests and applicable CI.                                                                                            |
| [#1645](https://github.com/albertocavalcante/gvy/pull/1645) | Golden write policy, confinement and read-only runfile compatibility; 45 native tests and applicable CI.                                                                    |
| [#1649](https://github.com/albertocavalcante/gvy/pull/1649) | Session/workspace resources, launch configuration and exit checks; 39 native tests and applicable CI. A later CI run exposed the worker-exit race tracked below.            |
| [#1652](https://github.com/albertocavalcante/gvy/pull/1652) | Notification evidence/checkpoints and one evaluation per candidate; 57 native tests and applicable CI.                                                                      |
| [#1653](https://github.com/albertocavalcante/gvy/pull/1653) | Reader-derived initialization-option guards; 64 native tests, a production-reader mutation control and applicable CI.                                                       |
| [#1661](https://github.com/albertocavalcante/gvy/pull/1661) | Actual listener-thread joins after executor termination; ten final native controls and all 20 applicable CI checks.                                                         |
| [#1662](https://github.com/albertocavalcante/gvy/pull/1662) | Structured provider outcomes and cancellation propagation; 23 native controls and applicable CI. Full F3 fencing remains open.                                              |
| [#1663](https://github.com/albertocavalcante/gvy/pull/1663) | Automatic document versions and server URI identity; 54 native controls and applicable CI. Applied workspace state remains open.                                            |
| [#1656](https://github.com/albertocavalcante/gvy/pull/1656) | Repro oracle repairs; 100 native controls, 13 actual known-xfail stdio scenarios and applicable CI. Product issues remain open.                                             |
| [#1665](https://github.com/albertocavalcante/gvy/pull/1665) | Independent mutable editor fixtures; macOS and Windows each passed 667 unit and 36 integration tests.                                                                       |
| [#1667](https://github.com/albertocavalcante/gvy/pull/1667) | Deterministic retirement-waiter entry handshake; 11 native controls and applicable CI. Production behavior unchanged.                                                       |
| [#1671](https://github.com/albertocavalcante/gvy/pull/1671) | Retained analysis-run outcomes bound to snapshot/configuration/incarnation; 68 native controls and applicable CI. Wire and leaf contracts remain open.                      |
| [#1677](https://github.com/albertocavalcante/gvy/pull/1677) | Valid dependency fixture, Java 17 library and full-publication assertions; 21 native tests and applicable CI. #981 stays open for the provider fence.                       |
| [#1678](https://github.com/albertocavalcante/gvy/pull/1678) | CodeNarc analyzer/resolver boundary failures reach the collector; 260 native tests, clean module lint and applicable CI. Other leaf boundaries remain open.                 |
| [#1673](https://github.com/albertocavalcante/gvy/pull/1673) | Whole-scenario deadline, lazy LSP startup and owned CLI cleanup; 207 composed native controls plus 29 overlapping follow-up controls, independent review and applicable CI. |
| [#1681](https://github.com/albertocavalcante/gvy/pull/1681) | CodeNarc conversion/loading faults and bounded workspace reads; 281 native controls and applicable CI. Filesystem discovery remains separate.                               |
| [#1682](https://github.com/albertocavalcante/gvy/pull/1682) | Ordered custom-rule failures, healthy results and context-accessor honesty; 119 native controls and applicable CI. Held context remains separate.                           |
| [#1685](https://github.com/albertocavalcante/gvy/pull/1685) | Concurrent bounded CLI capture with explicit cleanup confirmation; 28 native controls and applicable CI. Descendant containment remains open.                               |
| [#1688](https://github.com/albertocavalcante/gvy/pull/1688) | Checked formatter cancellation and fatal errors propagate before recovery; 17 native controls and applicable CI. Lower formatter stages remain #1686.                       |
| [#1696](https://github.com/albertocavalcante/gvy/pull/1696) | Raw JSON request/notification shapes and received nested nulls; 75 native controls and applicable CI. Numeric precision, general I/O and outer envelopes remain separate.   |
| [#1693](https://github.com/albertocavalcante/gvy/pull/1693) | Held AST/parser context, source provenance and terminal-outcome fixture assertions; 208 native controls and applicable CI. Captured mode and the wire fence remain open.    |
| [#1699](https://github.com/albertocavalcante/gvy/pull/1699) | Captured Jenkins mode and compiled-pattern classification; 226 native controls and applicable CI. Legacy live callers remain available; the wire fence remains open.        |
| [#1723](https://github.com/albertocavalcante/gvy/pull/1723) | Linux x86_64 kernel component; native and Linux CI each passed all 38 controls without skips. CLI integration and platform containment remain #1670.                        |

All listed PRs received independent final-head review and verified squash merges. Their PR descriptions retain the
reviewed head and check evidence. Passing these slices does not establish whole-harness lint or protocol coverage.

## Active follow-ups and proof limits

- **Runtime ownership:** #1661 resolved the worker-exit race found after #1649. A deterministic exit gate proves that
  logical executor termination is insufficient; teardown now joins owned threads within the shared timeout budget.
  Native controls, macOS/Windows editor checks and all applicable CI passed. Broader execution limits remain below.
- **Golden IO:** #1645 passed full CI and closed #1617. Comparisons accept read-only Bazel runfile links; updates retain
  lexical and physical write confinement. Earlier compilation and runfile-test failures remain recorded.
- **Repro contracts:** #1656 merged after 100 native good/mutant controls, 13 actual known-xfail stdio scenarios and
  applicable CI passed. These are not product fixes. The macOS editor rename failure led to #1665: mutating tests now
  own distinct URIs and uniquely named fixtures; final macOS and Windows runs each passed 667 unit and 36 integration
  tests. The original failure's cause was not proven. N7 still requires actual buffer/file verification under #1654.
- **Diagnostics and editor state:** harness/client owns #1657 and #1654. A settled result must bind the current document
  incarnation and configuration and expose provider failures. Parser-first publications, a bare job join, startup-ready
  status and quiet-window sleeps cannot establish that result. The collector component (#1662) passed 23 native
  controls, independent final-head review and all 21 applicable CI checks, then merged. Retained core outcomes (#1671)
  also merged after 68 native controls and applicable CI. CodeNarc's analyzer boundary (#1678), conversion/loading
  (#1681) and custom-rule outcomes (#1682) also merged with independent review, native controls and applicable CI. The
  protocol/harness fence remains active work. Ruleset discovery, temporary cleanup, the terminal wire API and the
  high-level harness fence retain separate proof obligations. Held AST/parser context merged in #1693 and captured
  Jenkins classification in #1699. Reviewing the first passing CI logs exposed three weak fixtures; terminal-result
  assertions reproduced those failures before repair and fresh publication-head validation. Edit callbacks must change
  state before acknowledging it.
- **Document versions:** F16 reproduced real discarded edits: YAML reused version 1 and Kotlin reused version 2.
  Harness/client delivered per-document automatic versions in #1663, a partial #1654 slice. Independent review also
  reproduced alias close/reopen errors; bookkeeping now uses the same URI equality as the server, preserving authored
  wire URIs. Explicit stale versions remain available; 54 native controls and applicable CI passed. This does not
  implement applied-edit or buffer-text state.
- **Lint:** #1557 remains non-green. Checkout ancestors named `test` can match absolute exclusion globs and hide
  findings; path-independent rules and sentinel checks are required before gating. Counts from different revisions or
  checkout paths are not comparable. New files receive explicit typed-lint inspection rather than a module `NO-SOURCE`
  claim.
- **Schema documentation:** unknown `expect` fields fail in the merged strict parser. The matching E2E README correction
  is prepared separately; its narrative-path CI classification remains pending #1646.
- **Protocol inventory:** the pinned
  [official 3.17 model](https://github.com/microsoft/language-server-protocol/blob/0ef5f1713c1344eb718dbd5f898149b1e11cb92e/_specifications/lsp/3.17/metaModel/metaModel.json)
  contains 64 stable requests and 26 notifications after excluding three proposed 3.18 requests. Harness/protocol owns
  the method/capability evidence matrix, gvy extensions and an advertisement drift check. Inventory is not coverage.
- **Protocol fidelity:** #1696 merged raw JSON transport and closed #1691. YAML and untyped-response numeric precision
  (#1695), transport I/O failure visibility and pending-request retirement (#1694), and complete envelope control remain
  separate work. Raw transport cannot recover precision already lost during scenario loading.
- **Formatting outcomes:** #1688 preserves checked-formatter cancellation and fatal failures. Lower OpenRewrite stages
  still recover faults as refusal or unchanged text; #1686 owns explicit engine failures and per-operation error state.
  F12 still requires the desired formatting result and applied, idempotent edit assertions.
- **Execution and resource limits:** #1673 merged whole-scenario deadlines, lazy CLI-only execution and CLI cleanup.
  Interrupted exit confirmation retries within one budget. #1685 merged concurrent byte-limited capture after 28 native
  controls and applicable CI, closing #1669. Owned-descendant cleanup (#1670) remains open: a parent can exit while its
  identified child stays alive outside the descendants snapshot. A private POSIX group handles ordinary orphans, but an
  escaped session survives group cleanup. Neither polling nor group cleanup proves arbitrary containment.
- **Diagnostic artifacts:** #1603 runtime producer/consumer enforcement and structured skip reasons remain pending. A
  Markdown ledger or a schema proposal cannot substitute for an executed, complete journal with asserted
  expected-negative references.

- **Numeric validation:** #1703 is under review for #1695. Its first Bazel run exposed runtime differences at the
  BigDecimal boundary and an infinity-encoding failure. Those failures are repaired and native-verified with 211 passing
  controls on each of Java 17 and Java 21; fresh publication-head CI remains pending. Larger exponent support remains
  #1700; the full numeric domain is not certified. Structured string interpolation and snapshot rendering still require
  exact-number follow-ups; the assertion-message fix does not cover those separate serializers.
- **Diagnostic positions:** #1708 tracks the real CodeNarc final-newline producer's zero-based position. Four failing
  real-runner controls and three passing controls precede the narrow adapter correction; it is not yet merged.

## References

- [Harness design](e2e-harness.md)
- [Quality Bar](../../docs/quality-bar.md)
- [Quality harness epic](https://github.com/albertocavalcante/gvy/issues/1546)
- [Conformance program](https://github.com/albertocavalcante/gvy/issues/1548)
- [Ignored scenario keys](https://github.com/albertocavalcante/gvy/issues/1556)
- [Harness lint](https://github.com/albertocavalcante/gvy/issues/1557)

The formatter pipeline fix is under review in [#1737](https://github.com/albertocavalcante/gvy/pull/1737): 224 native
passes, five inherited skips, and exact-head CI still pending after a direct Bazel test dependency and
maintenance-fingerprint correction. Numeric [#1703](https://github.com/albertocavalcante/gvy/pull/1703) passed 211
controls on each of Java 17 and Java 21 after the portable exponent and diagnostic-rendering corrections; fresh
publication-head CI remains required. Neither candidate is recorded as merged.

CodeNarc position review also found [#1740](https://github.com/albertocavalcante/gvy/issues/1740): valid
internal-carriage-return input can crash a formatting rule, and the SDK file analyzer catches that failure and omits the
file. The root provider then returns empty diagnostics. The adapter failure boundary is being fixed separately from
#1708; a tracker alone does not resolve it.
