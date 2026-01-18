---
description: Proactive bug discovery through systematic codebase analysis
---

# /bughunt

<purpose>
A proactive bug discovery workflow that systematically searches for potential issues
before they manifest as user-reported bugs. Use this for quality sweeps and tech debt discovery.
</purpose>

<when_to_use>

- Periodic quality sweeps (monthly recommended)
- Before major releases
- When onboarding to unfamiliar code
- After receiving reports of "mysterious" issues
- Tech debt assessment

</when_to_use>

<output>
Markdown report with categorized findings and severity estimates.
</output>

---

## Phase 1: Static Analysis Deep Dive

### 1.1 Run Extended Lint Checks

```bash
# Run detekt with all rules enabled
./gradlew detekt --console=plain

# Capture full report
./gradlew detekt --console=plain 2>&1 | tee /tmp/detekt-report.txt
```

### 1.2 Find Suppressed Warnings

<critical>
Suppressed warnings often hide real issues. Each suppression should be justified.
</critical>

```bash
# Find all suppressions
grep -rn "@Suppress" --include="*.kt" | head -30

# Count by suppression type
grep -rn "@Suppress" --include="*.kt" | sed 's/.*@Suppress("\([^"]*\)").*/\1/' | sort | uniq -c | sort -rn
```

### 1.3 Categorize Findings

| Finding Type        | Count | Priority |
| ------------------- | ----- | -------- |
| Potential bugs      | ☐     | P1       |
| Code smells         | ☐     | P2       |
| Suppressed warnings | ☐     | Review   |
| Style violations    | ☐     | P3       |

---

## Phase 2: Known Fragile Areas

<critical>
Check areas documented in `/qa` as historically fragile.
</critical>

### 2.1 Parser Edge Cases

```bash
# Check parser error handling
grep -rn "catch\|try\|throw" --include="*.kt" groovy-lsp/src/main/kotlin/*/parser/ | wc -l

# Look for null-unsafe patterns in parser
grep -rn "!!\|as [A-Z]" --include="*.kt" groovy-lsp/src/main/kotlin/*/parser/ | head -20
```

### 2.2 Cache Coherency

```bash
# Find cache-related code
grep -rn "cache\|Cache\|invalidate\|evict" --include="*.kt" | head -30

# Check for potential stale cache issues
grep -rn "get.*cache\|cache.*get" --include="*.kt" -A 3 | head -40
```

### 2.3 Concurrent Operations

```bash
# Find potential race conditions
grep -rn "synchronized\|@Synchronized\|Lock\|Mutex" --include="*.kt" | wc -l

# Find unsynchronized shared state
grep -rn "companion object\|lateinit var\|var [a-z]*:" --include="*.kt" | grep -v "private" | head -20
```

### 2.4 Fragile Area Findings

| Area        | Potential Issues | Severity |
| ----------- | ---------------- | -------- |
| Parser      | ☐                |          |
| Cache       | ☐                |          |
| Concurrency | ☐                |          |
| File I/O    | ☐                |          |

---

## Phase 3: Code Smell Detection

### 3.1 Null Handling

```bash
# Find force unwraps (potential NPEs)
grep -rn "!!" --include="*.kt" | wc -l
grep -rn "!!" --include="*.kt" | head -20

# Find unsafe casts
grep -rn " as [A-Z]" --include="*.kt" | grep -v " as? " | head -20
```

### 3.2 Resource Leaks

```bash
# Find resources that might not be closed
grep -rn "FileInputStream\|FileOutputStream\|BufferedReader\|BufferedWriter" --include="*.kt" | head -20

# Check for use() pattern (proper resource handling)
grep -rn "\.use\s*{" --include="*.kt" | wc -l
```

### 3.3 Error Handling

```bash
# Find empty catch blocks
grep -rn "catch.*{" --include="*.kt" -A 1 | grep -B 1 "^[^}]*}$" | head -20

# Find catch-all patterns
grep -rn "catch\s*(\s*e\s*:\s*Exception\s*)" --include="*.kt" | head -20
grep -rn "catch\s*(\s*_\s*:\s*Throwable\s*)" --include="*.kt" | head -10
```

