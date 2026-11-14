<!--
SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
SPDX-License-Identifier: Apache-2.0
-->

# Jenkins Declarative IntelliSense delivery

Approved 2026-11-14. This is the canonical execution record for the Jenkins work under the
[Quality Bar](../docs/quality-bar.md) and [#1551](https://github.com/albertocavalcante/gvy/issues/1551). The private
audit and raw evidence remain outside Git. The approved scope and gates follow; [delivery state](#delivery-state)
distinguishes drafts from verified delivery.

## Current checkpoint

The first authoring baseline is implemented; publication remains pending. The full 24-slice program is open.

| Evidence layer              | Executed result                                                                | Scope / remaining gate                                                                                              |
| --------------------------- | ------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------- |
| Server JSON-RPC             | 3 fixture tests passed                                                         | Repeat with verified 1 GiB test-worker bound and the corrected raw transport capture.                               |
| VS Code integration         | 6 tests passed on 1.146.0                                                      | 3 provider captures plus 3 real suggest-widget acceptances; bundled candidate hash checked.                         |
| Desktop widget              | Settled blank lists and partial entry inspected                                | Both language modes showed the five parameter names in the controlled host; original noisy list was not reproduced. |
| Generic collection fix (03) | Fail-first plus 17 pure tests passed; independent review clear                 | Provider test, final lint and publication still pending.                                                            |
| Snippet capabilities (04)   | Omitted support reproduced as a failure; implementation reviewed and corrected | Separate omitted/false/true, wire/edit tests and lint await final execution.                                        |
| Compatibility oracle (02)   | Official root versions and advertised checksums inspected                      | Complete transitive locks, artifact-byte verification and both boot/control suites remain pending.                  |

**Publication gates:** complete final-head review and applicable CI are required. The native test worker was later found
to be configured at 4 GiB by a root build override despite a requested 1 GiB profile; functional results are retained,
but the bounded rerun is outstanding. No merge or full-program completion is claimed.

Private captures bind VS Code 1.146.0 to the candidate built from baseline `7caa1a59c`. The captured server hash is
`33d42df2c5831e08f3d2782b9995a1de0733e56029b73c83d7fbee72793da23b`. With `snippetSuggestions=inline`,
`suggest.showSnippets=true` and the built-in Groovy extension present, the controlled extension host showed exactly
`booleanParam`, `choice`, `password`, `string` and `text` after settling in both Jenkinsfile and explicitly switched
Groovy mode. This is a scoped counterexample, not proof about the original editor/profile. G01 stays open for
lifecycle/isolation and final packaged verification. The argument response includes all four definition keys and seven
globals, preserving the G06 key-site leakage evidence. Accepted entry text is a bare name, preserving the G02 insertion
defect. Earlier infrastructure and harness failures remain in the private run record.

Review found that the initial server JSON captures reconstructed response envelopes after deserialization. Their
completion contents remain useful, but they are not raw protocol evidence. The corrected test records both actual framed
byte streams, including the transport's request IDs. Execution of that capture remains a publication gate.

<details>
<summary>Owned verification limits and next actions</summary>

| Item                         | Impact / reason                                                                                                                                                                                                 | Owner and next action                                                                                                                                                                                       | Closure gate                                                                                                                                     |
| ---------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| Test-worker resource profile | Root Gradle configuration applied a 4 GiB worker cap in the initial native run. Functional results are retained; the requested 1 GiB bound was not established.                                                 | Coordinator; use the reviewed post-evaluation test-limit override and inspect its execution assertion and child arguments in the next unit run.                                                             | Passing applicable tests with the effective cap recorded; never rewrite the earlier result as compliant.                                         |
| Raw transport capture        | Initial server captures serialized reconstructed envelopes with fixture-name IDs.                                                                                                                               | Coordinator; execute the reviewed client/server byte-stream recorder and correlate the actual completion request and response IDs.                                                                          | Framed request/response evidence from the final test source, with passing assertions.                                                            |
| Editor helper diagnostics    | Installed Code 1.146.0 emitted Node `DEP0169` for `url.parse()` and AgentHost session/receipt messages. An attempted trace did not identify the callsite. These are outside the current Gradle diagnostic gate. | Editor-test harness / coordinator, tracked under [#1603](https://github.com/albertocavalcante/gvy/issues/1603); attribute the helper callsite and determine the supported fix or exact temporary exception. | Trace-bound ownership and a warning-free affected run or a separately reviewed, owned, expiring exception. No warning-free editor claim is made. |
| Original popup attribution   | Settled controlled-host lists were clean in both modes despite enabled snippet settings and an available built-in Groovy extension. Original user-profile state is unknown.                                     | 05/24 editor integration; preserve this counterexample and verify actual association/provider conditions before claiming isolation.                                                                         | Language lifecycle and packaged authoring matrix with accurately identified providers.                                                           |
| Compatibility inputs         | Root versions and advertised checksums are known; transitive graph hashes and boot receipts are absent.                                                                                                         | 02/12 extractor; generate and verify complete locks, then both oracle controls.                                                                                                                             | Both locked graphs boot, validate controls and regenerate deterministically.                                                                     |

Raw private source, request and screenshot evidence stays outside Git. This table records unresolved work; it does not
close it.

</details>

## 1. Outcome and fixed decisions

Deliver consistent completion, insertion, hover, signature help, navigation, and diagnostics across Declarative Pipeline
constructs. Close every record in the [audit closure ledger](#audit-closure-ledger) with executable evidence, including
the additional findings from planning.

The agreed scope is **offline IntelliSense, profile-file import, and an isolated development oracle**. Connecting to a
user’s controller, authentication, and remote validation commands are excluded.

Two compatibility targets will be locked:

| Profile | Jenkins core | Declarative plugin       |
| ------- | ------------ | ------------------------ |
| Current | **2.580.2**  | **2.2293.v6e7193cec599** |
| Legacy  | **2.479.3**  | **2.2265.v140e610fe9d5** |

The current core and plugin versions come from the [official download page](https://www.jenkins.io/download/) and
[plugin catalogue](https://plugins.jenkins.io/pipeline-model-definition/). The legacy plugin’s
[release POM](https://github.com/jenkinsci/pipeline-model-definition-plugin/blob/140e610fe9d5290e5efbb75c6da39cfb18ac7c7d/pom.xml)
declares Jenkins 2.479.3.

The schema’s existing `2.2214.v00573e73ddf1` provenance could not be resolved through the checked upstream tag or Maven
release path. It must not become an oracle input.

Other fixed decisions:

- Keep the native Groovy parser. Add a shared Declarative view over its captured source snapshot.
- Reuse the existing `jenkinsfile` language ID.
- Keep language intelligence in the server and editor presentation in the extension.
- Generate descriptor metadata in isolated development tooling; never execute workspace plugin code inside the LSP.
- Preserve explicit user settings. Do not silently change file associations or suppress snippets globally.
- Treat profile provenance, syntax confidence, and controller availability as separate facts.
- Ship early correctness fixes as their gates pass; do not wait for all 24 PRs.

## 2. Shared contracts and compatibility rules

### Domain and feature boundaries

The `jenkins` module owns the pure Declarative model, metadata, binding rules, and symbol resolution. The `gls` module
owns snapshot capture, the Groovy-AST adapter, LSP positions, edits, cancellation, and feature orchestration. The
existing extractor owns fetching, descriptor execution, generation, and the external oracle.

Introduce these minimum shared contracts:

| Contract                 | Required behavior                                                                                                                            |
| ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------- |
| `DeclarativeDocument`    | Immutable source identity, UTF-16 ranges, construct roles, ancestry, sibling declarations, and recovery confidence.                          |
| `CompletionSite`         | Distinguishes structural statements, entry names, argument keys, expressions, literal values, members, Groovy bodies, and unknown positions. |
| `ResolvedJenkinsCall`    | Resolved, ambiguous, or unknown; includes namespace, binding model, call forms, documentation, provenance, and availability.                 |
| `JenkinsMetadataProfile` | Versioned descriptor graph with exact core/plugin inputs, aliases, parameter shapes, contexts, cardinality, and artifact hashes.             |

Resolution must distinguish, for example:

- `parameters { string(...) }`
- `withCredentials([string(...)])`
- `options { timeout(...) }`
- `steps { timeout(...) { ... } }`

Completion, hover, signature help, and diagnostics must consume the same resolution result. Local methods, variables,
and shared-library declarations retain their shadowing behavior.

Requiredness and availability need explicit unknown states. An unknown value must never become an invented optional
parameter or a claim that a plugin is absent.

### Configuration and precedence

Add flat server settings `jenkins.metadata.profile` and `jenkins.metadata.profileFile`, exposed with the extension’s
existing `groovy.` prefix.

Selection order:

1. Explicit profile file.
2. Explicit bundled profile ID.
3. Bundled current profile, visibly identified as the assumed fallback.

Metadata precedence:

1. Explicit user metadata overrides, within the matching namespace and labelled as overrides.
2. Selected descriptor profile.
3. Static discovery for otherwise unknown symbols.
4. Curated fallback metadata.

Parsed GDSL may add callables and globals without degrading known descriptor signatures.

Existing plugin configuration continues to constrain fallback catalogues. A complete imported manifest owns its
profile’s plugin graph. Contradictory **explicit versions** produce a configuration error; omitted versions remain
unknown. Preserve disabled-versus-absent information when supplied.

Profile loading must work without a plugins file and must not invoke the plugin downloader. Do not activate dormant
configuration-discovery paths incidentally.

Imports are data-only JSON, limited to 16 MiB and depth 64, with no executable content or remote references. Reject
unsupported schema major versions. An invalid replacement retains the exact last-good profile, marked stale with its
digest and error; an invalid first import uses a visibly identified bundled fallback.

Add a read-only `groovy/jenkinsContext` request exposing effective mode, selected profile identity, provenance, and
readiness. The extension uses it for per-document status, without per-keystroke requests.

### Snapshot and mode rules

Mode precedence is explicit enable/disable configuration, then the client’s `jenkinsfile` language ID, then existing
filename detection.

Preserve the language ID through document changes and reset it correctly on close/reopen. Derived state must be keyed by
document incarnation, version/content identity, effective configuration, profile generation, and classpath context. Each
request uses one consistent snapshot.

## 3. PR sequence

Dependencies below refer to PR numbers in this plan.

### Wave A — Establish evidence and ship independent fixes

| PR                                                                  | Change                                                                                                                                                                                                             | Dependencies and completion gate                                                                                                                                                                                                                                  |
| ------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **01 — Establish Declarative authoring regressions**                | Create the canonical delivery record, reconcile the audit’s provenance correction, and add representative server/editor fixtures. Capture raw LSP results separately from the actual suggest widget.               | **None.** Reproduce confirmed defects on the base. Inferred defects become reproduced failures or documented counterexamples. Any repository-supported expected failure has an owning issue and is removed by its fix.                                            |
| **02 — Pin compatibility inputs and build the oracle**              | Lock both complete transitive plugin graphs, core artifacts, tool inputs, versions, and hashes. Establish isolated upstream parser/validator execution and known accepted/rejected cases.                          | **01.** Both graphs boot and validate their controls. Dependency minimums and hashes are verified. Explicit oracle requests fail when inputs are unavailable. Runs use unique non-root resources, loopback binding, explicit heap limits, and cleanup on failure. |
| **03 — Fix generic collection snippets**                            | Correct generic lists/maps, fully qualified names, nested generics, arrays, primitives, wrappers, and unknown-type handling. Keep legal string-form `choices` support.                                             | **01.** Fail-first cases prove `List<String>` receives a collection scaffold. Existing primitive and raw-container behavior remains correct.                                                                                                                      |
| **04 — Negotiate snippet capabilities**                             | Honor omitted, false, and true LSP `snippetSupport`. Render valid plain-text alternatives through structured insertion templates.                                                                                  | **01.** Every affected completion path handles both `insertText` and `textEdit` correctly. Clients without snippet support receive no placeholder syntax.                                                                                                         |
| **05 — Preserve Jenkins document mode and verify editor isolation** | Carry language ID through document lifecycle; support untitled Jenkins documents; exercise existing associations and selectors. Offer an explicit switch action when a detected Jenkins document uses Groovy mode. | **01.** Actual editor tests cover associations, explicit overrides, switching, reopen, grammar, and LSP activation. Built-in Groovy snippets are isolated in Jenkinsfile mode; unrelated providers are accurately attributed.                                     |

### Wave B — Establish one semantic path for IntelliSense

| PR                                                              | Change                                                                                                                                                                                                                                                                         | Dependencies and completion gate                                                                                                                                                                                                                                  |
| --------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **06 — Preserve typed metadata and provenance**                 | Separate executable steps, nested Describables, directive entries, options, conditions, agents, tools, triggers, and globals. Preserve positional arguments, return types, value domains, block support, contexts, aliases, and unknown states. Add adapters for current data. | **01.** Round trips preserve these fields. Legacy inputs remain readable. Credentials-binding `string` no longer enters the executable-step catalogue merely because its name is known.                                                                           |
| **07 — Derive snapshot-bound Declarative context**              | Build the immutable document view and completion-site classifier. Reuse existing UTF-16 conversion utilities. Add bounded recovery for incomplete source.                                                                                                                      | **06.** Tests cover ancestry, sibling state, argument roles, Groovy escape bodies, malformed input, CRLF/bare CR, astral characters, cancellation, and stale snapshots.                                                                                           |
| **08 — Unify contextual call resolution and hover**             | Route directive-key completion, method hover, and parameter-key hover through the shared resolver. Preserve local/shared-library shadowing and unresolved dynamic calls.                                                                                                       | **06, 07.** Parameter-definition and credential-binding `string` display the appropriate model. Nested unrelated calls remain unrelated. Ambiguous calls do not acquire invented signatures.                                                                      |
| **09 — Insert useful calls and provide Jenkins signature help** | Generate required arguments, tab stops, legal call forms, and descriptor-aware named-argument signatures. Integrate signature help with accepted calls in VS Code through the thin presentation layer.                                                                         | **03, 04, 08.** Blank/partial entry names, existing parentheses, command syntax, optional arguments, and commas produce correct edits. Active-parameter help agrees with hover and completion. Unknown metadata falls back safely without duplicate signatures.   |
| **10 — Filter candidates by site and structure**                | Separate key, value, literal, member, and structural candidates. Use placement, sibling cardinality, and execution-body exclusivity.                                                                                                                                           | **07, 08.** Complete-list assertions cover every site. Already-present singleton sections disappear appropriately, while editing their existing declaration and adding repeatable entries still work. Groovy expression completion remains available where legal. |
| **11 — Index declared parameters and environment variables**    | Index literal declarations from unsaved snapshots; provide scoped members, types, hover, and definition links.                                                                                                                                                                 | **07, 08.** Root build parameters inform `params`; pipeline/stage environment declarations inform scoped `env`. Edits, removal, shadowing, duplicate/dynamic names, and cross-stage visibility remain correct. Environment values retain string semantics.        |

### Wave C — Replace static assumptions with reproducible profiles

| PR                                                      | Change                                                                                                                                                                                                                                                              | Dependencies and completion gate                                                                                                                                                                                                                                    |
| ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **12 — Extract complete Declarative descriptor graphs** | Extend the existing extractor to obtain parameter definitions, agents, conditions, options, triggers, tools, steps, nested Describables, globals, aliases, and binding forms. Keep structural rules as explicit versioned tables checked against upstream behavior. | **02, 06.** Extraction is deterministic. A small fixture plugin adds a parameter, condition, and agent without editing curated JSON. Descriptor names differing from class names are correct. Unsupported static discoveries remain unknown.                        |
| **13 — Load profiles and offline imports consistently** | Implement profile selection, schema migration, precedence, all-catalogue filtering, atomic reload, status reporting, and legacy metadata integration. Remove the plugins-file dependency from metadata loading.                                                     | **08, 12.** Metadata-only configuration works with no plugin downloads. Test both targets, conflicting explicit versions, missing versions, disabled plugins, invalid/oversized imports, unknown schema versions, concurrent reloads, and exact last-good identity. |

“Verified extraction” means verified against the locked inputs. It does not mean those plugins were observed on the
user’s controller.

### Wave D — Complete each Declarative authoring surface

| PR                                                  | Change                                                                                                                                                             | Dependencies and completion gate                                                                                                                                                                                                 |
| --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **14 — Complete legal agent forms**                 | Model inline zero-argument agents, value-taking agents, and block agents. Add positive node-agent child completion and profile-specific Docker/Kubernetes support. | **09, 10, 13.** Valid inserted forms pass the oracle. `node` offers its legal options. Unsupported forms and unavailable profile capabilities are handled accurately.                                                            |
| **15 — Apply option scope and wrapper eligibility** | Distinguish pipeline, stage, and matrix option contexts using descriptor information. Support eligible wrapper steps without treating every step as an option.     | **10, 13.** Exact candidate sets match both locked targets; parameter help and hover use the same eligible model.                                                                                                                |
| **16 — Complete typed `when` conditions**           | Add condition argument schemas, values, aliases, flags, and child cardinality. Separate outer flags, combinators, and expression bodies.                           | **09, 10, 13.** `beforeAgent`/`beforeInput`/`beforeOptions` stay at the outer container. `not`, `allOf`, and `anyOf` enforce their verified rules. Condition keys and values complete; expression bodies retain Groovy behavior. |
| **17 — Model Declarative libraries**                | Add `lib` entries, insertion, hover, and structural handling. Preserve existing `@Library` resolution and scripted library calls.                                  | **09, 10, 13.** `libraries` offers its legal entries without a generic step catalogue. Accepted examples validate, and shared-library navigation regressions pass.                                                               |
| **18 — Complete input and descriptor-entry bodies** | Finish nested input parameters, triggers, and tools using generated models. Connect stage-input declarations to their verified stage-scoped environment exposure.  | **09, 10, 11, 13.** Nested parameter definitions resolve correctly. Input parameters do not become root `params` entries. Tool names and other controller-specific values remain unknown unless supplied as data.                |
| **19 — Complete parallel and matrix authoring**     | Add containing-stage `failFast` for both parallel and matrix execution; finish axes, exclusions, contextual `notValues`, and legal nesting candidates.             | **10, 13.** Positive and negative cases match the oracle. `failFast` is not inserted inside matrix configuration, and remains distinct from scripted parallel arguments and `parallelsAlwaysFailFast`.                           |

Each DSL PR must include complete candidate-set, insertion, hover/signature where applicable, and accepted/rejected
upstream cases.

### Wave E — Close validation, recovery, and delivery gaps

| PR                                                                 | Change                                                                                                                                                                                                                                            | Dependencies and completion gate                                                                                                                                                                                                              |
| ------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **20 — Validate Declarative structure**                            | Extend the AST-based stage rule into shared-model validation for required sections, legal placements, singleton cardinality, duplicate constructs where upstream rejects them, execution bodies, agents, conditions, input, and matrix structure. | **14–19.** Every rule has independent accepted/rejected fixtures, stable diagnostic codes, and correct ranges. Scripted pipelines and Groovy escape bodies avoid Declarative false positives.                                                 |
| **21 — Complete values and validate argument binding**             | Add editing support inside existing value literals and validate known argument names, requiredness, supported literal types, and binding forms.                                                                                                   | **09, 13, 20.** Booleans, enums, collections, quoting, escaping, nested Describables, and existing keys behave correctly. Dynamic expressions and unknown models do not produce speculative errors.                                           |
| **22 — Gate target-language compatibility**                        | Establish a feature ledger against both exact target compilers/converters. Add local incompatibility diagnostics only for syntax whose target rejection is proven.                                                                                | **02, 13, 20.** Fixtures demonstrate relevant differences between gvy’s Groovy parser and target Jenkins parsing. Support claims distinguish local parsing, Declarative validation, CPS/sandbox behavior, and execution.                      |
| **23 — Complete recovery, concurrency, and performance hardening** | Sweep incremental edits, profile changes, reopen, cancellation, cache eviction, and large/recovered documents. Remove redundant scans and obsolete recovery paths.                                                                                | **09–22.** No stale edits/results cross source or profile generations. Closed documents and retired profiles release state. Fixed workloads report cold/warm latency and memory without repeatable regressions attributable to the new model. |
| **24 — Verify regeneration, packaged IntelliSense, and closure**   | Wire reproducible regeneration and upstream drift detection into CI; exercise the packaged extension; reconcile design notes and issue status; remove superseded feature paths.                                                                   | **All earlier PRs.** Locked inputs reproduce identical metadata. Drift creates a reviewable change. Packaged editor tests prove the complete authoring flow. Every audit record has a passing proof or documented disconfirmation.            |

## 4. Verification and merge discipline

Each behavior-changing PR follows fail-first development: prove the regression against its actual base, implement the
fix, and verify the final publication head.

Use three complementary layers:

- **Pure and server tests:** exact candidate sets, namespaces, insertion edits, binding rules, diagnostics,
  source/profile identity, and capability negotiation.
- **Real VS Code tests:** actual suggest-widget contents and accepted text, plus hover/signature behavior and document
  language lifecycle. Provider API results alone are insufficient for the screenshot regression.
- **Independent Jenkins oracle:** generated output and handwritten positive/negative fixtures checked against both
  locked environments. Expected results must not be generated by gvy’s candidate parser.

The final matrix includes:

- Parameter definitions versus credential bindings; root and stage input parameters.
- Blank/partial names, keys, expressions, literals, members, nested maps/calls, and command syntax.
- All agent forms, option scopes, conditions/combinators, post bodies, libraries, tools, triggers, parallel, and matrix.
- Unsaved declarations, removal, shadowing, ambiguous/dynamic names, and scoped visibility.
- Incomplete syntax, strings/comments/interpolation, Unicode positions, line endings, rapid edits, cancellation, and
  reopen.
- Profile selection, import failure, plugin changes, schema migration, fallback identity, and offline startup.
- Clients with snippet support omitted, disabled, and enabled.

Ordinary tests remain offline. Oracle execution is an explicit development gate and never executes fixture Pipelines.

Run repository-required lint and applicable checks. Use `build-slot --` for heavy builds, one Gradle build at a time,
and one oracle environment at a time. Shell changes receive ShellCheck and shfmt. CI changes retain verified SHA pins
and exact version comments.

Every PR receives independent complete-diff review. State-sensitive PRs—especially snapshot handling and profile
reload—receive higher-effort review. Merge only the reviewed head with all applicable checks satisfied, then verify the
squash commit and clean up the owned branch/worktree.

## 5. Integration, rollout, and finish line

Use one coordinator, at most two implementers on independent paths, and an independent reviewer. Terra at medium effort
is the default for implementation and routine review; use Luna for bounded inventories and mechanical work, and
stronger/high-effort review for unresolved state or compatibility issues. Heavy validation remains serialized.

The main dependency paths are:

- **Immediate fixes:** 01 → 03/04/05.
- **Shared IntelliSense:** 06 → 07 → 08 → 09/10/11.
- **Metadata:** 02 + 06 → 12 → 13.
- **DSL coverage:** 09/10/13 → 14–19 → 20–22.
- **Final closure:** 23 → 24.

Correctness fixes become default behavior when their gates pass. Internal adapters preserve behavior during migration;
they must not produce duplicate candidates or conflicting feature answers. Revert a failing slice without changing user
configuration.

Track delivery through one canonical task plan linked to the existing quality track and relevant issues, including
**#607, #516, #588, and #1461**. Keep raw private evidence outside Git. Reconcile stale design claims instead of
creating a competing roadmap.

The program is complete when:

- All **G01–G25** records have executable closure or documented disconfirmation.
- Snippet-capability handling, metadata-only loading, and unverifiable schema provenance are also resolved.
- Both compatibility profiles pass their declared conformance suites.
- The packaged extension demonstrates the screenshot workflow and subsequent parameter authoring.
- No relevant expected failure, unowned exception, or missing required check is hidden by a completion claim.
- All changes are reviewed, merged, verified, and cleaned up.

Unknown controller configuration, arbitrary plugin runtime behavior, and full Pipeline execution remain explicitly
outside the offline support claim.

## Delivery state

Baseline: `7caa1a59c2e619a23ea163cebcfa2348749ec5e1`. The current checkpoint separates executed evidence from
publication and feature closure. Native work moved to an isolated Air checkout because the Mini's canonical Gradle home
resolves to the nearly full T9 volume. No implementation gate is closed by writing this record.

The coordinator owns integration, publication-head validation, independent review, merge verification and cleanup. Each
row's next action is to implement its change and satisfy the completion gate in section 3 after its dependencies.

| Slice | Responsible scope                | State       | Evidence / next action                                                                                         |
| ----- | -------------------------------- | ----------- | -------------------------------------------------------------------------------------------------------------- |
| 01    | Server/editor tests; coordinator | In progress | Add common authoring fixtures, capture protocol and widget separately; reproduce defects against the baseline. |
| 02    | Extractor/oracle                 | Pending     | Lock both complete graphs; boot and validate controls.                                                         |
| 03    | Completion insertion             | Draft       | Generic collection fail-first work; independent review and publication follow 01.                              |
| 04    | LSP capability negotiation       | Draft       | Omitted/false/true support across insertion fields and edits; follows 01.                                      |
| 05    | Document lifecycle/editor        | Pending     | Preserve language ID and test actual editor isolation.                                                         |
| 06    | Jenkins metadata                 | Pending     | Preserve typed namespaces and unknown provenance.                                                              |
| 07    | Declarative syntax view          | Pending     | Snapshot identity, UTF-16 ranges and recovery confidence.                                                      |
| 08    | Shared resolution/hover          | Pending     | Context-qualified calls with shadowing.                                                                        |
| 09    | Insertion/signature help         | Pending     | Required arguments and accepted-call signatures.                                                               |
| 10    | Completion sites                 | Pending     | Exact candidate sets, siblings and cardinality.                                                                |
| 11    | Declaration index                | Pending     | Unsaved scoped params/env members and navigation.                                                              |
| 12    | Descriptor extraction            | Pending     | Deterministic graphs and custom fixture plugin.                                                                |
| 13    | Offline profile loading          | Pending     | Atomic reload, precedence and exact last-good identity.                                                        |
| 14    | Agents                           | Pending     | All legal forms, including node child options.                                                                 |
| 15    | Options                          | Pending     | Pipeline/stage/matrix scopes and eligible wrappers.                                                            |
| 16    | Conditions                       | Pending     | Typed arguments, outer flags and child cardinality.                                                            |
| 17    | Libraries                        | Pending     | lib entries and preserved shared-library navigation.                                                           |
| 18    | Input/entries                    | Pending     | Scoped input parameters, triggers and tools.                                                                   |
| 19    | Parallel/matrix                  | Pending     | Containing-stage failFast and axis/exclusion structure.                                                        |
| 20    | Structure diagnostics            | Pending     | Independent positive/negative cases and stable codes/ranges.                                                   |
| 21    | Values/binding                   | Pending     | Literal edits and conservative known-model validation.                                                         |
| 22    | Target language                  | Pending     | Both exact compilers/converters; no execution claims.                                                          |
| 23    | Recovery/performance             | Pending     | Generations, cancellation, eviction and measured workloads.                                                    |
| 24    | Regeneration/package closure     | Pending     | Deterministic regeneration, actual packaged flow and audit closure.                                            |

## Audit closure ledger

The audit recorded source findings separately from executable reproductions. All records remain open until passing proof
or a measured counterexample is linked here. The named slices own the next action and acceptance criteria in section 3;
private source snapshots establish provenance, not passing behavior.

| Record     | Impact                          | Owning slices | Current evidence / next action                                                                                     |
| ---------- | ------------------------------- | ------------- | ------------------------------------------------------------------------------------------------------------------ |
| G01        | Editor snippet pollution        | 01, 05, 24    | Controlled-host widget/acceptance captured; original noisy list not reproduced. Preserve that limit through 05/24. |
| G02        | Bare descriptor-entry insertion | 01, 09        | Source finding; reproduce blank and partial entry insertion.                                                       |
| G03        | Quoted generic collections      | 01, 03        | Fail-first `List<String>`: quoted string instead of collection; draft fix requires final checks/review.            |
| G04        | Context-insensitive hover       | 01, 08        | Reproduce parameter-definition versus credentials-binding string.                                                  |
| G05        | Missing Jenkins signature path  | 01, 09        | Measure actual signature results before asserting absence.                                                         |
| G06        | Key/value candidate leakage     | 01, 07, 10    | Assert entire lists at keys, expressions and nested calls.                                                         |
| G07        | Existing literal editing        | 01, 21        | Reproduce edits inside existing quoted values.                                                                     |
| G08        | Declaration-aware params/env    | 01, 11        | Measure unsaved declarations, edits and scope.                                                                     |
| G09        | Static schema/version selection | 02, 12, 13    | Existing provenance is unverified; never use as oracle input.                                                      |
| G10        | Placement not enforced          | 07, 10, 20    | Test ancestor roles and illegal placement.                                                                         |
| G11        | Singleton/exclusive siblings    | 07, 10, 20    | Test suppression while preserving existing-node editing.                                                           |
| G12        | Option scope                    | 12, 15        | Compare exact eligible sets against both locked targets.                                                           |
| G13        | Agent forms/node children       | 14            | Positive child candidates and oracle-accepted inserted forms.                                                      |
| G14        | When flags/combinators          | 16            | Outer flags and verified child counts.                                                                             |
| G15        | Typed condition arguments       | 12, 16        | Descriptor schemas, aliases and literal values.                                                                    |
| G16        | Declarative failFast            | 19            | Containing-stage parallel and matrix controls.                                                                     |
| G17        | Libraries body                  | 17            | Proper lib entry set and accepted insertion.                                                                       |
| G18        | Declarative dynamic discovery   | 12            | Fixture plugin adds parameter, condition and agent.                                                                |
| G19        | Partial plugin filtering        | 13            | All catalogues, disabled/absent/unknown states.                                                                    |
| G20        | Static scanner guesses          | 06, 12        | Differing descriptor function/class/Symbol control.                                                                |
| G21        | Flattened rich metadata         | 06            | Round-trip shape/provenance and consumer tests.                                                                    |
| G22        | Limited semantic diagnostics    | 20, 21        | Independent accepted/rejected rules; script escape controls.                                                       |
| G23        | Separate recovery model         | 07, 23        | Snapshot-bound malformed/Unicode/line-ending cases.                                                                |
| G24        | Target-language compatibility   | 02, 22        | Exact target rejection before local diagnostics.                                                                   |
| G25        | End-to-end proof gaps           | 01, 24        | Protocol, actual widget and accepted text captured for initial sites; oracle/full packaged matrix remain open.     |
| Additional | Snippet capabilities            | 04            | Omitted and false support must have plain insertion and edits.                                                     |
| Additional | Metadata-only loading           | 13            | No plugins-file/download dependency.                                                                               |
| Additional | Unverifiable schema provenance  | 01, 02, 12    | Curated schema is not verified extraction; replace only with locked evidence.                                      |

## Provenance correction

The curated schema records `2.2214.v00573e73ddf1`. The audit could not resolve this exact version through the checked
upstream tag or Maven release path. The earlier automation proposal also used an unverified `2.2277.v00573e73ddf1`
example and a speculative version matrix. Neither establishes compatibility. The approved current/legacy inputs above
are fixed inputs to verify and lock in 02. The upstream AST JSON schema describes interchange, not the complete Groovy
DSL or runtime descriptor graph. Static scanning stays a bounded fallback; isolated descriptor execution belongs in the
existing extractor.
