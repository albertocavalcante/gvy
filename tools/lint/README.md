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
  lambda (except the argument of `lazy`). Hoist the regex into a file-scope, `object` or `companion object` property.
  Patterns built from a runtime value (parameter, interpolated expression, call, `var`) cannot be hoisted and are not
  reported. Modules that are not hoisted yet are listed under the rule's `excludes` in `detekt.yml`; remove a module's
  line in the pull request that hoists it. Do not add detekt baseline entries for this rule (#2420).
- `VolatileProperty`: reports `@Volatile` annotations, including `@field:Volatile`, qualified names and import aliases.
  Use `OnceFlag`, `SharedValue` or `Counter` from `:common:concurrent`, `lazy`, or a lock instead. It guards every
  linted module; there are no excludes, and none or a baseline entry may be added (#2474).
- `JdkAtomics`: reports imports and qualified uses of `java.util.concurrent.atomic` outside tests, `common/concurrent`
  and the standalone published modules (`bsp/*`, `parser/core`). Use the `:common:concurrent` types instead. Modules
  still to migrate are listed under its `excludes`; remove a module's line in the pull request that migrates it.
