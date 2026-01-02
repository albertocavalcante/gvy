# /review
// turbo-all

A strict, deterministic workflow for addressing PR review feedback and verifying CI health for a SINGLE Pull Request. This "God Mode" workflow combines strict thread resolution with deep CI verification.

## Ironclad Rules

1.  **NEVER IGNORE ANY COMMENT**: Every single thread must be accounted for.
2.  **GRAPHQL IS TRUTH**: Use the saved GraphQL query for the authoritative state of threads (Resolved/Outdated).
3.  **USE TEMP FILES**: Always dump API output to temporary JSON files.
4.  **REPLY & RESOLVE**: Every addressed thread must have a reply and be explicitly resolved via mutation.
5.  **NO BROWSER**: Use `gh` CLI exclusively.

## Phase 1: Deep Status & Fetching

1.  **Get Detailed PR Status with CI**:
    ```bash
    gh pr view <PR_NUMBER> --json state,statusCheckRollup --jq '{state: .state, checks: [.statusCheckRollup[]? | {name: .name, status: .status, conclusion: .conclusion}]}'
    ```

2.  **Fetch Threads (Source of Truth)**:
    Use the saved query `.agent/queries/pr-review-threads.graphql`.
    ```bash
    # Fetch threads
    gh api graphql -F owner=':owner' -F name=':repo' -F number=<PR_NUMBER> -f query="$(cat .agent/queries/pr-review-threads.graphql)" --paginate > /tmp/pr-<PR_NUMBER>-threads.json
    
    # Verify the capture (Check for PR ID and Thread count)
    jq '{pr_id: .data.repository.pullRequest.id, threads: .data.repository.pullRequest.reviewThreads.nodes | length}' /tmp/pr-<PR_NUMBER>-threads.json
    ```

3.  **Inventory Actionable Threads**:
    List threads that are `isResolved: false` and `isOutdated: false`.
    ```bash
    python3 .agent/scripts/inventory_threads.py /tmp/pr-<PR_NUMBER>-threads.json
    ```
    *(Or manual jq filter if script unavailable)*:
    ```bash
    jq '.data.repository.pullRequest.reviewThreads.nodes[] | select(.isResolved==false and .isOutdated==false) | {id, path, line, body: .comments.nodes[0].body}' /tmp/pr-<PR_NUMBER>-threads.json
    ```

## Phase 2: Evaluation & Execution

For EACH actionable thread:

1.  **Evaluate**: Fix, Reject, or Defer.
2.  **Edit**: Apply changes in the code.
3.  **Verify**: Run local tests (TDD).
    ```bash
    ./gradlew :MODULE:test --tests "com.example.TestClass"
    ```
4.  **Commit**: `git commit -m "fix: address feedback ..."`

### Conflict Resolution
If you encounter merge conflicts during checkout or commits (e.g., GraphQL state mismatches vs Git state), STOP and follow the standard protocol:
-> [Conflict Resolution Workflow](file:///Users/adsc/dev/ws/groovy-devtools/graphql-refinement/.agent/workflows/conflict-resolution.md)


### Quick Reference: Common Actions
| Reviewer Feedback | Action |
|------------------|--------|
| "Remove metadata from comments" | Move tool-specific info to PR description |
| "Pre-compile regex" | Extract to class/object level constant |
| "Unused variable" | Remove or replace with `_` |
| "Cognitive complexity too high" | Extract helper methods |
| "Add tests" | Follow TDD, add test file first |

## Phase 3: Deterministic Resolution

1.  **Reply (GraphQL Mutation)**:
    **Crucial**: You need the `pullRequestId` (PR Node ID) from Phase 1 and the `inReplyTo` (Comment Node ID) from the thread.
    ```bash
    gh api graphql -F pullRequestId="<PR_NODE_ID>" -F inReplyTo="<COMMENT_NODE_ID>" -F body="Fixed in <SHORTHASH>." -f query="$(cat .agent/queries/reply-to-thread.graphql)"
    ```

2.  **Resolve Thread (GraphQL)**:
    Mark the thread as resolved using its Node ID.
    ```bash
    gh api graphql -F threadId="<THREAD_NODE_ID>" -f query="$(cat .agent/queries/resolve-review-thread.graphql)"
    ```

## Phase 4: Verify CI Status (Deep Dive)

1.  **Push Changes**:
    ```bash
    git push origin <BRANCH_NAME>
    ```

2.  **Watch Checks**:
    ```bash
    gh pr checks <PR_NUMBER> --watch
    ```

3.  **Investigate Skipped/Failing Jobs**:
    If `Build and Test` is SKIPPED but shouldn't be:
    ```bash
    # Check paths filter output
    gh run view <RUN_ID> --log 2>/dev/null | grep -E "(filter|run_main_ci)" | head -20
    
    # Verify changed files match CI trigger paths
    gh pr view <PR_NUMBER> --json files --jq '.files[].path'
    ```

## Phase 5: Final Verification (The "Zero" Check)

1.  **Re-Fetch Data**:
    Run the Phase 1 Fetch command again.

2.  **Verify Unresolved Count is 0**:
    ```bash
    jq '[.data.repository.pullRequest.reviewThreads.nodes[] | select(.isResolved == false and .isOutdated == false)] | length' /tmp/pr-<PR_NUMBER>-threads.json
    ```
    **Result MUST be 0.**

## Phase 6: Document Learnings

After reviewing PRs, note any patterns or issues to add to `.agent/rules/`:

| Issue Found | Where to Document |
|------------|------------------|
| Code comment anti-patterns | `.agent/rules/code-quality.md` |
| Git workflow issues | `.agent/rules/git-workflow.md` |
| CI configuration problems | `.github/workflows/ci.yml` + AGENTS.md |
| Recurring lint issues | `.agent/rules/code-quality.md` |

## Phase 7: Summary Report

Produce a summary table for the user:

```markdown
## PR Review Summary

| PR | Title | Comments | Changes Made | Pushed | CI Status | Ready |
|----|-------|----------|--------------|--------|-----------|-------|
| #123 | Title | 0 | - | ✅ | ✅ Pass | ✅ |
```
