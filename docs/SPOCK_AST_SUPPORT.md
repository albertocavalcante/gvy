# Spock Support: Moving From Regex to AST/Token-Based Detection

This document proposes a careful migration from lightweight regex heuristics to a robust, AST/token-driven Spock
integration.

It is intended to complement `docs/SPOCK_SUPPORT.md` by focusing specifically on:

- How to reliably detect “this is a Spock spec” without regex.
- How to determine “what Spock block is the cursor in” without fragile string heuristics.
- How to do this in a way that matches how Spock itself understands a spec.

## Background: how Spock recognizes specs and blocks

Spock is implemented as a Groovy compiler plugin.

- `org.spockframework.compiler.SpockTransform` is an `ASTTransformation` that runs at Groovy `SEMANTIC_ANALYSIS`.
  - It considers a class a spec if `ClassNode.isDerivedFrom(spock.lang.Specification)`.
  - It then parses the spec structure and rewrites/annotates the AST.
- `org.spockframework.compiler.SpecParser` identifies “feature methods” by scanning a method’s statements and checking
  whether any statement has a label: `Statement.getStatementLabel() != null`.
  - It then builds blocks by splitting statements on these labels (e.g., `given:`, `when:`, `then:`, `where:`).

Key takeaway:

- Spock blocks are primarily a _source-structure feature_ represented by Groovy statement labels, and Spock can rewrite
  the AST later in compilation.

## Problem statement

Today our Spock integration uses regex and source-text heuristics:

- `SpockDetector` uses regex checks like `import spock.` / `extends spock.lang.Specification`.
- Completion gating relies on “line start” and best-effort suppression heuristics for strings/comments.

This gets us a good first milestone, but it has gaps:

- False positives/negatives from text markers.
- Inability to answer “am I currently inside `then:`?” with confidence.
- Heuristic suppression for multiline comments/strings is not a real tokenizer; it will inevitably drift from Groovy’s
  lexical rules.

## Core constraint: “true Spock AST” is phase-dependent

If we ask the Groovy compiler for an AST after `SEMANTIC_ANALYSIS` (or later), Spock transformations may have already
rewritten the feature method bodies.

That implies:

- **Spec detection** should prefer semantic AST (because Spock itself uses semantic class hierarchy checks).
- **Block modeling** should prefer _pre-transform_ structure (because blocks are best represented before Spock rewrites
  the method).

The “right” solution is therefore _hybrid_:

1. Use AST/classpath to decide if a document is a Spock spec.
2. Use a pre-transform AST or a token stream to compute block and cursor context.

## Design goals

1. **Correctness-first**
   - Avoid regex and ad-hoc substring scans for core decisions.
2. **Incremental delivery**
   - Land foundations in small PRs with clear tests and low blast radius.
3. **Fast enough for interactive LSP**
   - Cache per `(URI, contentHash)`; avoid repeated expensive work.
4. **Graceful under edits**
   - Work with incomplete code and syntax errors where possible.
5. **Minimal coupling**
   - `groovy-spock` owns Spock-specific logic, but consumes generic parser/token services from `parser/native`.

## Proposed data model: document-level Spock context

Introduce a cached document framework context (name TBD; examples below):

```kotlin
data class SpockDocumentContext(
    val isSpockSpec: Boolean,
    val specClasses: List<SpecClassInfo>,
    val blockIndex: SpockBlockIndex?,
    val tokenIndex: GroovyTokenIndex?,
)

data class SpecClassInfo(
    val className: String,
    val classRange: SourceRange,
)

data class SourceRange(
    val startOffset: Int,
    val endOffset: Int,
)
```

`SpockBlockIndex` answers “what block am I in?” using source offsets:

```kotlin
enum class SpockBlockKind { SETUP, GIVEN, EXPECT, WHEN, THEN, CLEANUP, WHERE, FILTER, COMBINED, AND, UNKNOWN }

data class SpockBlockRange(
    val kind: SpockBlockKind,
    val range: SourceRange,
    val ownerMethodName: String?,
)

interface SpockBlockIndex {
    fun blockAtOffset(offset: Int): SpockBlockRange?
}
```

`GroovyTokenIndex` answers “is the cursor inside a comment/string?” deterministically from lexer tokens:

