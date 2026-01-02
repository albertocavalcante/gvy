---
description: Deterministic protocol for fetching GitHub issue details, analyzing complexity, planning, and executing the implementation.
---

# /solve

A strict, phased workflow for solving GitHub issues. This protocol ensures thorough understanding before implementation and optimizes for agent efficiency based on issue complexity.

## Ironclad Rules

1. **NEVER CODE WITHOUT A PLAN**: Always create an implementation plan before writing code.
2. **ISSUE IS TRUTH**: Read the full issue including all comments before planning.
3. **SIZE DETERMINES STRATEGY**: Single-agent for small issues, multi-phase for large.
4. **VERIFY AGAINST ISSUE**: Implementation must address every acceptance criterion.
5. **DOCUMENT DEVIATIONS**: If you deviate from the issue's proposal, explain why.

---

## Phase 1: Issue Ingestion

### 1.1 Fetch Issue Details

```bash
# Fetch full issue body and metadata
gh issue view <ISSUE_NUMBER> --json title,body,labels,comments,createdAt,author > /tmp/issue-<ISSUE_NUMBER>.json

# Display summary
jq '{title, author: .author.login, labels: [.labels[].name], commentCount: (.comments | length)}' /tmp/issue-<ISSUE_NUMBER>.json
```

### 1.2 Read Issue Comments

```bash
# If comments exist, read them for additional context
jq '.comments[] | {author: .author.login, body: .body, createdAt}' /tmp/issue-<ISSUE_NUMBER>.json
```

### 1.3 Extract Key Information

Create a mental checklist:
- [ ] **Problem Statement**: What is broken or missing?
- [ ] **Acceptance Criteria**: What does "done" look like?
- [ ] **Proposed Approach**: Does the issue suggest a solution?
- [ ] **Affected Files/Modules**: What areas of code are impacted?
- [ ] **Dependencies**: Are there blocking issues or PRs?

---

## Phase 2: Complexity Assessment

### Decision Matrix

| Indicator | Single-Agent (size/XS-M) | Multi-Phase (size/L-XL) |
|-----------|--------------------------|-------------------------|
| **Files Changed** | ≤5 files | >5 files |
| **Modules Affected** | 1 module | 2+ modules |
| **New APIs/Interfaces** | None | Yes |
| **Cross-Cutting Concerns** | No | Yes (refactoring, migrations) |
| **Estimated LOC** | <300 lines | >300 lines |
| **Test Requirements** | Unit tests only | Unit + Integration + E2E |

### 2.1 Label-Based Sizing

Extract from issue labels:
```bash
jq '.labels[] | select(.name | startswith("size/")) | .name' /tmp/issue-<ISSUE_NUMBER>.json
```

| Label | Estimated Effort | Strategy |
|-------|------------------|----------|
| `size/XS` | <2 hours | Single-agent, single PR |
| `size/S` | 2-4 hours | Single-agent, single PR |
| `size/M` | 1-2 days | Single-agent, may need user review |
| `size/L` | 1-2 weeks | **Multi-phase**, plan per phase |
| `size/XL` | 2+ weeks | **Multi-phase**, defer some phases |

### 2.2 Complexity Output

Document your assessment:
```markdown
## Complexity Assessment
- **Size**: [XS/S/M/L/XL]
- **Strategy**: [Single-Agent | Multi-Phase]
- **Phases**: [List if multi-phase]
- **Risk Factors**: [Any blockers or unknowns]
```

---

## Phase 3: Codebase Reconnaissance

### 3.1 Locate Affected Code

```bash
# Find files mentioned in issue or comments (replace <EXTENSION> as needed, e.g., kt, groovy)
grep -r "pattern_from_issue" --include="*.<EXTENSION>" -l

# Find related tests (replace <EXTENSION> as needed)
find . -path "*/test/*" -name "*RelatedFeature*Test.<EXTENSION>"
```

### 3.2 Understand Current Implementation

- View file outlines for affected files
- Read existing tests to understand expected behavior
- Identify integration points and dependencies

### 3.3 Document Findings

```markdown
## Codebase Analysis
- **Files to Modify**: [list with paths]
- **Files to Create**: [list with paths]
- **Files to Delete**: [list with paths]
- **Existing Tests**: [list test files]
- **Test Gaps**: [what's not covered]
```

