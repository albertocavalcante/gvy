---
description: Periodic codebase health check for proactive quality maintenance
---

# /qa-health

<purpose>
A periodic health check workflow for proactive codebase maintenance.
Use this for weekly checks, after major merges, or to assess overall project health.
</purpose>

<when_to_use>

- Weekly/bi-weekly health checks
- After merging major features
- Before starting new feature work
- When onboarding new contributors
- Periodic tech debt assessment

</when_to_use>

<duration>
Target: 30-60 minutes
</duration>

---

## Phase 1: Test Suite Health

### 1.1 Run Full Test Suite

```bash
# Run all tests with coverage
./gradlew test --console=plain

# Capture test metrics
./gradlew test --console=plain 2>&1 | tee /tmp/test-output.txt
grep -E "tests|passed|failed|skipped" /tmp/test-output.txt
```

### 1.2 Test Metrics

| Metric        | Current | Trend |
| ------------- | ------- | ----- |
| Total tests   | ☐       | ↑/↓/→ |
| Pass rate     | ☐       | ↑/↓/→ |
| Skipped tests | ☐       | ↑/↓/→ |
| Flaky tests   | ☐       | ↑/↓/→ |

<checkpoint>
**GATE 1**: All tests pass. Investigate any flaky tests.
</checkpoint>

---

## Phase 2: Dependency Health

### 2.1 Check for Outdated Dependencies

```bash
# Gradle dependencies
./gradlew dependencyUpdates -Drevision=release

# Node dependencies (if applicable)
cd editors/code && pnpm outdated || true
```

### 2.2 Security Audit

```bash
# Check for known vulnerabilities
./gradlew dependencyCheckAnalyze || true

# Node security audit
cd editors/code && pnpm audit || true
```

### 2.3 Dependency Report

| Category         | Count | Action Needed  |
| ---------------- | ----- | -------------- |
| Outdated (minor) | ☐     | Low priority   |
| Outdated (major) | ☐     | Plan upgrade   |
| Security issues  | ☐     | Immediate      |
| Deprecated       | ☐     | Plan migration |

<checkpoint>
**GATE 2**: No critical security vulnerabilities. Document any deferred updates.
</checkpoint>

---

## Phase 3: Code Quality Audit

### 3.1 Lint Status

```bash
# Run full lint check
./gradlew detekt --console=plain

# Count issues by severity
./gradlew detekt --console=plain 2>&1 | grep -E "style|warning|error" | sort | uniq -c
```

### 3.2 TODO/FIXME Audit

```bash
# Count TODOs and FIXMEs
grep -r "TODO" --include="*.kt" --include="*.groovy" -c | awk -F: '{sum+=$2} END {print "TODOs:", sum}'
grep -r "FIXME" --include="*.kt" --include="*.groovy" -c | awk -F: '{sum+=$2} END {print "FIXMEs:", sum}'

# List TODOs without issue references
grep -rn "TODO" --include="*.kt" --include="*.groovy" | grep -v "#[0-9]" | head -20
```

### 3.3 Deprecated API Usage

```bash
# Find deprecated annotations
grep -rn "@Deprecated" --include="*.kt" | head -10

# Find suppress deprecation
grep -rn "DEPRECATION" --include="*.kt" | head -10
```

### 3.4 Code Quality Report

| Metric               | Count | Status         |
| -------------------- | ----- | -------------- |
| Lint errors          | ☐     | Must be 0      |
| Lint warnings        | ☐     | Track trend    |
| TODOs total          | ☐     | Categorize     |
| TODOs without issues | ☐     | Create issues  |
| FIXMEs               | ☐     | Priority items |
| Deprecated usages    | ☐     | Plan migration |

---

## Phase 4: Performance Baseline

### 4.1 Build Performance

```bash
# Clean build timing
time ./gradlew clean build -x test

# Incremental build timing
touch groovy-lsp/src/main/kotlin/org/groovylsp/server/GroovyLanguageServer.kt
time ./gradlew build -x test
```

