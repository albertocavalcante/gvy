# Implementation Roadmap

> **Overview**: Unified roadmap for completing `groovyparser-core` and integrating it into the LSP.

## Current Branch Status

**Branch**: `feat/groovyparser-core`

| Category | Status |
|----------|--------|
| Unpushed commits | 3 (type inference, AST fixes, heuristic docs) |
| Uncommitted changes | Engine scaffolding in `groovy-lsp/` |
| Tests passing | ✅ 95+ tests |

---

## Recommended Execution Order

### Step 1: Push Current Work ⏱️ 5 min

```bash
# Verify branch
git branch --show-current  # Should be feat/groovyparser-core

# Push the 3 unpushed commits
git push origin feat/groovyparser-core
```

### Step 2: Commit Engine Scaffolding ⏱️ 30 min

The uncommitted engine work is a good foundation but needs cleanup:

1. **Fix diagnostics mapping** (currently returns empty list)
2. **Add basic tests** for engine creation
3. **Commit with clear message**

```bash
# After fixes
git add groovy-lsp/src/main/kotlin/com/github/albertocavalcante/groovylsp/engine/
git add groovy-lsp/src/main/kotlin/com/github/albertocavalcante/groovylsp/compilation/GroovyCompilationService.kt
git add groovy-lsp/src/main/kotlin/com/github/albertocavalcante/groovylsp/services/GroovyTextDocumentService.kt
git add groovy-lsp/src/main/kotlin/com/github/albertocavalcante/groovylsp/GroovyLanguageServer.kt

git commit -m "feat(lsp): add Language Engine abstraction for parser integration

- Add LanguageEngine, LanguageSession, FeatureSet interfaces
- Implement NativeLanguageEngine wrapping GroovyParserFacade
- Add NativeHoverProvider using ParseResult
- Wire hover to use session.features.hoverProvider
- Add getSession(uri) to GroovyCompilationService"
```

### Step 3: Complete Parser TODOs ⏱️ 4-6 hours

Follow `.spec/GROOVYPARSER_CORE_COMPLETION.md`:

| Phase | Task | Effort |
|-------|------|--------|
| 1 | Local variable resolution | 2-3 hours |
| 2 | Nested class resolution | 1-2 hours |
| 3 | Method type parameters | 1 hour |

**TDD Workflow for each**:
1. Write failing test
2. Run test, verify RED
3. Implement minimal fix
4. Run test, verify GREEN
5. Commit

### Step 4: Migrate LSP Providers ⏱️ 4-6 hours

Follow `.spec/LSP_ENGINE_INTEGRATION.md` Phase 2:

Migrate in this order (dependencies first):
1. Definition (used by many others)
2. References
3. Completion
4. DocumentHighlight
5. Rename
6. Others...

### Step 5: Create PR ⏱️ 30 min

```bash
# Ensure all tests pass
make test

# Ensure lint passes
make lint

# Create PR
gh pr create --base main --title "feat: groovyparser-core standalone library with LSP integration" \
  --body-file /tmp/pr-body.md
```

---

## PR Structure Options

### Option A: Single Large PR

**Pros**: Complete feature in one review
**Cons**: Large diff, harder to review

### Option B: Split PRs (Recommended)

| PR | Scope | Base |
|----|-------|------|
| PR 1 | `groovyparser-core` module (current 95+ tests) | `main` |
| PR 2 | Parser TODO fixes (local vars, nested classes) | PR 1 |
| PR 3 | Engine abstraction + hover migration | PR 2 |
| PR 4 | Remaining provider migrations | PR 3 |

**Pros**: Easier review, incremental merge
**Cons**: More coordination

### Recommendation

Given the branch already has significant work:
1. **Push current state** as PR 1 (groovyparser-core foundation)
2. **Fix TODOs** in follow-up PR 2
3. **Engine integration** in PR 3

---

## Risk Mitigation

### Risk: Regressions in LSP

**Mitigation**:
- Run E2E tests before each commit
- Keep Native engine as fallback
- Feature flag for new code paths

### Risk: Type inference edge cases

**Mitigation**:
- Documented heuristics ("Golden Notes")
- Fallback to `Object` type
- Comprehensive test coverage

### Risk: Performance degradation

**Mitigation**:
- Benchmark before/after
- Cache session objects
- Lazy provider initialization

---

## Definition of Done

### groovyparser-core Complete
- [ ] All 3 TODOs resolved
- [ ] 100+ tests passing
- [ ] Published to GitHub Packages
- [ ] README with usage examples

### LSP Integration Complete
- [ ] All providers use engine abstraction
- [ ] No direct ParseResult access in TextDocumentService
- [ ] E2E tests pass
- [ ] No performance regression

### Documentation Complete
- [ ] API documentation (KDoc)
- [ ] Architecture decision records
- [ ] Migration guide for contributors

---

## Timeline Estimate

| Milestone | Effort | Target |
|-----------|--------|--------|
| Push current work | 5 min | Today |
| Commit engine scaffolding | 30 min | Today |
| Parser TODOs | 4-6 hours | +1-2 days |
| Provider migration | 4-6 hours | +2-3 days |
| PR review & merge | Variable | +3-5 days |

**Total**: ~2 weeks to full integration (with review cycles)

---

## Quick Reference

### Key Files

| File | Purpose |
|------|---------|
| `groovyparser-core/` | Standalone parser library |
| `.../resolution/contexts/` | Symbol resolution contexts (TODOs here) |
| `.../engine/api/` | LSP engine interfaces |
| `.../engine/impl/native/` | Native engine implementation |
| `GroovyCompilationService.kt` | Bridge between LSP and parser |
| `GroovyTextDocumentService.kt` | LSP protocol handlers |

### Commands

```bash
# Run parser tests
./gradlew :groovyparser-core:test

# Run specific test
./gradlew :groovyparser-core:test --tests "*MethodContextTest*"

# Run all tests
make test

# Check lint
make lint

# Fix lint
make format
```

### Related Specs

- `.spec/GROOVYPARSER_CORE_COMPLETION.md` - Parser TODO details
- `.spec/LSP_ENGINE_INTEGRATION.md` - Engine integration details
