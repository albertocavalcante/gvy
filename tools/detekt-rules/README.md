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
`Pattern.compile(p, ...)` when `p` is a constant and the call sits inside code that runs repeatedly. Fix it by moving
the regex to a file-scope, `object` or `companion object` property (or `by lazy`), or by using a plain string search.

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

### Enabling a module

The rule is on for every module except those listed under `gvy > RegexInFunctionBody > excludes` in
`tools/lint/detekt.yml`. Those modules still contain unhoisted regexes. In the pull request that hoists a module, remove
its `excludes` line so the rule guards it. Never add an `excludes` line or a detekt baseline entry for a module that is
clean (#2420).

## Running the tests

```sh
./gradlew :tools:detekt-rules:test
```

Fixtures are in `src/test/resources/regex-in-function-body/`. Each `// FLAG` line in `violations.kt.fixture` must be
reported and `compliant.kt.fixture` must stay silent; add a case to the fixture before changing the rule.

## No Bazel target

This module is Gradle-only. detekt runs through Gradle, and nothing in the Bazel build consumes the rules. A Bazel
target would need the detekt API artifacts pinned in `maven_install.json` for no consumer.

## `DirectCompilationCacheAccess` (integration in progress)

This PSI-only change detector reports direct compilation cache reads, known compiler acquisition calls and named legacy
entry calls in GLS packages. The ordinary `lint` task loads the rule without type resolution (#2409). The exception
ledger in `CacheAccessExceptions.kt` identifies the exact package, file, containing class and function parameter types,
call text and allowed count, with a rationale, owner and removal gate. Adding or changing an exception requires review;
never use a file-wide exemption. The six initial compiler compatibility forwarders are not permission to add wire uses.

The rule recognizes typed compiler receivers, simple property aliases of known receiver names, imported call aliases,
safe calls and callable references. It ignores `getAst` on a uniquely declared `RuleContext` receiver and
`getDiagnostics` on a uniquely declared `DiagnosticsService` receiver; ambiguous duplicate names are reported. It is not
a call graph or type resolver: unrelated same-named calls can be reported, and shadowing, unknown receiver names,
aliases through arbitrary expressions, extensions, wrappers, reflection and function values can escape detection. Legacy
calls with ambiguous overloads are reported for explicit narrow review, not type-guessed. The final composed GLS source
must be inventoried and legitimate compatibility calls classified in the ledger before this rule can pass ordinary lint;
no baseline or suppression is authorized for remaining wire paths. Hosted rule tests, ordinary lint and real
direct-getter and legacy-acquisition mutation checks remain required before this guard is ready.
