---
description: Deterministic protocol for addressing PR review comments with traceable fixes and thread resolution.
---

# /pr-address-reviews

This workflow defines the required process for addressing PR review comments. Every resolved thread must include a reply that references the commit that fixed it, and the thread must be explicitly resolved.

## Phase 1: Gather Review Threads

1. Identify the PR number and branch.
   ```bash
   gh pr view <PR_NUMBER> --repo <OWNER>/<REPO>
   ```

2. List unresolved review threads with IDs and comment IDs (GraphQL).
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

## Phase 2: Address Comments (TDD Required)

1. Create or switch to the PR worktree.
2. Write or update tests first.
3. Implement the fix.
4. Commit the change with a clear message.
5. Capture the commit SHA.
   ```bash
   git rev-parse --short HEAD
   ```

## Phase 3: Reply + Resolve Threads

For each review thread you fixed:

1. Reply to the specific comment with the commit that fixes it.
   ```bash
   gh api graphql -f query='mutation {
     addPullRequestReviewComment(input:{
       pullRequestId:"<PR_NODE_ID>",
       inReplyTo:"<COMMENT_NODE_ID>",
       body:"Fixed in <COMMIT_SHA>."
     }) { comment { id } }
   }'
   ```

2. Resolve the thread.
   ```bash
   gh api graphql -f query='mutation {
     resolveReviewThread(input:{threadId:"<THREAD_NODE_ID>"}) {
       thread { isResolved }
     }
   }'
   ```

## Phase 4: Verify

1. Push the branch.
   ```bash
   git push
   ```

2. Re-check unresolved threads.
   ```bash
   gh api graphql -f query='query {
     repository(owner:"<OWNER>", name:"<REPO>") {
       pullRequest(number:<PR_NUMBER>) {
         reviewThreads(first:50) { nodes { id isResolved } }
       }
     }
   }'
   ```

## Expected Behavior

- Every addressed review comment has a reply referencing the exact commit SHA.
- Every addressed thread is explicitly resolved.
- Tests are added or updated before code changes (TDD).
- No thread is resolved without a commit reference.
