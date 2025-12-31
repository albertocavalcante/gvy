# LSP Engine Integration Plan

> **Goal**: Complete the Abstract Factory pattern integration to allow the LSP to use `groovyparser-core` through a clean, extensible architecture.

## Current State

### What Exists (Uncommitted)

```
groovy-lsp/src/main/kotlin/.../engine/
├── api/
│   └── LanguageEngineApi.kt      # Interfaces: LanguageEngine, LanguageSession, FeatureSet, HoverProvider
└── impl/
    └── native/
        ├── NativeLanguageEngine.kt       # Factory using GroovyParserFacade
        └── features/
            └── NativeHoverProvider.kt    # Hover implementation using ParseResult
```

### Integration Points (Modified)

| File | Change |
|------|--------|
| `GroovyCompilationService.kt` | Added `getSession(uri)` method, `activeEngine` field |
| `GroovyTextDocumentService.kt` | Hover now delegates to `session.features.hoverProvider` |
| `GroovyLanguageServer.kt` | Passes `documentProvider` and `sourceNavigator` to compilation service |

### What's Missing

1. **Other providers not migrated** - Only Hover is wired up
2. **No configuration** - Engine is hardcoded to `NativeLanguageEngine`
3. **No tests** - Engine layer is untested
4. **Diagnostics not mapped** - `ParseResultMetadata.diagnostics` returns empty list

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    GroovyTextDocumentService                     │
│  (LSP Protocol Handler)                                          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    GroovyCompilationService                      │
│  - compile(uri, content) → CompilationResult                     │
│  - getSession(uri) → LanguageSession                             │
└─────────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
┌─────────────────────────┐     ┌─────────────────────────┐
│    NativeLanguageEngine │     │   (Future) CoreEngine   │
│    (Groovy Compiler)    │     │   (groovyparser-core)   │
└─────────────────────────┘     └─────────────────────────┘
              │                               │
              ▼                               ▼
┌─────────────────────────┐     ┌─────────────────────────┐
│   NativeLanguageSession │     │    CoreLanguageSession  │
│   - ParseResult         │     │    - CompilationUnit    │
│   - features: FeatureSet│     │    - features: FeatureSet│
└─────────────────────────┘     └─────────────────────────┘
```

### Why This Pattern?

1. **Decoupling**: LSP services don't know which parser is used
2. **Extensibility**: Can add `CoreEngine` using `groovyparser-core`'s custom AST
3. **Testability**: Can mock `LanguageSession` for unit tests
4. **Future-proofing**: Could support multiple engines (e.g., different Groovy versions)

---

## Phase 1: Complete Native Engine (Current PR)

**Goal**: Finish the uncommitted work and make it production-ready.

### 1.1 Fix Diagnostics Mapping

**Current Issue**: `ParseResultMetadata.diagnostics` returns `emptyList()`.

```kotlin
// NativeLanguageSession.kt - CURRENT (broken)
override val result: ParseResultMetadata = object : ParseResultMetadata {
    override val diagnostics: List<Diagnostic> = emptyList() // TODO: Map diagnostics
}
```

**Fix**:
```kotlin
override val result: ParseResultMetadata = object : ParseResultMetadata {
    override val isSuccess: Boolean = parseResult.isSuccessful
    override val diagnostics: List<Diagnostic> = parseResult.diagnostics.map { it.toLspDiagnostic() }
}
```

### 1.2 Add Missing Provider Interfaces

Extend `FeatureSet` with all LSP features:

```kotlin
interface FeatureSet {
    val hoverProvider: HoverProvider
    val definitionProvider: DefinitionProvider
    val completionProvider: CompletionProvider
    val referencesProvider: ReferencesProvider
    val renameProvider: RenameProvider
    val documentHighlightProvider: DocumentHighlightProvider
    val foldingRangeProvider: FoldingRangeProvider
    val codeActionProvider: CodeActionProvider
    // ... etc
}
```

### 1.3 Implement Stub Providers

For Phase 1, create stub implementations that delegate to existing providers:

```kotlin
class NativeDefinitionProvider(
    private val parseResult: ParseResult,
    private val compilationService: GroovyCompilationService,
    private val sourceNavigator: SourceNavigator?,
) : DefinitionProvider {
    
    override fun getDefinition(params: DefinitionParams): CompletableFuture<...> {
        // Delegate to existing DefinitionResolver
        val resolver = DefinitionResolver(compilationService, sourceNavigator)
        return resolver.resolve(params)
    }
}
```

### 1.4 Wire Up in TextDocumentService

Migrate each method to use session:

```kotlin
// BEFORE
override fun definition(params: DefinitionParams) = coroutineScope.future {
    val resolver = DefinitionResolver(compilationService, sourceNavigator)
    resolver.resolve(params)
}

// AFTER
override fun definition(params: DefinitionParams) = coroutineScope.future {
    val uri = URI.create(params.textDocument.uri)
    ensureAstPrepared(uri)
    val session = compilationService.getSession(uri)
    session?.features?.definitionProvider?.getDefinition(params)?.await()
        ?: Either.forLeft(emptyList())
}
```

### 1.5 Add Tests

```kotlin
class NativeLanguageEngineTest {
    
    @Test
    fun `createSession returns session with working hover`() {
        val engine = NativeLanguageEngine(parserFacade, documentProvider, null)
        val session = engine.createSession(ParseRequest(...))
        
        assertNotNull(session)
        assertNotNull(session.features.hoverProvider)
    }
    
