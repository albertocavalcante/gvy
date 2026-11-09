# Spock developer experience: implementation specification

Use this plan to deliver Spock-aware analysis, standard Language Server Protocol (LSP) features, and editor extensions
in small reviewed changes.

Status: implementation contract and baseline audit dated 2026-11-08. The accompanying implementation adds
pipe-table/data-pipe/derived-variable declarations, unroll-root mapping, data-variable completion and definition lookup,
legal cleanup/where transitions, filter/combined vocabulary, and conservative table alignment. The remaining
capabilities below are proposals with separate acceptance gates. Related designs: [Spock support](SPOCK_SUPPORT.md) and
[AST support](SPOCK_AST_SUPPORT.md). Their historical status statements need reconciliation as changes land.

## Correctness constraints

Target Spock 2.4 semantics initially; record version-dependent behavior in workspace framework context.

- A data table uses `a | b`, optionally `||` between inputs and outputs. A data pipe uses `a << provider`. Groovy's `in`
  operator is not a Spock pipe.
- Semicolon-separated tables are also valid. Switching between pipe and semicolon separators starts a separate table;
  incomplete support must suppress speculative unknown-variable diagnostics.
- Method parameters may be omitted: `where:` declarations still introduce data variables. Providers cannot access
  `given:` locals or plain instance fields. Shared and static fields are available.
- `cleanup:` precedes `where:`. A `where:` block may be followed by `filter:`, which comes last. `combined:` joins
  providers through a Cartesian product.
- Interactions are valid before the relevant `when:`, in fixture or instance helper methods, and at mock creation.
  Rejecting all interactions outside `then:` or `setup:` would reject valid programs.
- `Mock`, `Stub`, and `Spy` are inherited APIs. Byte Buddy supplies runtime proxy generation; a missing Byte Buddy
  import does not establish a source error.
- Unroll expressions support a data-variable root followed by properties or zero-argument calls. Built-in roots include
  `featureName`, `iterationIndex`, `dataVariables`, and `dataVariablesWithIndex`. An explicit `@Unroll` pattern
  overrides the method-name pattern. Respect `@Rollup` and configuration before asserting interpolation behavior.

