---
description: Strict, deterministic workflow for addressing PR review feedback and verifying CI health
---

# /review

// turbo-all

<purpose>
A STRICT, DETERMINISTIC workflow for addressing PR review feedback. This is "God Mode" — every step is explicit, every decision has a rule, every action has verification.
</purpose>

<ironclad_rules>

1. **NEVER IGNORE ANY COMMENT** — Every thread MUST be accounted for
2. **GRAPHQL IS TRUTH** — Use saved queries for authoritative thread state
3. **DIFF FIRST** — ALWAYS read `gh pr diff` before ANY action
4. **TEMP FILES MANDATORY** — Dump ALL API output to `/tmp/pr-<N>-*.json`
5. **REPLY THEN RESOLVE** — Every addressed thread gets a reply AND explicit resolution
6. **NO BROWSER** — `gh` CLI exclusively
7. **VERIFY BEFORE COMMIT** — Run tests for EVERY code change </ironclad_rules>

---

## Phase 0: Understand the PR (MANDATORY FIRST STEP)

<critical>
You MUST understand what the PR does BEFORE looking at review comments.
Skipping this step leads to incorrect fixes and wasted cycles.
</critical>

### 0.1 Get PR Metadata

```bash
gh pr view <PR_NUMBER> --json title,body,headRefName,baseRefName,state,author \
  --jq '{title, body: (.body[:500] + "..."), branch: .headRefName, base: .baseRefName, state, author: .author.login}'
```

### 0.2 Read the Diff (SOURCE OF TRUTH FOR CHANGES)

```bash
# Full diff to file for reference
gh pr diff <PR_NUMBER> > /tmp/pr-<PR_NUMBER>-diff.patch

# Quick summary: files changed
gh pr diff <PR_NUMBER> --name-only

# Stat summary
gh pr view <PR_NUMBER> --json files --jq '.files[] | "\(.path) +\(.additions) -\(.deletions)"'
```

<decision_tree id="diff-analysis"> BEFORE proceeding, answer these questions by reading the diff:

1. What is the PRIMARY change? (feature/fix/refactor/test)
2. Which files are CORE to the change vs supporting?
3. Are there any RISKY changes? (public API, config, build)
4. What tests cover this change?

Document answers mentally before Phase 1. </decision_tree>

---

## Phase 1: Fetch Status & Threads

### 1.1 CI Status Check

```bash
gh pr view <PR_NUMBER> --json state,statusCheckRollup \
  --jq '{state: .state, checks: [.statusCheckRollup[]? | {name: .name, status: .status, conclusion: .conclusion}]}'
```

### 1.2 Fetch Review Threads (GraphQL Source of Truth)

```bash
# NOTE: ':owner' and ':repo' are gh CLI magic variables that auto-resolve from git remote
gh api graphql -F owner=':owner' -F name=':repo' -F number=<PR_NUMBER> \
  -f query="$(cat .agent/queries/pr-review-threads.graphql)" \
  --paginate > /tmp/pr-<PR_NUMBER>-threads.json

# Verify capture
jq '{pr_id: .data.repository.pullRequest.id, total_threads: .data.repository.pullRequest.reviewThreads.nodes | length}' \
  /tmp/pr-<PR_NUMBER>-threads.json
```

### 1.3 Inventory Actionable Threads

```bash
uv run .agent/scripts/review_manager.py inventory <PR_NUMBER>
```

<fallback>
If script unavailable:
```bash
jq '.data.repository.pullRequest.reviewThreads.nodes[] | select(.isResolved==false and .isOutdated==false) | {threadId: .id, commentId: .comments.nodes[0].id, path, line, body: .comments.nodes[0].body}' /tmp/pr-<PR_NUMBER>-threads.json
```
</fallback>

---

## Phase 2: Evaluate Each Thread

<critical>
For EACH actionable thread, you MUST classify it using this decision tree.
NO EXCEPTIONS. NO SKIPPING.
</critical>

<decision_tree id="thread-classification">

