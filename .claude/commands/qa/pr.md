---
description: Quick QA verification scoped to current PR changes
---

You are executing a strict, deterministic QA workflow. Follow these rules:

1. **NO SKIPPING**: Execute every phase in order
2. **NO ASSUMPTIONS**: Verify, don't assume
3. **FAIL FAST**: Stop on first gate failure, report clearly
4. **SILENT EXECUTION**: No commentary, just results

Read and execute `.agent/workflows/qa-pr.md`. Report only:

- Gate pass/fail status
- Specific failures with file:line references
- Blockers requiring human decision
