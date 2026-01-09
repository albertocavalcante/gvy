---
description: Comprehensive quality assurance workflow for verifying LSP functionality, performance, and release readiness.
---

# /qa

<purpose>
A systematic quality assurance workflow for the Groovy Language Server.
Use this workflow for pre-release verification, regression testing, or comprehensive feature validation.
</purpose>

<when_to_use>

- Before tagging a release
- After major refactoring (e.g., cache changes, parser updates)
- When investigating reported regressions
- Periodic health checks on main branch </when_to_use>

---

## Phase 0: Environment Setup

### 0.1 Clean Build

<critical>
Always start QA from a clean state to catch missing dependencies or build issues.
</critical>

```bash
# Full clean build
./gradlew clean build -x test

# Verify shadow JAR is created
ls -la groovy-lsp/build/libs/gls-*-all.jar
```

### 0.2 Version Verification

```bash
# Check version is correctly embedded
java -jar groovy-lsp/build/libs/gls-*-all.jar version
```

---

## Phase 1: Automated Test Pyramid

Execute tests in order of speed and isolation. **Stop immediately on failure.**

### 1.1 Unit Tests (Fast, Isolated)

```bash
# Run all unit tests
./gradlew test --console=plain

# Expected: All tests pass (some skipped tests are OK)
# Time: ~2-5 minutes
```

<checkpoint>
**GATE 1**: Unit tests must be 100% green before proceeding.
</checkpoint>

### 1.2 Integration Tests

```bash
# Run integration tests (included in standard test task)
./gradlew :groovy-lsp:test --tests "*IntegrationTest*" --console=plain
```

### 1.3 Property-Based Tests

Property tests verify invariants under random input:

```bash
# Run property tests explicitly
./gradlew test --tests "*Property*" --console=plain
```

<verification>
- `ParserPropertiesTest` — Parser never throws unchecked exceptions
- `RangeValidationPropertyTest` — Range handling is robust
- `MultipleDiagnosticsPropertyTest` — Code actions handle edge cases
</verification>

### 1.4 E2E Scenario Tests

<critical>
E2E tests spawn real LSP server processes. They are slow but comprehensive.
</critical>

```bash
# Run full E2E suite
./gradlew :tests:e2eTest --console=plain

# Run specific category (if investigating)
./gradlew :tests:e2eTest -Dgroovy.lsp.e2e.filter="completion"
./gradlew :tests:e2eTest -Dgroovy.lsp.e2e.filter="definition"
./gradlew :tests:e2eTest -Dgroovy.lsp.e2e.filter="hover"
```

<checkpoint>
**GATE 2**: E2E tests must pass. Disabled scenarios (prefixed `_`) are acceptable.
</checkpoint>

---

## Phase 2: LSP Feature Verification Matrix

Manually verify each LSP capability works correctly. Use VS Code with the extension.

### 2.1 Start Extension Development Host

```bash
cd editors/code
pnpm install
pnpm run compile
# Press F5 in VS Code or:
code --extensionDevelopmentPath="$(pwd)"
```

### 2.2 Feature Checklist

Test each feature in a sample Groovy project:

| Feature                           | Test Action                   | Expected Result           | Status |
| --------------------------------- | ----------------------------- | ------------------------- | ------ |
| **Completion**                    | Type `String.`                | Method list appears       | ☐      |
| **Completion (local)**            | Type partial variable name    | Variable suggested        | ☐      |
| **Hover**                         | Hover over method call        | Signature + docs shown    | ☐      |
| **Go to Definition**              | Ctrl+Click on method          | Jumps to definition       | ☐      |
| **Go to Definition (cross-file)** | Ctrl+Click on imported class  | Opens source file         | ☐      |
| **Find References**               | Right-click → Find References | List of usages            | ☐      |
| **Diagnostics**                   | Introduce syntax error        | Red squiggly appears      | ☐      |
| **Code Actions**                  | Click lightbulb on warning    | Quick fixes offered       | ☐      |
| **Formatting**                    | Format Document               | Code is formatted         | ☐      |
| **Folding**                       | Click fold icon               | Code block collapses      | ☐      |
| **Document Symbols**              | Ctrl+Shift+O                  | Class/method outline      | ☐      |
| **Workspace Symbols**             | Ctrl+T, search class          | Finds across workspace    | ☐      |
| **Rename**                        | F2 on variable                | Renames all occurrences   | ☐      |
| **Signature Help**                | Type `(` after method         | Parameters shown          | ☐      |
| **Semantic Tokens**               | Check syntax highlighting     | Methods/variables colored | ☐      |

