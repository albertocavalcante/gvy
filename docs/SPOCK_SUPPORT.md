# Spock First-Class Support (Design + Landing Plan)

This document describes how to add **first-class Spock support** to this Groovy LSP: not just “Groovy can parse Spock
code”, but editor features that understand Spock’s DSL, blocks, and data-driven tests (`where:` tables).

It is written as an implementation plan you can land incrementally (small PRs with clear acceptance criteria).

## Background: what “Spock support” means in an LSP

Spock is a Groovy testing framework built around:

- `spock.lang.Specification` as the base class for tests (“specs”).
- **Block labels** like `given:`, `when:`, `then:`, `expect:`, `where:`, `cleanup:`, etc.
- **Data-driven tests** with `where:` blocks (data tables, data pipes, derived variables).
- **Mocking DSL** (e.g. `Mock()`, `Stub()`, interactions like `1 * service.call(_) >> value`).
- Annotations and metadata like `@Unroll`, `@Shared`, `@Stepwise`, `@Subject`, `@Timeout`, …

For an LSP, “first-class” means the server should help you _author_ these constructs:

- Completions/snippets for block labels and common Spock idioms.
- Correct symbol visibility across blocks, especially `where:` → feature method body.
- Type inference for common Spock DSL calls (`Mock(Foo)`, `thrown(Foo)`, …).
- Diagnostics that are Spock-aware (block order, invalid `where:` constructs, unknown data variables, …).
- Reasonable navigation/hover for Spock APIs and generated semantics.

## Current state in this repo (relevant to Spock)

There is already partial Spock awareness:

- Dependency versions include Spock (`org.spockframework:spock-core`).
- `groovy-diagnostics:codenarc` detects “Spock in Gradle” and can select different rulesets.
- `groovy-gdsl` ships a tiny `spock.gdsl` contributor, but the LSP currently only loads GDSL metadata for Jenkins.

So the shortest path is not “start from zero”; it’s “generalize + deepen”:

- Reuse the existing “workspace context” patterns (Jenkins manager, project type detection).
- Expand GDSL contributions where they help.
- Add Spock-specific completion + type inference + `where:` modeling where GDSL is insufficient.

## Goals and non-goals

### Goals (what we ship)

1. **Spock-aware completions**:
   - Block label snippets (`given:`, `when:`, `then:`, `expect:`, `where:`, …).
   - Snippets for common skeletons (feature method, `where:` table, data pipes).
   - Completions for key Spock APIs (`thrown`, `noExceptionThrown`, `with`, `verifyAll`, mock factories).
2. **Spock-aware type inference**:
   - `Mock(Foo)` / `Stub(Foo)` / `Spy(Foo)` → `Foo`.
   - `thrown(Foo)` → `Foo` (and best-effort for `thrown()`).
3. **Data-driven test support**:
   - Recognize and index `where:` data variables and derived variables.
   - Provide completion/navigation for `where:` variables used in `expect:` / `then:` blocks.
4. **Spock-specific diagnostics (initial slice)**:
   - Missing or duplicate `where:` blocks.
   - Use of unknown data variables in `@Unroll` patterns / GStrings.
   - Basic block-order validation (configurable, best-effort).
5. **Test coverage**:
   - Unit tests for analyzers and inference.
   - E2E harness scenarios validating completions/hover/diagnostics in a fixture workspace.

### Non-goals (initially)

- Running Spock tests from the LSP (that’s an editor feature beyond LSP core).
- Full semantic equivalence with the Spock compiler for every edge case on day one.
- Perfect alignment/formatting of `where:` data tables (we can add later).

## Architectural proposal

### Add a dedicated module: `groovy-spock`

Create a module whose sole responsibility is Spock-aware analysis and editor features. This keeps `groovy-lsp` from
accumulating framework-specific logic directly and mirrors the existing `groovy-jenkins` integration.

Suggested responsibilities for `groovy-spock`:

- Spock file classification (`isSpockSpec`, block detection helpers).
- Completion snippets and keyword sets for Spock blocks and DSL calls.
- Type calculators for Spock DSL calls (plugged into `GroovyTypeResolver`).
- `where:` block parsing/modeling utilities (data table + pipe + derived vars).
- Optional diagnostics rules (block order, unknown vars, invalid `where` syntax).
- Bundled GDSL contributors for Spock (expanded beyond the current tiny stub).

Suggested API surface:

