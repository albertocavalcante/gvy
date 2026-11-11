# CI diagnostic accountability

A successful command is insufficient when it emits actionable diagnostics. Agents own the complete CI result for the
head they ship: failures, warnings, dependency advice, skips and tracked debt. Inspect the check results and generated
diagnostic summary before declaring work ready. Report counts, fixes, remaining issue links and validation limits in the
PR and completion message. Independent review must cover changes to diagnostic policy and exceptions.

## Bazel gate

`CI (Bazel)` runs on source, build, dependency, extension and gate changes before merge, plus daily on main. Missing
direct Kotlin dependencies and duplicate Maven versions fail natively. Build/test logs are checked even when a build
fails. Bash preserves pipeline failures; missing, empty or incomplete logs fail the diagnostic gate.

The gate detects compiler/tool warnings, dependency add/remove advice, duplicate artifact versions and repository
reproducibility advice. Routine progress and generated-file listings are informational. Raw logs, sorted deduplicated
diagnostics and a Markdown report are retained as CI artifacts. The job summary exposes new diagnostics and every
tracked exception, including exceptions absent from a warm cache's output. New formats must be covered by a regression
test when discovered; text classification is not proof that every future tool's diagnostic syntax is understood.

Run the same checks locally:

```bash
node --test tools/ci/bazel-diagnostics.test.mjs
mkdir -p /tmp/gvy-diagnostics
set -o pipefail
bazel build //... --color=no --curses=no 2>&1 | tee /tmp/gvy-diagnostics/build.log
bazel test //... --test_output=errors --color=no --curses=no 2>&1 | tee /tmp/gvy-diagnostics/test.log
node tools/ci/bazel-diagnostics.mjs --baseline tools/ci/bazel-diagnostics.json \
  --report /tmp/gvy-diagnostics /tmp/gvy-diagnostics/build.log /tmp/gvy-diagnostics/test.log
```

## Resolve or explicitly account for debt

Fix first-party warnings and dependency declarations. Preserve necessary runtime dependencies when removing compile
dependencies. Never mute an entire warning category, turn off dependency analysis, rewrite a baseline automatically or
mark a failed check successful to obtain a green result.

For an unavoidable upstream limitation, add an exact diagnostic to `tools/ci/bazel-diagnostics.json` only after
independent review. Every entry requires an owner, repository issue, impact/reason, next action, acceptance criteria and
expiry. A different file, target or diagnostic fails; there are no regular-expression exceptions. Expired or malformed
exceptions fail even when cached actions emit nothing. Renewing an exception requires fresh evidence and review; remove
obsolete entries when their fix is verified with a fresh build. Repeated Kotlin worker output is deduplicated, and
generated-source temporary roots are normalized to `generated/`.

Current tooling limits are tracked in [Wire generation](https://github.com/albertocavalcante/gvy/issues/1599) and the
exception ledger. Narrow deprecated-API compatibility paths are tracked in
[recipe and Maven migration](https://github.com/albertocavalcante/gvy/issues/1600); suppressions document their purpose
at the compatibility boundary. These items remain debt until their acceptance criteria are met.

## Coverage limits

This gate enforces the Bazel build/test diagnostic stream. Native checks and independent review still apply to every
other CI job. Other workflow logs, skipped checks and runtime warnings must also be evaluated and recorded by the
shipping agent; they are not automatically covered by this classifier. Additional tool integrations must preserve
command exit codes, validate complete evidence, test diagnostic detection and expose owned exceptions before claiming
equivalent enforcement.

Passing tests' captured runtime logs are outside this gate (`--test_output=errors` only emits failing test output).
Gradle deprecations are now visible instead of globally suppressed; equivalent Gradle enforcement and migration of known
deprecated DSL/plugin APIs remain tracked in [#1603](https://github.com/albertocavalcante/gvy/issues/1603).