### 3.4 Code Smell Findings

| Smell                | Count | Examples | Priority |
| -------------------- | ----- | -------- | -------- |
| Force unwraps (!!)   | ☐     |          | P1       |
| Unsafe casts         | ☐     |          | P1       |
| Resource leaks       | ☐     |          | P1       |
| Empty catch blocks   | ☐     |          | P2       |
| Catch-all exceptions | ☐     |          | P2       |

---

## Phase 4: Dependency Vulnerabilities

### 4.1 Security Scan

```bash
# Check for known CVEs
./gradlew dependencyCheckAnalyze --info 2>&1 | grep -E "CVE|CRITICAL|HIGH" | head -20

# Node dependencies
cd editors/code && pnpm audit --json 2>/dev/null | jq '.vulnerabilities | to_entries | map({name: .key, severity: .value.severity})' || true
```

### 4.2 Outdated with Known Issues

```bash
# Check dependency updates
./gradlew dependencyUpdates -Drevision=release 2>&1 | grep -E "->|available" | head -30
```

### 4.3 Dependency Findings

| Dependency | Issue | Severity | Action |
| ---------- | ----- | -------- | ------ |
| ☐          | ☐     | ☐        |        |

---

## Phase 5: Test Gap Analysis

### 5.1 Coverage Hotspots

```bash
# Find complex files without tests
for file in $(find . -name "*.kt" -path "*/main/*" | head -20); do
  test_file=$(echo "$file" | sed 's|/main/|/test/|' | sed 's|\.kt$|Test.kt|')
  if [ ! -f "$test_file" ]; then
    echo "Missing test: $file"
  fi
done
```

### 5.2 Untested Edge Cases

```bash
# Find TODO comments about tests
grep -rn "TODO.*test\|FIXME.*test" --include="*.kt" | head -20

# Find @Ignore or @Disabled tests
grep -rn "@Ignore\|@Disabled" --include="*.kt" | head -10
```

### 5.3 Test Gap Findings

| File/Area | Coverage | Priority |
| --------- | -------- | -------- |
| ☐         | Low/None | ☐        |

---

## Phase 6: API Contract Issues

### 6.1 Breaking Changes Risk

```bash
# Find public APIs that might break
grep -rn "public fun\|public class\|public interface" --include="*.kt" | wc -l

# Find APIs without docs
grep -rn "public fun" --include="*.kt" -B 1 | grep -v "^\-\-$" | grep -v "/\*\*" | head -20
```

### 6.2 Deprecation Without Migration

```bash
# Find deprecated without replacement
grep -rn "@Deprecated" --include="*.kt" -A 2 | grep -v "replaceWith" | head -20
```

---

## Phase 7: Generate Bug Hunt Report

### Summary Template

```markdown
# Bug Hunt Report - [DATE]

## Executive Summary

- **Total Issues Found**: X
- **Critical (P0)**: X
- **High (P1)**: X
- **Medium (P2)**: X
- **Low (P3)**: X

## Critical Issues (Immediate Action)

1. [Issue description]
   - Location: [file:line]
   - Evidence: [code snippet]
   - Suggested fix: [description]

## High Priority Issues

[...]

## Medium Priority Issues

[...]

## Observations

[General patterns, trends, recommendations]

## Action Items

- [ ] Create GitHub issues for P0/P1 findings
- [ ] Add to tech debt backlog for P2/P3
- [ ] Update fragile areas documentation
```

---

## Quick Reference

```bash
# Quick bug hunt (most common issues)
grep -rn "!!\|catch.*Exception" --include="*.kt" | head -50

# Find all suppressions
grep -rn "@Suppress" --include="*.kt" | wc -l

# Check for resource handling
grep -rn "FileInputStream\|use\s*{" --include="*.kt" | head -30
```

---

## Follow-up Actions

After completing the bug hunt:

1. **For P0/P1 issues**: Use `/bugreport` to create GitHub issues immediately
2. **For P2/P3 issues**: Use `/defer` to add to backlog with proper tracking
3. **Update documentation**: Add new fragile areas to `/qa` regression checklist
4. **Schedule fixes**: Prioritize in next sprint planning