```kotlin
interface GroovyTokenIndex {
    fun isInComment(offset: Int): Boolean
    fun isInString(offset: Int): Boolean
    fun isInCommentOrString(offset: Int): Boolean = isInComment(offset) || isInString(offset)
}
```

## Spec detection (AST-first, classpath-aware)

### Detection tiers

We should replace regex markers with AST and import-aware checks.

Tier 1: semantic class hierarchy (most correct)

- If the compilation classpath can load `spock.lang.Specification`, then:
  - For each `ClassNode` in the module, check `clazz.isDerivedFrom(specificationClassNode)`.

Tier 2: import-aware, no class loading

- If classpath does not include Spock (or class loading fails), detect via AST structure:
  - If superclass is fully qualified `spock.lang.Specification`, it is a spec.
  - If superclass is `Specification` and imports include:
    - `import spock.lang.Specification`, or
    - `import spock.lang.*`, then it is a spec.

Tier 3: filename heuristic (cheap fallback)

- If path ends with `Spec.groovy` (case-insensitive), consider it a spec.

### Why not regex?

AST-based approaches avoid false matches in comments/strings and are resilient to whitespace formatting.

### Practical note: classpath availability in LSP

Our LSP already compiles with a file-specific classpath (via `WorkspaceManager.getClasspathForFile`), so Tier 1 becomes
viable for real projects where Spock is declared as a dependency.

## Block modeling: pre-transform “Spock-style” blocks

### Source of truth: statement labels

Spock treats blocks as sequences of statements partitioned by statement labels:

- `Statement.getStatementLabel()` is the primary signal (`given`, `when`, `then`, `where`, etc.).

Therefore, our `SpockBlockIndex` should be derived from statement labels, not from scanning text for `given:`.

### Critical choice: which compilation phase?

If we derive blocks from an AST compiled after Spock transforms ran, we risk observing a rewritten AST rather than the
original source structure.

We need “pre-transform” or “pre-Spock-transform” structure:

- Option A (recommended): instrument compilation with a customizer at `CONVERSION` and record labeled statements.
- Option B (incremental): compile an additional lightweight parse to `CONVERSION` only when Spock context is requested.

### Representing block ranges

We should compute source ranges for blocks based on statement positions:

- Convert statement `(line, column)` metadata into offsets using the document text.
- The block range spans from the first statement of the block to the last statement of the block.

Block inheritance rules (mirroring Spock behavior):

- `and:` continues the previous block’s kind (e.g., `then:` followed by `and:` is still `THEN` context).
- `setup:` is commonly used as an alias of `given:` in Spock usage; however, Spock distinguishes labels in some tooling.
  - We can choose either:
    - treat it as `SETUP` kind and map it to `GIVEN`-equivalent behaviors, or
    - normalize it to `GIVEN` kind.
  - This doc recommends: keep `SETUP` as a distinct kind, but treat it as a “setup-like” block for completions.

### Block vocabulary should include “non-obvious” Spock blocks

Spock supports additional observable blocks beyond the usual `given/when/then/where`:

- `combined:` (used in data-driven features)
- `filter:` (used for filtering iterations)

Even if our first features ignore these blocks, our block index should be able to represent them so that future work
doesn’t need another breaking migration.

## Token-based cursor context (replace heuristics)

Regex/substring-based detection for “inside comment/string” will be flaky for Groovy-specific literals.

Instead:

1. Generate a token stream using Groovy’s lexer (ANTLR runtime is already on our classpath via Groovy 4).
2. Build an index of token ranges that correspond to:
   - line comments
   - block comments
   - string literals (including multiline strings)
   - (future) slashy and dollar-slashy strings, if the lexer exposes them distinctly
3. Completion/hover providers can query `tokenIndex.isInCommentOrString(offset)` in O(log n) time (or O(1) with a
   bitmap/interval tree, if needed later).

## Caching strategy (for LSP performance)

All of this should be computed per document and cached:

- Key: `(URI, contentHash)` where `contentHash` is a stable hash of the document text.
- Store:
  - `SpockDocumentContext`
  - `SpockBlockIndex`
  - `GroovyTokenIndex`

In LSP terms:

- Compute context at `didOpen` / `didChange` after compilation completes.
- Providers only read from cache; they do not re-run compilation.

## Integration points in this repo

### Existing relevant components

