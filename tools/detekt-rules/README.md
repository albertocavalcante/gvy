<!--
SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
SPDX-License-Identifier: Apache-2.0
-->

# detekt-rules

gvy's own detekt rules, in the `gvy` rule set. Every module's `detektPlugins` includes this module, so the rules run in
the ordinary `detekt` task: in `lint`, `check` and the pull-request lint gate. That gate runs detekt **without type
resolution** (#2409), so every rule here must be purely syntactic (PSI only). A rule that needs types would pass the
gate without ever firing.

## `RegexInFunctionBody`

Compiling a regex costs far more than matching with it, so a constant regex must not be rebuilt on every call. The rule
reports `Regex(p)`, `Regex.fromLiteral(p)`, `p.toRegex(...)`, `p?.toRegex(...)`, `p.toPattern()` and
`Pattern.compile(p, ...)` when `p` is a constant and the call sits inside code that runs repeatedly. `Regex` and
`Pattern` may be qualified (`kotlin.text.Regex`, `java.util.regex.Pattern`). The qualifier is read by PSI segment, so
backticks, comments and line breaks inside it do not hide the call. Fix it by moving the regex to a file-scope, `object`
or `companion object` property (or `by lazy`), or by using a plain string search.

| Reported                                                                            | Not reported                                                                                                                                    |
| ----------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| Inside a named, local or anonymous function                                         | File-scope, `object` and `companion object` property initialisers                                                                               |
| Inside a property getter or setter                                                  | `by lazy { ... }` at those scopes                                                                                                               |
| Inside a lambda, including a stored one (`val f = { Regex("a") }`) or `map { ... }` | A direct lambda argument of `lazy`, `run`, `let`, `also`, `apply`, `with`, `buildList`, `buildMap`, `buildSet` or `buildString` at those scopes |
| Inside a secondary constructor or a class `init` block                              | An `object` or `companion object` `init` block (runs once)                                                                                      |
| `"..."?.toRegex()`                                                                  | A pattern from a runtime value: parameter, interpolated expression, call such as `Regex.escape(x)`, `var`, or a name from another file          |

Deliberately not covered:

- Class-level property initialisers (`class A { val r = Regex("a") }`) run once per instance and are not reported.
- Primary-constructor default arguments, enum entries and annotation arguments are not inspected.
- Option arguments (`RegexOption`, `Pattern` flags) are not inspected; only the pattern decides.
- A pattern is constant when it is a plain literal, a `+` concatenation of constants, `trimIndent()`/`trimMargin()` of a
  constant, or a name this file declares as a `const val` or a literal `val` at file or `object` scope (and no
  parameter, local, loop, catch or destructuring name of that spelling is in scope, and no other property of that name
  is declared in the file). Names declared in other files count as dynamic, because resolving them needs type
  resolution.

Use `@Suppress("RegexInFunctionBody")` only with a comment that says why hoisting is impossible.

### Known limits

The rule cannot resolve names, so it errs in these documented ways:

1. A constant name is ignored for the whole file when a non-hoisted property declaration shares its spelling (a class
   member, a `var`, a computed `val`, an object-literal member). This is silent: detection for that name is off in that
   file. Functions and classes with the same spelling are not considered.
2. Qualified references (`this.NAME`, `Outer.NAME`) and import aliases count as dynamic (false negatives).
3. Shadowing ignores declaration order: a local declared after the use still hides the constant (false negative).
4. The one-shot exemption is by callee name. A user function named `run`, `let` and so on that invokes its lambda
   repeatedly is missed, and a labelled lambda (`run label@{ ... }`) at file scope is reported.
5. A name that resolves to a member of a `with(x) { }` receiver, or to an inherited member declared in another file,
   cannot be seen and may be reported. Suppress that site locally with `@Suppress("RegexInFunctionBody")` and a reason.
6. The construction is recognised by name. An import alias of `Regex` or `Pattern` is missed: after
   `import java.util.regex.Pattern as P`, the rule does not see `P.compile("a")`. Another class named `Regex` or
   `Pattern` is taken for the standard one and reported; suppress that site with a reason.

### Enabling a module

The rule is on for every module except those listed under `gvy > RegexInFunctionBody > excludes` in
`tools/lint/detekt.yml`. Those modules still contain unhoisted regexes. In the pull request that hoists a module, remove
its `excludes` line so the rule guards it. Never add an `excludes` line or a detekt baseline entry for a module that is
clean (#2420).

## `VolatileProperty`

`@Volatile` makes single reads and writes of a field visible to other threads, but not a compound update: `x++`,
check-then-act (`if (!closed) { closed = true; ... }`) and two related fields read one after the other all still race,
with no compiler warning. The rule reports `@Volatile` annotations in the code the lint task reads. Replace the field
with the type that says what it is for (see [Shared mutable state](../../docs/kotlin-style.md#shared-mutable-state)):

| The field holds                                  | Use                                                                         |
| ------------------------------------------------ | --------------------------------------------------------------------------- |
| A one-way transition: closed, shut down, started | `OnceFlag` from `:common:concurrent`                                        |
| A value replaced as a whole                      | `SharedValue<T>`, with `update {}` for a value derived from the current one |
| A count                                          | `Counter`                                                                   |
| A value computed once                            | `lazy`                                                                      |
| Fields that change together                      | One immutable value in a `SharedValue`, or every access under one lock      |

The rule recognises `@Volatile`, `@field:Volatile`, `@[Volatile]`, `@kotlin.jvm.Volatile`, `@kotlin.concurrent.Volatile`
and import aliases of the last two. Names are read by PSI segment, so backticks, comments and line breaks inside them
(`` @`Volatile` ``, `@kotlin.jvm./* c */Volatile`) do not hide the annotation. Mentions in comments, KDoc and strings
are not annotations. When a file imports a different annotation named `Volatile`, the unqualified name refers to that
one and is not reported (see limit 2 for other imports of that name).

It also reports a typealias of the annotation (`typealias V = kotlin.jvm.Volatile`), at the typealias. Its `@V` uses
look like any other annotation, so removing the typealias is the fix.

A field that an `AtomicIntegerFieldUpdater`, `AtomicLongFieldUpdater` or `AtomicReferenceFieldUpdater` updates must be
declared volatile: the updater rejects any other field. That, or a hot path where a measured benchmark shows the extra
object matters, is the only accepted reason for `@Suppress("VolatileProperty")`, with a comment that says which applies.
A `VarHandle` needs no volatile field; its access mode supplies the ordering.

### Known limits

The rule cannot resolve names, so it errs in these documented ways:

1. A declaration named `Volatile` in the same file or package (`annotation class Volatile`) is not told apart from
   Kotlin's: its unqualified use is reported. Suppress that site with a reason, or rename the declaration.
2. An import of any other symbol named `Volatile`, even a function, makes the unqualified name count as that symbol, so
   a Kotlin `@Volatile` in that file is missed. Qualified uses are still reported. A star import errs the other way.
   `import com.example.annotations.*` outranks Kotlin's default `kotlin.jvm.*` import, so when that package declares a
   `Volatile`, the plain name means that one. The rule cannot see what a star-imported package declares, so it still
   reports `@Volatile` and `typealias X = Volatile` in that file (false positives). Import the other annotation by name,
   which the rule follows, or suppress the site with a reason.
3. A typealias of the annotation is reported where it is declared, never at its `@V` uses. A typealias declared where
   the lint task does not look (limit 4) or in a library is missed. gvy has none.
4. The lint task reads only the `kotlin` and `java` folders of `src/main` and `src/test` unless a module sets
   `detekt.source` (as `launcher` and `common/concurrent` do). Other Kotlin, such as `tests/e2e` and the Kotlin
   Multiplatform sources of `viz/ast-model`, is not seen by any detekt rule yet (#2480).

### Coverage

The rule guards every module the lint task reads: the migration in #2474 removed the last `excludes` line, and no
baseline entry exists. `tools/ci/detekt-gvy-excludes.test.mjs` keeps it so: the rule and the `gvy` rule set must stay
active, neither may exclude anything, and no `detekt-baseline.xml` may hold its findings. A sanctioned `@Volatile` is
suppressed at its site, with a reason.

## `JdkAtomics`

Shared state goes through gvy's own types in `:common:concurrent` (`OnceFlag`, `SharedValue`, `Counter`), which keep the
JVM's atomics in one module (see [Shared mutable state](../../docs/kotlin-style.md#shared-mutable-state)). The rule
reports every import of `java.util.concurrent.atomic` and every qualified use of a type in it, such as
`java.util.concurrent.atomic.AtomicLong()`. Names are read by PSI segment, so backticks, comments and line breaks inside
a qualified name do not hide it. Mentions in comments and strings, and a longer package such as
`java.util.concurrent.atomicity`, are not uses.

| The JDK type was used as                                        | Use                                                                           |
| --------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| `AtomicBoolean` claimed with `compareAndSet(false, true)`       | `OnceFlag.trySet()`                                                           |
| `AtomicBoolean` set unconditionally, never cleared              | `OnceFlag.set()`                                                              |
| `AtomicBoolean` set and cleared                                 | `SharedValue<Boolean>`                                                        |
| `AtomicReference`                                               | `SharedValue<T>`: `exchange` for `getAndSet`, `update {}` for a derived value |
| `AtomicReference.compareAndSet(null, value)` (first value wins) | `update { it ?: value }`                                                      |
| `AtomicInteger`, `AtomicLong` counters                          | `Counter` (convert with `toInt()` where an `Int` is needed)                   |
| Any of these, used by one thread only                           | A plain `var`; an atomic there claims the state is shared                     |

Permanent excludes in `tools/lint/detekt.yml`: test sources, `common/concurrent` itself, and the modules published on
their own (`bsp/*`, `parser/core`), which must not depend on the unpublished `:common:concurrent`. No other module is
excluded (#2474). `tools/ci/detekt-gvy-excludes.test.mjs` requires exactly these excludes and no baseline entry.

`Counter.increment()` returns the new count, like `incrementAndGet()`. Code that used `getAndIncrement()` needs the
previous value, `increment() - 1`.

A field updater that needs the JDK type is the accepted reason for `@Suppress("JdkAtomics")`, with a comment. Write the
qualified name at the suppressed declaration: an imported name is reported at its import, which only a file-wide
`@file:Suppress` can silence.

### Known limits

The rule reads syntax only, so it does not see:

1. A JDK atomic behind a type alias declared where the rule does not look (tests, excluded or unlinted code), or a type
   inferred from another API (`val counter = api.counter()`).
2. Java sources, and Kotlin outside the source roots the lint task reads (#2480).

## `StdlibAtomics`

The stdlib's `kotlin.concurrent.atomics` is experimental (`@ExperimentalAtomicApi`,
[KT-82228](https://youtrack.jetbrains.com/issue/KT-82228)). `:common:concurrent` is the only module that opts in, behind
`OnceFlag`, `SharedValue` and `Counter`, so an upstream change breaks that module alone (#2474). The rule reports every
import of the package, star imports included, and every qualified use, such as `kotlin.concurrent.atomics.AtomicInt(0)`
or `@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)`. It shares its detection with `JdkAtomics`:
backticks, comments and line breaks inside a name do not hide it, and mentions in comments, strings and KDoc are not
uses.

| The stdlib API was used as                                                           | Use                                                                                           |
| ------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------- |
| `AtomicBoolean` claimed with `compareAndSet(false, true)`                            | `OnceFlag.trySet()`                                                                           |
| `AtomicBoolean` set with `store(true)`, never cleared                                | `OnceFlag.set()`                                                                              |
| `AtomicBoolean.load()` of such a one-way flag                                        | `OnceFlag.isSet`                                                                              |
| `AtomicBoolean` stored and cleared                                                   | `SharedValue<Boolean>`, read with `value`                                                     |
| `AtomicReference<T>`: `load()`, `store(v)`, `exchange(v)`                            | `SharedValue<T>`: `value`, `set(v)`, `exchange(v)`                                            |
| `AtomicReference.update {}`, `updateAndFetch {}`                                     | `SharedValue.update {}`, which returns the new value; keep the transform pure                 |
| `AtomicReference.fetchAndUpdate {}`, which returns the old value                     | `update {}` cannot return it: `exchange(v)` if the new value ignores the old one, else a lock |
| `AtomicReference.compareAndSet(null, value)` (first value wins)                      | `update { it ?: value }`                                                                      |
| `AtomicInt`, `AtomicLong` counters: `incrementAndFetch()`                            | `Counter.increment()` (convert with `toInt()` where an `Int` is needed)                       |
| `fetchAndIncrement()`                                                                | `Counter.increment() - 1`                                                                     |
| A counter's `load()`                                                                 | `Counter.value`, a `Long`                                                                     |
| `addAndFetch`, `fetchAndAdd`, `decrementAndFetch`, `compareAndExchange`              | No equivalent: use a lock, or extend the facade in `:common:concurrent`                       |
| `@OptIn(ExperimentalAtomicApi::class)`, or `@ExperimentalAtomicApi` on a declaration | Nothing: remove it with the uses it covered                                                   |
| Any of these, used by one thread only                                                | A plain `var`; an atomic there claims the state is shared                                     |

The compiler's opt-in requirement already stops accidental use. The rule is the second fence, and it also reports a
module that adds the opt-in on purpose. It replaces a detekt `ForbiddenImport` pattern, which matched import paths only
and so missed `import kotlin.concurrent.atomics.*` and fully qualified uses.

Its only exclude in `tools/lint/detekt.yml` is `common/concurrent` itself. Test sources are not excluded, because no
test outside the facade needs the package. `tools/ci/detekt-gvy-excludes.test.mjs` requires exactly this exclude and no
baseline entry.

### Known limits

The rule reads syntax only, so it does not see:

1. A stdlib atomic behind a typealias declared where the rule does not look (`common/concurrent` or unlinted Kotlin), or
   a type inferred from another API (`val counter = holder.counter`).
2. Java sources, and Kotlin outside the source roots the lint task reads, such as `tests/e2e` and the multiplatform
   sources of `viz/ast-model` (#2480).

## Conventions

The rules share these conventions, documented on `GvyRuleSetProvider`. `GvyRuleSetProviderTest` checks each rule's
severity, debt and description prefix, and the message shape of a few representative findings per rule. Review and each
rule's spelling fixtures keep the last convention.

- A finding's message names the reported code in backticks and says what is wrong with it, then gives the fix and the
  issue that set the policy.
- The issue description says what the rule reports, starting with "Reports". It does not repeat the message.
- The severity is detekt's category for the harm: `Defect` for a likely race (`VolatileProperty`), `Maintainability` for
  an API kept to one module (`JdkAtomics`, `StdlibAtomics`), `Performance` for repeated work (`RegexInFunctionBody`).
- The debt is the usual time to fix one finding: 20 minutes for a volatile field, whose every access needs checking, 10
  for an atomic and 5 for a regex.
- Dotted names are read by PSI segment through `PsiNames.kt`, never compared as source text.

## Running the tests

```sh
./gradlew :tools:detekt-rules:test
```

Each rule has fixtures in `src/test/resources/<rule-name>/` (`regex-in-function-body/`, `volatile-property/`,
`jdk-atomics/`, `stdlib-atomics/`). Each `// FLAG` line in `violations.kt.fixture` must be reported and
`compliant.kt.fixture` must stay silent; add a case to the fixture before changing the rule. `RuleFixtures.kt` holds the
shared helpers that read and check them.

`GvyRuleSetProviderTest` covers every rule at once. The provider registers it, the plugin's default config activates it,
and `inactive-config.yml` switches it off. Its severity and debt match the conventions above, its description starts
with "Reports", and the messages of its representative findings have the shared shape. A new rule adds a row to that
test's table and a line in `src/test/resources/inactive-config.yml`.

## No Bazel target

This module is Gradle-only. detekt runs through Gradle, and nothing in the Bazel build consumes the rules. A Bazel
target would need the detekt API artifacts pinned in `maven_install.json` for no consumer.
