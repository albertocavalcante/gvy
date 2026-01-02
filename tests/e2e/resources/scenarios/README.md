# E2E Test Scenarios

This directory contains YAML scenario files that define end-to-end LSP test cases.

## Running Scenarios

```bash
# All scenarios
./gradlew :tests:e2eTest

# Specific scenario
./gradlew :tests:e2eTest -Dgroovy.lsp.e2e.filter=hover-documentation
```

## Scenario Categories

| Category             | Scenarios                                                                     | Description                   |
| -------------------- | ----------------------------------------------------------------------------- | ----------------------------- |
| **Lifecycle**        | `initialize-basic`                                                            | Server handshake and shutdown |
| **Hover**            | `hover-println`, `hover-documentation`                                        | Hover information             |
| **Completion**       | `completion-basic`, `completion-gdk`                                          | Code completion               |
| **Definition**       | `definition-*`                                                                | Go-to-definition              |
| **Diagnostics**      | `diagnostics-*`                                                               | Error reporting               |
| **Document Symbols** | `document-symbol-basic`                                                       | Document symbols              |
| **Signature**        | `signature-help`                                                              | Method signature help         |
| **Build Tools**      | `gradle-hover-deps`                                                           | Gradle integration            |
| **CodeLens**         | `codelens-spock`                                                              | Test CodeLens commands        |
| **Jenkins**          | `jenkins-pipeline`, `jenkins-completion`, `jenkins-hover`, `jenkins-scripted` | Jenkins pipeline support      |

## Server Mode Migration

> **Note**: Scenarios are being migrated from `mode: stdio` to `mode: inProcess` for faster test execution. See the
> parent [README.md](../README.md) for details.

Currently migrated:

- ✅ `initialize-basic.yaml`

Pending migration:

- ⏳ All other scenarios

## Adding New Scenarios

1. Create `your-test.yaml` in this directory
2. Define the server mode, workspace fixture, and steps
3. Run with `./gradlew :tests:e2eTest -Dgroovy.lsp.e2e.filter=your-test`

See [../README.md](../README.md) for full scenario format documentation.