```
┌─────────────────────────────────────────────────────────────┐
│                    THREAD CLASSIFICATION                     │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Q1: Is this feedback VALID?                                │
│      ├─ NO  → REJECT (explain why, cite evidence)           │
│      │        (Note: Handle false positives here)           │
│      └─ YES → Continue to Q2                                │
│                                                             │
│  Q2: Is this IN SCOPE for this PR?                          │
│      ├─ NO  → DEFER (create issue, link in reply)           │
│      └─ YES → Continue to Q3                                │
│                                                             │
│  Q3: Is this a QUICK FIX (< 5 min)?                         │
│      ├─ YES → FIX immediately                               │
│      └─ NO  → FIX with dedicated commit                     │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

</decision_tree>

### Classification Actions

<action id="FIX">
1. Locate the file and line from thread metadata
2. Cross-reference with `/tmp/pr-<PR_NUMBER>-diff.patch`
3. Make the code change
4. Run relevant tests: `./gradlew :MODULE:test --tests "TestClass"`
5. Commit: `git commit -m "fix: address review - <brief description>"`
</action>

<action id="REJECT">
1. Formulate clear technical reasoning
2. Cite specific code/docs as evidence
3. Reply with explanation (Phase 3)
4. Do NOT resolve — let reviewer respond
</action>

<action id="DEFER">
1. Follow `/defer` workflow to create GitHub issue
2. Reply: "Created #NNN to track this. Out of scope for this PR because [reason]."
3. Resolve thread after reply
</action>

---

## Phase 2.5: Common Feedback Patterns (Few-Shot Examples)

<examples>
<example id="remove-metadata">
<feedback>"Remove tool metadata from code comments"</feedback>
<classification>FIX</classification>
<action>
1. Find comments with tool-specific info (timestamps, agent names, etc.)
2. Move useful context to PR description if needed
3. Delete or rewrite comments to be tool-agnostic
</action>
<reply>"Fixed in abc123. Moved context to PR description."</reply>
</example>

<example id="precompile-regex">
<feedback>"Pre-compile this regex"</feedback>
<classification>FIX</classification>
<action>
1. Extract regex to companion object or top-level:
   ```kotlin
   companion object {
       private val PATTERN = Regex("...")
   }
   ```
2. Replace inline usage with constant reference
</action>
<reply>"Fixed in abc123. Extracted to companion object constant."</reply>
</example>

<example id="unused-variable">
<feedback>"Unused variable"</feedback>
<classification>FIX</classification>
<action>
1. If truly unused: delete it
2. If destructuring: replace with `_`
3. If needed later: add `@Suppress("UNUSED_VARIABLE")` with TODO
</action>
<reply>"Fixed in abc123. Removed unused variable."</reply>
</example>

<example id="cognitive-complexity">
<feedback>"Cognitive complexity too high"</feedback>
<classification>FIX or DEFER</classification>
<decision>
- If refactor is straightforward (extract 1-2 methods): FIX
- If requires significant restructuring: DEFER with issue
</decision>
<action_fix>
1. Identify nested conditionals or loops
2. Extract to well-named private methods
3. Verify tests still pass
</action_fix>
<reply_fix>"Fixed in abc123. Extracted helper methods to reduce complexity."</reply_fix>
<reply_defer>"Created #NNN. Refactoring this properly requires touching [X, Y, Z] which is out of scope."</reply_defer>
</example>

<example id="add-tests">
<feedback>"Add tests for this"</feedback>
<classification>FIX (TDD MANDATORY)</classification>
<action>
1. Write failing test FIRST
2. Verify test fails for the right reason
3. If implementation already exists, test should pass
4. If test passes immediately, verify it's testing the right thing
</action>
<reply>"Added test coverage in abc123."</reply>
</example>

<example id="disagree-with-approach">
<feedback>"This approach is wrong, you should use X instead"</feedback>
<classification>REJECT or FIX</classification>
<decision>
- If reviewer is correct: FIX
- If current approach is valid: REJECT with reasoning
</decision>
<reply_reject>"I considered X but chose current approach because [technical reason]. The tradeoffs are [A vs B]. Happy to discuss further."</reply_reject>
</example>

<example id="nitpick-style">
<feedback>"Nitpick: rename this variable"</feedback>
<classification>FIX (unless strongly disagree)</classification>
<action>Just do it. Style consistency matters more than personal preference.</action>
<reply>"Fixed in abc123."</reply>
</example>
</examples>

---

## Phase 3: Reply & Resolve (GraphQL Mutations)

<critical>
You need THREE IDs from Phase 1 data:
- `PR_NODE_ID`: From `.data.repository.pullRequest.id`
- `COMMENT_NODE_ID`: From thread's `.comments.nodes[0].id` (for reply)
- `THREAD_NODE_ID`: From thread's `.id` (for resolve)
</critical>

### 3.1 Extract IDs

```bash
# Get PR Node ID
jq -r '.data.repository.pullRequest.id' /tmp/pr-<PR_NUMBER>-threads.json

# Get Thread and Comment IDs for a specific thread
jq -r '.data.repository.pullRequest.reviewThreads.nodes[] | select(.isResolved==false) | {thread_id: .id, comment_id: .comments.nodes[0].id, path: .path, line: .line}' /tmp/pr-<PR_NUMBER>-threads.json
```

### 3.2 Reply to Thread

```bash
gh api graphql \
  -F pullRequestId="<PR_NODE_ID>" \
  -F inReplyTo="<COMMENT_NODE_ID>" \
  -F body="Fixed in <SHORT_SHA>." \
  -f query="$(cat .agent/queries/reply-to-thread.graphql)"
```

### 3.3 Resolve Thread

```bash
gh api graphql \
  -F threadId="<THREAD_NODE_ID>" \
  -f query="$(cat .agent/queries/resolve-review-thread.graphql)"
```

<reply_templates>

| Scenario             | Reply Template                                                                         |
| -------------------- | -------------------------------------------------------------------------------------- |
| Fixed                | "Fixed in `abc123`."                                                                   |
| Fixed with detail    | "Fixed in `abc123`. [brief explanation of change]"                                     |
| Deferred             | "Created #NNN to track this. [reason for deferral]"                                    |
| Rejected             | "[Technical reasoning]. [Evidence/citation]. Happy to discuss."                        |
| Clarification needed | "Could you clarify [specific question]? I want to make sure I address this correctly." |
| </reply_templates>   |                                                                                        |

---

## Phase 4: Push & Verify CI

### 4.1 Pre-Push Checklist

```bash
# Verify branch
git branch --show-current

