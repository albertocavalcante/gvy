---
description: Review open PRs, address feedback, and verify CI status
---
// turbo-all

# PR Review Workflow

Systematic review of open pull requests, addressing reviewer feedback, and verifying CI health.

## Phase 1: List and Prioritize PRs

### 1.1 Get All Open PRs (Summary View)
```bash
gh pr list --json number,title,state,author,reviewDecision,createdAt --jq '.[] | "\(.number): \(.title) [\(.reviewDecision // "PENDING")]"'
```

### 1.2 Get Detailed PR Status with CI
```bash
gh pr view PR_NUMBER --json state,statusCheckRollup --jq '{state: .state, checks: [.statusCheckRollup[]? | {name: .name, status: .status, conclusion: .conclusion}]}'
```

---

## Phase 2: Review Comments and Feedback

### 2.1 Get PR Review Comments (Inline Code Comments)
```bash
gh api repos/{owner}/{repo}/pulls/PR_NUMBER/comments --jq '.[] | {author: .user.login, path: .path, line: .line, body: .body}'
```

### 2.2 Get PR Level Reviews (Approve/Request Changes)
```bash
gh pr view PR_NUMBER --json reviews --jq '.reviews[] | {author: .author.login, state: .state, body: .body}'
```

### 2.3 Get Conversation Comments
```bash
gh pr view PR_NUMBER --json comments --jq '.comments[] | {author: .author.login, body: .body}'
```

---

## Phase 3: Address Feedback

### 3.1 Checkout PR Branch
```bash
git checkout BRANCH_NAME
```

### 3.2 Make Required Changes
After making code changes, verify:
```bash
# Compile check
./gradlew compileKotlin --no-daemon

# Run relevant tests
./gradlew :MODULE:test --no-daemon
```

### 3.3 Commit with Reference to Feedback
```bash
git commit -m "chore: address reviewer feedback

- Point 1 from review
- Point 2 from review"
```

### 3.4 Push and Verify CI
```bash
git push origin BRANCH_NAME
```

---

## Phase 4: Verify CI Status

### 4.1 Check CI Run Status
```bash
# Get latest run for the branch
gh run list --branch BRANCH_NAME --limit 1 --json databaseId,status,conclusion

# View detailed job status
gh run view RUN_ID --json jobs --jq '.jobs[] | {name: .name, status: .status, conclusion: .conclusion, startedAt: .startedAt, completedAt: .completedAt}'
```

### 4.2 Check Expected Jobs Ran
Expected jobs for code changes:
- `check-paths` → SUCCESS
- `Check Runner Availability` → SUCCESS or SKIPPED (for github-hosted)
- `Build and Test` → SUCCESS (NOT skipped for code changes!)
- `Validation Check` → SUCCESS

### 4.3 Investigate Skipped Build and Test
If `Build and Test` is SKIPPED but shouldn't be:
```bash
# Check paths filter output
gh run view RUN_ID --log 2>/dev/null | grep -E "(filter|run_main_ci)" | head -20

# Verify changed files match CI trigger paths
gh pr view PR_NUMBER --json files --jq '.files[].path'
```

---

## Phase 5: Document Learnings

After reviewing PRs, note any patterns or issues to add to `.agent/rules/`:

| Issue Found | Where to Document |
|------------|------------------|
| Code comment anti-patterns | `.agent/rules/code-quality.md` |
| Git workflow issues | `.agent/rules/git-workflow.md` |
| CI configuration problems | `.github/workflows/ci.yml` + AGENTS.md |
| Recurring lint issues | `.agent/rules/code-quality.md` |

---

## Quick Reference: Common Review Actions

| Reviewer Feedback | Action |
|------------------|--------|
| "Remove metadata from comments" | Move tool-specific info to PR description |
| "Pre-compile regex" | Extract to class/object level constant |
| "Unused variable" | Remove or replace with `_` |
| "Cognitive complexity too high" | Extract helper methods |
| "Add tests" | Follow TDD, add test file first |

---

## Phase 6: Summary Report

After completing the review, produce a summary table for the user:

### Output Format

```markdown
## PR Review Summary

| PR | Title | Comments | Changes Made | Pushed | CI Status | Ready |
|----|-------|----------|--------------|--------|-----------|-------|
| #293 | Extract string constants | 2 | Remove SonarCloud metadata | ✅ | ✅ Pass | ✅ |
| #294 | Decompose JenkinsContextDetector | 1 | Pre-compile regex | ✅ | ✅ Pass | ✅ |
| #295 | Decompose SpockBlockIndex | 0 | - | N/A | ✅ Pass | ✅ |

### Legend
- **Comments**: Number of review comments addressed
- **Changes Made**: Brief description of fixes
- **Pushed**: Whether changes were pushed to remote
- **CI Status**: Latest CI run result
- **Ready**: ✅ = Ready for merge, ⏳ = Pending CI, ❌ = Needs work
```

### Checklist Before Reporting

For each PR, confirm:
- [ ] All inline review comments addressed
- [ ] Changes committed with descriptive message
- [ ] Pushed to remote branch
- [ ] CI run triggered and passed (or pending)
- [ ] No new lint issues introduced