- `SpockDetector` (pure, fast): `fun isLikelySpockSpec(uri, text, astModel?): Boolean`
- `SpockCompletionProvider` (pure): returns `CompletionItem`s and snippets given cursor context
- `SpockTypeCalculator : TypeCalculator` (plug-in)
- `WhereBlockModel` + `WhereBlockAnalyzer` (extract variables + ranges + columns)
- `SpockDiagnosticsProvider` (optional diagnostics beyond Groovy compiler errors)

### Integrate via “framework context” instead of hardcoding

Instead of “if file contains Jenkinsfile then X”, introduce a small framework classification layer that can return
multiple hints for a file:

- `FrameworkHint.Jenkins`
- `FrameworkHint.Spock`
- `FrameworkHint.GradleDsl`
- …

`GroovyCompilationService` / `WorkspaceManager` can surface the hints (or a cached `DocumentFrameworkContext`) to
completion/hover/diagnostics providers.

This is the key to making Spock support composable:

- A Gradle project that uses Spock should activate both Gradle + Spock features where appropriate.
- A multi-module workspace can have some Spock specs and some plain Groovy scripts.

## Implementation plan (incremental PRs)

This is designed to be landed in small, reviewable slices. Each item can be a PR with its own tests.

### Milestone 0 — Spec + fixtures (docs-first)

1. Add this doc (design + plan).
2. Add a fixture workspace containing a minimal Spock project:
   - `src/test/groovy/.../*Spec.groovy` with blocks, `where:` tables, mocks, and `@Unroll`.
   - `build.gradle(.kts)` with Spock dependency.
3. Add initial E2E scenario(s):
   - Open a spec, request completion at a `given:` location, verify block label suggestions exist.

Acceptance criteria:

- E2E runner can open a Spock fixture workspace and the server initializes without regressions.

### Milestone 1 — Spock detection + plumbing

1. Add `groovy-spock` module and wire it into `settings.gradle.kts` and `groovy-lsp` dependencies.
2. Add a fast `SpockDetector`:
   - File name heuristic: `*Spec.groovy`.
   - Content heuristic: `import spock.*`, `import spock.lang.*`, `extends spock.lang.Specification`.
   - AST heuristic (when available): superclass is `spock.lang.Specification` (planned).
3. Expose “framework hints” to providers:
   - Add `DocumentFrameworkContext` (cached by URI + content hash) (planned).
   - Make completion/hover/diagnostics providers able to ask “is Spock active here?”.

Acceptance criteria:

- Spock specs are detected with low overhead.
- Non-spec Groovy files do not pay extra cost.

