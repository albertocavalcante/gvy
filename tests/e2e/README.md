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
  fixture: my-fixture  # Directory under resources/fixtures/
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
          expect: { contains: "println" }
  - shutdown: {}
  - exit: {}
```

## Available Step Types

| Step               | Description                                                      |
| ------------------ | ---------------------------------------------------------------- |
| `initialize`       | Sends `initialize` request with optional `initializationOptions` |
| `initialized`      | Sends `initialized` notification                                 |
| `shutdown`         | Sends `shutdown` request                                         |
| `exit`             | Sends `exit` notification                                        |
| `openDocument`     | Opens a document via `textDocument/didOpen`                      |
| `changeDocument`   | Modifies a document via `textDocument/didChange`                 |
| `saveDocument`     | Saves a document via `textDocument/didSave`                      |
| `closeDocument`    | Closes a document via `textDocument/didClose`                    |
| `request`          | Sends any LSP request and captures response                      |
| `notification`     | Sends any LSP notification                                       |
| `waitNotification` | Waits for a notification from the server                         |
| `assert`           | Asserts conditions on saved results                              |

## Assertion Syntax

```yaml
checks:
  - jsonPath: $.some.path
    expect: { equals: "expected value" }
  - jsonPath: $.array
    expect: { contains: "item" }
  - jsonPath: $.field
    expect: { matchesRegex: "pattern.*" }
  - jsonPath: $.optional
    expect: { exists: true }
  - jsonPath: $.size
    expect: { size: 5 }
```

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

## Performance Considerations

1. **Memory**: Each stdio-mode test spawns a JVM (~512MB-2GB). Tests run sequentially by default.
2. **Parallelism**: Use `-Pe2eParallelForks=N` only with `inProcess` mode or ample memory.
3. **Timeouts**: Default 5-minute timeout per test. Override via system property.
