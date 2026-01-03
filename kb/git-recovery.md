# Git Worktree Recovery Guide

This guide documents how to recover when you accidentally start working in the wrong worktree (e.g., `main`) or branch.

## Scenario: Working in `main` by mistake

**Problem:** You started editing files in the `main` worktree, which should be treated as read-only/reference. **Goal:**
Move your uncommitted changes to a new feature branch in a separate worktree without losing work.

## Recovery Steps

### 1. Stash your changes

In the incorrect worktree (e.g., `main`), stash your changes with a descriptive message.

```bash
git stash push -m "descriptive-name-of-changes"
```

### 2. Create a new worktree

Create the new worktree and branch where the work _should_ be.

```bash
# Syntax: git worktree add <path> -b <branch-name>
git worktree add ../my-feature-worktree -b feature/my-feature
```

### 3. Move to the new worktree

Change directory to the new worktree.

```bash
cd ../my-feature-worktree
```

### 4. Verify stash availability

Ensure your stash is visible (stashes are shared across worktrees in the same repo).

```bash
git stash list
# You should see: stash@{0}: On main: descriptive-name-of-changes
```

### 5. Apply and drop the stash

Apply the stashed changes to your new worktree and remove them from the stash list.

```bash
git stash pop stash@{0}
```

### 6. Verify changes

Check that your changes are applied correctly.

```bash
git status
./gradlew build  # Verify build
```

## Tips

- Always check `git branch` or `pwd` before starting work.
- Use `git stash list` to see available stashes.
- `git stash apply` keeps the stash (useful if you want to apply to multiple places or verify first).
- `git stash pop` applies and deletes the stash (cleaner if you are moving changes).
