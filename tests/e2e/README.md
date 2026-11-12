# E2E Test Suite

End-to-end tests for the Groovy Language Server that verify complete LSP request/response cycles.

## Quick Start

```bash
# Focused harness controls, without assembling the fat server JAR or distribution
./gradlew :tests:harnessTest

# Run all E2E tests
./gradlew :tests:e2eTest

# Run a specific scenario
./gradlew :tests:e2eTest -Dgroovy.lsp.e2e.filter=initialize-basic

# Run with parallel forks (memory permitting)
./gradlew :tests:e2eTest -Pe2eParallelForks=4
```

`harnessTest` runs an inspected allowlist of parser, schema, assertion, scenario execution/resource and known-failure
controls. It reuses the E2E test sources and resources, but does not depend on `shadowJar` or `installDist`. Normal
compilation and thin library JAR dependencies still run as needed. It does not prove actual server transport or editor
behavior. `e2eTest` continues to run the full suite, including these controls and the artifact-dependent tests. Use
`make harness-test` for the same focused command.

The Make targets inherit the outer build's Gradle user home, preserving shared caches and daemons. Server processes
still use the harness's isolated runtime Gradle home unless a scenario explicitly configures one.

The `e2eTest` task always starts a fresh test process, including filtered invocations and snapshot update runs. It does
not reuse cached test results or report itself as up-to-date: those results cannot establish the current server,
transport or host-tool readiness. Compilation and server packaging retain their normal caches. Use the focused
`harnessTest` task for cacheable harness controls.

## Architecture

```
tests/e2e/
├── kotlin/                    # Test harness implementation
│   └── .../e2e/
│       ├── ScenarioRunnerTest.kt    # JUnit test factory
│       ├── ScenarioLoader.kt        # YAML scenario parser
│       ├── ScenarioExecutor.kt      # Step execution engine
│       ├── StepExecutors.kt         # Individual step handlers
│       ├── ScenarioContext.kt       # Runtime state & assertions
│       ├── JsonBridge.kt            # JSON conversion utilities
│       └── LanguageServerSessionFactory.kt  # Server lifecycle
├── resources/
│   ├── scenarios/             # YAML test scenarios
│   └── fixtures/              # Test workspaces
└── README.md                  # This file
```

## Server Launch Modes

| Mode        | Description                            | Boundary exercised                         |
| ----------- | -------------------------------------- | ------------------------------------------ |
| `stdio`     | Starts a separate server JVM           | Subprocess transport and process lifecycle |
| `inProcess` | Creates the server inside the test JVM | In-memory transport and shared JVM state   |

For `stdio`, `server.args` follows the `stdio` command and `server.env` overrides inherited environment values. An
explicit `GRADLE_USER_HOME` also overrides the isolated runtime home; otherwise the harness must prepare an isolated
home successfully. Bazel uses `TEST_TMPDIR` for that default. The server JVM defaults to a 1 GiB maximum heap.
In-process mode rejects process arguments and environment overrides, since it shares the test JVM.

`LanguageServerSession.exit()` waits for the process exit or in-process exit callback and checks its code (zero by
default). Closing a session releases its resources independently, so failed scenarios can be cleaned up without claiming
a successful protocol exit. The YAML `exit` step expects code zero; nonzero expectations currently use the Kotlin
session API.

`stdio` remains the default. Select `inProcess` for scenarios intended to share the test JVM:

```yaml
server:
  mode: inProcess
```

Server initialization still runs in either mode. Keep subprocess transport and lifecycle controls in `stdio`; an
in-process pass does not verify those boundaries. Measure the selected scenario set before changing its mode or fork
count.

## Scenario Format

Scenarios are YAML files defining LSP interaction sequences:

