---
description: Deterministic rebase protocol (preflight → rebase → resolve → verify → force-with-lease) for this repo.
---

# /rebase

A deterministic workflow to rebase a feature branch onto `origin/main` **safely**.

This repo uses squash merges, so rebasing is normal. Rebasing rewrites history; treat it as a controlled operation.

## Agent contract (non-negotiable)

- MUST have a clean working tree before starting.
- MUST fetch `origin/main` and rebase onto it (not local `main`).
- MUST NOT guess during conflict resolution. Always inspect both sides.
- MUST run verification commands after the rebase.
- MUST push with `--force-with-lease` (never `--force`).

## Preflight (before rebasing)

1. Confirm you are not on `main`:

```bash
git branch --show-current
```

2. Ensure clean working tree:

```bash
git status --porcelain
```

- If not empty: STOP. Commit, stash, or revert intentionally.

3. Capture “before” state (for debugging):

```bash
git rev-parse --short HEAD
git log --oneline --decorate -n 20
```

4. Run the repo lint gate **before** rebasing (baseline signal):

```bash
make lint
```

If it fails: STOP. Fix first (use `/lintfix`).

## Rebase

1. Fetch latest `origin/main`:

```bash
git fetch origin main
```

2. Start the rebase:

```bash
git rebase origin/main
```

## Conflict resolution (when rebase stops)

1. List conflicts:

```bash
git status
```

2. For each conflicted file:

- Open the file and resolve `<<<<<<<`, `=======`, `>>>>>>>` markers.
- Keep the minimal correct union; do not do style-only refactors while resolving.
- Remove all conflict markers.

3. Stage resolved files explicitly:

```bash
git add path/to/file1.kt
```

4. Continue:

```bash
git rebase --continue
```

### Deterministic conflict tactic (few-shot)

When in doubt, resolve by **intent**, not by side:

- If the conflict is purely formatting/whitespace: pick either side, then run format/lint after.
- If upstream changed APIs and your code calls them: adapt your code to the new API.
- If both sides changed the same logic:
  - Prefer the version that matches the new `main` contract.
  - Re-apply your feature change minimally.

If you cannot explain why a hunk is chosen: STOP and ask for guidance.

## Abort (safe escape)

If you get confused or the conflict surface is too large:

```bash
git rebase --abort
```

Then re-run the Preflight and try again with a smaller change surface.

## Post-rebase verification

1. Confirm clean state and new HEAD:

```bash
git status --porcelain
git rev-parse --short HEAD
```

2. Run repo lint gate:

```bash
make lint
```

3. If the changes are non-trivial, run build:

```bash
make build
```

## Push (update existing PR)

Because rebase rewrites commit SHAs:

```bash
git push --force-with-lease
```

## Output (required)

Record exactly:

```
- Before: <old sha>
- After:  <new sha>
- Conflicts: [none | list files]
- Verification: make lint PASS (and make build if run)
- Push: force-with-lease done
```
