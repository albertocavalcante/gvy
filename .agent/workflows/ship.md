---
description: Strict, deterministic workflow for shipping code (commit, push, PR) with maximum safety guarantees
---

# /ship

// turbo-all

<purpose>
A STRICT, DETERMINISTIC workflow for shipping code. verifying worktree isolation, selective staging, hook compliance, and structured PR creation.
</purpose>

<ironclad_rules>

1. **WORKTREE IS KING** — NEVER ship from `main`. ALWAYS verify you are in a feature worktree.
2. **SELECTIVE STAGING** — `git add .` and `git add -A` are BANNED. Add files explicitly.
3. **HOOK COMPLIANCE** — Manually verify `direnv allow` and `spotlessApply` BEFORE commit.
4. **SEMANTIC COMMITS** — Use conventional commit format. Use multiple `-m` flags for body.
5. **TEMP FILE PR BODY** — Write PR body to a temp file first, then create.
6. **NO BROWSERS** — Use `gh` CLI for everything. </ironclad_rules>

---

## Phase 0: Verification (SAFETY FIRST)

### 0.1 Worktree & Branch Check

<critical>
You MUST be in a feature branch/worktree. Shipping from `main` is FORBIDDEN.
</critical>

```bash
# 1. Verify we are NOT on main
git branch --show-current | grep -v "main" || { echo "ERROR: You are on main!"; exit 1; }

# 2. Verify we are in a worktree (optional but recommended)
git worktree list | grep "$(pwd)"
```

### 0.2 Hook Prerequisites

<critical>
Pre-commit hooks often fail on these. Run them PROACTIVELY.
</critical>

```bash
# 1. Allow direnv (if using)
direnv allow .

# 2. Apply formatting (essential for Java/Kotlin/Groovy)
./gradlew spotlessApply
```

---

## Phase 1: Staging (PRECISION SURGERY)

<critical>
`git add .` is banned. It creates noise and unintended commits.
</critical>

### 1.1 Review Changes

```bash
# See what's changed
git status --short
```

### 1.2 Select Files

Add files individually or by specific patterns.

```bash
git add <path/to/file1> <path/to/file2>
# OR
git add <directory/>
```

### 1.3 Verify Staged Content

```bash
git diff --cached --name-status
```

---

## Phase 2: Committing (SEMANTIC & CLEAN)

### 2.1 Commit Message Construction

Use conventional commits: `type(scope): description`. For multiline bodies, use multiple `-m` flags.

**Types:** `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`.

```bash
git commit \
  -m "feat(gradle): implement JDK compatibility service" \
  -m "This adds a data-driven compatibility service and failure analyzer." \
  -m "- Added gradle-compatibility.json" \
  -m "- Implemented GradleCompatibilityService" \
  -m "- Added unit tests"
```

---

## Phase 3: Pushing

### 3.1 Push to Remote

```bash
# Push to the current branch on origin
git push origin "$(git branch --show-current)"
```

---

## Phase 4: Pull Request (STRUCTURED)

### 4.1 Draft PR Body

Use `write_to_file` (or your agent's equivalent native file tool) to create the PR description. This avoids shell
escaping hell.

**Target File:** `/tmp/pr_body.md` (or a git-ignored path like `.gemini/pr_body.md`)

```markdown
# Description

[Brief summary of changes]

# Type of Change

- [ ] Bug fix
- [x] New feature
- [ ] Refactoring
- [ ] Documentation

# Checklist

- [x] My code follows the code style of this project
- [ ] I have added tests to cover my changes
- [ ] All new and existing tests passed

# Verification

[Details on how you verified this change]
```

### 4.2 Create PR

```bash
gh pr create \
  --title "<Semantic Title>" \
  --body-file /tmp/pr_body.md \
  --web=false
```

### 4.3 Cleanup

```bash
rm /tmp/pr_body.md
```

---

## Quick Reference

```
1. VERIFY: git branch | grep -v main && direnv allow . && ./gradlew spotlessApply
2. STAGE:  git add <files> (NO git add .)
3. COMMIT: git commit -m "title" -m "body"
4. PUSH:   git push origin <branch>
5. PR:     echo "body" > /tmp/b.md && gh pr create -F /tmp/b.md
```
