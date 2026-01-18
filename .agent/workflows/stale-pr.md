---
description: Deterministic workflow for reviewing potentially stale PRs and deciding on close, supersede, or extract actions
---

# /stale-pr - Stale PR Review Workflow

<purpose>
A STRICT, DETERMINISTIC workflow for reviewing PRs that may be stale.
Determines whether to CLOSE (stale), SUPERSEDE (rewrite needed), CHERRY-PICK (extract valuable parts),
or MERGE (still relevant). Ensures valuable concepts are preserved as issues before closing.
</purpose>

<ironclad_rules>

1. **NEVER CLOSE WITHOUT ANALYSIS** - Every PR must go through the full decision tree
2. **PRESERVE VALUE** - Extract valuable concepts as issues before closing
3. **COMMENT WITH RATIONALE** - Always explain why a PR is being closed
4. **QUANTIFY DIVERGENCE** - Use concrete metrics (commits behind, files changed, etc.)
5. **LOCAL CHECKOUT FOR LARGE PRs** - PRs with 10+ files MUST be checked out locally

</ironclad_rules>

---

## Phase 0: Initial Assessment (REMOTE ONLY)

<critical>
Start with remote analysis. Only checkout locally if needed (Phase 2).
This phase determines the scale of investigation required.
</critical>

### 0.1 Get PR Metadata

```bash
gh pr view <PR_NUMBER> --json title,body,createdAt,updatedAt,headRefName,baseRefName,state,author,commits \
  --jq '{
    title,
    created: .createdAt,
    updated: .updatedAt,
    branch: .headRefName,
    base: .baseRefName,
    author: .author.login,
    commits: (.commits | length)
  }'
```

### 0.2 Calculate Divergence

```bash
# How many commits is main ahead of the PR branch?
gh api repos/{owner}/{repo}/compare/{head_branch}...main --jq '.ahead_by'

# PR age in days
gh pr view <PR_NUMBER> --json createdAt --jq '
  (now - (.createdAt | fromdateiso8601)) / 86400 | floor
'
```

### 0.3 Get File Change Summary

```bash
# File count and summary
gh pr view <PR_NUMBER> --json files --jq '{
  total_files: (.files | length),
  by_status: (.files | group_by(.status) | map({status: .[0].status, count: length})),
  total_additions: ([.files[].additions] | add),
  total_deletions: ([.files[].deletions] | add)
}'
```

### 0.4 Initial Classification

<decision_tree id="scale-classification">

```
┌─────────────────────────────────────────────────────────────┐
│                    SCALE CLASSIFICATION                      │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Q1: How many files changed?                                │
│      ├─ 1-9 files   → SMALL PR (Phase 1 remote analysis)   │
│      └─ 10+ files   → LARGE PR (Phase 2 local checkout)    │
│                                                             │
│  Q2: How far behind is the PR?                              │
│      ├─ 0-50 commits  → RECENT (likely rebasable)          │
│      ├─ 51-200 commits → STALE (needs investigation)       │
│      └─ 200+ commits   → VERY STALE (likely close)         │
│                                                             │
│  Q3: PR age?                                                │
│      ├─ < 30 days   → FRESH                                │
│      ├─ 30-90 days  → AGING                                │
│      └─ 90+ days    → OLD                                  │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

</decision_tree>

**Record these metrics** - they inform all subsequent decisions.

---

## Phase 1: Remote Content Analysis (SMALL PRs or Pre-Local)

### 1.1 List Changed Files

```bash
gh pr view <PR_NUMBER> --json files --jq '.files[].path'
```

### 1.2 Check if Files Still Exist on Main

For each key file from the PR:

```bash
# Check if file exists on main
gh api repos/{owner}/{repo}/contents/{file_path}?ref=main --jq '.name' 2>/dev/null || echo "FILE_REMOVED"
```

### 1.3 Compare Key Files (for small PRs)

```bash
# Get file content from PR branch
gh api repos/{owner}/{repo}/contents/{file_path}?ref={pr_branch} --jq '.content' | base64 -d > /tmp/pr-version.txt

# Get file content from main
gh api repos/{owner}/{repo}/contents/{file_path}?ref=main --jq '.content' | base64 -d > /tmp/main-version.txt

# Compare
diff /tmp/pr-version.txt /tmp/main-version.txt
```

### 1.4 Quick Architecture Check

Look for signs of major architecture changes:

```bash
# Check if directories from PR still exist
gh api repos/{owner}/{repo}/contents/{directory_path}?ref=main --jq '.[].name' 2>/dev/null || echo "DIRECTORY_REMOVED"

