---
description: Git workflow, branching, and commit conventions
---

# Git Workflow

<critical>
These rules are NON-NEGOTIABLE. Violations require immediate correction.
</critical>

## Safety Rules

| Rule                   | Command                                   | Rationale                   |
| ---------------------- | ----------------------------------------- | --------------------------- |
| Never commit on main   | `git branch --show-current` before commit | Protects main branch        |
| Stage files explicitly | `git add file1.kt file2.kt`               | Prevents accidental commits |
| Verify before push     | `git status` + `git diff --cached`        | Catches mistakes early      |
| Use worktrees          | `git worktree add -b <branch> <path>`     | Keep main worktree clean    |

<forbidden>
- `git add .` or `git add -A` — NEVER use wildcard staging
- `git commit` without verifying branch — ALWAYS check first
- `git push --force` — Use `--force-with-lease` instead
</forbidden>

---

## Branching Strategy

### For New Work

```bash
# 1. Ensure main is current
git checkout main
git pull origin main

# 2. Create a worktree for the feature branch
git worktree add -b <type>/<short-description> ../gvy-<short-description>
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
2. Branch can be safely deleted after merge
3. PR title becomes the commit message — make it good

### Post-Merge Cleanup

```bash
git checkout main
git pull origin main
git branch -d <merged-branch>  # Safe delete
```

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

You can work on multiple PRs simultaneously:

1. Complete work on Branch A → commit, push, create PR
2. Switch to main → pull → create Branch B
3. If PR A needs fixes → `git stash push -m "B work"` → checkout A → fix → checkout B → `git stash pop`

**Key**: Always verify current branch before committing.

---

## Local Ignore

For files that should be ignored only on YOUR machine (not shared):

```bash
echo "my-local-notes.md" >> .git/info/exclude
echo ".scratch/" >> .git/info/exclude
```

Use `.git/info/exclude` for personal files. Use `.gitignore` only for files ALL developers should ignore.
