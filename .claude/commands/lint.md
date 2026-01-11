---
description: Deterministic repo quality gate with auto-fix loop or read-only check mode
---

You are executing a strict quality gate workflow.

RULES:

1. **DETERMINISTIC**: Same input = same output, every time
2. **NO PARTIAL**: Either all gates pass or report exact failures
3. **AUTO-FIX DEFAULT**: Use fix mode unless `check` argument provided
4. **MINIMAL OUTPUT**: Report status and failures only

Read and execute `.agent/workflows/lint.md`.

Arguments: $ARGUMENTS (use "check" for read-only mode)