# Check module structure (if multi-module project)
gh api repos/{owner}/{repo}/contents/?ref=main --jq '[.[] | select(.type=="dir") | .name]'
```

---

## Phase 2: Local Checkout Analysis (LARGE PRs)

<critical>
For PRs with 10+ files or complex changes, local checkout is MANDATORY.
Remote API calls are too slow and token-intensive for large PRs.
</critical>

### 2.1 Create Review Worktree

```bash
# Check available slots
wt

# Create worktree for review
wt new pr-<PR_NUMBER>-review

# Checkout the PR branch
cd w<N>
git fetch origin <pr_branch>
git checkout <pr_branch>
```

### 2.2 Measure Divergence Locally

```bash
# Commits PR is behind main
git rev-list --count HEAD..main

# Commits PR is ahead of merge-base
git rev-list --count $(git merge-base HEAD main)..HEAD

# Files that would conflict on merge
git merge-tree $(git merge-base HEAD main) HEAD main 2>&1 | grep -c "CONFLICT" || echo "0"
```

### 2.3 Compare Architecture

```bash
# Diff stats between PR and main
git diff --stat main...HEAD | tail -5

# Check for moved/renamed files
git diff --name-status main...HEAD | grep -E "^R"

# Find files from PR that don't exist on main
git diff --name-only main...HEAD | while read f; do
  git show main:"$f" 2>/dev/null || echo "MISSING: $f"
done
```

### 2.4 Search for Functionality on Main

```bash
# For each key feature in PR, search if it exists on main
cd /path/to/main/worktree

# Search for specific patterns/classes/functions
rg "ClassName|functionName|FEATURE_FLAG" --type kt

# Check if the fix/feature exists differently
rg -A5 "the specific fix pattern" src/
```

### 2.5 Document Findings

Create a structured analysis:

| Category  | PR Content | Main Status                | Verdict  |
| --------- | ---------- | -------------------------- | -------- |
| Feature A | `file.kt`  | Already in `other/file.kt` | STALE    |
| Feature B | `new.kt`   | Does not exist             | VALUABLE |
| Docs      | `doc.md`   | Outdated architecture      | EXTRACT  |

---

## Phase 3: Decision Matrix

<decision_tree id="final-decision">

```
┌─────────────────────────────────────────────────────────────┐
│                      FINAL DECISION                          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Q1: Is ALL code from PR already on main (same or better)?  │
│      ├─ YES → Go to Q2                                      │
│      └─ NO  → Go to Q3                                      │
│                                                             │
│  Q2: Is there valuable documentation/concepts to extract?   │
│      ├─ YES → CREATE ISSUES then CLOSE                      │
│      └─ NO  → CLOSE (fully superseded)                      │
│                                                             │
│  Q3: Does the PR's approach still make sense for main?      │
│      ├─ NO (architecture changed) → Go to Q4                │
│      └─ YES → Go to Q5                                      │
│                                                             │
│  Q4: Are there valuable concepts worth preserving?          │
│      ├─ YES → CREATE ISSUES then CLOSE                      │
│      └─ NO  → CLOSE (obsolete approach)                     │
│                                                             │
│  Q5: How much work to make it mergeable?                    │
│      ├─ Minor (rebase + small fixes) → REBASE & MERGE       │
│      ├─ Medium (significant rework) → SUPERSEDE with new PR │
│      └─ Major (complete rewrite) → CREATE ISSUES then CLOSE │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

</decision_tree>

### Decision Definitions

| Decision        | When to Use                                  | Actions                                                      |
| --------------- | -------------------------------------------- | ------------------------------------------------------------ |
| **CLOSE**       | All content stale/superseded                 | Create issues for concepts, comment with rationale, close PR |
| **SUPERSEDE**   | Core idea valid but needs new implementation | Create new branch, implement fresh, reference original PR    |
| **CHERRY-PICK** | Some changes valuable, others stale          | Extract valuable commits to new PR, close original           |
| **MERGE**       | PR still valid, just needs rebase            | Rebase, fix conflicts, merge                                 |

---

## Phase 4: Extract Value (Before Closing)

<critical>
NEVER close a PR without first extracting valuable concepts as issues.
Even "stale" PRs may contain research, documentation, or ideas worth preserving.
</critical>

### 4.1 Identify Extractable Value

Categories to check:

1. **Documentation** - Comprehensive docs that could be updated for current architecture
2. **Research** - Analysis, comparisons, or investigations that inform future work
3. **Test cases** - Test scenarios or edge cases not covered on main
4. **Feature ideas** - Partially implemented features worth completing differently
5. **Bug descriptions** - Well-documented bugs even if fix is stale

