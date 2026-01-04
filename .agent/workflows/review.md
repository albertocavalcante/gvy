---
description: Strict, deterministic workflow for addressing PR review feedback using pr.py
---

# /review

// turbo-all

<purpose>
A STRICT, DETERMINISTIC workflow for addressing PR review feedback.
Powered by `.agent/scripts/pr.py` to enforce traceability and reduce token usage.
</purpose>

<ironclad_rules>

1. **NEVER IGNORE ANY COMMENT** — Every thread MUST be accounted for
2. **SCRIPT IS KING** — Use `.agent/scripts/pr.py` for ALL thread interactions
3. **DIFF FIRST** — ALWAYS read `gh pr diff` before ANY action
4. **REPLY THEN RESOLVE** — Every addressed thread gets a reply AND explicit resolution via script
5. **VERIFY BEFORE COMMIT** — Run tests for EVERY code change </ironclad_rules>

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
```

---

## Phase 1: Fetch Status & Threads

### 1.1 CI Status Check

```bash
gh pr view <PR_NUMBER> --json state,statusCheckRollup \
  --jq '{state: .state, checks: [.statusCheckRollup[]? | {name: .name, status: .status, conclusion: .conclusion}]}'
```

### 1.2 Fetch Review Threads (SINGLE TRUTH SOURCE)

Use the dedicated script to fetch and display threads in a token-optimized format.

```bash
uv run .agent/scripts/pr.py threads <PR_NUMBER>
```

---

## Phase 2: Evaluate Each Thread

<critical>
For EACH actionable thread (listed by `pr.py`), you MUST classify it using this decision tree.
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
1. Locate the file and line from thread output (`L=...`)
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

## Phase 3: Reply & Resolve (SCRIPT ENFORCED)

<critical>
You MUST use `pr.py resolve` to close threads.
The script ENFORCES that your reply contains a commit SHA or issue reference.
ID (`T=...`) comes from Phase 1 output.
</critical>

```bash
# Template:
# uv run .agent/scripts/pr.py resolve <THREAD_ID> "Fixed in <SHA>."

uv run .agent/scripts/pr.py resolve <THREAD_ID> "Fixed in <COMMIT_SHA>."
```

<reply_templates>

| Scenario             | Reply Template                                                                         |
| -------------------- | -------------------------------------------------------------------------------------- |
| Fixed                | "Fixed in `abc123`."                                                                   |
| Fixed with detail    | "Fixed in `abc123`. [brief explanation of change]"                                     |
| Deferred             | "Created #NNN to track this. [reason for deferral]"                                    |
| Rejected             | "[Technical reasoning]. [Evidence/citation]. Happy to discuss."                        |
| Clarification needed | "Could you clarify [specific question]? I want to make sure I address this correctly." |

</reply_templates>

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

---

## Phase 5: Final Verification (ZERO CHECK)

<critical>
This phase is NON-NEGOTIABLE.
</critical>

### 5.1 Re-Fetch Threads

```bash
# Should show COUNT=0
uv run .agent/scripts/pr.py threads <PR_NUMBER> --refetch
```

<assertion>
**RESULT MUST BE ZERO ACTIONABLE THREADS**

If not zero:

1. List remaining threads
2. Return to Phase 2
3. DO NOT proceed until zero
   </assertion>