- `GroovyParserFacade` compiles Groovy code to `CANONICALIZATION`.
- `GroovyCompilationService` caches parse results and exposes `astModel` and `symbolTable`.

### New/extended components (proposed)

1. `ParseRequest.compilePhase` (default stays current behavior)
2. `GroovyParserFacade.parse(... compilePhase = Phases.CONVERSION)`
3. `parser/native` token utilities (`GroovyTokenIndex`)
4. `groovy-spock` AST-first detection (`SpockDetector` that consumes AST/ParseResult)
5. `groovy-spock` block modeling (`SpockBlockIndexer`)
6. `groovy-lsp` framework context cache (document-level)

## Phased delivery plan (PR-by-PR), with TDD expectations

This section is intentionally specific so each PR is easy to review and revert if needed.

### PR 1 — Add compilation phase support to `parser/native` (foundation)

Goal: allow parsing at `CONVERSION` without changing existing behavior.

- Add `compilePhase` to `ParseRequest`:
  - default: `org.codehaus.groovy.control.Phases.CANONICALIZATION`
- Update `GroovyParserFacade` to compile to the requested phase.
- Tests:
  - Parse a labeled-statement sample at `CONVERSION` and assert the module/classes exist.
  - Parse the same at `CANONICALIZATION` and ensure unchanged semantics.
  - Ensure diagnostics still appear under syntax errors (no regressions).

Acceptance:

- All existing tests pass with default phase.
- New tests validate we can stop earlier at `CONVERSION`.

### PR 2 — AST-first `SpockDetector` (remove regex)

Goal: determine “is Spock spec?” from AST/imports/classpath.

- Add an overload that accepts AST or `ParseResult`:
  - `SpockDetector.isSpockSpec(uri, parseResult, content)` (naming TBD)
- Detection tiers (Tier 1 → Tier 3) implemented with tests for each tier.
- Keep the filename heuristic as last-resort fallback.

Acceptance:

- `groovy-spock` no longer needs regex-based scanning of content for detection.

### PR 3 — Pre-transform `SpockBlockIndex` from `CONVERSION` AST

Goal: compute block ranges and block-at-cursor answers that match Spock’s view.

- Implement `SpockBlockIndexer`:
  - Input: `ModuleNode` (from `CONVERSION` parse), plus document text for offset mapping.
  - Output: `SpockBlockIndex`
- Tests:
  - `and:` inherits previous kind.
  - `where:` recognized.
  - `combined:` and `filter:` recognized and mapped to explicit kinds.

Acceptance:

- LSP provider can query `blockAtOffset` reliably.

### PR 4 — Token index + deterministic suppression

Goal: remove heuristic comment/string suppression from completions.

- Add `GroovyTokenIndex` implementation in `parser/native`.
- Replace existing best-effort suppression checks in completions with token index queries.
- Tests for multiple comment/string forms (at least block comment, triple-quoted strings).

Acceptance:

- Completions never suggest Spock block labels inside comments/strings.

### PR 5 — Spock completions upgraded to use block context

Goal: use `SpockBlockIndex` to gate higher-level completions.

- In `THEN`/`EXPECT` blocks, suggest:
  - `thrown()`, `noExceptionThrown()`, `notThrown(Foo)`
- Add snippets (feature skeleton, `where:` table, `where:` pipe) only in meaningful contexts.
- TDD: tests first, then implement.

## Open questions (to decide before PR 3)

1. One compilation vs dual compilation:
   - Should we compute conversion-phase info via:
     - a compilation customizer (single compilation), or
     - a second lightweight parse to `CONVERSION` (simpler to implement, potentially more CPU)?
2. How to map Groovy AST line/column to offsets robustly:
   - Some AST nodes can have missing/invalid positions; we must define fallback rules.
3. Multi-class files:
   - Should “spec context” be per file, or per class?
   - For now: file-level context is fine; block index should attach blocks to a class/method where possible.

## Non-goals (for this migration)

- Running Spock transformations inside the LSP.
  - We do not want to execute user code or third-party transforms in the server beyond what Groovy compilation already
    does for parsing, and we want to avoid unpredictable side effects.
- Full semantic “Spock runtime model” (`SpecInfo`) in LSP.
  - We only need enough to provide editor features; the model can be lightweight and source-driven.
