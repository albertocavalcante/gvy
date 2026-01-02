---
description: Bulk reconciliation workflow for addressing review feedback across multiple PRs
---

# /bulk-review
// turbo-all

This workflow defines the process for bulk reconciliation across multiple PRs. It iterates over open PRs and applies the strict `/review` protocol to each.

## Phase 1: Inventory Open PRs

1.  **List Open PRs**:
    ```bash
    gh pr list --repo <OWNER>/<REPO> --state open --json number,headRefName,url,updatedAt,title
    ```

2.  **Map to Worktrees**:
    Ensure you have a worktree for each PR you intend to address.
    ```bash
    git worktree list
    git worktree add -b <branch> ../<repo>-<branch> origin/<branch>
    ```

## Phase 2: Execution Loop (Per PR)

For EACH open PR:

1.  **Execute Single Review Workflow**:
    Follow the steps in `.agent/workflows/review.md` (invoke `/review` workflow).
    
    - **Fetch Threads** (GraphQL Source of Truth)
    - **Inventory** (Unresolved & Not Outdated)
    - **Fix & Commit** (TDD)
    - **Reply & Resolve** (Deterministic GraphQL Mutations)
    - **Verify Zero Unresolved**

2.  **Verify CI**:
    Ensure `gh pr checks` are passing.

## Phase 3: Reconciliation Pass

1.  **Re-check all PRs**:
    Are they all in **READY** state (No unresolved threads, CI passing)?

## Expected Behavior

- **GraphQL is Truth**: No guessing thread state.
- **Mutations**: ALL replies and resolutions use `.agent/queries/*.graphql`.
- **Zero Unresolved**: Validated by `jq` filter at the end.