Status (as of PR #200):

- Implemented: `groovy-spock` module + lightweight `SpockDetector`.
- Planned: AST-driven detection and `DocumentFrameworkContext` caching.

### Milestone 2 — Block label completions (high value, low risk)

Implement completion/snippets for Spock blocks (purely text-level; no AST transforms required):

- `given:`, `setup:`, `when:`, `then:`, `expect:`, `where:`, `cleanup:`, `and:`
- `thrown()`, `noExceptionThrown()`, `notThrown(Foo)`
- Snippets:
  - Feature method skeleton:
    - `def "..."() { given: ... when: ... then: ... }`
  - `where:` data table skeleton:
    - `where:\n  a | b || c\n  _ | _ || _`
  - `where:` data pipe skeleton:
    - `where:\n  a << [..]\n  b << [..]`

Acceptance criteria:

- Completion in a Spock spec suggests block labels and inserts `:` correctly.
- Snippets appear only in Spock specs (or behind a config flag).

Status (as of PR #200):

- Implemented: block label completions (gated to detected specs; suppressed inside comments/strings via best-effort
  heuristics).
- Planned: `thrown()` / `noExceptionThrown()` / `notThrown(Foo)` completions and the listed snippets.

### Milestone 3 — Load and expand bundled Spock GDSL

GDSL is useful for “methods/properties available on `Specification`” and for mock API helpers.

1. Expand `groovy-gdsl` bundled `spock.gdsl`:
   - Add key `Specification` methods/properties (as best-effort).
   - Add mock factory methods and common helpers used in specs.
2. Generalize GDSL loading beyond Jenkins:
   - Introduce a generic “bundled GDSL registry” and allow multiple frameworks to register GDSL files.
   - Load Spock GDSL when `FrameworkHint.Spock` is active.

Acceptance criteria:

- A spec using `with {}` / `verifyAll {}` / `thrown()` shows those methods as available in completions.
- Jenkins behavior remains unchanged.

### Milestone 4 — Spock-aware type inference (mock factories + thrown)

Add a `SpockTypeCalculator` registered into the `GroovyTypeCalculator` chain with higher priority than the default.

Core cases:

- `Mock(Foo)` / `Stub(Foo)` / `Spy(Foo)` / `GroovyMock(Foo)` / `GroovySpy(Foo)` → `Foo`
- `thrown(Foo)` / `notThrown(Foo)` → `Foo`

Acceptance criteria:

- Hover and completion can infer the right type from these calls.
- Unit tests cover representative patterns (positional args, named args, chained expressions).

### Milestone 5 — `where:` block modeling (data tables + data pipes)

This is the biggest “first-class” feature.

Deliver in layers:

1. Extract `where:` block range(s) in the source:
   - Identify the `where:` labeled statement(s) within a feature method.
2. Parse data variables:
   - Data pipes: `x << [1,2]`
   - Data tables: header row and subsequent rows (including `||` separator)
   - Derived vars: `c = a + b`
3. Expose `WhereBlockModel` to completion/type inference:
   - Variables defined in `where:` become in-scope for other blocks in the method.
   - Provide completion items for these variables at appropriate cursor locations.

Notes on parsing approach:

- Prefer AST-driven extraction for block boundaries and statement nodes.
- For the table syntax itself (`|` / `||`), a lightweight tokenizer over the `where:` block text is often more reliable
  than trying to infer it from transformed AST nodes.

Acceptance criteria:

- Completion inside `expect:` suggests `where:` data variables.
- Diagnostics report unknown identifiers that are actually `where:` vars less often (reduced false positives).
- Unit tests cover table and pipe examples.

### Milestone 6 — Spock diagnostics (initial, configurable)

Add a diagnostics provider for Spock-specific validations. Keep it configurable and off-by-default if it risks noise.

Initial rules (best-effort):

- `where:` appears at most once per feature method.
- `where:` must be the last block in the method (unless `cleanup:` is present; configurable).
- `@Unroll` strings reference only known data variables.
- Data table rows match header width (warn).

Acceptance criteria:

- Diagnostics are stable and do not spam non-Spock files.
- Rules can be toggled in configuration.

### Milestone 7 — Navigation and UX polish

- Go-to-definition for `where:` data variables from uses inside the feature method.
- Folding ranges for blocks (`given:` … `when:` …) and optionally for `where:` tables.
- Semantic tokens for block labels (if/when semantic tokens are implemented).

Acceptance criteria:

- Clicking a `where:` variable use can take you to its definition in the `where:` block.

## Compatibility and versioning

Target:

- Groovy 4.x
- Spock 2.x (JUnit Platform)

Considerations:

- Projects may use different Spock versions. Prefer using the workspace’s resolved Spock jars rather than bundling Spock
  into the server’s runtime.
- Some “Spock compiler internals” are not public API; avoid depending on them unless we can lock compatibility with a
  version range and test it.

## Testing strategy

### Unit tests

- `SpockDetectorTest` with representative file names and source snippets.
- `SpockTypeCalculatorTest` for mock factories + `thrown`.
- `WhereBlockAnalyzerTest` for:
  - tables with `|` and `||`
  - data pipes
  - derived vars
  - comments and blank lines

### E2E tests

Add a fixture workspace with a small Spock project and validate:

- completion results include block labels + `where:` vars
- hover shows inferred types for `Mock(Foo)` and `thrown(Foo)`
- diagnostics include (or do not include) expected Spock-specific messages

## Risk / complexity notes

The two biggest risks are:

1. **`where:` parsing correctness**: the table syntax is flexible and interacts with Groovy expressions. Expect an
   iterative approach with good tests and explicit trade-offs.
2. **AST transforms vs source mapping**: Spock relies on AST transformations; transformed nodes can be synthetic or have
   confusing source positions. Avoid building core editor UX on transformed node positions unless validated.

If we introduce any heuristic parsing (especially for `where:`), make the trade-off explicit in code and build a path to
a deterministic approach over time.

## “Done” definition (first-class MVP)

We can call Spock support “first-class MVP” when:

- Spock specs get block-label and common DSL completions.
- `Mock(Foo)` and `thrown(Foo)` type inference works in hover + completion.
- `where:` variables are recognized for completion and basic navigation.
- There is test coverage (unit + e2e) guarding these behaviors.