    @Test
    fun `session diagnostics are mapped correctly`() {
        val code = "class Foo { invalid syntax"
        val session = engine.createSession(ParseRequest(uri, code, ...))
        
        assertFalse(session.result.isSuccess)
        assertTrue(session.result.diagnostics.isNotEmpty())
    }
}
```

### 1.6 Acceptance Criteria

- [ ] Diagnostics properly mapped from ParseResult
- [ ] All 15 LSP features have provider interfaces
- [ ] NativeEngine implements all providers (delegating to existing code)
- [ ] TextDocumentService uses session for hover
- [ ] Unit tests for engine creation and session
- [ ] No regressions in existing LSP functionality

---

## Phase 2: Migrate All Providers

**Goal**: Move all LSP features to use the engine pattern.

### Provider Migration Checklist

| Provider | Interface | Native Impl | Migrated in TDS |
|----------|-----------|-------------|-----------------|
| Hover | ✅ | ✅ | ✅ |
| Definition | ⬜ | ⬜ | ⬜ |
| TypeDefinition | ⬜ | ⬜ | ⬜ |
| References | ⬜ | ⬜ | ⬜ |
| Implementation | ⬜ | ⬜ | ⬜ |
| Completion | ⬜ | ⬜ | ⬜ |
| SignatureHelp | ⬜ | ⬜ | ⬜ |
| DocumentHighlight | ⬜ | ⬜ | ⬜ |
| DocumentSymbol | ⬜ | ⬜ | ⬜ |
| CodeAction | ⬜ | ⬜ | ⬜ |
| CodeLens | ⬜ | ⬜ | ⬜ |
| FoldingRange | ⬜ | ⬜ | ⬜ |
| Rename | ⬜ | ⬜ | ⬜ |
| SemanticTokens | ⬜ | ⬜ | ⬜ |
| Formatting | ⬜ | ⬜ | ⬜ |

### Migration Strategy

1. **One provider per commit** - Easy to review and revert
2. **Preserve existing behavior** - Native impl delegates to current code
3. **Add interface test** - Verify contract before migrating

---

## Phase 3: Add Core Engine (Future)

**Goal**: Create an alternative engine using `groovyparser-core`'s custom AST.

### Why?

The custom AST in `groovyparser-core` provides:
- Cleaner node hierarchy (no Groovy compiler internals)
- Better position tracking
- Comment preservation
- Type inference integration

### 3.1 Create CoreLanguageEngine

```kotlin
class CoreLanguageEngine(
    private val typeSolver: TypeSolver,
) : LanguageEngine {
    override val id = "core"
    
    override fun createSession(request: ParseRequest): LanguageSession {
        val cu = StaticGroovyParser.parse(request.content)
        val resolver = GroovySymbolResolver(typeSolver)
        resolver.inject(cu)
        return CoreLanguageSession(cu, resolver)
    }
}
```

### 3.2 Implement Core Providers

These would use the custom AST directly:

```kotlin
class CoreHoverProvider(
    private val compilationUnit: CompilationUnit,
    private val resolver: GroovySymbolResolver,
) : HoverProvider {
    
    override fun getHover(params: HoverParams): CompletableFuture<Hover> {
        val position = params.position.toGroovyPosition()
        val node = compilationUnit.findNodeAt(position)
        
        return when (node) {
            is VariableExpr -> {
                val resolved = resolver.resolve(node)
                if (resolved.isSolved) {
                    createHoverForDeclaration(resolved.getDeclaration())
                } else {
                    emptyHover()
                }
            }
            is MethodCallExpr -> createHoverForMethodCall(node)
            // ... etc
        }
    }
}
```

### 3.3 Engine Selection

Add configuration to select engine:

```kotlin
// In GroovyCompilationService
private val activeEngine: LanguageEngine by lazy {
    when (config.engine) {
        "native" -> NativeLanguageEngine(parser, documentProvider, sourceNavigator)
        "core" -> CoreLanguageEngine(createTypeSolver())
        else -> NativeLanguageEngine(...)
    }
}
```

### 3.4 Gradual Migration

1. Start with `native` as default
2. Add `core` as experimental option
3. Compare behavior in tests
4. Switch default when `core` is stable

---

## Phase 4: Configuration & Polish

### 4.1 Engine Configuration

```json
// .groovy-lsp/config.json
{
  "engine": "native",  // or "core"
  "features": {
    "typeInference": true,
    "flowAnalysis": false
  }
}
```

### 4.2 Telemetry

Track which engine is used and feature usage:

```kotlin
interface LanguageSession {
    val engineId: String
    fun recordFeatureUsage(feature: String)
}
```

### 4.3 Documentation

- Update README with engine options
- Document when to use each engine
- Add troubleshooting guide

---

## Execution Plan

| Phase | Scope | Effort | PR |
|-------|-------|--------|-----|
| 1.1-1.3 | Fix diagnostics, add interfaces | 1 hour | Current uncommitted |
| 1.4-1.6 | Wire hover, add tests | 2 hours | Current uncommitted |
| 2 | Migrate all providers | 4-6 hours | `feat/engine-providers` |
| 3 | Core engine | 8+ hours | `feat/core-engine` |
| 4 | Config & polish | 2 hours | `feat/engine-config` |

### Immediate Next Steps

1. **Commit current work** - Push the uncommitted engine scaffolding
2. **Fix diagnostics** - Map ParseResult diagnostics to LSP
3. **Add tests** - Verify engine creation works
4. **Migrate hover fully** - Ensure no regressions

---

## Success Metrics

After Phase 2:
- [ ] All LSP features work through engine abstraction
- [ ] No direct ParseResult access in TextDocumentService
- [ ] Unit tests for each provider interface
- [ ] No regressions in E2E tests

After Phase 3:
- [ ] Core engine passes same tests as Native engine
- [ ] Can switch engines via configuration
- [ ] Performance comparable between engines