Sources: [data-driven testing](https://spockframework.org/spock/docs/2.4/data_driven_testing.html),
[block rules](https://spockframework.org/spock/docs/2.4/spock_primer.html#_cleanup_blocks), and
[interaction placement](https://spockframework.org/spock/docs/2.4/interaction_based_testing.html#_where_to_declare_interactions).

## Audit inventory

These findings describe the baseline before this change. Regression tests must establish their user-visible effects
before fixes land. Class names below identify files within the named module; package prefixes are omitted for
readability.

| ID | Evidence                                                                                                                                                                                                                                  | Impact and required proof                                                                                                                   |
| -- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| S1 | `ext/spock`, `SpockBlockIndex.validNextBlocks`, allows cleanup after where and no where after cleanup.                                                                                                                                    | Incorrect transition suggestions if consumed. Test both legal and illegal transitions.                                                      |
| S2 | `ext/spock`, `SpockBlock` lacks FILTER and COMBINED. `SpockBlockIndex` reads only the first statement label.                                                                                                                              | Incomplete context. Test additional labels, consecutive labels, and continuation inheritance.                                               |
| S3 | `gls`, `SpockCompletionStrategy` emits a fixed label list at whitespace-only line starts using heuristic file detection.                                                                                                                  | Missing scoped API/data-variable completion and possible activation in another class. Test class boundaries and typed prefixes.             |
| S4 | `gls`, `SpockTestStructureRule` uses filename matching and a 20-line lookahead without isolating method bodies or literals.                                                                                                               | Adjacent methods, comments, and long bodies can affect diagnostics. Replace with model-based analysis.                                      |
| S5 | `ext/spock` models detection, blocks, and feature names, but has no data-variable/unroll source model. No Spock data-binding path was found in semantic modules.                                                                          | Navigation and column typing need explicit declaration identities. Distinguish missing behavior from ordinary Groovy resolution in tests.   |
| S6 | `ParseRequest` defaults to CONVERSION; `GroovyCompilationService` requests CANONICALIZATION. `GroovyParserFacade` retries selected failures at CONVERSION and builds tokens separately.                                                   | Phase fallback does not prove incremental syntax recovery. Test dangling labels and incomplete expressions using current-version locations. |
| S7 | `SpockBlockIndex` uses statement line positions and ends spans at the last statement.                                                                                                                                                     | Blank lines, label-only edits, and same-line constructs need cursor-context tests and offset ranges.                                        |
| S8 | `TestCodeLensProvider` already emits `groovy.test.run`, debug, and coverage commands. The editor uses `groovy/runTest`. `editors/code/client/src/features/testing/testTarget.ts` maps dynamic iteration items to their enclosing feature. | Feature execution exists. Isolated iteration reruns need engine identities before they can be advertised.                                   |
| S9 | No Spock-specific lifecycle hover or block classification was found in current hover/semantic-token providers.                                                                                                                            | Add these through standard providers once source context exists.                                                                            |

## Shared source model

### Observed syntax-recovery limit

A native-parser probe using Groovy 4.0.33 and `ParseRequest(..., compilePhase = Phases.CONVERSION)` confirmed that
either a dangling `then:` or an unfinished `value ==` removes the entire file AST, including the adjacent `surviving`
feature. Completing `value == 1` restores both methods with no syntax diagnostics. This is an observed S6 limitation;
the source-model and provider changes do not fix parser recovery.

Reproduce with this fixture, leaving `then:` empty, then trying `value ==` and `value == 1` as its body:

```groovy
class ExampleSpec extends spock.lang.Specification {
    def broken() {
        given: def value = 1
        when: value++
        then:
    }
    def surviving() {
        expect: true
    }
}
```

The parser already disables `org.spockframework.compiler.SpockTransform` explicitly, preserving source labels when Spock
is on the classpath. Recovery must address syntax failure independently of transform suppression. The acceptance gate is
current-version partial structure and usable adjacent features after every edit; reusing old coordinates without a
source-version match is insufficient.

### Model ownership

Ownership: `ext/spock` owns pure AST/token analysis; `gls` owns caching and protocol adapters; the editor owns rendering
and explicit test execution. The full target model contains:

| Entity           | Required information                                                                                                   |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------- |
| Document context | URI, exact content/version identity, parser outcome, framework version, classes, features                              |
| Feature          | Owning class, method identity, declaration/name ranges, parameters, blocks, data declarations, unroll pattern          |
| Block            | Kind, original label range, half-open body range, effective continuation kind                                          |
| Data variable    | Name, declaration range, feature owner, origin (header, pipe, destructuring, assignment), inferred type and confidence |
| Table            | Header/cell ranges, separator tokens, rows, structural validity                                                        |
| Unroll reference | Root and full expression ranges, annotation/name origin, resolved declaration or built-in                              |

Use pre-transform structure. Retain offsets internally and centralize LSP UTF-16 conversion, including CRLF and
supplementary characters. Preserve spelling and trivia separately from normalized symbols. Lexer tokens must distinguish
separators from pipes inside strings, comments, closures, and nested expressions. Never split source on every `|`.

Cache against exact content and configuration; invalidate on change/close. Older AST coordinates must not produce
current-version edits or diagnostics. If malformed syntax loses a feature, return partial context and suppress
speculative semantic errors there. Preserve syntax diagnostics and usable symbols elsewhere.

## Core LSP contracts

All entries below are proposed enhancements to existing standard providers.

| Capability         | Behavior                                                                                                 | Acceptance criteria                                                                                                                                         |
| ------------------ | -------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Completion/resolve | Valid labels, scoped APIs, feature skeletons, data names; resolve supplies documentation.                | `thrown`/`notThrown` in valid exception-condition contexts, `old` in then; no suggestions in literals/comments; typed prefixes and `and:` inheritance work. |
| Data completion    | Declared names in feature logic; undeclared parameters as header candidates.                             | No duplicates or cross-feature leakage; omitted parameters remain supported.                                                                                |
| Definition         | `#root` to its data declaration; mock calls through existing method resolution after receiver inference. | Headers, pipes, destructuring, derived assignments, and root navigation through member chains. Built-ins get no invented source location.                   |
| Hover              | Fixture frequency, shared-state restrictions, block purpose, data origin/type.                           | Only actual specs receive lifecycle documentation; no invented runtime values.                                                                              |
| Semantic tokens    | Distinguish Spock labels from ordinary labels.                                                           | Prefer standard token type/modifier; advertise custom legend entries and fallback explicitly. Preserve token delta behavior.                                |
| Diagnostics        | Invalid/duplicate declarations, row arity, unknown unroll roots, proven block-order errors.              | Stable codes, exact ranges, related locations, configurable severity; no unjustified errors for dynamic/coercible values.                                   |

Resolve the effective unroll pattern first. Never evaluate user properties or methods for validation. A valid root with
dynamic members remains uncertain unless type evidence proves an error. Model headers as declarations rather than
arbitrary Groovy operator operands.

## Code actions and custom protocol

Prefer standard `textDocument/codeAction` and versioned `WorkspaceEdit` for source changes. Proposed kinds:
`source.groovy.spock.alignTable`, `refactor.rewrite.groovy.spock.pipeToTable`, and `refactor.extract`.

### Tables

Alignment operates on the selected/current table. Preserve expression tokens, separator choices, comments, indentation,
and newline convention. Disable malformed or ambiguous cases with an explanation. Applying alignment twice produces no
additional change.

Convert `<<` only when finite literal providers establish equivalent order and count. Preserve coercion and
destructuring. Do not evaluate providers to discover values. Decline unequal lengths, Cartesian products, lazy
providers, and dependent expressions until dedicated support exists. A single-variable table needs a valid ignored
column.

### Capability negotiation and model inspection

Proposed extension inside `InitializeResult.capabilities`:

```json
{
  "experimental": {
    "groovySpock": {
      "protocolVersion": 1,
      "dataModel": true,
      "iterationExecution": false,
      "assertionPreview": false
    }
  }
}
```

Advertise each flag only after its server path and editor consumer pass integration tests. Ordinary LSP clients must
work without the extension.

Proposed read-only `groovy/spock/dataModel` request:

```json
{
  "textDocument": { "uri": "file:///workspace/ExampleSpec.groovy", "version": 12 },
  "position": { "line": 8, "character": 6 }
}
```

Result fields: `version`, `featureId`, source `range`, `variables`, `tables`, `unrollReferences`, and `partial`. All
ranges use LSP positions. Return `ContentModified` for a stale version, `InvalidParams` for malformed input, and null
outside a feature. Return source expressions, not evaluated data. Bound response sizes and honor cancellation.

### Test execution

Extend existing `groovy/runTest` command generation and editor execution rather than introducing another runner.
Preserve feature selection. Proposed iteration targeting adds an opaque engine identity:

```json
{
  "uri": "file:///workspace/ExampleSpec.groovy",
  "suite": "example.ExampleSpec",
  "test": "maximum of #a and #b",
  "iteration": { "engineId": "opaque-id-from-a-prior-run", "runId": "opaque-run-id" }
}
```

This example is an additive proposal, not the current request schema. Verify existing field names before implementation.
Validate workspace, build tool, prior run identity, and engine support. Display names and row numbers are insufficient
because filters/products/providers can change identity. Unsupported iteration selection must explain the limitation
rather than silently run the whole feature.

Use process argument arrays, never shell interpolation of feature names. Preserve workspace checks, cancellation, debug,
and coverage controls. Analysis and code actions never execute tests implicitly.

### Assertion preview

Proposed `groovy/spock/assertionResult` notification includes run ID, URI/version, feature/iteration identity, source
range, bounded plain-text rendering, and optional structured evaluated nodes. Associate results with exact saved content
from the run. Drop or visibly mark stale results; clear decorations when content changes or a run starts.

Reuse existing test-result reporting first. Treat stack traces and assertion text as untrusted input: bound size/depth,
reject invalid coordinates, and avoid interpreting markup or terminal escapes. Preserve original failure text if
decoding fails. Never re-evaluate expressions in the server. Virtual text needs an editor extension; standard clients
can receive diagnostics and related information.

### Extraction and snippets

Helper extraction requires data-flow analysis for inputs, assignments, captures, and returns. Preserve condition
instrumentation, interaction relocation, fixture state, and data bindings. Disable extraction across block boundaries or
interactions until equivalence is demonstrated. Standard completion snippets can ship earlier, with plain-text fallback.

## Delivery gates

| Stage | Deliverable                               | Required evidence                                                                                                                                   |
| ----- | ----------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1     | Reproduction inventory and source model   | Tests for tables, pipes, derived variables, unroll roots, multiple features/classes, CRLF, Unicode, comments, malformed edits, and partial results. |
| 2     | Block fixes and symbol resolution         | Failing regressions first; parser-backed completion/definition tests; no cross-feature/version leakage.                                             |
| 3     | Table action and unroll mapping           | Versioned edits, expression/trivia preservation, idempotence, annotation precedence, ordinary Groovy negative cases.                                |
| 4     | Hover, semantic tokens, diagnostics       | Protocol tests for legend, codes/ranges, legal interaction placement, and uncertain types.                                                          |
| 5     | Iteration execution and assertion preview | Real Spock fixtures, stable engine targeting, cancellation, stale-result rejection, malformed failure output.                                       |
| 6     | Extraction                                | Runtime equivalence for supported forms; explicit disabled cases.                                                                                   |

Test edit sequences with dangling `when:`/`then:`, empty `where:`, missing cells, unfinished calls, and adjacent valid
features. Assert diagnostics and surviving completion/definition after each edit. Source-model unit tests alone do not
prove integration.

Run relevant module tests, touched protocol paths, and repository formatting/lint checks. Verify intended Runmux
execution and record missing platform coverage. Each implementation PR requires independent review of the final head and
affected call paths. Do not claim a feature shipped until server/editor acceptance gates pass.

## Unresolved relevant debt

### Validation of the initial implementation

The initial slice has parser-backed declaration tests, bounded synthetic-AST tests, completion and definition tests, and
table-action/provider tests. Default-compilation tests exercise both location and location-link navigation, body
references, versioned table edits, stale-buffer rejection and action-kind filtering. Additional cases cover annotated
methods, real Spock classpath field shadowing, compact features on one line, literal pipes, CRLF, table idempotence and
combined providers.

Repository-wide regular `lint` and `verifyKotlinFormattingTargets` passed. The root `lintFix` attempt stopped on
existing diagnostics in unchanged `common` and `build-tool` sources; its unbaselined auto-correction task also reports
existing server findings. New findings were fixed, and regular module lint passes. This does not establish runtime
column typing, malformed-source recovery, editor rendering, isolated iteration execution or assertion decoding.
Publication must separately verify CI runner coverage; Runmux migration is tracked in
[PR #1165](https://github.com/albertocavalcante/gvy/pull/1165).

### Remaining component work

Ownership below identifies component scope, not an assigned individual. Documentation does not resolve these items.

| Item                  | Impact and reason open                                                                                                                                    | Responsible scope        | Next action and acceptance                                                                                            |
| --------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------ | --------------------------------------------------------------------------------------------------------------------- |
| S1–S2 remaining       | Transition fixes, filter/combined vocabulary and outer-label recognition are included; individual label ranges and blank-line context remain incomplete.  | `ext/spock`              | Offset-based label/body spans with parser-backed blank-line and consecutive-label location tests.                     |
| S3–S4                 | Heuristic providers cause false positives and missing scoped help; shared model pending.                                                                  | Completion/diagnostics   | Consume source context; prove long-method, lexical, and adjacent-method cases.                                        |
| S5 remaining          | Declaration/root mapping is included; semicolon tables, class-level unroll patterns, complete diagnostic confidence and column typing remain unsupported. | Spock/semantic providers | Extend the model and prove coercion, mixed types, configuration and unsupported syntax before publishing diagnostics. |
| S6–S7                 | Phase fallback does not establish malformed-source recovery.                                                                                              | Parser/compilation       | Edit-sequence tests; explicit partial behavior; no stale locations.                                                   |
| S8                    | Iteration display currently reruns the enclosing feature.                                                                                                 | Testing server/editor    | Engine identity and support detection; prove exactly one iteration executes.                                          |
| S9                    | Lifecycle help and Spock label tokens absent.                                                                                                             | Hover/semantic tokens    | Standard provider adapters with non-Spock negative cases.                                                             |
| Assertions/extraction | Need runtime/source correspondence and equivalence proof.                                                                                                 | Testing/refactoring      | Bounded failure decoder and proven extraction forms in later stages.                                                  |
| Historical status     | Earlier designs describe older repository behavior.                                                                                                       | Documentation            | Reconcile after each stage lands and link validation evidence.                                                        |