### 2.3 Framework-Specific Features

| Feature              | Test Action              | Expected Result          | Status |
| -------------------- | ------------------------ | ------------------------ | ------ |
| **Spock**            | Open `*Spec.groovy`      | Block labels highlighted | ☐      |
| **Jenkins Pipeline** | Open `Jenkinsfile`       | Steps recognized         | ☐      |
| **Jenkins vars**     | Reference `vars/` script | Go-to-definition works   | ☐      |
| **Gradle**           | Open `build.gradle`      | DSL completion works     | ☐      |

---

## Phase 3: Performance Verification

### 3.1 Startup Time

```bash
# Measure cold start
time java -jar groovy-lsp/build/libs/gls-*-all.jar version

# Target: < 3 seconds for version command
```

### 3.2 Memory Footprint

```bash
# Start server and observe memory
java -Xmx512m -jar groovy-lsp/build/libs/gls-*-all.jar stdio &
PID=$!
sleep 10
ps -o rss= -p $PID | awk '{print $1/1024 " MB"}'
kill $PID
```

<thresholds>
- **Idle memory**: < 200 MB
- **With medium project loaded**: < 500 MB
- **Large project (1000+ files)**: < 1 GB
</thresholds>

### 3.3 Response Latency

In VS Code with a medium project:

| Operation        | Target Latency | Acceptable |
| ---------------- | -------------- | ---------- |
| Completion popup | < 200ms        | < 500ms    |
| Hover tooltip    | < 100ms        | < 300ms    |
| Go to Definition | < 300ms        | < 1s       |
| Find References  | < 500ms        | < 2s       |
| Document Symbols | < 100ms        | < 300ms    |

### 3.4 Stress Test

Open 10+ Groovy files simultaneously:

```bash
# Monitor for memory leaks or degradation
# Observe VS Code output panel for errors
# Check server doesn't crash
```

---

## Phase 4: Cross-Platform Verification

### 4.1 CI Matrix Check

Verify CI passes on all platforms:

```bash
gh run list --workflow=ci.yaml --limit=5
gh run view <RUN_ID> --log-failed  # If any failed
```

| Platform       | CI Status | Manual Verified |
| -------------- | --------- | --------------- |
| macOS (ARM64)  | ☐         | ☐               |
| macOS (x64)    | ☐         | ☐               |
| Linux (Ubuntu) | ☐         | ☐               |
| Windows        | ☐         | ☐               |

### 4.2 Platform-Specific Issues

Known areas requiring cross-platform attention:

- **Path separators**: Windows uses `\`, others use `/`
- **File URI schemes**: `file:///C:/` vs `file:///home/`
- **Line endings**: CRLF vs LF handling
- **Case sensitivity**: Windows filesystem is case-insensitive

---

## Phase 5: VS Code Extension QA

### 5.1 Extension Tests

```bash
cd editors/code
pnpm test                    # Unit tests
pnpm run test:integration    # VS Code integration tests
```

### 5.2 Extension Manifest Verification

```bash
# Check package.json is valid
cd editors/code
pnpm run package  # Creates .vsix

# Verify activationEvents, contributes, etc.
cat package.json | jq '.activationEvents, .contributes.languages'
```

### 5.3 Extension Installation Test

