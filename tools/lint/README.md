# Lint Configs

This directory contains shared lint configuration files.

- `actionlint.yml`: Actionlint config for GitHub Actions workflows.
- `ghalint.yaml`: Ghalint config for GitHub Actions security policies.
- `detekt.yml`: Detekt rules for Kotlin sources.
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

## Detekt path policy

Gradle treats rule and ruleset `includes`/`excludes` in `detekt.yml` as repository-relative globs. The same source keeps
the same exemptions when a checkout's parent directory happens to be named `test`, `dsl` or `visitor`. Actual test and
DSL directories inside the repository retain their existing policy; both harness source roots remain checked.

Each project's internal `detektPathConfig` task generates a checkout-specific overlay under
`build/detekt/config/repository-paths.yml`. Invoke a Detekt check, typed check, autocorrect or baseline task to generate
its configuration. Edit `detekt.yml`, rather than the generated file. Core and plugin defaults remain enabled; their
effective path filters are anchored too. Configuration-validation exclusions keep their original meaning. Explicit
absolute globs retain their original scope; use relative globs for checkout-independent policy.

The producer reads the realized consumers' actual tool/plugin classpaths and refuses differing classpaths within one
project. Root spellings and classpath content participate in its inputs, so moving a checkout regenerates the overlay.
Enabled reports use task-specific filenames; the ordinary `detekt` report keeps its existing filename.

The helper's matcher, resource and policy tests run when its build-logic JAR is used. Run them explicitly with
`./gradlew -p buildSrc test`; root `lint` also checks the helper's Kotlin sources and root build script.
`verifyDetektPathConfiguration` checks actual normal, typed, corrective and baseline configuration plus enabled report
paths without running those analyses or writing baseline files. This path fix does not retire existing harness findings
or complete the remaining CI gate work in [#1557](https://github.com/albertocavalcante/gvy/issues/1557).
