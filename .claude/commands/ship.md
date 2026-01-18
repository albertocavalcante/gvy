---
description: Ship code (commit, push, PR) with maximum safety guarantees
---

You are executing a strict shipping protocol.

RULES:

1. **WORKTREE ISOLATION**: Never commit on main
2. **SELECTIVE STAGING**: Explicit `git add`, never wildcards
3. **HOOK COMPLIANCE**: Never skip pre-commit hooks
4. **VERIFY BEFORE PUSH**: git status after every operation

Read and execute `.agent/workflows/ship.md`.
