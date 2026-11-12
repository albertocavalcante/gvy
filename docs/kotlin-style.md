# Kotlin style and readability

Write Kotlin that a reviewer can read from top to bottom without decoding dense expressions or tracking changing
receivers. Correctness comes first; choose the clearest idiomatic form, not the fewest lines.

Use this guide for new and changed Kotlin, including tests and build scripts. Follow the
[Kotlin conventions](https://kotlinlang.org/docs/coding-conventions.html) for general language style. The choices below
are gvy's readability rules. Keep changes focused; do not reformat unrelated code.

## Formatting and review

The checked-in [EditorConfig](../.editorconfig) and [Spotless configuration](../build.gradle.kts) define mechanical
formatting: four spaces, a 120-column limit, explicit imports, and pinned ktlint. The column limit is a ceiling, not a
target. Break an expression earlier when that exposes its structure.

Run `./gradlew lintFix`, inspect its diff, and run the relevant lint and tests as described in
[Contributing](../CONTRIBUTING.md#validate-your-changes). On shared machines, use `build-slot --` for Gradle. Use dprint
for Markdown. Formatters check layout; reviewers must still check names, control flow, and meaning. Do not disable a
formatter rule or add a suppression to preserve a preferred layout; simplify the expression instead.

## Conditions that scan vertically

Keep a short, simple condition on one line. Once a Boolean chain needs wrapping, put each peer operand on its own line
with the operator at the end. Do not pack several operands onto one line and leave the rest on another. For an
assignment, begin the wrapped chain below `=`:

```kotlin
val canReuse =
    cachedDocument != null &&
        cachedDocument.version == requestedVersion &&
        cachedDocument.uri == requestedUri
```

Keep grouping explicit when mixing `&&` and `||`. If the reader must mentally parse several groups, name the domain
facts first, or use guards or `when`. A name such as `supportsNamedChecks` explains a decision better than `supported`,
`valid`, or `result`. Do not introduce a helper for every small expression; keep a local decision near its use.

For a list of alternatives on the same subject, prefer `when` over a long series of `is` tests. For example, avoid this
layout:

```kotlin
val supported = step is ScenarioStep.Assert || step is ScenarioStep.Completion ||
    step is ScenarioStep.CodeAction || step is ScenarioStep.Formatting || step is ScenarioStep.Rename
```

Make the classification and its use explicit:

```kotlin
val supportsNamedChecks = when (step) {
    is ScenarioStep.Assert,
    is ScenarioStep.Completion,
    is ScenarioStep.CodeAction,
    is ScenarioStep.Formatting,
    is ScenarioStep.Rename,
    -> true
    else -> false
}
val hasNamedMetadata = check.id != null || check.xfail != null
require(supportsNamedChecks || !hasNamedMetadata) {
    "Named checks are not supported in ${step::class.simpleName} steps"
}
```

Use one alternative per line in a multiline `when` branch. A two-term type check can remain a simple expression; do not
expand every small condition into a `when`.

## Calls, blocks, and names

- Wrap long calls with one argument per line. Use named arguments when adjacent values, especially Booleans and numbers,
  would otherwise require looking up the signature. Keep short, obvious calls compact.
- Expand a validation with a nested message lambda into a block when the one-line version hides the check. Separate
  logical phases with a blank line; do not separate every statement.
- Use a block body when branching, cleanup, or failure handling needs several statements. Keep expression bodies for
  straightforward computations.
- Name predicates for the question they answer (`hasNamedMetadata`, `supportsNamedChecks`). Name helpers for domain
  operations, not generic steps such as `processData`.
- Explain a non-obvious invariant or tradeoff in a comment. Avoid comments that merely narrate the next statement.

For example, keep the nullable value and validation visible:

```kotlin
check.id?.let { id ->
    require(id.isNotBlank()) { "A check ID must not be blank" }
}
```

## Idioms serve the reader

Use null safety, immutable values, data classes, and sealed hierarchies where they model the behavior accurately.
Idiomatic Kotlin also includes ordinary `if`, `for`, local variables, and explicit initialization.

- Use `?.` and `?:` when absence has the intended meaning. Use `requireNotNull` for an invalid argument and
  `checkNotNull` for an invalid state. Do not turn an error into `null`, an empty result, or an early success to shorten
  the code. Avoid `!!`; model or validate the invariant instead.
- Use collection operations for clear transformations and queries. Use a loop for a sequence of effects or when
  branching, early exit, or accumulation is clearer. Do not introduce `fold`, sequences, or chained transformations
  merely to avoid a loop.
- Use a scope function for one clear purpose, such as `apply` for object configuration or `let` for a nullable value.
  Follow the [Kotlin scope-function guidance](https://kotlinlang.org/docs/scope-functions.html): avoid nesting scope
  functions and confusing receiver changes. Use a named lambda parameter when the block is long or nested, or when `it`
  could refer to more than one value. Split a chain into named locals when that makes the stages easier to follow.
- Choose eager versus lazy initialization for lifecycle, cost, and concurrency semantics. `lazy`, `apply`, and `also`
  are not universal improvements over an explicit declaration or initialization block.

## Preserve behavior while improving style

Keep evaluation order, short-circuiting, null behavior, exception types, validation order, lazy error messages, and
resource cleanup intact. Extracting an operand into a local evaluates it eagerly: do this only when safe. Keep
side-effecting or failure-prone work behind the same guards. A style change must not broaden catch blocks or hide a
failure.

For a behavior-preserving refactor, run the existing tests covering the decision and its failure paths. Add regression
coverage when behavior changes or a coverage gap is found; do not add tests that only mirror line breaks.

Before requesting review, check:

- Can the reader see each alternative, logical group, and failure path at a glance?
- Do names explain the domain decision, and are receivers and nullable values unambiguous?
- Would a local, guard, loop, or `when` make a dense expression easier to follow?
- Does the refactor preserve behavior, and do the relevant tests and lint pass?