# Verify no uncommitted changes
git status

# Run full test suite
./gradlew test

# Run lint
./gradlew lintFix
```

### 4.2 Push

```bash
git push origin <BRANCH_NAME>
```

### 4.3 Watch CI

```bash
gh pr checks <PR_NUMBER> --watch
```

<troubleshooting id="ci-issues">
<issue>Build and Test is SKIPPED</issue>
<diagnosis>
```bash
# Check paths filter
gh run view <RUN_ID> --log 2>/dev/null | grep -E "(filter|run_main_ci)" | head -20

# Verify changed files match CI triggers

gh pr view <PR_NUMBER> --json files --jq '.files[].path'

````
</diagnosis>
<resolution>
If paths don't match CI triggers, either:
1. Add a dummy change to a tracked path
2. Update CI workflow to include new paths
</resolution>

<issue>Test failure</issue>
<diagnosis>
```bash
gh run view <RUN_ID> --log-failed
````

</diagnosis>
<resolution>
1. Reproduce locally: `./gradlew test --tests "FailingTest"`
2. Fix the issue
3. Commit and push
</resolution>
</troubleshooting>

---

## Phase 5: Final Verification (ZERO CHECK)

<critical>
This phase is NON-NEGOTIABLE. You MUST verify zero unresolved threads.
</critical>

### 5.1 Re-Fetch Threads

```bash
gh api graphql -F owner=':owner' -F name=':repo' -F number=<PR_NUMBER> \
  -f query="$(cat .agent/queries/pr-review-threads.graphql)" \
  --paginate > /tmp/pr-<PR_NUMBER>-threads-final.json
```

### 5.2 Count Unresolved

```bash
jq '[.data.repository.pullRequest.reviewThreads.nodes[] | select(.isResolved == false and .isOutdated == false)] | length' /tmp/pr-<PR_NUMBER>-threads-final.json
```

<assertion>
**RESULT MUST BE 0**

If not zero:

1. List remaining threads
2. Return to Phase 2
3. DO NOT proceed until zero
   </assertion>

---

## Phase 6: Cleanup & Summary

### 6.1 Remove Temp Files

```bash
rm -f /tmp/pr-<PR_NUMBER>-*.json /tmp/pr-<PR_NUMBER>-*.patch
```

### 6.2 Summary Report

```markdown
## PR #<NUMBER> Review Summary

| Metric            | Value             |
| ----------------- | ----------------- |
| Threads Addressed | X                 |
| Commits Added     | Y                 |
| CI Status         | ✅ Pass / ❌ Fail |
| Unresolved        | 0                 |

### Actions Taken

- Fixed: [list of fixes]
- Deferred: [list with issue links]
- Rejected: [list with reasoning]
```

---

## Quick Reference: Complete Workflow

```
┌────────────────────────────────────────────────────────────────┐
│                    /review WORKFLOW                            │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  PHASE 0: UNDERSTAND                                           │
│  ├─ gh pr view <N> --json title,body...                        │
│  ├─ gh pr diff <N> > /tmp/pr-<N>-diff.patch                    │
│  └─ gh pr diff <N> --name-only                                 │
│                                                                │
│  PHASE 1: FETCH                                                │
│  ├─ gh pr view <N> --json state,statusCheckRollup...           │
│  ├─ gh api graphql ... > /tmp/pr-<N>-threads.json              │
│  └─ python3 .agent/scripts/inventory_threads.py ...            │
│                                                                │
│  PHASE 2: EVALUATE (for each thread)                           │
│  ├─ Classify: FIX / REJECT / DEFER                             │
│  ├─ Execute action                                             │
│  ├─ Run tests                                                  │
│  └─ Commit                                                     │
│                                                                │
│  PHASE 3: RESOLVE (for each thread)                            │
│  ├─ Reply via GraphQL mutation                                 │
│  └─ Resolve via GraphQL mutation                               │
│                                                                │
│  PHASE 4: PUSH & CI                                            │
│  ├─ git push origin <branch>                                   │
│  └─ gh pr checks <N> --watch                                   │
│                                                                │
│  PHASE 5: VERIFY                                               │
│  ├─ Re-fetch threads                                           │
│  └─ Assert unresolved == 0                                     │
│                                                                │
│  PHASE 6: CLEANUP                                              │
│  ├─ rm /tmp/pr-<N>-*.json                                      │
│  └─ Output summary                                             │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

---

## Cross-References

| Situation           | Workflow                                  |
| ------------------- | ----------------------------------------- |
| Merge conflicts     | `.agent/workflows/conflict-resolution.md` |
| Defer to issue      | `.agent/workflows/defer.md`               |
| GitHub CLI patterns | `.agent/workflows/github-cli.md`          |
| Code quality rules  | `.agent/rules/code-quality.md`            |
| Git safety          | `.agent/rules/git-workflow.md`            |