### 4.2 Test Performance

```bash
# Time the test suite
time ./gradlew test

# Find slow tests
./gradlew test --console=plain 2>&1 | grep -E "[0-9]+\.[0-9]+ sec" | sort -t'(' -k2 -rn | head -10
```

### 4.3 Performance Metrics

| Metric            | Current | Baseline | Status |
| ----------------- | ------- | -------- | ------ |
| Clean build       | ☐       | < 2min   | ☐      |
| Incremental build | ☐       | < 30s    | ☐      |
| Test suite        | ☐       | < 5min   | ☐      |
| Startup time      | ☐       | < 3s     | ☐      |

---

## Phase 5: Documentation Health

### 5.1 README Currency

```bash
# Check README last modified
git log -1 --format="%ar" -- README.md

# Check for broken links (if available)
# npx markdown-link-check README.md
```

### 5.2 API Documentation

```bash
# Check for undocumented public APIs
grep -rn "public fun\|public class" --include="*.kt" | wc -l
grep -rn "/\*\*" --include="*.kt" | wc -l
```

### 5.3 Documentation Checklist

| Document     | Last Updated | Status    |
| ------------ | ------------ | --------- |
| README.md    | ☐            | Current?  |
| CHANGELOG.md | ☐            | Current?  |
| AGENTS.md    | ☐            | Current?  |
| API docs     | ☐            | Coverage? |

---

## Phase 6: CI/CD Health

### 6.1 Recent CI Status

```bash
# Check recent workflow runs
gh run list --limit 10

# Check for recurring failures
gh run list --status failure --limit 5
```

### 6.2 CI Metrics

| Metric             | Current | Target  |
| ------------------ | ------- | ------- |
| Main branch status | ☐       | Green   |
| Average CI time    | ☐       | < 15min |
| Failure rate (30d) | ☐       | < 5%    |
| Flaky workflows    | ☐       | 0       |

---

## Phase 7: Issue Backlog Health

### 7.1 Open Issues Summary

```bash
# Count by label
gh issue list --state open --limit 100 --json labels | jq '[.[].labels[].name] | group_by(.) | map({label: .[0], count: length})'

# High priority issues
gh issue list --state open --label "priority/P0" --label "priority/P1"

# Stale issues (no activity 30+ days)
gh issue list --state open --json number,title,updatedAt | jq '[.[] | select(.updatedAt < (now - 2592000 | todate))]'
```

### 7.2 Backlog Metrics

| Metric              | Count | Action     |
| ------------------- | ----- | ---------- |
| Total open issues   | ☐     | Track      |
| P0/P1 bugs          | ☐     | Prioritize |
| Stale issues (30d+) | ☐     | Triage     |
| Good first issues   | ☐     | Maintain   |

---

## Phase 8: Health Summary Report

### Overall Health Score

| Area          | Status                   | Notes |
| ------------- | ------------------------ | ----- |
| Tests         | ☐ Pass / ☐ Fail          |       |
| Dependencies  | ☐ Healthy / ☐ Outdated   |       |
| Code Quality  | ☐ Good / ☐ Needs Work    |       |
| Performance   | ☐ Baseline / ☐ Regressed |       |
| Documentation | ☐ Current / ☐ Stale      |       |
| CI/CD         | ☐ Stable / ☐ Flaky       |       |
| Backlog       | ☐ Manageable / ☐ Growing |       |

### Action Items

Generate a list of actionable items discovered during the health check:

1. **Immediate** (P0): Security issues, broken tests
2. **This Sprint** (P1): Outdated major deps, high-priority bugs
3. **Backlog** (P2+): Tech debt, minor improvements

---

## Quick Reference

```bash
# Quick health check (tests + lint)
./gradlew clean build test detekt

# Dependency check only
./gradlew dependencyUpdates

# Issue backlog summary
gh issue list --state open --json labels --jq '[.[].labels[].name] | group_by(.) | map({(.[0]): length}) | add'
```
