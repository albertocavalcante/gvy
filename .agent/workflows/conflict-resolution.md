---
description: Step-by-step guide for resolving Git conflicts during rebase or merge
---

# Conflict Resolution

When `git rebase` or `git pull` results in conflicts, follow these steps:

## Step-by-Step Process

1. **Identify Conflicts**
   ```bash
   git status
   ```
   Shows files with conflicts marked as "both modified"

2. **View Conflict Markers** Open the file and look for:
   ```
   <<<<<<< HEAD
   Your changes
   =======
   Incoming changes
   >>>>>>> branch-name
   ```

3. **Resolve**
   - Decide which version to keep (or combine both)
   - Remove the conflict markers (`<<<<<<<`, `=======`, `>>>>>>>`)
   - Ensure the code is syntactically correct

4. **Stage Resolved Files**
   ```bash
   git add resolved-file.kt
   ```

5. **Continue the Operation** For rebase:
   ```bash
   git rebase --continue
   ```
   For merge:
   ```bash
   git commit
   ```

6. **Push Changes** If you rebased:
   ```bash
   git push --force-with-lease
   ```
   If you merged:
   ```bash
   git push
   ```

## Abort If Stuck

If conflicts are too complex or something went wrong:

```bash
git rebase --abort  # Returns to pre-rebase state
git merge --abort   # Returns to pre-merge state
```

## Common Conflict Scenarios

### After Squash Merge of Base PR

When your stacked PR's base was squash-merged:

```bash
git fetch origin main
git rebase origin/main
# Resolve conflicts
git push --force-with-lease
```

### Conflicting Imports

Often happens with Kotlin imports. Keep the union of necessary imports.

### Format Differences

If conflicts are purely formatting, run the formatter after resolving:

```bash
./gradlew lintFix
```
