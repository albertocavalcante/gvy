---
description: Deterministic protocol for fetching, addressing, and resolving PR review comments with absolute precision using GraphQL.
---

# /pr-address-reviews

A strict, deterministic workflow for handling PR review feedback. This protocol leverages saved GraphQL queries to ensure accurate state tracking (resolved vs. unresolved) and precise verification.

## Ironclad Rules

1.  **NEVER IGNORE ANY COMMENT**: Every single thread must be accounted for.
2.  **GRAPHQL IS TRUTH**: Use the saved GraphQL query for the authoritative state of threads (Resolved/Outdated).
3.  **USE TEMP FILES**: Always dump API output to temporary JSON files.
4.  **REPLY & RESOLVE**: Every addressed thread must have a reply and be explicitly resolved via mutation.
5.  **NO BROWSER**: Use `gh` CLI exclusively.

## Phase 1: Precise Fetching (The "Skill")

We use a saved GraphQL query to fetch the exact state of all review threads.

1.  **Define Scope**:
    Identify the PR number (e.g., `536`).

2.  **Fetch Threads**:
    Use the saved query `.agent/queries/pr-review-threads.graphql`.

    ```bash
    # Fetch threads for PR 536
    gh api graphql -F owner=':owner' -F name=':repo' -F number=536 -f query="$(cat .agent/queries/pr-review-threads.graphql)" --paginate > /tmp/pr-536-threads.json
    
    # Verify the capture
    jq '.data.repository.pullRequest.reviewThreads.nodes | length' /tmp/pr-536-threads.json
    ```

3.  **Inventory**:
    Generate a precise list of actionable threads.
    ```bash
    # List Unresolved & Not Outdated threads
    python3 .agent/scripts/inventory_threads.py /tmp/pr-536-threads.json
    ```

## Phase 2: Evaluation & Execution

For EACH thread in the Inventory:

1.  **Evaluate**:
    - Is it valid?
    - **Action**: Fix, Reject, or Defer.

2.  **Edit & Verify**:
    - Apply changes.
    - Run tests locally to ensure no regressions (Crucial!).
    - `git commit -m "fix: ..."`

## Phase 3: Deterministic Resolution

1.  **Reply (REST API)**:
    Reply to the *latest comment* in the thread or the *root comment*.
    ```bash
    gh api -X POST repos/:owner/:repo/pulls/comments/<COMMENT_DATABASE_ID>/replies -f body="Fixed in <SHA>."
    ```

2.  **Resolve Thread (GraphQL)**:
    Mark the thread as resolved using its Node ID.
    ```bash
    gh api graphql -f query='mutation($id: ID!) { resolveReviewThread(input:{threadId:$id}) { thread { isResolved } } }' -F id="<THREAD_NODE_ID>"
    ```

## Phase 4: Final Verification

The goal is to mathematically prove that all feedback has been handled.

### Verification Equation

The **Total Thread Count** ($T_{total}$) must equal the sum of all categorized outcomes ($T_{outcome}$).

$$ T_{total} = T_{replies} + T_{deferred} + T_{previous} + T_{outdated} + T_{auto} + T_{unresolved} $$

Where:
- **$T_{replies}$ (Replies Sent)**: You verified the fix, pushed, and replied "Fixed".
- **$T_{deferred}$ (Deferred/Rejected)**: You replied "Deferred to #Issue" or "Wontfix".
- **$T_{previous}$ (Already Resolved)**: Thread was resolved by a reviewer or previous session.
- **$T_{outdated}$ (Outdated/Excluded)**: Thread points to code that was overwritten/deleted (`isOutdated: true`).
- **$T_{auto}$ (Auto-generated/Duplicate)**: Bot noise or duplicate threads.
- **$T_{unresolved}$ (Remaining)**: Threads still requiring action.

### The Final Check

At the end of the session, $T_{unresolved}$ MUST be **0**.

1.  **Re-Fetch Data**:
    Run the Phase 1 Fetch command again to get the latest state.

2.  **Verify Unresolved Count**:
    Execute this filter. It explicitly targets threads that are **Unresolved** AND **Not Outdated**.

    ```bash
    jq '[.data.repository.pullRequest.reviewThreads.nodes[] | select(.isResolved == false and .isOutdated == false)] | length' /tmp/pr-536-threads.json
    ```

    **Result MUST be 0.**

3.  **Audit "Resolved-but-Unanswered"**:
    Ensure you didn't resolve a thread without replying.
    ```bash
    # Find threads resolved by YOU but with no reply from YOU in the last step
    # (Manual check of the JSON or simple grep of the log)
    ```

## Comparison: GraphQL vs. REST

| Feature | REST API (`/comments`) | GraphQL API (`reviewThreads`) |
| :--- | :--- | :--- |
| **Unit** | Individual Comment | Review Thread (Group of comments) |
| **State** | No inherent state (just text) | **Explicit `isResolved` state** |
| **Context** | File path/line | **`isOutdated` status** |
| **Resolution** | Cannot resolve via API easily | Native `resolveReviewThread` mutation |
| **Verdict** | Good for "Grep", bad for Workflow | **The Source of Truth** |

This workflow shifts from the naive REST approach (listing all text) to the robust GraphQL approach (managing Thread State), ensuring accurate "Definition of Done".
