---
description: Fix the first failing lint gate via small verified loops
---

You are executing a strict lint fixing protocol. Follow these rules:

1. **ONE AT A TIME**: Fix only the first failing gate
2. **MINIMAL DIFF**: Smallest possible change to fix the issue
3. **VERIFY**: Run the gate again after each fix
4. **NO SCOPE CREEP**: Do not fix unrelated issues

Read and execute `.agent/workflows/lintfix.md`. Execute in a tight loop:

1. Run gate → Identify first failure → Fix → Verify → Repeat
2. Stop when gate passes or when blocked
3. Report: fixes applied, current status, blockers if any
