# Lint Configs

This directory contains shared lint configuration files.

- `actionlint.yml`: Actionlint config for GitHub Actions workflows.
- `ghalint.yaml`: Ghalint config for GitHub Actions security policies.
- `detekt.yml`: Detekt rules for Kotlin sources, including gvy's own `gvy` rule set.
- `shellcheckrc`: ShellCheck settings for shell scripts.

## How to run

- actionlint:
  - `actionlint -color -config-file tools/lint/actionlint.yml`
- ghalint:
  - `ghalint -c tools/lint/ghalint.yaml run`
- detekt (via Gradle):
  - `./gradlew detekt`
- shellcheck:
  - `shellcheck --rcfile tools/lint/shellcheckrc path/to/script.sh`

## gvy detekt rules

`tools/detekt-rules` holds gvy's own detekt rules. Every module's `detektPlugins` includes it, so the rules run in the
same `detekt` task as the built-in ones and therefore in `lint` and the pull-request lint gate. The gate runs detekt
without type resolution (#2409), so every rule here must be purely syntactic (PSI only).

- `RegexInFunctionBody`: reports `Regex(...)`, `Regex.fromLiteral(...)`, `"...".toRegex()`, `"...".toPattern()` and
  `Pattern.compile(...)` with a constant pattern inside a function, accessor, secondary constructor, class `init` or
  lambda. A lambda passed directly to `lazy`, `run`, `let`, `also`, `apply`, `with`, `buildList`, `buildMap`, `buildSet`
  or `buildString` runs where that call runs, so only the enclosing scope counts for it: such a lambda at file, `object`
  or `companion object` scope is not reported. Hoist the regex into a file-scope, `object` or `companion object`
  property. Patterns built from a runtime value (parameter, interpolated expression, call, `var`) cannot be hoisted and
  are not reported. Modules that are not hoisted yet are listed under the rule's `excludes` in `detekt.yml`; remove a
  module's line, and its `regexMigration` entry in the guard, in the pull request that hoists it. Neither another
  exclude nor a baseline entry may be added (#2420).
- `VolatileProperty`: reports `@Volatile` annotations, including `@field:Volatile`, qualified names and import aliases,
  and a typealias of the annotation where it is declared. Use `OnceFlag`, `SharedValue` or `Counter` from
  `:common:concurrent`, `lazy`, or a lock instead. It guards every linted module; it has no excludes, and no exclude,
  baseline entry or unlisted suppression may be added (#2474).
- `JdkAtomics`: reports imports and qualified uses of `java.util.concurrent.atomic` outside tests, `common/concurrent`
  and the standalone published modules (`bsp/*`, `parser/core`). Use the `:common:concurrent` types, or a plain field
  for state one thread owns. No other module is excluded, and no exclude, baseline entry or unlisted suppression may be
  added (#2474).
- `StdlibAtomics`: reports imports, star imports included, and qualified uses of the experimental
  `kotlin.concurrent.atomics` outside `common/concurrent`, the one module that opts into it. Use the
  `:common:concurrent` types. It replaces a `ForbiddenImport` pattern that missed star imports and qualified uses.
  `common/concurrent` is its only exclude, and no exclude, baseline entry or unlisted suppression may be added (#2474).

`tools/ci/detekt-gvy-guard.test.mjs` enforces these rules' configuration, detekt's `build:` block, the module builds'
detekt settings, and the multiplatform test excludes of the built-in rules: `**/commonTest/**` and `**/jvmTest/**`
appear only next to `**/test/**` on rules whose detekt default exempts every test source set.
[tools/detekt-rules/README.md](../detekt-rules/README.md#ci-guard) lists exactly what it checks and what it cannot see.