---

## Phase 4: Implementation Planning

### 4.1 Single-Agent Plan (size/XS-M)

For small/medium issues, create a focused plan:

```markdown
# Implementation Plan for #<ISSUE_NUMBER>

## Goal
[One-sentence summary]

## Changes
1. [File1]: [What changes]
2. [File2]: [What changes]

## Verification
- [ ] `./gradlew :module:test` passes
- [ ] New tests for [feature]
```

### 4.2 Multi-Phase Plan (size/L-XL)

For large issues, create atomic phases that can be executed independently:

```markdown
# Implementation Plan for #<ISSUE_NUMBER>

## Goal
[One-sentence summary]

## Phase 1: [Name] (size/S)
### Changes
- [File changes]

### Verification
- [How to verify this phase independently]

### Dependencies
- None | Phase 0

---

## Phase 2: [Name] (size/M)
### Changes
- [File changes]

### Verification
- [How to verify]

### Dependencies
- Phase 1

---

[Continue for each phase]
```

> [!IMPORTANT]
> Each phase in a multi-phase plan MUST be independently verifiable and committable.
> This allows for incremental PR reviews and reduces blast radius.

---

## Phase 5: User Approval

Before executing any code changes:

1. **Write Plan to Artifact**: Save implementation plan to `.agent/out/implementation_plan.md`
2. **Request Review**: Use `notify_user` with `PathsToReview` pointing to the plan
3. **Wait for Approval**: Do NOT proceed until user approves or provides feedback
4. **Iterate if Needed**: Update plan based on feedback, re-request review

---

## Phase 6: Execution

### 6.1 Single-Agent Execution

For size/XS-M issues:
1. Create worktree (if not already done)
2. Implement all changes
3. Run verification commands
4. Commit with descriptive message
5. Push and create PR

### 6.2 Multi-Phase Execution

For size/L-XL issues:
1. Execute Phase 1 completely
2. Verify Phase 1 independently
3. Commit Phase 1 with `Phase 1/N:` prefix
4. **Consider**: Create interim PR for early review
5. Proceed to Phase 2
6. Repeat until all phases complete

### Commit Message Format

```text
<type>(<scope>): <description>

Phase <N>/<Total>: <Phase Name>

- Change 1
- Change 2

Fixes #<ISSUE_NUMBER>
```

---

## Phase 7: Verification

### 7.1 Automated Verification

Run all tests specified in the plan:
```bash
./gradlew :affected-module:test
./gradlew :tests:e2eTest -Dgroovy.lsp.e2e.filter=<relevant-scenario>
```

### 7.2 Manual Verification

If applicable, verify behavior manually:
- Start the language server
- Test in VS Code with sample files
- Verify edge cases mentioned in the issue

### 7.3 Documentation Update

If the change affects user-facing behavior:
- Update README or relevant docs
- Add/update API documentation

---

## Phase 8: Submission

### 8.1 Create PR

```bash
PR_BODY_FILE=$(mktemp)
cat > "$PR_BODY_FILE" <<EOF
Fixes #<ISSUE_NUMBER>

## Summary
[Brief description]

## Changes
- [List of changes]

## Testing
- [How it was tested]
EOF

gh pr create --title "<type>(<scope>): <description>" \
  --body-file "$PR_BODY_FILE" \
  --label "<appropriate-labels>"
rm "$PR_BODY_FILE"
```

### 8.2 Link to Issue

Ensure the PR body contains `Fixes #<ISSUE_NUMBER>` for auto-closing.

---

## Quick Reference: Workflow Selection

| Issue Size | Approach | Key Actions |
|------------|----------|-------------|
| **XS-S** | Fast Track | Read → Plan (brief) → Implement → PR |
| **M** | Standard | Read → Plan → User Review → Implement → PR |
| **L-XL** | Multi-Phase | Read → Phase Plan → User Review → Implement Phase 1 → Verify → ... → Final PR |

## Anti-Patterns to Avoid

1. ❌ **Coding before planning**: Always create a plan first
2. ❌ **Massive PRs**: Large issues should yield multiple atomic commits/PRs
3. ❌ **Ignoring issue comments**: Comments often contain critical context
4. ❌ **Skipping verification**: Every change must be tested
5. ❌ **Silent deviations**: If you change the approach, document why
