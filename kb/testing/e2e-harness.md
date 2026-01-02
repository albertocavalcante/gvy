# End-to-End Harness (Scaffolding)

The E2E harness drives full LSP sessions against the `groovy-lsp` process. It launches the server in **stdio** mode
(matching VS Code’s behaviour), copies a fixture workspace into a temporary directory, and executes YAML-defined steps.

## Running

```bash
GRADLE_USER_HOME=$(pwd)/.gradle ./gradlew e2eTest
```

Gradle sets the following system properties for the runner:

| Property                         | Purpose                                                                |
| -------------------------------- | ---------------------------------------------------------------------- |
| `groovy.lsp.e2e.scenarioDir`     | Directory containing YAML scenarios (`tests/e2e/resources/scenarios`). |
| `groovy.lsp.e2e.execJar`         | Path to the shaded `groovy-lsp` JAR used for process launches.         |
| `groovy.lsp.e2e.serverClasspath` | Fallback classpath if the JAR is unavailable.                          |
| `groovy.lsp.e2e.mainClass`       | Fully-qualified entry point (`MainKt`).                                |
| `groovy.lsp.e2e.gradleUserHome`  | Optional override for the isolated Gradle user home used by the LSP.   |

If Gradle cannot download the wrapper because of network limits, point `GRADLE_USER_HOME` to a writeable directory or
pre-install the distribution.

The harness launches the language server with an isolated Gradle user home (`build/e2e-gradle-home` by default, or
`GROOVY_LSP_E2E_GRADLE_USER_HOME` / `groovy.lsp.e2e.gradleUserHome` if set) to avoid cache lock contention with the
outer Gradle build.

## YAML DSL (initial slice)

```yaml
name: initialize-basic
description: Basic lifecycle handshake
workspace:
  fixture: empty-workspace
steps:
  - initialize: {}
  - initialized: {}
  - shutdown: {}
  - exit: {}
```

Each step is an object with a single key:

- `initialize` – issues `initialize` with optional `rootUri` and `initializationOptions`.
- `initialized` – sends the follow-up `initialized` notification.
- `shutdown` – performs graceful shutdown.
- `exit` – sends the `exit` notification once shutdown completes.

Fixtures live under `tests/e2e/resources/workspaces`. They are copied into a temp workspace so tests can mutate state
without affecting source files.

## Shared Test Client

The harness talks to the language server through the reusable client in
`tests/lsp-client/kotlin/com/github/albertocavalcante/groovylsp/testing/client`. If you need to stub extra client
behaviour (for example `workspace/configuration` responses), extend that module rather than the scenario runner so unit
and e2e tests share the same LSP-facing surface.

## Logging

`make e2e` runs Gradle with `--info --console=plain` and the harness ships a `logback-test.xml` (under
`tests/e2e/resources/`) that promotes harness/server logs to `INFO` while keeping other libraries at `WARN`. You should
see step-by-step progress like:

```
16:45:11.234 INFO  c.g.a.g.e.ScenarioExecutor - Running step 1 (Initialize) for scenario 'initialize-basic'
```

Shutdown requests use a dedicated timeout (`groovy.lsp.e2e.shutdownTimeoutMs`, default 30s) to let Gradle daemons close
cleanly on busy runners.

## Next Steps

- Extend the step DSL with document operations (`didOpen`, `completion`, etc.).
- Capture responses for JSONPath assertions and snapshot testing.
- Add socket-mode launch as an opt-in for CLI parity.
