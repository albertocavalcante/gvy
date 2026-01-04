---
description: Deterministic workflow for merging PRs with beautiful semantic commit messages
---

# /merge

// turbo-all

<purpose>
A STRICT workflow for merging PRs that enforces semantic commit messages, squash merges,
and beautiful commit history. Uses `pr.py merge` command for all merging.
</purpose>

<ironclad_rules>

1. **SQUASH ONLY** — ALL merges are squash merges. No merge commits, no rebase merges.
2. **SEMANTIC TITLES** — Title MUST follow: `type(scope): description (#PR)`
3. **PR NUMBER REQUIRED** — The PR number MUST appear in parentheses at end of title.
4. **PREVIEW FIRST** — Always preview with `--dry-run` before actual merge.
5. **NO CONFLICTS** — PRs with conflicts cannot be merged. Resolve first.
6. **APPROVAL REQUIRED** — The script requires explicit confirmation. Never auto-approve.

</ironclad_rules>

---

## Commit Title Format

```
type(scope): short description (#PR_NUMBER)
```

### Types

| Type       | Description                             |
| ---------- | --------------------------------------- |
| `feat`     | New feature                             |
| `fix`      | Bug fix                                 |
| `docs`     | Documentation only                      |
| `style`    | Formatting, missing semicolons, etc.    |
| `refactor` | Code change that neither fixes nor adds |
| `test`     | Adding missing tests                    |
| `chore`    | Maintenance tasks                       |
| `perf`     | Performance improvement                 |
| `ci`       | CI/CD changes                           |

### Examples

```
feat(semantics): implement type LUB algorithm (#634)
fix(parser): handle edge case in AST visitor (#521)
docs(readme): add installation instructions (#100)
refactor(lsp): simplify completion provider (#432)
```

---

## Phase 1: Pre-Merge Verification

### 1.1 Check PR Status

```bash
# Verify PR is open and mergeable
gh pr view <PR_NUMBER> --json state,mergeable,reviewDecision
```

Expected output:

- `state: OPEN`
- `mergeable: MERGEABLE`
- `reviewDecision: APPROVED` (if required)

### 1.2 Ensure All Threads Resolved

```bash
uv run .agent/scripts/pr.py threads <PR_NUMBER> --refetch
```

Expected: `COUNT=0` (no unresolved threads)

---

## Phase 2: Preview Merge

### 2.1 Dry Run

```bash
# Preview merge (auto-detect PR)
uv run .agent/scripts/pr.py merge --dry-run

# Include related issues (e.g. tracking issues)
uv run .agent/scripts/pr.py merge --relates-to 622 --dry-run
```

This will:

- Fetch PR details & linked issues (Fixes)
- Include related issues (Relates to #N)
- Validate/generate semantic title
- Show preview WITHOUT merging

### 2.2 If Title Invalid

If the existing PR title isn't semantic, provide one:

```bash
uv run .agent/scripts/pr.py merge \
  --title "feat(semantics): implement type inference (#<PR_NUMBER>)" \
  --dry-run
```

---

## Phase 3: Execute Merge

### 3.1 Final Merge

```bash
uv run .agent/scripts/pr.py merge \
  --title "feat(semantics): implement type inference (#<PR_NUMBER>)" \
  --relates-to 622
```

The script will:

1. Show preview
2. Ask for confirmation: `✨ Proceed with squash merge? [y/N]`
3. On `y`: Execute `gh pr merge --squash`
4. On `N`: Cancel

---

## Quick Reference

```
# Preview merge (auto-detect PR)
uv run .agent/scripts/pr.py merge --dry-run

# Generate semantic commit message using AI
uv run .agent/scripts/pr.py merge --ai --dry-run

# Merge with related issues
uv run .agent/scripts/pr.py merge --relates-to 622

# Merge with custom title
uv run .agent/scripts/pr.py merge --title "feat(semantics): add type LUB (#634)"
```

---

## Troubleshooting

### "Invalid commit title"

Your title doesn't match the semantic format. Requirements:

- Start with a type: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `perf`, `ci`
- Optional scope in parentheses: `(semantics)`, `(parser)`, `(lsp)`
- Colon and space after type/scope
- Descriptive message
- PR number at end in parentheses: `(#634)`

### "PR has conflicts"

Resolve conflicts first:

```bash
git fetch origin main
git rebase origin/main
# Resolve conflicts
git push --force-with-lease
```

### "PR is not open"

The PR may be closed or already merged. Check:

```bash
gh pr view <PR_NUMBER> --json state
```