### 4.2 Create Issues for Each Valuable Concept

Use the `/defer` workflow pattern:

```bash
cat > /tmp/issue-body.md << 'EOF'
## Summary
[What the concept/feature is]

## Context
Extracted from stale PR #<PR_NUMBER> which [brief description of PR].

## Suggested Approach
[How this could be implemented in current architecture]

## Original PR Content
[Relevant excerpts or links to specific files in the PR]

## Why Original PR is Stale
[Brief explanation - architecture changed, already implemented differently, etc.]
EOF

gh issue create -R {owner}/{repo} \
  --title "[area] Brief description of concept" \
  --body-file /tmp/issue-body.md \
  --label "enhancement" --label "extracted-from-pr"

rm /tmp/issue-body.md
```

### 4.3 Track Created Issues

Keep a list of all issues created:

```
- #NNN - Description 1
- #NNN - Description 2
```

---

## Phase 5: Close with Rationale

<critical>
The closing comment MUST include:
1. Clear explanation of why the PR is stale
2. List of what was already merged/exists on main
3. Links to all issues created from valuable content
4. Acknowledgment of the contributor's work
</critical>

### 5.1 Comment Template

```markdown
## Closing as Stale

This PR is **[N] commits behind main** and the codebase has evolved significantly since [date].

### Code Changes - Status

| Component   | PR Status           | Main Status                      |
| ----------- | ------------------- | -------------------------------- |
| [Feature A] | [PR implementation] | [Already in `path/to/file.kt`]   |
| [Feature B] | [PR approach]       | [Replaced by different approach] |

### Why This PR is Stale

[Detailed explanation of architecture changes, refactoring, etc.]

### Valuable Concepts Extracted

The following issues capture valuable ideas from this PR:

- #NNN - [Description]
- #NNN - [Description]

---

Thank you for the work on this PR! [Specific acknowledgment of what was valuable]
```

### 5.2 Close the PR

```bash
# First, create the comment file from the template above
cat > /tmp/pr-comment.md << 'EOF'
## Closing as Stale

This PR is **[N] commits behind main** and the codebase has evolved significantly since [date].

### Code Changes - Status

| Component   | PR Status           | Main Status                      |
| ----------- | ------------------- | -------------------------------- |
| [Feature A] | [PR implementation] | [Already in `path/to/file.kt`]   |
| [Feature B] | [PR approach]       | [Replaced by different approach] |

### Why This PR is Stale

[Detailed explanation of architecture changes, refactoring, etc.]

### Valuable Concepts Extracted

The following issues capture valuable ideas from this PR:

- #NNN - [Description]
- #NNN - [Description]

---

Thank you for the work on this PR! [Specific acknowledgment of what was valuable]
EOF

# Post the comment and close the PR
gh pr comment <PR_NUMBER> --body-file /tmp/pr-comment.md
gh pr close <PR_NUMBER>

# Clean up temp file
rm /tmp/pr-comment.md
```

### 5.3 Cleanup

```bash
# Release the review worktree if created
wt release w<N>

# Or if using standard worktree:
git worktree remove /path/to/worktree
```

---

## Quick Reference: Common Patterns

### Pattern 1: Complete Architecture Change

**Symptoms:**

- Files from PR don't exist on main
- Main has different module structure
- 200+ commits behind

**Action:** CLOSE with issues for valuable docs/concepts

### Pattern 2: Feature Already Implemented Differently

**Symptoms:**

- Same problem solved on main
- Different file locations
- Similar functionality, different approach

**Action:** CLOSE, acknowledge in comment that feature exists

### Pattern 3: Partially Valuable

**Symptoms:**

- Some files stale, others not on main
- Mix of code and documentation
- 50-200 commits behind

**Action:** Create issues for valuable parts, CLOSE original

### Pattern 4: Just Needs Rebase

**Symptoms:**

- < 50 commits behind
- Files still exist in same locations
- No major conflicts

**Action:** REBASE & MERGE (or ask contributor to rebase)

---

## Checklist Before Closing

- [ ] Calculated divergence metrics (commits behind, age)
- [ ] Verified files/functionality status on main
- [ ] Identified all valuable concepts
- [ ] Created issues for extractable value
- [ ] Wrote detailed closing comment with rationale
- [ ] Linked all created issues in comment
- [ ] Acknowledged contributor's work
- [ ] Closed PR
- [ ] Cleaned up review worktree
