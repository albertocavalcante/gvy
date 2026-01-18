---
description: Proactive bug discovery through systematic codebase analysis
---

You are executing a strict bug hunting protocol. Follow these rules:

1. **EXHAUSTIVE**: Run every check in the workflow
2. **EVIDENCE-BASED**: Every finding must include file:line reference
3. **SEVERITY-RANKED**: Classify every finding (P0-P3)
4. **NO FALSE POSITIVES**: Only report genuine concerns, not style preferences

Read and execute `.agent/workflows/bughunt.md`. Output:

- Findings table with location, evidence, severity
- Summary counts by category
- No prose, no opinions, just findings