```yaml
name: example-test
description: Brief description of what this tests
server:
  mode: stdio          # or inProcess
workspace:
  fixture: my-fixture  # Directory under resources/workspaces/ or resources/fixtures/
  files:               # Optional inline files written over the fixture before initialize
    src/Helper.groovy: |
      class |^helper|Helper {}
steps:
  - initialize: {}
  - initialized: {}
  - openDocument:
      path: src/Main.groovy
      languageId: groovy
      version: 1
      text: |
        println "Hello"
  - request:
      method: textDocument/hover
      params:
        textDocument:
          uri: '{{workspace.uri}}/src/Main.groovy'
        position: { line: 0, character: 0 }
      saveAs: hoverResult
  - assert:
      source: hoverResult
      checks:
        - jsonPath: $.contents.value
          expect: { type: CONTAINS, value: "println" }
  - shutdown: {}
  - exit: {}
```

## Available Step Types

| Step               | Description                                                                     |
| ------------------ | ------------------------------------------------------------------------------- |
| `initialize`       | Sends `initialize` request with optional `initializationOptions`                |
| `initialized`      | Sends `initialized` notification                                                |
| `shutdown`         | Sends `shutdown` request                                                        |
| `exit`             | Sends `exit` notification                                                       |
| `openDocument`     | Opens a document via `textDocument/didOpen`                                     |
| `changeDocument`   | Modifies a document via `textDocument/didChange`                                |
| `saveDocument`     | Saves a document via `textDocument/didSave`                                     |
| `closeDocument`    | Closes a document via `textDocument/didClose`                                   |
| `request`          | Sends a request through the configured protocol route and captures its response |
| `notification`     | Sends any LSP notification                                                      |
| `waitNotification` | Waits for a notification from the server                                        |
| `assert`           | Asserts conditions on saved results                                             |
| `completion`       | Sends `textDocument/completion` at `position` or `line`/`character`             |
| `rename`           | Sends `textDocument/rename` at `position` or `line`/`character`                 |
| `codeAction`       | Sends `textDocument/codeAction` with a range and response checks                |
| `formatting`       | Sends `textDocument/formatting` with formatting options and response checks     |
| `wait`             | Pauses for the configured duration in milliseconds                              |
| `downloadPlugin`   | Downloads a Jenkins plugin and stores its path                                  |
| `goldenAssert`     | Compares an output file with a checked-in golden file                           |
| `cli`              | Runs a command with captured stdout, stderr and exit status                     |