```bash
# Install from .vsix
code --install-extension groovy-language-server-*.vsix

# Verify activation
# Open a .groovy file → Check "Groovy Language Server" in status bar
```

---

## Phase 6: Regression Checklist

Known historically fragile areas:

### 6.1 Parser Edge Cases

| Test Case                          | Verification         |
| ---------------------------------- | -------------------- |
| Empty file                         | No crash, no errors  |
| File with only comments            | Parses successfully  |
| Incomplete class declaration       | Graceful degradation |
| Deeply nested closures (5+ levels) | No stack overflow    |
| Very long lines (>10KB)            | No truncation issues |
| Unicode in identifiers             | Handled correctly    |

### 6.2 Cache Coherency

| Test Case                     | Verification               |
| ----------------------------- | -------------------------- |
| Edit file → Save → Edit again | Cache invalidated properly |
| Rename file                   | Old cache entry cleared    |
| Delete file                   | No stale references        |
| Git branch switch             | Workspace re-indexed       |

### 6.3 Concurrent Operations

| Test Case                             | Verification                 |
| ------------------------------------- | ---------------------------- |
| Type rapidly while completion open    | No race conditions           |
| Multiple files open, edit alternately | Correct diagnostics per file |
| Save during completion request        | No crash or hang             |

---

## Phase 7: Quality Gates Summary

<critical>
ALL gates must pass before release.
</critical>

| Gate   | Criteria                             | Status |
| ------ | ------------------------------------ | ------ |
| **G1** | Unit tests 100% pass                 | ☐      |
| **G2** | E2E scenarios pass (excl. disabled)  | ☐      |
| **G3** | No new lint warnings                 | ☐      |
| **G4** | CI green on all platforms            | ☐      |
| **G5** | VS Code extension tests pass         | ☐      |
| **G6** | Manual feature matrix verified       | ☐      |
| **G7** | Performance within thresholds        | ☐      |
| **G8** | No regression in known fragile areas | ☐      |

---

## Phase 8: Documentation

Before release, verify documentation is current:

```bash
# Check README is accurate
cat README.md | head -50

# Verify CHANGELOG has entry for this release
cat CHANGELOG.md | head -30

# Check VS Code extension README
cat editors/code/README.md | head -30
```

---

## Quick Reference Commands

```bash
# Full QA suite (automated portion)
./gradlew clean build test :tests:e2eTest

# Specific test categories
./gradlew test --tests "*ParserTest*"        # Parser tests
./gradlew test --tests "*CompletionTest*"    # Completion tests
./gradlew test --tests "*DefinitionTest*"    # Definition tests
./gradlew test --tests "*SemanticTest*"      # Semantic analysis

# E2E by feature
./gradlew :tests:e2eTest -Dgroovy.lsp.e2e.filter="completion"
./gradlew :tests:e2eTest -Dgroovy.lsp.e2e.filter="definition"
./gradlew :tests:e2eTest -Dgroovy.lsp.e2e.filter="hover"
./gradlew :tests:e2eTest -Dgroovy.lsp.e2e.filter="diagnostic"

# Extension
cd editors/code && pnpm test && pnpm run test:integration
```

---

## Troubleshooting

### Test Timeout

```bash
# Increase timeout for slow machines
./gradlew test -Djunit.jupiter.execution.timeout.default=600s
```

### E2E Flakiness

```bash
# Run single scenario with debug output
./gradlew :tests:e2eTest \
  -Dgroovy.lsp.e2e.filter="scenario-name" \
  --info
```

### Memory Issues During Tests

```bash
# Reduce parallel forks
./gradlew test -PmaxTestForks=1

# Increase heap
./gradlew test -Dorg.gradle.jvmargs="-Xmx4g"
```

### Viewing Test Reports

```bash
# HTML reports
open groovy-lsp/build/reports/tests/test/index.html
open tests/build/reports/tests/e2eTest/index.html

# JUnit XML (for CI integration)
ls groovy-lsp/build/test-results/test/*.xml
```
