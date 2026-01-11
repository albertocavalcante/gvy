---
description: Quick QA verification scoped to current PR changes
---

# /qa-pr

<purpose>
A fast, focused quality assurance workflow scoped to the current PR's changes.
Use this for rapid verification before requesting review or after addressing feedback.
</purpose>

<when_to_use>

- Before requesting PR review
- After addressing review feedback
- Quick sanity check on changes
- When full `/qa` is overkill

</when_to_use>

<duration>
Target: 5-15 minutes (vs hours for full `/qa`)
</duration>

---

## Phase 0: Identify Changes

### 0.1 Get Changed Files

```bash
# Get list of files changed in this PR
git diff --name-only origin/main...HEAD

# Get summary of changes
git diff --stat origin/main...HEAD
```

### 0.2 Identify Affected Modules

```bash
# Categorize changes by module
git diff --name-only origin/main...HEAD | cut -d'/' -f1-2 | sort -u
```

---

## Phase 1: Targeted Lint Check

<critical>
Only check lint on changed files, not the entire codebase.
</critical>

### 1.1 Run Lint on Changed Files

```bash
# Get changed Kotlin files
CHANGED_KT=$(git diff --name-only origin/main...HEAD | grep '\.kt$' | tr '\n' ' ')

# Run detekt on changed files only (if any)
if [ -n "$CHANGED_KT" ]; then
  ./gradlew detekt --include "$CHANGED_KT"
fi
```

### 1.2 Check for New Warnings

```bash
# Compare lint baseline
git diff origin/main...HEAD -- '**/detekt-baseline.xml'
```

<checkpoint>
**GATE 1**: No new lint warnings introduced by this PR.
</checkpoint>

---

## Phase 2: Affected Tests

### 2.1 Identify Related Tests

```bash
# Find test files related to changed source files
for src in $(git diff --name-only origin/main...HEAD | grep -E '\.kt$|\.groovy$'); do
  # Convert src path to test path pattern
  test_pattern=$(echo "$src" | sed 's|/main/|/test/|' | sed 's|\.kt$|Test.kt|' | sed 's|\.groovy$|Test.groovy|')
  find . -path "*$test_pattern" 2>/dev/null
done
```

### 2.2 Run Affected Tests

```bash
# Run tests for affected modules only
AFFECTED_MODULES=$(git diff --name-only origin/main...HEAD | grep -E '^[^/]+/' | cut -d'/' -f1 | sort -u)

for module in $AFFECTED_MODULES; do
  if [ -d "$module" ]; then
    ./gradlew ":$module:test" --console=plain
  fi
done
```

<checkpoint>
**GATE 2**: All tests in affected modules pass.
</checkpoint>

---

## Phase 3: PR Description Verification

### 3.1 Cross-Reference Changes

Verify that the PR description accurately reflects the actual changes:

```bash
# Show PR description
gh pr view --json title,body

# Compare with actual changes
git log origin/main...HEAD --oneline
git diff --stat origin/main...HEAD
```

### 3.2 Checklist

| Verification                                    | Status |
| ----------------------------------------------- | ------ |
| PR title follows conventional commit format     | ☐      |
| PR body describes the "why" not just the "what" | ☐      |
| All changed files are mentioned or implied      | ☐      |
| Breaking changes are documented (if any)        | ☐      |
| Related issues are linked                       | ☐      |

---

## Phase 4: Quick Smoke Test

<critical>
Only if PR touches core functionality. Skip for docs/config changes.
</critical>

### 4.1 Build Verification

```bash
# Ensure it builds
./gradlew assemble -x test
```

### 4.2 Minimal Functional Check

If changes affect LSP features, perform ONE quick verification:

1. Start the language server
2. Open a test Groovy file
3. Verify the changed feature works
4. Close and confirm clean shutdown

---

## Phase 5: Summary

### Quality Gates (PR-Scoped)

| Gate   | Criteria                   | Status |
| ------ | -------------------------- | ------ |
| **G1** | No new lint warnings       | ☐      |
| **G2** | Affected module tests pass | ☐      |
| **G3** | PR description accurate    | ☐      |
| **G4** | Build succeeds             | ☐      |

<note>
This is a subset of full `/qa`. For release verification, use `/qa` instead.
</note>

---

## Quick Reference

```bash
# One-liner for basic PR QA
./gradlew assemble test -x e2eTest && echo "PR QA passed"

# Check what would be tested
git diff --name-only origin/main...HEAD | head -20
```
