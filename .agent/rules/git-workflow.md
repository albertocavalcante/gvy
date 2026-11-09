---
description: Git workflow, branching, and commit conventions
---

# Git Workflow

<critical>
These rules are NON-NEGOTIABLE. Violations require immediate correction.
</critical>

## Safety Rules

| Rule                   | Command                                        | Rationale                   |
| ---------------------- | ---------------------------------------------- | --------------------------- |
| Never commit on main   | `git branch --show-current` before commit      | Protects main branch        |
| Stage files explicitly | `git add file1.kt file2.kt`                    | Prevents accidental commits |
| Verify before push     | `git status` + `git diff --cached`             | Catches mistakes early      |
| Use worktrees          | `wt new <branch>` in the worker-pool workspace | Keep main worktree clean    |

<forbidden>
- `git add .` or `git add -A` — NEVER use wildcard staging
- `git commit` without verifying branch — ALWAYS check first
- `git push --force` — Use `--force-with-lease` instead
</forbidden>

---

## Branching Strategy

### For New Work

```bash
# 1. Fetch without switching or modifying an existing checkout
git fetch --prune origin

# 2. In the worker-pool workspace, claim an isolated slot
wt new <type>/<short-description>
# Otherwise: git worktree add -b <branch> <path> origin/main
# Examples: feat/completion-methods, fix/null-pointer, refactor/extract-utils
```

### Branch Naming Convention

```
<type>/<short-kebab-description>
```

| Type        | Use Case             |
| ----------- | -------------------- |
| `feat/`     | New features         |
| `fix/`      | Bug fixes            |
| `refactor/` | Code restructuring   |
| `test/`     | Test additions/fixes |
| `docs/`     | Documentation        |
| `ci/`       | CI/CD changes        |
| `chore/`    | Maintenance tasks    |

---

## Commit Format

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

### Examples

```bash
# Simple
git commit -m "feat: add method signature completion"

# With scope
git commit -m "fix(parser): handle null AST nodes"

# With body (use temp file)
cat > /tmp/commit-msg.txt << 'EOF'
refactor(lsp): extract completion provider

- Move completion logic to dedicated class
- Add unit tests for edge cases
- Reduce complexity in main handler
EOF
git commit -F /tmp/commit-msg.txt
rm /tmp/commit-msg.txt
```

### Type Reference

| Type       | Description                             |
| ---------- | --------------------------------------- |
| `feat`     | New feature                             |
| `fix`      | Bug fix                                 |
| `refactor` | Code change that neither fixes nor adds |
| `test`     | Adding/updating tests                   |
| `docs`     | Documentation only                      |
| `ci`       | CI configuration                        |
| `chore`    | Maintenance, dependencies               |
| `perf`     | Performance improvement                 |

---

## Squash Merge Handling

This repository uses **squash merge**. All PR commits become ONE commit on main.

### Implications

1. Original commit SHAs don't exist on main after merge
2. A branch needs exact merged-head identity or containment proof before deletion
3. PR title becomes the commit message — make it good

### Post-Merge Cleanup

The agent that creates a task branch and worktree owns their cleanup. A task is complete only after landing and cleanup
are verified, or a concrete reason for retaining work is reported. GitHub's automatic head-branch deletion is enabled;
verify the actual remote state instead of assuming the setting deleted it.

1. Record the reviewed publication head, branch, PR number, and actual merge commit. Use
   `gh pr view <number> --json state,headRefName,headRefOid,mergeCommit`; a successful merge-command exit alone is
   insufficient.
2. Fetch `origin/main` and prove the merge commit reached it with
   `git merge-base --is-ancestor <merge-sha> origin/main`. A PR merged only into an unmerged feature branch is
   insufficient.
3. Preserve open PR heads and bases, default/release branches, and every other agent's checked-out branch. Rebase and
   retarget owned stacked PRs before deleting their base. Never delete a branch merely because it is old or its name
   appeared on a merged PR; reused branches and commits added after merge need separate review.
4. For an owned branch, compare its current tip with the recorded merged PR head. If they differ, require commit
   containment proof; otherwise retain it. Squash/rebase merges usually make `git branch --merged` and `git branch -d`
   insufficient. Do not manufacture proof by creating backup/archive branches.
5. If GitHub has not deleted the remote head, delete only the reviewed tip with an explicit expected-SHA lease:

   ```bash
   git push --force-with-lease=refs/heads/<branch>:<verified-tip> origin :refs/heads/<branch>
   ```

   A changed tip must stop deletion. For bulk cleanup, publish a dry-run inventory with a reason and SHA for every
   candidate, use complete PR history and fresh remote refs, and fail closed on API/fetch/status errors. Recheck open
   PRs and active worktrees before applying; use atomic leased deletion where supported. Preserve unclassified work.
6. From another checkout, recheck that the owned worktree is clean (including untracked files) and unlocked, then use
   `wt release <slot>` in this workspace; elsewhere use `git worktree remove <path>` without force. Recheck the local
   branch tip before deleting a remaining owned branch. Never release another agent's checkout as incidental cleanup.
7. Fetch/prune tracking refs for remotes pointing at this repository, verify the branch/worktree is gone, and report
   deliberately retained work with its owner/scope and next action. Keep the deletion ledger as evidence rather than
   permanent backup branches.

---

## Stacked PRs

When PR2 depends on PR1's code (not yet merged):

```bash
# Create PR1
git checkout -b feat/base-feature
# ... work ...
git push -u origin feat/base-feature
gh pr create --title "feat: base feature"

# Create PR2 stacked on PR1
git checkout -b feat/dependent-feature  # branches from feat/base-feature
# ... work ...
git push -u origin feat/dependent-feature
gh pr create --title "feat: dependent feature" --body "Stacked on #<PR1_NUMBER>"
```

### After PR1 Merges

```bash
git checkout feat/dependent-feature
git fetch origin main
git rebase origin/main
# Resolve conflicts if any
git push --force-with-lease
```

---

## Multi-PR Work

Use a separate owned worktree for each task. Fetch the remote default branch before creating new work; do not switch,
reset, clean, or stash another task's checkout to work around a shared tree. Preserve dirty or locked worktrees and
coordinate with their owner before any cleanup. Land small reviewed changes, then perform the cleanup above.

---

## Local Ignore

For files that should be ignored only on YOUR machine (not shared):

```bash
echo "my-local-notes.md" >> .git/info/exclude
echo ".scratch/" >> .git/info/exclude
```

Use `.git/info/exclude` for personal files. Use `.gitignore` only for files ALL developers should ignore.
