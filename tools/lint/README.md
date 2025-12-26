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
