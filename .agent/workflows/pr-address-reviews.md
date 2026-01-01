---
description: Deterministic protocol for fetching, addressing, and resolving PR review comments with absolute precision.
---

# /pr-address-reviews

A strict, deterministic workflow for handling PR review feedback. This protocol ensures zero comments are missed, hallucinated, or left unresolved.

## Ironclad Rules

1.  **NEVER IGNORE ANY COMMENT**: Every single comment (inline or general) must be accounted for.
2.  **COUNT FIRST**: Establish the total count of comments before starting. Verification must match this count.
3.  **USE TEMP FILES**: Always dump API output to temporary JSON files to avoid shell truncation and parsing errors.
4.  **REPLY & RESOLVE**: Every addressed thread must have a reply and be explicitly resolved.
5.  **NO BROWSER**: Use `gh` CLI exclusively.

## Phase 1: Precise Fetching

1.  **Define Scope**:
    Identify the PR number(s). If addressing feedback from multiple PRs (e.g., stacked PRs), define the "Source PRs" (where comments live) and "Target PR" (where fixes go).

2.  **Fetch & Persist (Avoid Truncation)**:
    Dump *all* comments to a structured JSON file. **Do not rely on terminal output for large comment sets.**

    ```bash
    # For a specific PR (e.g., 534)
    gh api repos/:owner/:repo/pulls/534/comments --paginate --jq '.' > /tmp/pr-534-comments.json
    
    # Verify the count immediately
    jq length /tmp/pr-534-comments.json
    ```

3.  **Inventory**:
    Read the JSON file to list all actionable items.
    ```bash
    jq -r '.[] | "[\(.id)] \(.path):\(.line) - \(.user.login): \(.body | split("\n")[0])..."' /tmp/pr-534-comments.json
    ```

## Phase 2: Evaluation & Execution

For EACH comment ID in the JSON:

1.  **Evaluate**:
    - Is the feedback valid?
    - Does it apply to the current code?
    - **Action**: Fix, Reject, or Defer.

2.  **Edit**:
    - Apply changes.
    - Run `make fmt` or `spotlessApply`.
    - Run tests.

3.  **Commit**:
    - `git commit -m "fix(scope): address review feedback #ID"`

## Phase 3: Deterministic Resolution

1.  **Reply (REST API)**:
    Use the `gh api` to post replies. This is robust and supports replying to comments from *any* PR if you have access.

    ```bash
    # Template: Reply to a specific comment ID
    gh api -X POST repos/:owner/:repo/pulls/comments/<COMMENT_ID>/replies \
      -f body="@<USER> Fixed in <HEAD_SHA>. <DETAILS>"
    ```

2.  **Resolve Thread (GraphQL)**:
    After replying, mark the thread as resolved. This requires the **Node ID** (not integer ID). You may need to fetch the GraphQL Node ID if you only have the integer ID, or just iterate fully unresolved threads.

    ```bash
    # Fetch unresolved threads with Node IDs
    gh api graphql -f query='query { repository(owner:":owner", name:":repo") { pullRequest(number:<PR_NUM>) { reviewThreads(first:50) { nodes { id isResolved } } } } }' --jq '.data.repository.pullRequest.reviewThreads.nodes[] | select(.isResolved==false) | .id' > /tmp/unresolved_threads.txt
    
    # Resolve them (Loop)
    while read thread_id; do
      gh api graphql -f query="mutation { resolveReviewThread(input:{threadId:\"$thread_id\"}) { thread { isResolved } } }"
    done < /tmp/unresolved_threads.txt
    ```

## Phase 4: Final Verification

**Equation Must Balance**:
`Initial Count` == `Replies Sent` + `Deferred/Rejected Items`

1.  **Check Unresolved Count**:
    Run the GraphQL query again. It must return **0** unresolved threads for the target reviewer(s).

2.  **Status Reporting**:
    Generate a markdown table summarizing the actions taken.

    ```markdown
    | Comment ID | File | Status | Action |
    | :--- | :--- | :--- | :--- |
    | 12345 | src/Foo.kt | ✅ Fixed | Removed redundant cast |
    | 12346 | src/Bar.kt | ⏳ Deferred | Requires major refactor (Issue #99) |
    ```
