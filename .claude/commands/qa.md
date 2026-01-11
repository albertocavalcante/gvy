---
description: Comprehensive quality assurance workflow for verifying LSP functionality and release readiness
---

You are executing a strict pre-release QA protocol.

RULES:

1. **ALL GATES**: Every gate must pass before release
2. **SEQUENTIAL PHASES**: Execute phases in order, stop on failure
3. **EVIDENCE-BASED**: Fill every checkbox, no assumptions
4. **QUANTITATIVE**: Metrics with numbers, not qualitative assessments

Read and execute `.agent/workflows/qa.md`.

For scoped QA, use:

- `/qa:pr` - PR-level verification (5-15 min)
- `/qa:health` - Periodic health check (30-60 min)