Cursor markers (`|^|`, `|^name|`) in `openDocument.text`, full-text `changeDocument.text` and `workspace.files` are
stripped before the server sees the text. There is no escape syntax, so text such as `~/(a|^b|c)/` is read as a marker.
See [Cursor Markers and Inline Files](resources/scenarios/README.md#cursor-markers-and-inline-files).

## Snapshots

`expect: { snapshot: hover/println.md }` compares a value with a checked-in file under `resources/snapshots/`. Workspace
locations are normalized, and `-Dgroovy.lsp.e2e.updateSnapshots=true` rewrites files locally (never on CI or Bazel). See
[snapshots/README.md](resources/snapshots/README.md).

## Known failures (`xfail`) and disabled scenarios

A scenario for a bug that is not fixed yet carries an `xfail` block instead of being disabled:

```yaml
name: hover-type-inference
xfail:
  issue: 432                        # gvy issue that tracks the bug (required)
  reason: hover shows no inferred type  # what is wrong (required)
  match: "Actual:.*Variable"        # optional regex the failure message must contain
```

- It runs as `[xfail gvy#432] hover-type-inference`. An assertion failure or an LSP error response (or a timeout, only
  when `match` is set) is reported as aborted (skipped) with the reason; any other error (bad YAML, crashed server)
  still fails, and so does a failure whose message does not match `match`.
- When the scenario passes, the test **fails** with `gvy#432 appears fixed: remove xfail from <file>`. Remove the block
  (and close or update the issue) in the fix that makes it pass.
- Request and notification-wait timeouts are capped at 15 seconds so a hung request does not eat shard time.
  `initialize` and the `groovy/status` readiness wait are never capped, since a slow machine would otherwise fail the
  scenario.
- Write `match` so it describes the expected failure (for example the actual value), not just the assertion path; a
  timeout needs one because it can also be caused by a slow machine.
- Unknown keys under `xfail` are rejected.

Prefer `xfail` to the `_` filename prefix. A `_`-prefixed file is skipped by the runner but must still parse and must
start with a `# disabled: <reason or issue>` comment (checked by `ScenarioCatalogTest`).

## Assertion Syntax

```yaml
checks:
  - jsonPath: $.some.path
    expect: { type: EQUALS, value: "expected value" }
  - jsonPath: $.array
    expect: { type: CONTAINS, value: "item" }
  - jsonPath: $.field
    expect: { type: MATCHES_REGEX, value: "pattern.*" }
  - jsonPath: $.optional
    expect: { type: EXISTS }
  - jsonPath: $.size
    expect: { type: SIZE, value: 5 }
    message: Shown when the check fails # belongs to the check, not to `expect`
```

`expect` accepts `type` (an `ExpectationType` from `Scenario.kt`), `value` and `values`, or a standalone `snapshot`
path. Unknown fields are rejected, so `expect: { contains: "x" }` fails during scenario loading. Equality requires a
`value`; `value: null` represents JSON null. An empty `expect: {}` checks existence. See the
[scenario schema notes](resources/scenarios/README.md) for validation details.

## Variable Interpolation

Use `{{variable}}` syntax to reference runtime values:

```yaml
- openDocument:
    uri: '{{workspace.uri}}/src/File.groovy'
    text: 'println "{{someValue}}"'
```

Built-in variables:

- `{{workspace.uri}}` - Workspace root URI
- `{{workspace.path}}` - Workspace root path
- `{{cursor}}`, `{{cursor.name}}` - Cursor marker positions, with `.line`, `.character`, `.uri` and `.path`

A string that is exactly one placeholder keeps the value's JSON type: `position: "{{cursor.name}}"` sends an object.

## Execution limits

Each scenario has a 300000 ms deadline, starting before workspace preparation and covering server startup and all steps.
Set `-Dgroovy.lsp.e2e.scenarioTimeoutMs=<milliseconds>` to change it. The value must be a positive integer; explicit
blank or invalid values fail before setup.

Expiration interrupts the scenario worker; interrupted request waits cancel their unfinished RPC futures. The harness
allows a 30-second worker-cleanup grace. If an owned worker does not exit, the failure reports cleanup as unconfirmed.
This infrastructure failure cannot satisfy `xfail`.

The language server starts on first use, so CLI-only scenarios avoid an unused LSP session. CLI steps own their direct
child process and streams on success and failure. Direct-process exit confirmation retries interruption within a
five-second budget and restores the signal after cleanup. Capture-reader shutdown has a separate five-second budget; a
shared CLI cleanup budget is still part of [#1670](https://github.com/albertocavalcante/gvy/issues/1670). CLI steps
capture stdout and stderr concurrently, with a default 1048576-byte limit per stream. Set
`-Dgroovy.lsp.e2e.cliOutputLimitBytes=<bytes>` to an ASCII decimal integer from 1 through 16777216; explicit blank or
invalid values fail before launch. Within the budget, the complete output strings remain available for assertions.
Overflow fails as a harness error before assertions, with the stream name, limit, observed-byte lower bound and a sample
of at most 256 captured bytes. Cleanup reports direct-process and reader termination as confirmed or unconfirmed;
descendant containment remains open in [#1670](https://github.com/albertocavalcante/gvy/issues/1670).

## Performance Considerations

1. **Memory**: Budget for the test JVM and each active server JVM. The `stdio` server defaults to a 1 GiB maximum heap;
   other JVM allocations also use memory.
2. **Parallelism**: Local `e2eTest` runs default to one worker. GitHub Actions defaults to two workers on macOS/Windows
   and three on Linux. `-Pe2eParallelForks=N` overrides that count; include all workers and active server JVMs in the
   memory budget for either mode. The focused `harnessTest` task always uses one worker.
3. **Timeouts**: The whole-scenario deadline and bounded cleanup grace apply as described above.
