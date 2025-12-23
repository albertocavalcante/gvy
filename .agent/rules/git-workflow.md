# Git Workflow Rules

These rules are ALWAYS applied when working on this repository.

## Critical Safety Rules

1. **Never commit on main** - Always create a feature branch first.
2. **Stage files explicitly** - Use `git add file1.kt file2.kt`, NEVER `git add .` or `git add -A`.
3. **Verify branch** - Run `git branch --show-current` before any commit.

## No Worktrees Policy

We deliberately DO NOT use git worktrees. The agent operates in a single working directory.

This does NOT prevent parallel work on multiple PRs:
- Switch branches to work on different PRs
- Use `git stash push -m "context"` for quick context switches
- Always verify current branch before committing

## Multi-PR Parallelism

PRs are NOT serial. You can work on multiple PRs simultaneously:

1. Complete a logical unit of work on Branch A
2. Commit, push, create PR
3. Switch to `main`, pull latest, create Branch B for next task
4. If PR A needs review fixes, switch back: `git checkout branch-a`

**Key Rule**: Always branch from `main` for independent work.

## Squash Merge Handling

This repo uses SQUASH MERGE. All commits in a PR are squashed into ONE commit on main.

**Critical Implication**: After a PR is merged, the original commit SHAs no longer exist on main.

**Post-Merge Sync**:
```bash
git checkout main && git pull
git branch -d old-branch-name  # Safe delete, squashed commits are on main
```

## Stacked PRs (Branch from Branch)

Sometimes you may branch from an existing feature branch (stacked PRs).
This is acceptable but requires extra care.

**When to use**: Sequential dependent changes where PR2 depends on PR1's code.

**Workflow**:
1. Create `branch-a` from main, make changes, push, create PR1
2. Create `branch-b` from `branch-a` (not main!), make dependent changes, push, create PR2
3. Mark PR2 as "DO NOT MERGE - Stacked on #PR1" in description
4. After PR1 is squash-merged:
   ```bash
   git checkout branch-b
   git fetch origin main
   git rebase origin/main
   # Resolve any conflicts
   git push --force-with-lease
   ```
5. PR2 is now ready for independent merge

## Commit Format

Use conventional commits: `type: description`

Examples:
- `feat: add Jenkins metadata scaffold`
- `fix: handle null pointer in completion`
- `refactor: extract helper methods`
- `test: add edge case coverage`
- `docs: update API documentation`
- `ci: fix workflow permissions`
- `chore: update dependencies`

## PR Title Format

Pull Request titles MUST also use conventional style: `type: description`

## Ship It Definition

When user says "ship it": execute full workflow and open PR.
Expected delivery: Pull Request created and ready for review.

## Local Git Ignore

File: `.git/info/exclude` (local ignore, not shared with others)

Add files/folders that should be ignored only on your machine:
```bash
echo "filename.ext" >> .git/info/exclude
echo "folder-name/" >> .git/info/exclude
```

Use for: local dev files, personal notes, temp directories.
NOT for: files all developers should ignore (use .gitignore instead).

**Default Behavior**: When asked to "ignore a file" or "add to ignore": ALWAYS use `.git/info/exclude`. Only use .gitignore when explicitly told "add to .gitignore".
