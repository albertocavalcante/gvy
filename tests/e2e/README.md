# E2E Test Suite

End-to-end tests for the Groovy Language Server that verify complete LSP request/response cycles.

## Quick Start

```bash
# Run all E2E tests
./gradlew :tests:e2eTest

# Run a specific scenario
./gradlew :tests:e2eTest -Dgroovy.lsp.e2e.filter=initialize-basic

# Run with parallel forks (memory permitting)
./gradlew :tests:e2eTest -Pe2eParallelForks=4
```

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

| Mode        | Description                 | Performance                | Use Case                      |
| ----------- | --------------------------- | -------------------------- | ----------------------------- |
| `stdio`     | Spawns separate JVM process | Slow (~2-5s startup)       | Realistic integration testing |
| `inProcess` | Runs in same JVM as tests   | Fast (no startup overhead) | Development, CI optimization  |

### Future: In-Process by Default

> **Planned Migration**: All scenarios will be migrated to `mode: inProcess` by default to significantly reduce E2E test
> suite execution time.
>
> The `stdio` mode will remain available for testing actual subprocess communication and for debugging scenarios that
> behave differently across process boundaries.

To opt into in-process mode today, update your scenario:

```yaml
server:
  mode: inProcess  # Eliminates JVM startup overhead
```

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

| Step               | Description                                                         |
| ------------------ | ------------------------------------------------------------------- |
| `initialize`       | Sends `initialize` request with optional `initializationOptions`    |
| `initialized`      | Sends `initialized` notification                                    |
| `shutdown`         | Sends `shutdown` request                                            |
| `exit`             | Sends `exit` notification                                           |
| `openDocument`     | Opens a document via `textDocument/didOpen`                         |
| `changeDocument`   | Modifies a document via `textDocument/didChange`                    |
| `saveDocument`     | Saves a document via `textDocument/didSave`                         |
| `closeDocument`    | Closes a document via `textDocument/didClose`                       |
| `request`          | Sends any LSP request and captures response                         |
| `notification`     | Sends any LSP notification                                          |
| `waitNotification` | Waits for a notification from the server                            |
| `assert`           | Asserts conditions on saved results                                 |
| `completion`       | Sends `textDocument/completion` at `position` or `line`/`character` |
| `rename`           | Sends `textDocument/rename` at `position` or `line`/`character`     |

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

`expect` takes `type` (one of the `ExpectationType` values in `Scenario.kt`), `value` and `values`. Other keys under
`expect` are currently ignored, so `expect: { contains: "x" }` only checks that the path exists.

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

## Performance Considerations

1. **Memory**: Each stdio-mode test spawns a JVM (~512MB-2GB). Tests run sequentially by default.
2. **Parallelism**: Use `-Pe2eParallelForks=N` only with `inProcess` mode or ample memory.
3. **Timeouts**: Default 5-minute timeout per test. Override via system property.
