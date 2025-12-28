---
description: Deterministic protocol for reconciling review comments and CI status across multiple open PRs and worktrees.
---

# /bulk-pr-address-review

This workflow defines the REQUIRED process for bulk reconciliation across multiple PRs. It is optimized for situations where you have several open branches/worktrees and need to verify review comments, CI failures, and outstanding tasks. Follow steps IN ORDER. DO NOT skip steps.

## Phase 1: Inventory Open PRs (Single Source of Truth)

1. List open PRs and capture their numbers, head branches, and URLs.
   ```bash
   gh pr list --repo <OWNER>/<REPO> --state open --json number,headRefName,url,updatedAt,title
   ```

2. For each PR, record:
   - PR number
   - headRefName (branch)
   - URL
   - last updated time

## Phase 2: Identify Worktrees and Branches

1. List local worktrees.
   ```bash
   git worktree list
   ```

2. Map each open PR branch to a worktree path (or note missing worktree).
   - IF NO WORKTREE EXISTS: create one before editing.
     ```bash
     git worktree add -b <branch> ../<repo>-<branch> origin/<branch>
     ```

## Phase 3: Review Comments and CI Status (Per PR)

For EACH open PR:

1. Review unresolved threads.
   ```bash
   gh api graphql -f query='query {
     repository(owner:"<OWNER>", name:"<REPO>") {
       pullRequest(number:<PR_NUMBER>) {
         reviewThreads(first:50) {
           nodes {
             id
             isResolved
             comments(first:10) {
               nodes { id databaseId author{login} body path line originalLine }
             }
           }
         }
       }
     }
   }'
   ```

2. Review checks and failing CI.
   ```bash
   gh pr checks <PR_NUMBER> --repo <OWNER>/<REPO>
   ```

3. Classify each PR into ONE of these states:
   - **READY**: No unresolved threads, checks passing
   - **NEEDS_REVIEW_FIXES**: Unresolved threads present
   - **NEEDS_CI_FIXES**: Checks failing or missing
   - **NEEDS_WORKTREE**: Branch not checked out locally

## Phase 4: Fixes (Deterministic)

### A) Review Comment Fixes (Per Thread)

1. CHECK OUT the PR worktree.
2. FOLLOW TDD (tests first), then implement the fix.
3. COMMIT the change and CAPTURE the commit SHA.
   ```bash
   git rev-parse --short HEAD
   ```
4. Reply to the review comment WITH the commit SHA.
   ```bash
   gh api graphql -f query='mutation {
     addPullRequestReviewComment(input:{
       pullRequestId:"<PR_NODE_ID>",
       inReplyTo:"<COMMENT_NODE_ID>",
       body:"Fixed in <COMMIT_SHA>."
     }) { comment { id } }
   }'
   ```
5. RESOLVE the thread.
   ```bash
   gh api graphql -f query='mutation {
     resolveReviewThread(input:{threadId:"<THREAD_NODE_ID>"}) {
       thread { isResolved }
     }
   }'
   ```

### B) CI Fixes

1. Locate the failing job logs.
   ```bash
   gh run list --workflow=<WORKFLOW_NAME> --branch <BRANCH> -L 5 --repo <OWNER>/<REPO>
   gh run view <RUN_ID> --repo <OWNER>/<REPO>
   ```
2. Apply the minimum fix in the PR worktree.
3. Commit and push.
4. Re-run checks if needed (only when workflow supports it).
   ```bash
   gh workflow run <WORKFLOW_FILE> --ref <BRANCH> --repo <OWNER>/<REPO>
   ```

## Phase 5: Reconciliation Pass

1. Re-check all PRs for unresolved threads and failing checks.
2. DO NOT stop until all PRs are in **READY** state or explicitly deferred.

## Expected Behavior

- EVERY addressed thread has a reply with the exact commit SHA.
- EVERY addressed thread is resolved.
- ALL fixes use TDD.
- NO PR is left with failing checks unless explicitly documented.
- WORKTREE mapping is accurate and complete.
