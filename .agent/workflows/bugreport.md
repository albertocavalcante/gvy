---
description: Create detailed, actionable bug reports for GitHub issues
---

# /bugreport

<purpose>
A systematic workflow for creating high-quality bug reports that enable efficient
debugging and resolution. Use this when you've discovered a bug and need to document it properly.
</purpose>

<when_to_use>

- After discovering a bug during development
- When a user reports an issue that needs documentation
- Following a `/bughunt` session to create issues for findings
- When investigating unexpected behavior

</when_to_use>

<output>
GitHub issue URL with properly structured bug report.
</output>

---

## Phase 1: Symptom Collection

### 1.1 Describe the Problem

Answer these questions:

1. **What happened?** (Actual behavior)
2. **What should have happened?** (Expected behavior)
3. **When did it start?** (Regression? Always broken?)

### 1.2 Capture Error Information

```bash
# Check for recent errors in logs
tail -100 ~/.local/share/groovy-lsp/logs/*.log 2>/dev/null | grep -i "error\|exception" | tail -20

# Check VS Code output panel
# View → Output → Groovy Language Server
```

### 1.3 Collect Symptoms

| Question                                 | Answer |
| ---------------------------------------- | ------ |
| Error message                            | ☐      |
| Frequency (always/sometimes/once)        | ☐      |
| User impact (blocks work/annoying/minor) | ☐      |
| Workaround available                     | ☐      |

---

## Phase 2: Reproduction Steps

<critical>
A bug without reproduction steps is very hard to fix. Invest time here.
</critical>

### 2.1 Create Minimal Reproduction

1. **Start fresh**: New project or clean state
2. **Minimal steps**: Remove anything not needed to trigger the bug
3. **Document each step**: Screenshot or copy exact commands

### 2.2 Reproduction Template

````markdown
## Steps to Reproduce

1. Create a new Groovy file `Test.groovy`
2. Add the following content:
   ```groovy
   [minimal code that triggers the bug]
   ```
````

3. [Trigger action - e.g., "Hover over the method name"]
4. Observe: [What happens]

### Minimal Reproducible Example

[Attach or link to minimal project that reproduces the issue]

````
### 2.3 Verify Reproduction

```bash
# Verify the bug reproduces on clean install
code --disable-extensions --install-extension groovy-language-server-*.vsix

# Test with minimal config
code --user-data-dir /tmp/vscode-clean
````

---

## Phase 3: Environment Capture

### 3.1 Gather System Information

```bash
# System info
uname -a
java -version
node --version

# VS Code info
code --version

# Extension version
cat ~/.vscode/extensions/*/package.json 2>/dev/null | grep -A1 '"name": "groovy-language-server"' | head -5

# Groovy LSP version
java -jar groovy-lsp/build/libs/gls-*-all.jar version 2>/dev/null || echo "Not built locally"
```

### 3.2 Environment Template

```markdown
## Environment

- **OS**: [e.g., macOS 14.0, Ubuntu 22.04, Windows 11]
- **Java**: [e.g., OpenJDK 17.0.9]
- **VS Code**: [e.g., 1.85.0]
- **Extension Version**: [e.g., 0.5.0]
- **Groovy Version**: [e.g., 4.0.15 - if relevant]
```

### 3.3 Relevant Configuration

```bash
# Check for custom settings
cat .vscode/settings.json 2>/dev/null | grep -i groovy

# Check workspace type
ls -la build.gradle* settings.gradle* pom.xml 2>/dev/null | head -5
```

---

## Phase 4: Root Cause Hypothesis

<note>
You don't need to find the root cause, but initial analysis helps prioritize.
</note>

### 4.1 Initial Investigation

```bash
# Search for related code
grep -rn "relevantKeyword" --include="*.kt" | head -20

# Check recent changes to related files
git log --oneline --since="2 weeks ago" -- "**/RelatedFile*"

# Check for similar issues
gh issue list --search "similar keywords" --state all --limit 10
```

### 4.2 Hypothesis Template

```markdown
## Initial Analysis

**Suspected area**: [e.g., Completion provider, Parser, Cache] **Suspected cause**: [e.g., Race condition, Null
handling, Edge case] **Related code**: [Link to file:line if known] **Similar issues**: [Link to related issues if any]
```

---

## Phase 5: Impact Assessment

### 5.1 Severity Classification

| Severity     | Criteria                            | Example                 |
| ------------ | ----------------------------------- | ----------------------- |
| **Critical** | System crash, data loss             | LSP crashes on startup  |
| **High**     | Core feature broken, no workaround  | Completion never works  |
| **Medium**   | Feature degraded, workaround exists | Hover shows wrong type  |
| **Low**      | Minor inconvenience                 | Formatting slightly off |

### 5.2 Impact Template

```markdown
## Impact

**Severity**: [Critical/High/Medium/Low] **Affected users**: [All/Some/Edge case] **Workaround**: [Yes - describe / No]
**Regression**: [Yes - since version X / No / Unknown]
```

---

## Phase 6: Create GitHub Issue

### 6.1 Issue Labels

Select appropriate labels:

```bash
# View available labels
gh label list

# Standard bug labels:
# - bug
# - priority/P0, priority/P1, priority/P2, priority/P3
# - area/lsp-core, area/parser, area/completion, area/vscode
# - size/XS, size/S, size/M, size/L
```

### 6.2 Full Issue Template

````markdown
## Description

[One paragraph describing the bug clearly]

## Steps to Reproduce

1. [Step 1]
2. [Step 2]
3. [Step 3]

## Expected Behavior

[What should happen]

## Actual Behavior

[What actually happens]

## Minimal Reproducible Example

```groovy
[Code that triggers the bug]
```
````

## Environment

- **OS**:
- **Java**:
- **VS Code**:
- **Extension Version**:

## Logs/Screenshots

<details>
<summary>Error logs</summary>

```
[Paste relevant logs here]
```

</details>

## Initial Analysis

**Suspected area**: **Related code**:

## Impact

**Severity**: **Workaround**:

````
### 6.3 Create the Issue

```bash
# Create issue with labels
gh issue create \
  --title "bug(<area>): <brief description>" \
  --body-file /tmp/bug-report.md \
  --label "bug" \
  --label "priority/P2" \
  --label "area/lsp-core"
````

---

## Phase 7: Follow-up

### 7.1 Add Supporting Information

```bash
# Add log file as comment
gh issue comment <ISSUE_NUMBER> --body "$(cat /tmp/debug-logs.txt)"

# Link related issues
gh issue comment <ISSUE_NUMBER> --body "Related to #123"
```

### 7.2 Verification Checklist

| Check                          | Status |
| ------------------------------ | ------ |
| Reproduction steps are clear   | ☐      |
| Environment info is complete   | ☐      |
| Logs/screenshots attached      | ☐      |
| Severity is appropriate        | ☐      |
| Labels are accurate            | ☐      |
| No duplicate of existing issue | ☐      |

---

## Quick Reference

```bash
# Quick issue creation
gh issue create --title "bug: <description>" --label "bug" --web

# Search for duplicates first
gh issue list --search "<keywords>" --state all

# Get issue template
cat .github/ISSUE_TEMPLATE/bug_report.md 2>/dev/null || echo "No template"
```

---

## Integration with Other Workflows

- **After `/bughunt`**: Use this workflow to create issues for findings
- **Before `/solve`**: Ensure the issue has all information needed
- **With `/defer`**: Link TODO comments to the created issue
