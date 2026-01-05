---
description: Deterministic protocol for developing, verifying, and managing End-to-End (E2E) test scenarios.
---

# /tests-tests-tests

This workflow defines the mandatory procedure for developing End-to-End (E2E) tests. It prioritizes determinism,
assertion strictness, and explicit failure management.

## Reference Documentation

Before proceeding, read the following files to understand the test runner structure and scenario format:

1. `tests/e2e/resources/scenarios/README.md`: Lists available scenarios and execution commands.
2. `tests/e2e/resources/README.md`: Documents the full scenario YAML schema.

## Phase 1: Setup

1. **Worktree Isolation** Execute the following command to create a clean environment for your test development:
   ```bash
   git worktree add -b feat/e2e-[feature_name] ../groovy-lsp-e2e-[feature_name] origin/main
   cd ../groovy-lsp-e2e-[feature_name]
   ```

2. **Scenario Creation** Create a new YAML file in `tests/e2e/resources/scenarios/`. Use a kebab-case naming convention
   describing the feature and scope (e.g., `completion-basic.yaml`).

## Phase 2: Implementation Standards

Adhere to the following strict rules when defining test steps and assertions.

### 1. Assertions Must Be Quantitative

Do not use `NOT_EMPTY` or `EXISTS` unless the data is nondeterministic (e.g., timestamps).

- **Correct**: Use `type: SIZE` with a precise integer value.
- **Correct**: Use `type: EQUALS` with exact expected content.

### 2. Deep Verification

Verify nested properties to ensure structure correctness.

- **Correct**: Use JSONPath filters (e.g., `$.items[?(@.label == 'println' && @.kind == 3)]`).
- **Incorrect**: Checking the root object exists only.

### 3. Harness Expansion

If the current test framework (Kotlin) lacks the necessary step types or assertion operators (`ExpectationType`) to
express a strict test:

1. **Do Not Compromise**: Do not write a weaker test.
2. **Extend**: Modify `tests/e2e/kotlin/com/github/albertocavalcante/groovylsp/e2e` to add the missing capability.
3. **Refactor**: Ensure the new capability is generic and reusable.

## Phase 3: Execution

Run tests from the repository root using the Gradle wrapper.

### Run a Single Scenario

Use the `-Dgroovy.lsp.e2e.filter` property to isolate the test. This uses substring matching.

```bash
./gradlew :tests:e2eTest -Dgroovy.lsp.e2e.filter=[scenario_filename_basename]
```

### Run Full Suite

Execute the entire E2E suite to ensure no regressions.

```bash
./gradlew :tests:e2eTest
```

## Phase 4: Resolution Protocol

When a test fails, you must follow one of the following two deterministic paths. **Do not delete test code.**

### Path A: Verification Failure (Bug)

Use this path if the server implements the feature but returns incorrect data.

1. **Update Assertion**: Modify the test to assert the _current incorrect behavior_.
2. **Documentation**: Add a `TODO` comment explicitly stating the bug and expected correct behavior.
3. **Result**: The test passes (confirming the bug exists), preventing future regressions.

### Path B: Unsupported Feature

Use this path if the server throws `UnsupportedOperationException`, `Internal Error`, or lacks the feature entirely.

1. **Disable Test**: Rename the scenario file to prefix it with an underscore (e.g., `_completion-basic.yaml`).
2. **Deferral**: Execute the `/defer` workflow to create a GitHub issue tracking the missing feature.
3. **Linkage**: Add a header to the YAML file referencing the issue:
   ```yaml
   # TODO(DEFERRED): #<issue-id> - Test disabled. Feature not implemented.
   # See: https://github.com/albertocavalcante/groovy-devtools/issues/<issue-id>
   ```

4. **Result**: The file is committed as a specification for future implementation.

## Phase 5: Submission

1. **Commit**: Create a commit. If Path B was chosen, explicitly mention "Disabled test spec" in the message.
2. **Pull Request**: Push the branch and create a PR.
