# gvy Modernization Audit

Tech-debt inventory, Kotlin idiom upgrade plan, breaking-change RFCs and a phased PR plan for the Groovy Language
Server, grounded in a read-only review of every main-source module.

| Field              | Value                                                              |
| ------------------ | ------------------------------------------------------------------ |
| Repository         | albertocavalcante/gvy                                              |
| Language / runtime | Kotlin 2.3.0, JVM 17 target, LSP 3.17 (lsp4j 0.24)                 |
| Audited head       | `d6f5ca66` (main, 2026-11-09)                                      |
| Scope: main Kotlin | 95.4K LOC / 38 modules                                             |
| Scope: tests       | 115.8K LOC / 4,571 tests                                           |
| Method             | 7 parallel subsystem reviews, 11 High claims re-verified at source |

Contents: Executive summary, Part 1 (debt inventory), Part 2 (Kotlin idiom plan), Part 3 (breaking-change RFCs), Part 4
(phased PR plan), Appendix (verification and limits).

## Executive summary: the server is architecturally sound at its core and carries its debt at the edges

The compile pipeline landed in #1193 and #1194 (snapshot identity binding, owner/waiter retry, explicit lock ordering)
is careful and well commented. The debt sits around it: three parallel resolution engines that can disagree, three
parallel ASTs of which only one carries production, mutable workspace state read without synchronization, a dozen broad
catches that swallow coroutine cancellation, and per-keystroke work that rebuilds data nothing invalidated.

- **126** findings across 7 subsystems

- **24** rated High

- **234** detekt baseline suppressions in 16 modules

- **0** value classes, contracts, or explicitApi() anywhere

- **11** resolve-to-declaration paths in gls

- **~4.1K** LOC with zero production callers

### Six findings that change what you build next

#### Resolution runs on raw Groovy AST; the SemanticDB index is a third fallback.

_gls/providers/definition/DefinitionResolver.kt:64-80 · HoverProvider has zero gvy.semantics imports · 7 callers of
resolveToDefinition_

The index is populated on every compile and already drives rename, implementation and cross-file references. Definition,
hover and signature help re-derive answers on `org.codehaus.groovy` nodes through 11 distinct paths and 4 copies of
receiver-type inference. Unifying on the index is the highest-leverage structural change (RFC-1).

#### Workspace model is mutable shared state read without synchronization.

_gls/compilation/WorkspaceManager.kt:34-38, 129-138, 206, 219, 273_

Source roots and classpath are plain mutable collections written from the dependency-manager callback on
`Dispatchers.Default` while every compile reads them. Torn fingerprints or a `ConcurrentModificationException` surface
as a bogus "Compilation error" diagnostic at 0:0 (CompilationOrchestrator.kt:120-126).

#### Cancellation is swallowed in at least nine request paths.

_GroovyTextDocumentService.kt:485-491 · WorkspaceCompiler.kt:267-277 · SymbolIndexingService.kt:188-219 ·
SignatureHelpProvider.kt:342 · TestRequestDelegate.kt:58-67 · ReferenceProvider.kt:116-126 · plus runCatching in
semantics/native and build-tool_

`catch (e: Exception)` inside `future {}` and `runCatching` around suspend calls turn `$/cancelRequest` into logged
errors and fake empty results. The repo already has the right helper
(`utils/CoroutineUtils.kt: rethrowIfCancellationOrError`); it is used inconsistently.

#### Per-keystroke work rebuilds state that nothing invalidated.

_jenkins/JenkinsContext.kt:259-262 · codenarc/ProjectTypeDetector.kt:186-199 · CompletionProvider.kt:89,98 ·
JavaSourceInspector.kt:48-61 · HoverProvider.kt:77-96_

Every completion and hover re-reads and re-deserializes both Jenkins metadata JSON resources and re-sorts every map.
Every diagnostics run walks `src/test/groovy` reading each spec twice. Completion compiles the buffer twice per
keystroke with no cancellation check. Hover re-parses JDK sources with JavaParser on cache hit.

#### Three ASTs coexist; two are dead weight.

_parser/core Node.kt:10-27 · parser/core/build.gradle.kts:17 api(libs.groovy.core) · gls imports: org.codehaus 94 files,
parser/core 7, parser/api 1_

The Kotlin tree in parser/core is a lossy copy 3 to 4 times heavier per node than the Groovy tree it mirrors, with ~700
LOC of cloners and a 53-overload visitor that have no production callers. parser/api has three implementations and one
consumer. The gls engine abstraction serves one line of production code (hover) and defaults to the parser nothing else
uses.

#### A raw-string regex bug silently disables command-expression completion.

_gls/providers/completion/CompletionContextDetector.kt:235_

`Regex("""\\b…\\s+…""")` inside a raw string matches a literal backslash, so `isCommandExpression` never returns true in
production. No test exercises it. One-line fix plus a test; also a signal that regex construction (84 sites, 22 inside
method bodies) needs a lint rule.

> [!WARNING]
>
> **Environment blocker.** The build disk was full at audit time, so Gradle builds and the test suite would fail with
> ENOSPC until space is reclaimed. Phase 0 below starts there.

## Part 1 · Technical debt inventory

Severity: High wrong results, hangs, leaks, data races or severe latency under normal editor use. Medium correctness
risk under specific conditions, measurable waste, or blocks modular testing. Low hygiene and idiom. Performance impact
is rated separately where the finding is on a request hot path (hover, completion, signature help fire per cursor move
or keystroke).

### 1.1 High severity (consolidated)

| ID  | Area           | Defect                                                                                                                                                                                                                                          | Evidence                                                                                                                                   | Perf                     | Fix direction                                                      |
| --- | -------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------ | ------------------------------------------------------------------ |
| H1  | concurrency    | Mutable `sourceRoots`/`workspaceSources`/`dependencyClasspath` written on Default, read by every compile unsynchronized; live list handed to strategies                                                                                         | `WorkspaceManager.kt:34-38,129-138,206,219,273`                                                                                            | —                        | Immutable `WorkspaceModel` in `AtomicReference` (RFC-2)            |
| H2  | lifecycle      | New `URLClassLoader` per classpath update, old one never closed; plain `var` read from request threads                                                                                                                                          | `ClasspathService.kt:26,49,199`                                                                                                            | metaspace leak           | `AtomicReference`, `getAndSet(new)?.close()`                       |
| H3  | error-handling | Broad catch in `future{}` swallows `CancellationException` (it is an `IllegalStateException`), logs ERROR, returns empty                                                                                                                        | `GroovyTextDocumentService.kt:485-491`                                                                                                     | —                        | Rethrow cancellation first; adopt `rethrowIfCancellationOrError`   |
| H4  | concurrency    | `runBlocking` inside `updateWorkspaceModel` on a Default worker (via `GdkProvider.initialize`); deadlock risk on small pools                                                                                                                    | `GroovyCompilationService.kt:230-237` · `GroovyGdkProvider.kt:40` · `DependencyManager.kt:128`                                             | startup stall            | Make `initialize` suspend                                          |
| H5  | perf           | JDK source cache hit still re-reads and re-parses the whole file with JavaParser per hover/definition                                                                                                                                           | `JavaSourceInspector.kt:48-61` · `JdkSourceResolver.kt:48-57`                                                                              | hover                    | Cache `InspectionResult` keyed by path+mtime                       |
| H6  | error-handling | Exception mid-scan completes the `channelFlow` normally: client gets a partial reference list indistinguishable from complete                                                                                                                   | `ReferenceProvider.kt:116-126,220-223`                                                                                                     | —                        | Rethrow as `ResponseErrorException` or typed partial result        |
| H7  | perf           | `getAllNodes()` (all URIs, re-flattened) and `resolveToDefinition` on every node per references/rename/highlight                                                                                                                                | `ReferenceProvider.kt:361-374`                                                                                                             | references               | `getNodes(uri)` + name pre-filter; or SemanticDB occurrences first |
| H8  | error-handling | Typed `ResolutionError` collapsed to null → telemetry reports NO_DEFINITION for real failures; unknown exceptions rewritten as "symbol not found"                                                                                               | `DefinitionResolver.kt:119-122,238-251` · `DefinitionProvider.kt:444-468`                                                                  | —                        | Return the `Either`; map Left → RESOLUTION_FAILED                  |
| H9  | abstraction    | `FeatureSet` has one production consumer (hover); completion/definition/symbols bypass the engine; default engine `Core` means hover uses a parser nothing else uses                                                                            | `LanguageEngineApi.kt:63-84` · `HoverRequestHandler.kt:41` · `ServerConfiguration.kt:16`                                                   | second compile per hover | Flip default to Native; delete dead engine surface (RFC-3b)        |
| H10 | correctness    | Raw-string regex escapes doubled; `isCommandExpression` never matches                                                                                                                                                                           | `CompletionContextDetector.kt:235`                                                                                                         | —                        | Fix escape, hoist regex, add test                                  |
| H11 | perf           | Completion is `suspend` but never checks cancellation; two `compileTransient` per keystroke                                                                                                                                                     | `CompletionProvider.kt:74-160`                                                                                                             | completion               | `ensureActive()` after each compile; single compile                |
| H12 | perf           | Three full workspace symbol scans per keystroke                                                                                                                                                                                                 | `MemberAccessCompletionStrategy.kt:151-165` · `ImportCompletionStrategy.kt:330-342` · `AutoImportCompletionProvider.kt:78-95`              | completion               | Simple-name index in `WorkspaceSymbolIndex` (#861)                 |
| H13 | duplication    | Signature parsing / `formatType` / keyword snippets copied verbatim across three files; copies in `CompletionProvider` have no call site                                                                                                        | `CompletionProvider.kt:358-432` vs `MemberAccessCompletionStrategy.kt:294-368`                                                             | —                        | Delete; move signature parsing to semantics                        |
| H14 | duplication    | Raw-AST `resolveToDefinition` is primary for definition/hover/references/rename/highlight; SemanticDB third                                                                                                                                     | `DefinitionResolver.kt:64-80` · `HoverProvider.kt`                                                                                         | —                        | RFC-1                                                              |
| H15 | duplication    | Method resolution reimplemented 4×; only `CallResolver` is hierarchy- and arity-aware                                                                                                                                                           | `CallResolution.kt:166-183` · `MethodResolutionStrategy.kt:62-64` · `SignatureHelpProvider.kt:176-190` · `WorkspaceSymbolIndex.kt:203-227` | —                        | Expose `CallResolver.resolve()` as the one API (#650)              |
| H16 | api-boundary   | gls reverse-engineers SemanticDB symbol-id strings (scans for `(`, `<`, backticks the builder never emits)                                                                                                                                      | `WorkspaceSymbolIndex.kt:174-175,361-402` · `SemanticDBResolutionStrategy.kt:103-130`                                                      | —                        | `value class Symbol` with parsers in semantics/core                |
| H17 | perf           | Every `findMethod`/`getAllMembers` copies the whole DB map under the read lock then linear-scans all symbols                                                                                                                                    | `WorkspaceSymbolIndex.kt:209,252` · `GroovySemanticDB.kt:84`                                                                               | completion, inlay        | Owner→members secondary index                                      |
| H18 | ast-model      | parser/core tree: open, mutable, eager child lists, 2 objects per Range; ≈136 B/node vs ≈32 B for the Groovy node it mirrors, both alive                                                                                                        | `parser/core/ast/Node.kt:10-27` · `Range.kt:7`                                                                                             | memory                   | RFC-3a sealed/immutable/packed Span                                |
| H19 | allocation     | `getChildNodes()` allocates a fresh list per call; 22 getters defensively `.toList()`; every traversal is O(n) garbage                                                                                                                          | `CompilationUnit.kt:30,36,62-68` · `VoidVisitorAdapter.kt:87-88`                                                                           | GC                       | Read-only views; `forEachChild`                                    |
| H20 | error-model    | Lossy conversion: all-named args dropped; unknown expressions become `ConstantExpr(text)`; unknown statements become empty blocks; 8 node classes never constructed                                                                             | `ExpressionConverter.kt:76-81` · `GroovyAstConverter.kt:320-325` · `StatementConverter.kt:75-80`                                           | —                        | Exhaustive `when` + explicit `Unsupported` node                    |
| H21 | extension-api  | GDSL executor runs scripts and discards results; `JenkinsGdslAdapter` and the regex `GdslParser` have no production callers                                                                                                                     | `JenkinsContext.kt:229-238`                                                                                                                | —                        | Wire `executeAndCapture` → adapter → merger; delete regex parser   |
| H22 | perf           | Full Jenkins metadata rebuilt per completion/hover: new loader, two JSON resources deserialized, every map re-sorted                                                                                                                            | `JenkinsContext.kt:259-262` · callers `CompletionProvider.kt:238`, `JenkinsStepHoverStrategy.kt:97`                                        | completion, hover        | Memoize merged base; `@Volatile` scan snapshot                     |
| H23 | perf           | Per-diagnostics-run FS walk of `src/test/groovy`, reading every spec twice; `reloadRulesets()` is a no-op                                                                                                                                       | `ProjectTypeDetector.kt:186-199` · `RulesetResolver.kt:244-246`                                                                            | diagnostics              | Cache `RulesetConfiguration` per root with invalidation            |
| H24 | error-handling | Ruleset DSL errors in user `.codenarc` swallowed to empty list: zero diagnostics, no signal                                                                                                                                                     | `CodeNarcDiagnosticProvider.kt:88-102`                                                                                                     | —                        | Synthetic config-error diagnostic at 0:0                           |
| H25 | security/leak  | Untrusted GDSL in a `GroovyShell` parented to the server loader: no `SecureASTCustomizer`, no timeout, loader never closed, `runCatching` swallows `Error` (gated off by default)                                                               | `GdslExecutor.kt:78,113-118`                                                                                                               | metaspace                | Sandbox, timeout, `use {}` the loader, rethrow `Error`             |
| H26 | duplication    | build-tool depends on published bsp-core yet ships a parallel BSP client + connection-file parser; bsp-core has zero consumers                                                                                                                  | `build-tool/build.gradle.kts:27` · `BspClient.kt:37` · `BspConnectionDetails.kt:19`                                                        | —                        | Port `BspBuildTool` onto `BspConnector`                            |
| H27 | concurrency    | `Thread.sleep` backoff (2+4+8 s) inside a coroutine on IO; ignores cancel. Long-lived blocking readers on `ForkJoinPool.commonPool`. Maven BSP does network resolution via `supplyAsync` on commonPool with no timeout and a racy `var modules` | `GradleDependencyResolver.kt:145` · `BspClient.kt:106,120` · `BspConnector.kt:106,134` · `MavenBuildServer.kt:70,82-289`                   | startup                  | suspend + `delay` + `runInterruptible`; dedicated executor         |

H1 to H27 is the merged list; a few agents rated items differently and three were merged, which is why the summary
counts 24 distinct High items.

### 1.2 Medium severity, grouped by theme

- **Cancellation and non-fatal-error hygiene (12 sites).** `runCatching` swallowing `Error`/`CancellationException` in
  `AbstractDiagnosticRule.kt:29-35`, `CoreDefinitionService.kt:45-68`, `NativeTypeContext.kt:42-51,228-237`,
  `ReflectionAccess.kt:32,62`, `BspClient.kt:46-85`, `GradleDependencyResolver.kt:123,162,231`; `typeDefinition` uses
  `exceptionally` so cancel yields a fake empty answer (`GroovyTextDocumentService.kt:448-454`); every non-cancellation
  exception becomes a user-visible "Compilation error" diagnostic (`CompilationOrchestrator.kt:120-126`).
- **Unsynchronized or weakly published state (8).** `WorkspaceCompiler.moduleCache` (`:64,153,190`),
  `ProjectStartupManager` vars (`:88-94,251`), `ProjectStrategyRegistry.activeStrategies`,
  `JenkinsContext.dynamicMetadataCache` keyed by `List.hashCode()` (`:248-251,334-349`), `ReflectionAccess` second
  `containsKey` outside the lock (`:45,75`), `BuildServerConnection.closed`, `MavenBuildServer.modules`,
  `GradleConnectionPool` connecting inside `computeIfAbsent` (`:37-43`).
- **Heavy work inside map remapping functions (2).** Full `SemanticDocumentBuilder.build()` inside
  `ConcurrentHashMap.computeIfPresent` (`SemanticIndexer.kt:92-114`); `GradleConnector.connect()` inside
  `computeIfAbsent`.
- **Orphaned compile jobs repopulate the cache with stale fingerprints.** `invalidate`/`clear` drop the registry entry
  but do not cancel the `Deferred`; fingerprint-less `getCached(uri)` happily returns it
  (`CompilationCacheService.kt:145-148` · `ParseResultAccessor.kt:49`). Related to open #1184.
- **Dispatcher policy.** CPU-bound compile on `Dispatchers.IO` (`CompilationOrchestrator.kt:254`) while workspace
  compile uses Default; blocking `Files.readString`/`Files.walk` on Default (`SymbolIndexingService.kt:189`,
  `WorkspaceManager.kt:193`, `ParseResultAccessor.kt:148`).
- **Shutdown does not close or join.** `doShutdown` never closes classloaders; `coroutineScope.cancel()` does not join
  so compiles still run at `exit()` (`GroovyLanguageServer.kt:306-328`).
- **Traversal duplication in providers.** Two independent full-text lexer loops per request
  (`GroovySemanticKeywords.kt:22-41` vs `FoldingRangeBuilder.kt:124-139`); the snapshot/`requireCurrent`/`ensureActive`
  boundary copied three times; Groovy→LSP range built inline 5× with two different end-column conventions
  (`SemanticSourceRanges.kt:31-35` vs `UnusedImportDiagnosticProvider.kt:71-74`); `InlayHintsProvider` iterates all URIs
  filtered only by line (`:117-120,139-152`); 10 hand-rolled parent-walk loops.
- **Hot-path allocation.** 13 hover strategies allocated per hover (`HoverProvider.kt:77-96`); per-request
  `DefinitionResolver` and `SemanticTypeResolver` construction (`MethodResolutionStrategy.kt:33-35`); 22 `Regex(`
  constructions inside method bodies on hover/diagnostic paths (`DocExtractor.kt:143-151`, `RuleRangeCalculator.kt` 8
  per violation, `SpockTestStructureRule.kt:47-59`); `content.lines()` 8 to 10× per completion; 19 `logger.info` calls
  on the definition path including `allClassNodes.map { it.name }`.
- **Symbol model defects.** Field and property of a Groovy property share one id with two DEFINITION rows
  (`SemanticDocumentBuilder.kt:556-564`); script methods indexed twice, once with an orphan id (`:50-57`);
  `GroovySemantics` flattens all methods' parameters and locals into one root scope, last writer wins
  (`GroovySemantics.kt:79-100`); position lookups are O(n) scans and the design doc describes caches that do not exist
  (`GroovySemanticDB.kt:148-155`).
- **Lossy or inconsistent models.** Six `Position` types across 1-based/0-based/column/character conventions with
  contradictory inclusive/exclusive range docs (`parser/core Range.kt:5` vs `CoreParseUnit.kt:48-49,106`); directly
  relevant to open #1190. `MetadataMerger` hard-codes `positionalParams = emptyList()` (`:195-199`). `diagnostics/api`
  depends on lsp4j and Groovy; SARIF matches `source == "codenarc"` while the provider emits `"CodeNarc"`.
- **Reflection where static typing was available.** Type calculators are `TypeCalculator<Any>` using string-name
  reflection on Groovy AST the module depends on; registry tries every calculator per node
  (`MethodCallCalculator.kt:20-33` · `TypeCalculatorRegistry.kt:40-49`). CodeNarc private fields set via
  `getDeclaredField` (`CodeAnalyzer.kt:87-94`). `PositionNodeVisitor` dispatches via `Map<KClass, lambda>` with
  exact-class match that misses subclasses (`:104,159-171`).
- **Extension points are ad hoc.** No SPI, no discovery; three detectors hard-wired in
  `GroovyLanguageServer.init:188-190`; closed `enum TestFramework`; `FixHandlerRegistry` is a private map in one
  694-line file; `RuleRegistry` SPI has zero callers while rules are a hard-coded list (`BuiltinRules.kt:19-25`).
- **Build hygiene.** No convention plugins: ~40 copies of `useJUnitPlatform`, detekt formatting declared twice per
  module; `koverVerify` disabled in 5 modules; Gson used in build-tool but undeclared; three JDK↔Gradle compatibility
  matrices; `java.io.File` in public APIs; `api()` leaks of Arrow, coroutines, lsp4j-jsonrpc, jeromq, Groovy;
  `repl/GroovyExecutor` swaps process-global `System.out`.

### 1.3 Low severity and dead code

Roughly 4.1K lines of main source have no production callers and can be deleted outright: parser/core cloners (~700),
`GroovyVisitor` and adapters, `gls/engine/impl/core`, `impl/rewrite`, `engine/features`,
`CoreCompletionService`/`CoreDefinitionService` (~1,250), `AstCompletionContext*` (369),
`DefinitionProvider.findTargetsAt` family and `TargetKind`, `common/functional/*` (`DomainResult`, `Pipeline`),
`common/cache/ThreadSafeCache`, `gls/cache/LRUCache`, `build-tool/bsp/BspClient` + `BspConnectionDetails`,
`GradleJdkCompatibility`, `semantics/dsl`, `semantics/openrewrite`, `dsl/dsld`, `jupyter/kernels/jenkins` placeholder,
`TokenEditDistance`, `DelegationResolver` stubs, `UserMetadataLoader`/`GdslParser`, unused `ContextFilter` sealed class,
`BuildTargetCache.refresh(connection: Any)`.

Visibility: no module uses `explicitApi()`; `internal` is zero in common, bsp-core, maven-bsp, kernel-core, reports,
parser/api. No module outside gls imports `gvy.gls.providers.*` or `gvy.gls.dsl`, so the entire provider layer can go
`internal` without downstream breakage. Eight package roots coexist (`gvy`, `groovyparser`, `com.github.groovylsp.bsp`,
`refactor`, `groovylsp`, `reports`, `nativeapi`, `com.github.gvy.examples`); the published bsp-core's package does not
match its Maven group.

### 1.4 Full evidence by subsystem

Each section is the reviewing agent's finding table, lightly edited. IDs are local to the section.

<details>
<summary>gls · compilation, services, worker, project, config, server (22 findings)</summary>

Flow observed: lsp4j thread → `GroovyTextDocumentService` wraps handlers in `coroutineScope.future {}` on a server-wide
`CoroutineScope(Dispatchers.Default + SupervisorJob())` → `DiagnosticsOrchestrator.trigger` (debounced LAZY jobs) →
`GroovyCompilationService.compileAsync` → `CompilationOrchestrator.scheduleCompilation` →
`CompilationCacheService.registerPending` (CAS on `CompilationIdentity(content, fingerprint, snapshot===)`;
the job keeps its fence ticket) → `WorkerSessionManager.parse` → `GroovyParserFacade` → cache,
`SymbolIndexingService`, `SemanticIndexer.publish`.
Publication re-validates snapshot identity, config and job ownership under `publicationLock`.

| ID  | Sev  | File:line                                                                                 | Defect                                                                       | Fix                                           |
| --- | ---- | ----------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- | --------------------------------------------- |
| F1  | High | `WorkspaceManager.kt:36-37,129-138,206,219`                                               | Mutable source roots/sources mutated on Default while compiles read them     | Immutable model in `AtomicReference`          |
| F2  | High | `WorkspaceManager.kt:273`                                                                 | Live mutable classpath list passed to strategies                             | Pass snapshot copy                            |
| F3  | High | `ClasspathService.kt:26,49`                                                               | URLClassLoader per update, never closed; plain var                           | `AtomicReference` + close                     |
| F4  | High | `GroovyTextDocumentService.kt:485-491`                                                    | Broad catch swallows cancellation as IllegalStateException                   | Rethrow cancellation first                    |
| F5  | High | `GroovyCompilationService.kt:230-237` · `GroovyGdkProvider.kt:40`                         | `runBlocking` on Default worker                                              | suspend `initialize`                          |
| F6  | Med  | `WorkspaceCompiler.kt:267-277`                                                            | Cancellation of workspace compile logged as ERROR, marks compiled            | Rethrow cancellation                          |
| F7  | Med  | `SymbolIndexingService.kt:188-219`                                                        | Same; cancellation guard at :233 is dead                                     | Same                                          |
| F8  | Med  | `GroovyTextDocumentService.kt:448-454`                                                    | `exceptionally` turns cancel into empty answer                               | Use `future {}` like siblings                 |
| F9  | Med  | `WorkspaceCompiler.kt:64,153,190`                                                         | `moduleCache` written concurrently, read unsynchronized                      | Delete or `ConcurrentHashMap`                 |
| F10 | Med  | `SemanticIndexer.kt:92-114,127-130`                                                       | Full semantic build inside `computeIfPresent`                                | Build outside, compute to store               |
| F11 | Med  | `CompilationCacheService.kt:145-148` · `ParseResultAccessor.kt:49`                        | Invalidate does not cancel Deferred; orphan repopulates with old fingerprint | Cancel; remove fingerprint-less overloads     |
| F12 | Med  | `CompilationOrchestrator.kt:254`                                                          | CPU compile on IO dispatcher                                                 | `Default.limitedParallelism`                  |
| F13 | Med  | `SymbolIndexingService.kt:189` · `ParseResultAccessor.kt:148` · `WorkspaceManager.kt:193` | Blocking file IO on Default                                                  | `withContext(IO)`                             |
| F14 | Med  | `GroovyLanguageServer.kt:306-328`                                                         | Shutdown closes nothing, cancel without join                                 | `AutoCloseable`; `cancelAndJoin` with timeout |
| F15 | Med  | `ProjectStartupManager.kt:88-94,251`                                                      | Plain vars read across threads                                               | `@Volatile`                                   |
| F16 | Med  | `CompilationOrchestrator.kt:120-126` · `CompilationErrorHandler.kt:59-63`                 | Any exception becomes a source diagnostic at 0:0                             | Map only compiler/IO exceptions               |
| F17 | Low  | `ProjectStrategyRegistry.kt:26-30`                                                        | Plain vars read on compile threads                                           | `@Volatile`                                   |
| F18 | Low  | `ProjectStartupManager.kt:69`                                                             | 7-positional-arg callback typealias, ~12 sites pass four nulls               | `(StatusNotification) -> Unit`                |
| F19 | Low  | `CompilationResult.kt:9-29`                                                               | Boolean+nullable encoding; 3 null meanings                                   | Sealed result                                 |
| F20 | Low  | 4 files                                                                                   | `buildLocatorCandidates` duplicated 4×                                       | One `buildSet` helper                         |
| F21 | Low  | `GroovyWorkspaceService.kt:100-106,134-149`                                               | Catch Exception around `URI.create`                                          | Catch IAE only                                |
| F22 | Low  | `CompilationOrchestrator.kt:372-373`                                                      | All open buffers copied per compile                                          | Lazy accessor                                 |

Verified fine: `registerPending` CAS semantics (formerly `registerCompilation`, removed in #1391),
`DocumentProvider` gating, `SemanticSnapshotGuard`,
`LanguageEngineManager` locking, `ConfigWatchDebouncer`, `ParentProcessWatchdog`; no `!!` or `lateinit` in scope.

</details>

<details>
<summary>gls · definition, references, rename, hover, signature help, sources, documentation (21 findings)</summary>

Everything starts from `GroovyAstModel.getNodeAt`, a linear filter over all tracked nodes with four heuristic passes and
no spatial index. Distinct node-at-position implementations: 8. Distinct resolve-to-declaration paths: 11 plus an unused
stub in parser/native.

| ID  | Sev  | File:line                                                                         | Defect                                                               | Fix                               |
| --- | ---- | --------------------------------------------------------------------------------- | -------------------------------------------------------------------- | --------------------------------- |
| F1  | High | `JavaSourceInspector.kt:48-61` · `JdkSourceResolver.kt:48-57`                     | Cache hit re-reads and re-parses JDK source per hover                | Cache inspection result           |
| F2  | High | `ReferenceProvider.kt:116-126,220-223`                                            | Partial reference list on exception, indistinguishable from complete | Typed error/partial               |
| F3  | High | `ReferenceProvider.kt:361-374`                                                    | Resolve every node across all URIs per request                       | Per-URI + name pre-filter         |
| F4  | High | `DefinitionResolver.kt:238-251` · `DefinitionProvider.kt:444-468`                 | Typed error collapsed to null; telemetry lies                        | Keep the Either                   |
| F5  | High | `DefinitionResolver.kt:119-122`                                                   | Unknown exceptions rewritten as "not found"                          | Propagate                         |
| F6  | Med  | `SignatureHelpProvider.kt:342`                                                    | `runCatching` around suspend swallows cancel                         | `runSuspendCatching`              |
| F7  | Med  | `HoverProvider.kt:77-96`                                                          | 13 strategies allocated per hover (getter)                           | `by lazy`                         |
| F8  | Med  | `MethodResolutionStrategy.kt:33-35` · `DefinitionProvider.kt:79,327,392`          | Resolver and type resolver built per request                         | Inject once                       |
| F9  | Med  | `DocExtractor.kt:143-151,156-183` + 6 files                                       | Regex compiled per comment line on hover path                        | Hoist                             |
| F10 | Med  | 4 files                                                                           | Four receiver-type resolvers with different implicit-this semantics  | One `ReceiverTypeResolver` (#650) |
| F11 | Med  | `DefinitionProvider.kt:178-215` vs `221-258`                                      | Two identical selection-range functions                              | One generic                       |
| F12 | Med  | `MethodResolutionStrategy.kt:121-153` vs `ClasspathResolutionStrategy.kt:135-163` | Navigation + 4 variants of `hasValidPosition`                        | Shared extension                  |
| F13 | Med  | `HoverContentGenerator.kt:81-137`                                                 | Double dispatch; `else` hides unhandled nodes                        | Sealed `HoverTarget`              |
| F14 | Med  | 5 files                                                                           | `getAst(uri) as? ModuleNode` repeated 9×                             | Type the accessor                 |
| F15 | Med  | `ReferenceProvider.kt:477-499`                                                    | 13-KClass set allocated per call; dead interface entries             | Companion val / `when`            |
| F16 | Med  | `LocalSymbolResolutionStrategy.kt:40-43,260-265,320-345`                          | 19 `logger.info` on the request path; repeated class scans           | Demote; compute once              |
| F17 | Low  | `DefinitionProvider.kt:313-355`                                                   | Dead `findTargetsAt` family                                          | Delete                            |
| F18 | Low  | `TypeDefinitionProvider.kt:26,60,89-122`                                          | Unused param; mutates shared resolver per request                    | Reuse classpath strategy          |
| F19 | Low  | `SymbolResolutionStrategy.kt:97-126`                                              | Duplicate of `common.Pipeline`                                       | Pick one, delete other            |
| F20 | Low  | 26 public classes                                                                 | Zero references outside gls                                          | `internal`                        |
| F21 | Low  | `SignatureHelpProvider.kt:417-439`                                                | GDK labels computed twice                                            | Reuse                             |

Verified fine: `RenameProvider`/`RenameSourceSnapshots`, `IdentifierRanges`/`TextIndex` (binary search),
`SymbolLspExtensions` exhaustive `when`, `SignatureFormatter`.

</details>

<details>
<summary>gls · completion, semantic tokens, inlay hints, code actions, diagnostics rules, folding, selection, engine (23 findings)</summary>

| ID  | Sev  | File:line                                                            | Defect                                             | Fix                            |
| --- | ---- | -------------------------------------------------------------------- | -------------------------------------------------- | ------------------------------ |
| F1  | High | `LanguageEngineApi.kt:63-84` · `GroovyTextDocumentService.kt:403`    | Engine serves hover only; everything else bypasses | Delete or complete (RFC-3b)    |
| F2  | High | `ServerConfiguration.kt:16` · `GroovyCompilationService.kt:140-144`  | Default Core engine re-parses for hover            | Default Native                 |
| F3  | High | `CompletionContextDetector.kt:235`                                   | Raw-string regex escape bug                        | Fix + test                     |
| F4  | High | `CompletionProvider.kt:74-160`                                       | No cancellation; double compile per keystroke      | `ensureActive`                 |
| F5  | High | 3 strategies                                                         | Three workspace scans per keystroke                | Name index                     |
| F6  | Med  | `CompletionContextDetector.kt` · `SpockCompletionStrategy.kt`        | `content.lines()` 8-10× per request                | One `TextIndex`                |
| F7  | High | `CompletionProvider.kt:358-432` et al.                               | 70-line bodies copied verbatim                     | Delete copies                  |
| F8  | Med  | `AstCompletionContext*.kt`                                           | Unused second sealed hierarchy, stubbed inference  | Delete or adopt                |
| F9  | Med  | `CompletionProvider.kt:452-461,324-337`                              | Three disjoint context types                       | One sealed `CompletionSite`    |
| F10 | Med  | `SemanticTokenVisitor.kt`                                            | Dual-mode class with five fallback branches        | Remove legacy ctor             |
| F11 | Med  | `GroovySemanticKeywords.kt:22-41` · `FoldingRangeBuilder.kt:124-139` | Two lexer passes per request                       | Shared lexed source            |
| F12 | Med  | 3 handlers                                                           | Request boundary copied 3×                         | `withCapturedDocument`         |
| F13 | Med  | 5 files                                                              | Inline range building, two end-column conventions  | One `lspRange`                 |
| F14 | Med  | `InlayHintsProvider.kt:117-120,139-152`                              | All URIs iterated per request                      | Per-URI                        |
| F15 | Med  | `FixHandlerRegistry.kt:45-112`                                       | Closed private registry; regex per call            | `fun interface LintFix`        |
| F16 | Med  | `RuleRegistry.kt` · `BuiltinRules.kt:19-25`                          | SPI unused; `getAst(): Any?`                       | Use SPI; type it               |
| F17 | Med  | `TestRequestDelegate.kt:58-67`                                       | Cancel becomes InternalError                       | Rethrow                        |
| F18 | Med  | 5 files                                                              | `runCatching` swallows `Error`                     | `rethrowIfCancellationOrError` |
| F19 | Low  | `CustomRulesProvider.kt:52-61,88-101`                                | Double wrapping; recomputed `hasErrors`            | Lazy                           |
| F20 | Low  | 4 rules                                                              | Regex in hot methods                               | Companion constants            |
| F21 | Low  | 3 files                                                              | Legend indices defined three ways                  | `value class TokenType`        |
| F22 | Low  | `CompletionBuilders.kt:111-180`                                      | Builder copies fields back 7×                      | `from(item)`                   |
| F23 | Low  | provider objects                                                     | Public with no external users                      | `internal`                     |

Verified fine: `folding/`, `selection/`, `SemanticTokensHandler`, `CompletionStrategy.aggregate`,
`ParserDiagnosticProvider`.

</details>

<details>
<summary>parser · api, core, native, rewrite (18 findings)</summary>

| ID  | Sev  | File:line                                                            | Defect                                                                           | Fix                               |
| --- | ---- | -------------------------------------------------------------------- | -------------------------------------------------------------------------------- | --------------------------------- |
| F1  | High | `core/ast/Node.kt:10-27`                                             | Open, mutable hierarchy; public `MutableList` children on 18 nodes               | Sealed, constructor-only children |
| F2  | High | `Node.kt` · `Range.kt` · `Position.kt`                               | ≈136 B/node before payload                                                       | Packed `Span`; shared empty lists |
| F3  | High | `CompilationUnit.kt:62-68` et al.                                    | Fresh list per `getChildNodes`; 22 defensive `toList()`                          | Read-only views                   |
| F4  | High | `ExpressionConverter.kt:76-81` et al.                                | Lossy conversion; 8 node classes never constructed                               | Exhaustive + `Unsupported`        |
| F5  | Med  | `VoidVisitorAdapter.kt:338-435`                                      | Hand-written non-exhaustive dispatch; `AnnotationExpr` skipped; 0 external users | Seal + `when`                     |
| F6  | Med  | `NodeCloner.kt` + 4 files                                            | ~700 LOC deep copy, 0 callers, unchecked casts                                   | Delete                            |
| F7  | Med  | 6 Position types                                                     | Contradictory range semantics across modules                                     | One value class (#1190)           |
| F8  | Med  | `core/build.gradle.kts:17`                                           | `api(groovy)` leaks via public `convertFromNative`                               | Bridge module                     |
| F9  | Med  | 3 ParseUnits                                                         | parser/api: 3 impls, 1 consumer, `typeAt` always null                            | Decide (RFC-3b)                   |
| F10 | Med  | `GroovyParser.kt:106-108,215-224`                                    | Lenient mode returns empty `CompilationUnit` on crash; flat `Problem`            | Sealed `ParseProblem`             |
| F11 | Med  | `NodeRelationshipTracker.kt:33-81` · `AstPositionQuery.kt:47,79-166` | 4 identity maps per node; whole set copied + 5 filter passes per lookup          | Per-URI interval index            |
| F12 | Med  | `GroovyParserFacade.kt:346-351,386,244`                              | Rescans every classpath jar per parse; token index rebuilt on retry              | Cache by fingerprint              |
| F13 | Low  | `PositionNodeVisitor.kt:104,159-171`                                 | KClass-map dispatch misses subclasses                                            | `when`                            |
| F14 | Low  | 4 node files                                                         | Widened setters; never-populated `members`; types as Strings                     | `TypeRef` node                    |
| F15 | Low  | `Node.kt:32-42` · `ParserConfiguration.kt`                           | 22 `Optional` uses; Java builders; `ThreadLocal` config                          | Nullable; data class              |
| F16 | Low  | `TokenEditDistance.kt`                                               | Foreign package, own Position, no users                                          | Delete                            |
| F17 | Low  | `nativeapi/ParseResult.kt:12-18`                                     | `data class` over compiler objects: equals traverses compiler                    | Plain class                       |
| F18 | Low  | `RecursiveAstVisitor.kt:199-203,284-295`                             | Double tracking of catch; parity doc stale                                       | One track                         |

Verified fine: `SourcePositionCommentParser`, `GroovyTokenIndex` (IntArray + binary search), `ParseOutcome`,
`MissingClassStubs` regex hoisting, `GroovyParserError` sealed hierarchy.

</details>

<details>
<summary>semantics · core, native, dsl, openrewrite; indexer (18 findings)</summary>

| ID  | Sev  | File:line                                                           | Defect                                                                     | Fix                         |
| --- | ---- | ------------------------------------------------------------------- | -------------------------------------------------------------------------- | --------------------------- |
| F1  | High | `DefinitionResolver.kt:70-73` · `HoverProvider.kt`                  | Raw AST primary, SemanticDB third                                          | SemanticDB first            |
| F2  | High | 4 files                                                             | Method resolution 4×                                                       | `CallResolver` API          |
| F3  | High | `WorkspaceSymbolIndex.kt:174-175,361-402`                           | Symbol-id strings reverse-engineered                                       | `value class Symbol`        |
| F4  | High | `WorkspaceSymbolIndex.kt:209,252` · `GroovySemanticDB.kt:84`        | Whole DB copied + scanned per member lookup                                | Owner index                 |
| F5  | Med  | `GroovySemanticDB.kt:87-98` · `ImplementationQuery.kt:31-40`        | Sorted copy of all documents per implementation request                    | Cache per generation        |
| F6  | Med  | `SemanticDocumentBuilder.kt:556-564`                                | Field and property share one id                                            | One PROPERTY symbol         |
| F7  | Med  | `SemanticDocumentBuilder.kt:50-57`                                  | Script methods indexed twice                                               | Drop second loop            |
| F8  | Med  | `SemanticDocumentBuilder.kt:504,475,492`                            | Ids built per occurrence; eager template before `takeIf`                   | Precompute                  |
| F9  | Med  | `SemanticDocumentBuilder.kt:197-288`                                | Ids recomputed per member/parameter; stateless helper allocated per import | Pass ids down               |
| F10 | Med  | `ReflectionAccess.kt:45,75`                                         | Access-ordered map read outside lock                                       | Sealed hit/miss inside lock |
| F11 | Med  | `GroovySemantics.kt:79-100,211-227`                                 | All locals flattened into one scope                                        | Per-method scopes           |
| F12 | Med  | `NativeTypeContext.kt:42-51,228-237`                                | `runCatching` swallows SOE/OOM into Unknown                                | Rethrow Error               |
| F13 | Med  | `MethodCallCalculator.kt:20-33` · `TypeCalculatorRegistry.kt:40-49` | `TypeCalculator<Any>` + string reflection                                  | Typed calculators, `when`   |
| F14 | Med  | `UnifiedIndexer.kt:22-33,71-72`                                     | Indexer has own compile, symbol scheme, Range; body visiting TODO          | Feed `SemanticDocument`     |
| F15 | Low  | `ScipWriter.kt:15,35-36,81-92`                                      | Whole index buffered                                                       | Stream                      |
| F16 | Low  | build files                                                         | No `explicitApi`; many 0-ref public types; Arrow as `api`                  | `internal`                  |
| F17 | Low  | `GroovySemanticDB.kt:148-155`                                       | O(n) position lookups; doc describes nonexistent caches                    | Binary search; fix doc      |
| F18 | Low  | `TypeLub.kt:126-127` · `WorkspaceSymbolIndex.kt:101-136`            | Unchecked casts; hierarchy stubs return null although facts exist          | Wire `ImplementationFacts`  |

Verified fine: `GroovySemanticDB` locking (concurrency test exists), `CallResolver.resolve`, `ImplementationQuery`
(pure, cycle-safe), `SemanticType`/`TypeInferenceError` sealed models.

</details>

<details>
<summary>jenkins, dsl, ext, diagnostics, jenkins-extractor (18 findings)</summary>

| ID  | Sev  | File:line                                                           | Defect                                                            | Fix                                 |
| --- | ---- | ------------------------------------------------------------------- | ----------------------------------------------------------------- | ----------------------------------- |
| J1  | High | `JenkinsContext.kt:229-238`                                         | GDSL results discarded                                            | Wire capture → merge                |
| J2  | High | `JenkinsContext.kt:259-262`                                         | Metadata rebuilt per request                                      | Memoize                             |
| J3  | High | `ProjectTypeDetector.kt:186-199`                                    | FS walk per diagnostics run, files read twice                     | Cache per root                      |
| J4  | High | `CodeNarcDiagnosticProvider.kt:88-102`                              | Ruleset errors swallowed                                          | Config diagnostic                   |
| J5  | High | `GdslExecutor.kt:78,113-118`                                        | Unsandboxed shell, no timeout, loader leak                        | Sandbox + timeout + close           |
| J6  | Med  | `MetadataMerger.kt:195-199`                                         | Positional params dropped on merge                                | Propagate                           |
| J7  | Med  | `StableStepDefinitions.kt:47-1097`                                  | 1123 LOC hand-maintained table duplicating JSON schema            | Resource + lazy facade              |
| J8  | Med  | `RuleRangeCalculator.kt`                                            | 70-arm string `when`; 8 regexes per violation                     | Table-driven; hoist                 |
| J9  | Med  | `CodeAnalyzer.kt:87-94`                                             | Reflection on CodeNarc private fields                             | Public setters / in-memory analyzer |
| J10 | Med  | `diagnostics/api/build.gradle.kts:6-7`                              | API module bound to lsp4j + Groovy; source-id mismatch with SARIF | Neutral `GvyDiagnostic`             |
| J11 | Med  | `TestFramework.kt:8-20` · `TestFrameworkRegistry.kt:46-58`          | Closed enum; mutable global registry                              | Value-class id; immutable registry  |
| J12 | Med  | `JenkinsConfiguration.kt:55-57` · `JenkinsPluginManager.kt:295-298` | Misconfiguration silently reset; bundled load failure → empty     | Typed error; fail fast              |
| J13 | Med  | `JenkinsContext.kt:248-251,334-349`                                 | Unsynchronized cache keyed by list hashCode                       | `@Volatile` snapshot                |
| J14 | Low  | `Descriptors.kt:57,90-120`                                          | Unused sealed `ContextFilter`; stringly maps                      | Parse into sealed type              |
| J15 | Low  | `ProjectType.kt:10-44`                                              | Hand-written `toString` objects; `Pair<Int,Int>` ranges           | `data object`; value class          |
| J16 | Low  | `GdslExecutor.kt:43-49`                                             | Double logging, lost cause                                        | Sealed failure                      |
| J17 | Low  | `GdslToJson.kt:1`                                                   | Directory/package mismatch (#1025)                                | Move                                |
| J18 | Low  | `DsldParser.kt:11-14` · `GdslParser.kt:169`                         | Dead module and dead parser                                       | Remove                              |

Verified fine: `PluginDownloader`, `BytecodeScanner`, `LibraryParser` (correct rethrow pattern), `RulesetFileCache`,
`LibrarySourceLoader` Zip-Slip guard, `JenkinsStubGenerator` purity. The d6f5ca66 range-anchoring fix is sound but
leaves two inconsistent public entry points on `RuleRangeCalculator`.

</details>

<details>
<summary>common, build-tool, bsp, jupyter, repl, reports, cli, build configuration (21 findings)</summary>

| ID  | Sev  | File:line                                            | Defect                                                       | Fix                                  |
| --- | ---- | ---------------------------------------------------- | ------------------------------------------------------------ | ------------------------------------ |
| F1  | High | `build-tool/build.gradle.kts:27` · `BspClient.kt:37` | Parallel BSP client; bsp-core unused                         | Port onto bsp-core                   |
| F2  | High | `GradleDependencyResolver.kt:145`                    | `Thread.sleep` in coroutine                                  | suspend + `delay`                    |
| F3  | High | `BspClient.kt:106,120` · `BspConnector.kt:106,134`   | Blocking readers on commonPool                               | IO dispatcher                        |
| F4  | High | `MavenBuildServer.kt:70,82-289`                      | Network on commonPool, no timeout, racy var                  | Executor; `@Volatile`                |
| F5  | Med  | `GradleConnectionPool.kt:37-43,120`                  | Connect inside `computeIfAbsent`; never closed               | Compute outside; invalidate on watch |
| F6  | Med  | 8 sites                                              | `runCatching` catches Throwable then retries after OOM       | Narrow                               |
| F7  | Med  | 5 files                                              | Stack traces discarded                                       | `logger.error(e)`                    |
| F8  | Med  | 3 files                                              | Three JDK↔Gradle matrices; undeclared Gson                   | Keep one                             |
| F9  | Med  | common/cache + 2                                     | Three cache abstractions                                     | One `LruCache`                       |
| F10 | Med  | `ThreadSafeLRUCache.kt:38-40`                        | Thread-safety by inheritance                                 | Composition                          |
| F11 | Med  | `DomainResult.kt` · `Pipeline.kt`                    | 0 callers; Arrow leaked as `api`                             | Delete                               |
| F12 | Med  | reports + build-tool                                 | `java.io.File` in public API                                 | `Path`                               |
| F13 | Med  | `BuildTargetCache.kt:189-197`                        | Published stub; 4 third-party `api` leaks                    | Remove; `explicitApi`                |
| F14 | Med  | `BspConnector.kt:146-147,193`                        | Fixed startup delay; untimed init                            | `withTimeout`                        |
| F15 | Med  | `LspCommand.kt:39-58,162`                            | 10-thread pool + shutdown hook per instance; untimed `get()` | Scope to `runSocket`                 |
| F16 | OK   | `CheckCommand.kt:196` · `LspCommand.kt:81`           | stdout discipline is correct                                 | Keep                                 |
| F17 | Med  | `GroovyExecutor.kt:73-79`                            | Swaps global `System.out`                                    | Bind `out` in Binding                |
| F18 | Med  | root + 20 module scripts                             | No convention plugins                                        | `build-logic`                        |
| F19 | Med  | 16 baselines                                         | 234 suppressions; kover disabled where lowest                | Burn down per module                 |
| F20 | Low  | `common/build.gradle.kts:22`                         | Leaf tests depend on parser/core                             | Move test                            |
| F21 | Low  | `kernel-core/build.gradle.kts:9-18`                  | jeromq, logging, repl as `api`                               | `implementation`                     |

Verified fine: `DefaultBuildFileWatcher`, `BuildServerConnection` timeouts + sealed `BspError`, `CachedResolver`,
`UriPathConverter`, `HmacSigner` constant-time verify, `MavenBuildTool.resolveViaCli` cleanup.

</details>

## Part 2 · Kotlin idiom refactoring plan

The codebase is already idiomatic in places (sealed `SemanticType`, `BspError`, `GroovyParserError`; exhaustive `when`
in `SymbolLspExtensions`; binary-searched `TextIndex`). The plan below targets the gaps the counts expose: zero value
classes, zero contracts, 84 `Regex(` sites, 474 `is` checks, 92 broad catches, 86 `runCatching`.

### 2.1 Targets by idiom

| Idiom                                       | Target files                                                                                                                                                                                                                                                                                                 | What changes                                                                                                                                                                                                                                                                      | Payoff                                                                                                                                    |
| ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| **value class**                             | `parser/api Position.kt`, `semantics Symbol`, `compilation ContentHash/ConfigurationFingerprint`, `SemanticToken.tokenType`, `TestFramework` id, `RuleId`, `ExtensionId`, `Fqn`                                                                                                                              | Packed `Pos(Long)`/`Span(Long)`; `Symbol(String)` with `owner/member/method/arity`; typed hashes compared across 5 compilation files; `TokenType`/`ModifierMask` replacing raw Ints defined three ways                                                                            | Removes 2 to 3 allocations per node position; makes mixed-coordinate bugs (#1190) a compile error; kills symbol-string hand parsing       |
| **sealed + exhaustive when**                | `CompilationResult`, `HoverContentGenerator`, `CompletionProvider` contexts, `parser/core Node`, `ParseProblem`, `GdslParseResult`, `DiagnosticResult`, `ContextFilter`                                                                                                                                      | Boolean+nullable encodings become variants; three disjoint completion context types become one `CompletionSite`; 13 hover strategies become a classifier + one renderer; `else -> defaultRender` fallbacks disappear                                                              | Unhandled node kinds fail at compile time instead of rendering `toString()`                                                               |
| **Collection builders and read-only views** | `parser/core CompilationUnit`, `ImportAction`, 4× `buildLocatorCandidates`, `MetadataMerger`, `RuleRangeCalculator`                                                                                                                                                                                          | `buildList`/`buildSet`/`buildMap` for the 78 `mutableListOf` sites that are only ever appended then returned; return backing lists typed as `List<T>` instead of 22 defensive `toList()`                                                                                          | One allocation instead of two per call on traversal paths                                                                                 |
| **Sequences for early-termination search**  | `DocExtractor.cleanDocComment`, `ReferenceProvider.findReferences`, `WorkspaceSymbolIndex.members`, `DocumentAst.nodes()`                                                                                                                                                                                    | `lineSequence()`, `asSequence().filter().firstOrNull()` where today a full list is built then `first()` taken                                                                                                                                                                     | Bounded work on hover/definition                                                                                                          |
| **Regex hoisting**                          | 22 method bodies: `DocExtractor`, `JavaSourceInspector:430-432`, `SourceNavigationService:285,293,464`, `RenameProvider:276`, `RuleRangeCalculator` (8), `SpockTestStructureRule` (4), `EmptyBlockRule`, `NullSafetyRule`, `ImportAction:160`, `FixHandlerRegistry:498,556`, `CompletionContextDetector:235` | `private val` in companion/object; identifier search via `indexOf` + boundary check; add a detekt custom rule or `forbiddenMethods` for `Regex(` in function bodies                                                                                                               | Regex compilation off every hover/diagnostic                                                                                              |
| **Contracts**                               | `utils/CoroutineUtils.rethrowIfCancellationOrError`, new `withCapturedDocument`, `requireCurrent`/`requireUnchanged`, `SemanticType.isKnown()`                                                                                                                                                               | `returns() implies (t !is CancellationException)`; `callsInPlace(block, EXACTLY_ONCE)` so `val` initialization through the boundary compiles; `returns(true) implies (this is Known)` for smart casts                                                                             | Lets the three copied request boundaries collapse into one inline helper without losing smart casts                                       |
| **Narrow catches**                          | 92 `catch (e: Exception)`, 86 `runCatching`                                                                                                                                                                                                                                                                  | Keep broad catch only at the lsp4j response boundary; everywhere else catch the documented type (`CompilationFailedException`, `IOException`, `GradleConnectionException`, `IllegalArgumentException`); every remaining `runCatching` goes through `rethrowIfCancellationOrError` | Cancellation and OOM stop being logged as feature errors; 33 `TooGenericExceptionCaught` + 9 `SwallowedException` baseline entries retire |
| **Visibility**                              | all gls providers, 26 navigation classes, semantics helpers, build-tool services                                                                                                                                                                                                                             | `internal` by default; `explicitApi()` via convention plugin in library modules                                                                                                                                                                                                   | Refactoring freedom; honest public surface for bsp-core and parser/core, which are published                                              |
| **data object / data class hygiene**        | `ProjectType`, `CursorPositionContext.BlockLevel`, `nativeapi.ParseResult`, `ParseRequest`                                                                                                                                                                                                                   | `data object` for singletons with hand-written `toString`; drop `data` from classes wrapping compiler objects whose `equals` traverses the compiler                                                                                                                               | Removes an accidental O(tree) `hashCode`                                                                                                  |

### 2.2 Before and after

#### Packed position replaces six Position types (parser/api, resolves #1190)

**before · parser/api/model/Position.kt**

```kotlin
data class Position(val line: Int, val column: Int)
    : Comparable<Position> { … }
data class Range(val start: Position, val end: Position) {
    fun contains(p: Position) = p >= start && p <= end
}
// plus 5 more Position types in core, native, nativeapi,
// indexer and TokenEditDistance, with mixed 0/1-based
// and inclusive/exclusive conventions
```

**after**

```kotlin
/** 1-based line and UTF-16 column; line-major by construction. */
@JvmInline value class Pos(private val packed: Long) : Comparable<Pos> {
    constructor(line: Int, column: Int) :
        this((line.toLong() shl 32) or (column.toLong() and 0xFFFF_FFFFL))
    val line: Int get() = (packed ushr 32).toInt()
    val column: Int get() = packed.toInt()
    override fun compareTo(other: Pos) = packed.compareTo(other.packed)
    companion object { val NONE = Pos(-1L) }
}
/** Half-open [start, end) span in UTF-16 offsets. */
@JvmInline value class Span(private val p: Long) { … }
fun Span.toLsp(index: LineIndex): org.eclipse.lsp4j.Range
```

#### Immutable workspace model (fixes H1, H2)

**before · WorkspaceManager.kt:34-38**

```kotlin
private val dependencyClasspath = mutableListOf<Path>()
private var workspaceRoot: Path? = null
private val sourceRoots = mutableSetOf<Path>()
private var workspaceSources: List<Path> = emptyList()
// … applyWorkspaceModel: sourceRoots.clear(); forEach(add)
// … compile path: fingerprintComputer(deps, sourceRoots.toList())
```

**after**

```kotlin
internal data class WorkspaceModel(
    val root: Path?, val classpath: List<Path>,
    val sourceRoots: Set<Path>, val sources: List<Path>,
    val fingerprint: ConfigurationFingerprint,
)
private val model = AtomicReference(WorkspaceModel.EMPTY)
fun update(root: Path, deps: List<Path>, dirs: List<Path>): Boolean {
    val next = model.get().with(root, deps, dirs)   // pure
    return model.getAndSet(next).fingerprint != next.fingerprint
}
fun current(): WorkspaceModel = model.get()        // one read per compile
```

#### Sealed compilation result (fixes F19; removes three meanings of null)

**before · CompilationResult.kt:9-29**

```kotlin
data class CompilationResult(
    val isSuccess: Boolean,
    val ast: ASTNode?,
    val diagnostics: List<Diagnostic>,
    val sourceText: String? = null,
)
// callers: result?.ast?.let { … } ; if (!isSuccess) …
```

**after**

```kotlin
sealed interface CompilationResult {
    val sourceText: String
    val diagnostics: List<ParserDiagnostic>   // lsp4j-free; mapped at publish
    data class Compiled(
        val ast: ModuleNode, val degraded: Boolean,
        override val diagnostics: List<ParserDiagnostic>,
        override val sourceText: String,
    ) : CompilationResult
    data class Failed(
        override val diagnostics: List<ParserDiagnostic>,
        override val sourceText: String,
    ) : CompilationResult
}
when (r) { is Compiled -> r.ast; is Failed -> … }   // exhaustive
```

#### One cancellation-safe request boundary with a contract (collapses three copies)

**before · SemanticTokensHandler, FoldingRangeRequestHandler, SelectionRangeProvider**

```kotlin
val original = documents.getSnapshot(uri) ?: return empty
val result = compilationService.compileTransient(uri, original.content)
currentCoroutineContext().ensureActive()
if (documents.getSnapshot(uri) != original)
    throw ResponseErrorException(contentModified(uri))
// … same 20 lines in two other files; plus, elsewhere:
try { … } catch (e: Exception) {           // swallows cancel
    logger.error(e) { … }; emptyList()
}
```

**after · gls/utils**

```kotlin
@OptIn(ExperimentalContracts::class)
internal suspend inline fun <T> withCapturedDocument(
    uri: URI, block: (DocumentSnapshot, ParseResult) -> T,
): T? {
    contract { callsInPlace(block, InvocationKind.AT_MOST_ONCE) }
    val snapshot = documents.getSnapshot(uri) ?: return null
    val parsed = compilation.compileTransient(uri, snapshot.content)
    currentCoroutineContext().ensureActive()
    val result = block(snapshot, parsed)
    requireUnchanged(uri, snapshot)        // throws ContentModified
    return result
}
@OptIn(ExperimentalContracts::class)
fun rethrowIfCancellationOrError(t: Throwable) {
    contract { returns() implies (t !is CancellationException) }
    if (t is CancellationException || t is Error) throw t
}
```

#### Typed symbol replaces string surgery (H16, H17)

**before · WorkspaceSymbolIndex.kt:174, 361-402**

```kotlin
val fieldSymbolId = "$classSymbolId$fieldName."
// … extractParameterCount scans for '(' ')' '<'
// … extractNameFromSymbolId handles backtick <init>
//     the builder never emits
val allDocuments = semanticDb.getAllDocuments()   // copies map
val methods = allDocuments.values.flatMap { doc ->
    doc.symbols.filter { it.owner == classSymbolId && … } }
```

**after · semantics/core**

```kotlin
@JvmInline public value class Symbol(val id: String) {
    val owner: Symbol? get() = id.lastIndexOf('#')
        .takeIf { it >= 0 }?.let { Symbol(id.substring(0, it + 1)) }
    fun member(name: String) = Symbol("$id$name.")
    fun method(name: String, erased: List<String>) =
        Symbol("$id$name(${erased.joinToString(",")}).")
    val arity: Int? get() = …
}
// GroovySemanticDB: owner → members secondary index, maintained
// in addToIndexes/removeFromIndexes under the existing lock
fun members(owner: Symbol, kind: SymbolKind? = null,
            name: String? = null): List<SymbolInfo>
```

#### Sealed completion site (merges three context types; F8, F9)

**before**

```kotlin
enum class ContextType { … }                 // 2 members
sealed class CursorPositionContext { object BlockLevel : … }
data class ImportCompletionContext(…)
// three detectors, three separate text/AST passes,
// buildCompletionsList branches on all three
```

**after**

```kotlin
internal sealed interface CompletionSite {
    data class Import(val prefix: String, val isStatic: Boolean,
                      val replace: Range) : CompletionSite
    data class MemberAccess(val qualifierType: String,
                            val qualifierName: String?) : CompletionSite
    data class TypeParameter(val prefix: String) : CompletionSite
    data class InsideCall(val methodName: String) : CompletionSite
    data object BlockLevel : CompletionSite
}
private fun CompletionsBuilder.addFor(site: CompletionSite, ctx: Ctx) =
    when (site) {
        is Import -> addImportCompletions(site, ctx)
        is MemberAccess -> addMemberAccess(site, ctx)
        is TypeParameter -> addTypeParameterClasses(site.prefix, ctx)
        is InsideCall -> addJenkinsMapKeys(site.methodName, ctx)
        BlockLevel -> addScopeCompletions(ctx)
    }
```

#### Allocation-free traversal for the parser/core tree (H19)

**before · Node.kt:47, CompilationUnit.kt:62-68**

```kotlin
abstract fun getChildNodes(): List<Node>      // new list each call
val imports: List<ImportDeclaration> get() = importDecls.toList()
fun <T : Node> collectNodes(node: Node, type: Class<T>,
                            out: MutableList<T>) {
    if (type.isInstance(node)) out.add(type.cast(node))
    node.getChildNodes().forEach { collectNodes(it, type, out) }
}
```

**after**

```kotlin
sealed interface Node { val span: Span; fun forEachChild(action: (Node) -> Unit) }
inline fun Node.walk(crossinline visit: (Node) -> Unit) {
    visit(this); forEachChild { it.walk(visit) }
}
inline fun <reified T : Node> Node.collect(
    into: MutableList<T> = ArrayList(),
): List<T> = into.also { out -> walk { if (it is T) out += it } }
fun Node.deepestAt(pos: Pos): Node? {
    var best: Node? = null
    walk { if (pos in it.span) best = it }
    return best
}
```

#### Memoized Jenkins metadata (H22, J13)

**before · JenkinsContext.kt:259-262**

```kotlin
fun getAllMetadata(): MergedJenkinsMetadata {
    val loader = VersionedMetadataLoader()        // per call
    val versionedMerged = loader.loadMerged()     // 2 JSON reads
    val dynamic = dynamicMetadataCache[currentClasspathHash]
    …
}
```

**after**

```kotlin
private val baseMerged: MergedJenkinsMetadata by lazy {
    VersionedMetadataLoader().loadMerged()
}
private data class ScanSnapshot(val classpath: List<Path>,
                                val dynamic: BundledJenkinsMetadata)
@Volatile private var scan: ScanSnapshot? = null
fun getAllMetadata(): MergedJenkinsMetadata =
    scan?.dynamic?.let { overlay(baseMerged, it) } ?: baseMerged
```

## Part 3 · Breaking-change RFCs

Five proposals, ordered by leverage. All are internal to the repository except RFC-5's published-module changes. "Blast
radius" is a ripgrep count of main-source files referencing the types that change; tests are listed separately where
they dominate.

### RFC-1 · One resolution engine: SemanticDB behind every navigation provider

**Rationale.** gls carries 11 resolve-to-declaration paths and 8 node-at-position implementations over raw
`org.codehaus.groovy` nodes while the SemanticDB index, already populated per compile, is used first only by rename,
implementation and cross-file references. Definition, hover and signature help can answer differently from rename for
the same cursor. Four receiver-type resolvers disagree on implicit `this`. The index is also the only hierarchy- and
arity-aware method resolver (`CallResolver`).

**Contract.**

```kotlin
// semantics/core, explicitApi()
public @JvmInline value class Symbol(val id: String)
public sealed interface Resolution {
    data class Found(val symbol: Symbol, val definition: Location?) : Resolution
    data class Unresolved(val reason: CallOutcome.Reason) : Resolution
}
public interface SemanticQueries {
    fun occurrenceAt(uri: URI, line: Int, col: Int): SymbolOccurrence?   // innermost, binary-searched
    fun definition(symbol: Symbol): Location?
    fun references(symbol: Symbol, includeDefinition: Boolean): List<Location>
    fun members(owner: Symbol, kind: SymbolKind? = null, name: String? = null): List<SymbolInfo>
    fun resolveCall(uri: URI, call: MethodCallExpression): CallOutcome    // reuses CallResolver
    fun implementations(uri: URI, occurrence: SymbolOccurrence): List<Location>
    fun hierarchy(owner: Symbol): ImplementationType?
}
// gls, internal
internal sealed interface SymbolTarget { val node: ASTNode; val uri: URI; val nameRange: Range? … }
internal sealed interface Declaration { Source(node, uri, nameRange); Indexed(symbol, location); Binary(uri, fqn, range) }
internal interface SymbolResolution {
    fun targetAt(doc: CompiledDocumentSnapshot, position: Position): SymbolTarget?
    suspend fun declarationOf(target: SymbolTarget): Either<ResolutionError, Declaration>
    fun occurrencesOf(decl: Declaration, scope: ReferenceScope): Sequence<Location>
    fun receiverTypeOf(call: SymbolTarget.Call): SemanticType
}
```

**Migration.** (1) `Symbol` value class and parsers; delete
`extractParameterCount`/`extractClassFqn`/`extractNameFromSymbolId`. (2) Owner→members index; rewire
`WorkspaceSymbolIndex.findMethod/getAllMembers/getSuperclass`. (3) Fix builder defects first (property/field id
collision, double script-method extraction) so the index is trustworthy. (4) `SemanticDBResolutionStrategy` first in
`DefinitionResolver`; `LocalSymbolResolutionStrategy` only when no document is stored. (5) `MethodResolutionStrategy`
and signature help call `resolveCall`. (6) Hover, rename, highlight, call hierarchy adopt `occurrenceAt`; the 13 hover
strategies become a classifier plus one renderer. (7) Fold `indexer/core UnifiedIndexer` onto `SemanticDocument` → SCIP
mapping.

**Blast radius.** gls main files importing `gvy.semantics`: 21 (+20 tests). `resolveToDefinition` callers: 7.
`getNodeAt`: 15 sites. `ResolutionContext`: 8 main + 6 test files; constructed directly in 7 test files (31 lines).
Definition package 2,327 LOC/12 files, hover 1,737/24, references 741/5. No other Gradle module references the
navigation classes, so they go `internal` for free.

**Risk and the tradeoff that flips it.** If the index proves unreliable for same-file symbols in scripts (the builder
defects above suggest gaps), keep the AST path as a documented fallback rather than deleting it, and add parity tests
that assert definition and rename agree on a corpus. Resolves or supersedes #650, #615, #861.

### RFC-2 · Immutable compilation state and a cancellation-safe request boundary

**Rationale.** H1 to H4, F6 to F16 in the compilation audit are one design gap: state is published by mutation and
errors are caught too broadly. The #1193/#1194 snapshot-identity work already models documents immutably; the workspace,
classpath and startup state did not follow.

**Changes.** `WorkspaceManager` exposes `current(): WorkspaceModel` and drops
`getSourceRoots/getDependencyClasspath/getWorkspaceSources`. `CompilationCacheService` deletes fingerprint-less
`getCached(uri)`, `getCached(uri, content)` and `putCached(uri, content, pr)`; `invalidate` cancels the `Deferred`.
`CompilationResult` becomes sealed and lsp4j-free (mapping to lsp4j moves to `DiagnosticsOrchestrator`), which makes
`compilation/` testable without lsp4j. `GroovyCompilationService : AutoCloseable`; `doShutdown` closes classloaders and
joins with a timeout. `ClasspathService` holds an `AtomicReference<URLClassLoader?>` and closes the predecessor.
`StatusUpdateCallback` becomes `(StatusNotification) -> Unit`. Compile moves to
`Dispatchers.Default.limitedParallelism(cpus)`; file IO to IO. Every `catch (e: Exception)` outside the lsp4j boundary
is narrowed; every `runCatching` goes through `rethrowIfCancellationOrError`; the three copied request boundaries become
`withCapturedDocument`. `CompilationErrorHandler` maps only `CompilationFailedException`/`IOException` to diagnostics.

**Blast radius.** `WorkspaceManager` API: 8 main / 12 test. Cache overloads: 4 main / 6 test. `CompilationResult`: 10
main / 8 test. `GroovyCompilationService` is referenced by 47 main / 107 test files, so `close()` is added additively
and nullability tightening (`getWorkspaceSymbolIndex()` non-null) is its own PR. Prerequisite: #914 (tests for
`GroovyTextDocumentService`) before touching its handlers.

### RFC-3 · Parser: one AST contract, sealed and span-based; retire the dead layers

**3a, the parser/core tree.** Seal `Node`/`Expression`/`Statement`; children become constructor `List<T>`;
`range: Range?` becomes non-null `span: Span` (UTF-16 offsets, line/column derived from a per-file `LineIndex`); drop
the `parentNode` field in favour of an on-demand `ParentIndex` (as parser/native already does); traversal is
`when`-based with one thin `NodeWalker` for JavaParser familiarity. Delete the five cloners, `GroovyVisitor`, `Optional`
accessors, `StaticGroovyParser` globals and `TokenEditDistance`. Rewrite `GroovyAstConverter` bottom-up with an
exhaustive `when` over Groovy node types and an explicit `Unsupported(text, span)` node so lossiness is visible. Move
`convertFromNative` to a `parser/core-groovy` bridge so core can declare Groovy as `implementation`. Blast radius:
parser/core itself (printers 4 files, utils 2, resolution/spi ~10), gls 7 files / 38 imports, viz/ast-model 1 file; 98
test files; 0 external users of visitors or cloners.

**3b, the engine and parser/api decision.** Evidence: `FeatureSet` has one production consumer; `impl/core`,
`impl/rewrite`, `engine/features`, `CoreCompletionService`, `CoreDefinitionService` are reachable only from 18 test
files; `OpenRewriteLanguageSession` returns null providers; parser/api's `typeAt` is null in all three backends.
Recommendation: flip the default to `Native` (one line plus three test assertions), delete the unreached half (~1,250
LOC main), and keep `LanguageEngine/LanguageSession` only if a second parser is a committed roadmap item. Otherwise
collapse `getHoverSession` into `HoverProvider.forSource(snapshot)` and delete `engine/` (4 consumers). The tradeoff
that flips it: if OpenRewrite-backed refactorings are planned for this year, keep `parser/api` and make gls engines
consume `ParseUnit` with a real `typeAt`; completing the abstraction otherwise means 29 provider files stop importing
`org.codehaus`, which is not realistic for tokens, folding and inlay hints that need full AST fidelity.

**3c, the native side table.** Smaller follow-up: per-URI interval index (sorted start offsets in `IntArray`s) built
once per parse to replace the copy-and-filter `getNodeAt`; cache the AST-transform classpath scan by fingerprint;
compute the token index once per content hash.

### RFC-4 · Extension SPI and an lsp4j-free diagnostic model

**Rationale.** Jenkins, Spock, JUnit, GDSL and CodeNarc each plug in through a different hard-coded seam;
`diagnostics/api` depends on lsp4j and Groovy; registries are mutable globals; the GDSL executor's output is discarded.

```kotlin
// new module :ext:api (depends on :parser:native only)
@JvmInline public value class ExtensionId(val id: String)
public sealed interface Contribution {
    data class Diagnostics(val source: DiagnosticSource) : Contribution
    data class TestDetector(val detector: TestFrameworkDetector) : Contribution
    data class DslMetadata(val id: ExtensionId, val metadata: MergedJenkinsMetadata) : Contribution
    data class FileClassifier(val classify: (URI) -> FileKind?) : Contribution
}
public interface GvyExtension {
    val id: ExtensionId
    fun contributes(ctx: ExtensionContext): List<Contribution>   // pure over immutable facts
}
public data class GvyDiagnostic(val ruleId: RuleId, val severity: Severity, val range: Span,
                                val message: String, val meta: Map<String, String> = emptyMap())
public sealed interface DiagnosticResult { Ok(List<GvyDiagnostic>); ConfigError(message, cause) }
public fun interface DiagnosticSource { suspend fun analyze(source: String, uri: URI): DiagnosticResult }
// discovery: gls builds listOf(JenkinsExtension(), SpockExtension(), JUnitExtension(), CodeNarcExtension())
// explicitly and testably; optional ServiceLoader for out-of-tree jars later
```

**Migration.** `ext/testing` keeps `TestFrameworkDetector`'s shape; `enum TestFramework` becomes `ExtensionId`; the
registry becomes an immutable constructor-injected list. `diagnostics/api` replaces lsp4j `Diagnostic` with
`GvyDiagnostic`; conversion to lsp4j happens once in gls; SARIF consumes `GvyDiagnostic` and the source-id mismatch
disappears. `jenkins` exposes `JenkinsExtension` absorbing gls-owned `JenkinsCapabilities`; GDSL contributions flow
through `executeAndCapture` → adapter → `MetadataMerger`. `FixHandlerRegistry` and `BuiltinRules` become lists of
`fun interface` implementations, one file each.

**Blast radius.** `DiagnosticProvider` 20 files, `MergedJenkinsMetadata` 19, `JenkinsCapabilities` 18, `GdslExecutor` 6,
`RuleRangeCalculator` 6, `TestFrameworkDetector` 5. About 60 to 70 files; the lsp4j decoupling is the largest single
step and can be staged behind an adapter.

### RFC-5 · Module and API rationalization

- **Delete** `dsl/dsld` (keep #1024), `semantics/dsl`, `semantics/openrewrite`, `jupyter/kernels/jenkins` placeholder,
  `build-tool/bsp/BspClient` + `BspConnectionDetails` (use bsp-core), `GradleJdkCompatibility`, `gls/cache/LRUCache`,
  `common/functional/*`, `common/cache/ThreadSafeCache`, `BuildTargetCache.refresh(Any)`.
- **Merge** `reports/api|coverage|results` into one `reports` module (865 LOC, identical build scripts); `indexer/scip`
  into `indexer/core`; `fmt` into `parser/rewrite` or gls.
- **Rename packages** to one root under `com.github.albertocavalcante.gvy`: `com.github.groovylsp.bsp` (published,
  mismatches its Maven group), `…groovylsp.{markdown,indexing}`, `…reports`. Fix `tools/jenkins-extractor` directory vs
  package (#1025).
- **Convention plugins** in `build-logic`: `gvy.kotlin-library` (toolchain, detekt, kover, JUnit platform,
  `explicitApi()`), `gvy.published-library` (POM, signing), `gvy.application`. Removes ~40 copies of per-module
  boilerplate.
- **explicitApi rollout order**: parser/api (12 declarations) → common → reports → bsp-core (published) → maven-bsp →
  kernel-core + repl → build-tool → diagnostics/\*, markdown → gls last with `internal` as the default. Enable via the
  convention plugin with a per-module opt-out property during migration.
- **Dependency scopes**: Arrow stays (32 main files, idiomatic `Either<BspError, T>`) but moves from `api` to the
  modules that use it; coroutines, lsp4j-jsonrpc, jeromq, kotlin-logging, serialization and `:repl` become
  `implementation`; `java.io.File` in public signatures becomes `Path`. Declare or drop Gson in build-tool. Related:
  #704 (Gson → kotlinx.serialization in the server).
- **Quality gates**: burn down the 234 baseline entries per module as part of the touching PR; raise kover `minBound`
  per module instead of disabling `koverVerify` in five; add a detekt rule forbidding `Regex(` inside function bodies
  and `catch (e: Exception)` outside the lsp4j boundary package.

## Part 4 · Phased PR plan

Each row is one or more pull requests sized to review in a sitting, with its validation gate. Order respects
dependencies: correctness before structure, structure before deletion, deletion before idiom sweeps so the sweeps do not
polish code about to be removed. Every PR follows the repository's TDD rule, runs `./gradlew lint`, and gets an
independent reviewer per the owner's standing review policy. Open PRs #1216, #1210, #1209, #1203 and #1196 touch jenkins
and build; rebase order matters for Phase 3.

#### Phase 0 (prerequisites)

- **Reclaim disk.** The build disk was full at audit time. Gradle and the 4,571-test suite cannot run until this is
  fixed. Candidates: stale task worktrees outside the repository (three at 49c57cb4/09ab03cd, all behind main), Gradle
  caches, the five other gvy worktrees' `build/` directories.
- **Land #914 first** (tests for `GroovyTextDocumentService`) since Phases 1 and 2 touch its handlers.
- **Baseline measurements.** Extend `parser/native/src/jmh VisitorBenchmark` with hover, completion and definition
  micro-benchmarks on a fixed corpus so Phase 2 claims are measured, not argued.

#### Phase 1 (correctness · 8 small PRs)

1. Regex escape fix + test (`CompletionContextDetector.kt:235`); hoist and fix all 22 in-body regexes; add the detekt
   forbid rule. S
2. Cancellation hygiene: add `rethrowIfCancellationOrError` contract; apply to the 9 request paths and the 12
   `runCatching` sites listed in 1.2. S
3. Immutable `WorkspaceModel` in `WorkspaceManager` (RFC-2 step 1). M
4. `ClasspathService` loader lifecycle + `doShutdown` close/join. S
5. Cache invalidation cancels the `Deferred`; remove fingerprint-less overloads (#1184). M
6. `runBlocking` removal in `GdkProvider.initialize`; dispatcher policy (compile on Default, IO for files). S
7. build-tool: `Thread.sleep` → `delay`; commonPool readers → IO; Maven BSP executor + `@Volatile`; narrow
   `runCatching`. M
8. CodeNarc config errors surfaced as a diagnostic; Jenkins config/bundled-load failures typed; `ReferenceProvider`
   partial-result error; definition telemetry keeps the typed error. M

Gate: full test suite, plus new tests for each defect (cancel mid-request yields a cancelled future, not an empty list;
stale fingerprint never repopulates).

#### Phase 2 (hot-path performance · 6 PRs)

1. Jenkins metadata memoization + `@Volatile` scan snapshot; wire GDSL capture (H21, H22, J13). M
2. CodeNarc ruleset cache per root; `RuleRangeCalculator` table-driven with hoisted regexes (H23, J8). M
3. Completion: single compile + `ensureActive`; one `TextIndex` per request; delete dead copies in `CompletionProvider`;
   simple-name index in `WorkspaceSymbolIndex` (#861). M
4. Hover: strategies `by lazy`; JDK inspection result cache; long-lived `DefinitionResolver` and shared
   `SemanticTypeResolver`; demote 19 info logs. M
5. References: per-URI nodes + name pre-filter; `InlayHintsProvider` per-URI; owner→members index in `GroovySemanticDB`;
   `SemanticIndexer` build outside `computeIfPresent`. M
6. Native side table: interval index for `getNodeAt`; cached transform scan; token index once per content hash (RFC-3c).
   M

Gate: JMH before/after on the Phase 0 corpus attached to each PR; no behavior change in golden tests.

#### Phase 3 (structural RFCs · stacked, one RFC per stack)

1. **RFC-1 stack** (7 PRs in the migration order above). Each step keeps old and new paths behind a parity test until
   the last PR removes the AST-first strategies. Rebase after #1209/#1210 (jenkins definition scope) land.
2. **RFC-2 remainder** (3 PRs): sealed `CompilationResult` and lsp4j-free compilation package; `withCapturedDocument`
   replacing the three boundaries; `GroovyCompilationService` nullability tightening. `GroovyTextDocumentService`
   extraction continues #958 Phase 1 (#949 to #951).
3. **RFC-4 stack** (5 PRs): `ext/api` module; `GvyDiagnostic` behind an adapter; CodeNarc and SARIF on the neutral
   model; test-framework registry immutable; Jenkins extension absorbing `JenkinsCapabilities`. Rebase after #1216
   (build evidence) and #1203 (stubs outside workspace).
4. **RFC-3b decision PR**: default engine to Native, delete unreached engine code (~1,250 LOC + 18 test files). One PR,
   large deletion, zero behavior change for non-hover features; hover gains native-AST parity with everything else.

Gate: parity corpus (definition == rename target for every identifier in the test fixtures); detekt baseline entries in
touched files removed, not re-baselined.

#### Phase 4 (parser core RFC-3a · 6 PRs)

1. `explicitApi()` on parser/api, core, native, rewrite; `Pos`/`Span` value classes in parser/api (additive; resolves
   #1190's model half).
2. Seal `Node`/`Expression`/`Statement`; non-null `span`. Breaks only external subclassers; none exist in the repo.
3. Constructor-only children; delete `addX` mutators; read-only views replace 22 `toList()`.
4. Bottom-up `GroovyAstConverter` with exhaustive `when` and `Unsupported` node; per-Groovy-node-type tests including
   `foo(a: 1)` and `sh script: '…'`.
5. Delete cloners, `GroovyVisitor`, `Optional` accessors, `StaticGroovyParser`, `TokenEditDistance`; port printers and
   `NodeUtils` to `when`.
6. `parser/core-groovy` bridge; Groovy becomes `implementation` in core.

Gate: pretty-printer round-trip corpus unchanged; `VisitorBenchmark` allocation profile attached; dependency-analysis
plugin clean.

#### Phase 5 (module rationalization and idiom sweep)

1. `build-logic` convention plugins; per-module scripts shrink to dependencies. One PR, mechanical, reviewed by diffing
   resolved configurations.
2. Deletions and merges from RFC-5 (one PR per module group so each is revertible).
3. Package renames (bsp first, since it is published; coordinate a bsp-core minor version).
4. `explicitApi` rollout in the stated order; `internal` sweep over gls providers.
5. Idiom sweep per module: `buildList`/sequences/`data object`/narrow catches, retiring the module's detekt baseline in
   the same PR. Delegate the mechanical parts to sonnet-class agents with the before/after table in Part 2 as the brief;
   keep an independent reviewer on every PR.

Gate: baseline files deleted module by module; kover `minBound` raised where `koverVerify` was disabled;
documentation-only changes take the docs CI path.

### Mapping to open issues

| Issue          | Title                                                       | Where it lands                                                                        |
| -------------- | ----------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| #958           | Refactor god classes to 600-line limit                      | Phase 3 (RFC-2 extraction continues #949 to #951); Part 1 lists 25 files over 430 LOC |
| #914           | Tests for GroovyTextDocumentService before refactoring      | Phase 0 prerequisite                                                                  |
| #650           | Consolidate receiver type resolution                        | RFC-1 `receiverTypeOf`; 4 copies found, not 2                                         |
| #1190          | Unicode source coordinates across providers                 | Phase 4 PR 1 (`Pos`/`Span`); six Position types inventoried                           |
| #1184          | Reject cache and symbol writes from invalidated generations | Phase 1 PR 5                                                                          |
| #1188          | Reuse healthy script parses without fallback heuristic      | Phase 2 PR 6 (native side table)                                                      |
| #861           | Cache workspace symbol lookups                              | Phase 2 PR 3                                                                          |
| #704           | Gson → kotlinx.serialization                                | RFC-5 dependency scopes                                                               |
| #454 #455 #456 | Per-provider metrics, timeouts, error diagnostics           | RFC-2 `withCapturedDocument` is the single place to add all three                     |
| #1024 #1025    | DSLD parser; extractor directory                            | RFC-5 deletions and renames                                                           |

## Appendix · Verification and limits

**What was verified.** Seven reviewers read every main-source file in their scope (about 95K lines) and skimmed tests
for contracts. Eleven High-severity claims were independently re-read at the cited lines by the coordinator: the regex
escape bug, the Core default engine, the single engine consumer in hover, the per-request Jenkins metadata loader, the
mutable workspace collections, the unclosed classloader, the discarded GDSL results, the strategy order in
`DefinitionResolver`, `api(libs.groovy.core)` in parser/core, `Thread.sleep` in the Gradle resolver, zero bsp-core
consumers and zero `explicitApi()`. All matched. One nuance: the parser/core `Node.parentNode` and `range` setters are
`internal`, not public; the public mutability is in its annotation and comment lists and in 18 nodes' child lists.

**What was not verified.** No Gradle build, test run, profiler or heap measurement was executed (the main worktree is
locked and both disks are near full). All performance findings are complexity and allocation arguments from source, not
measurements; Phase 0 adds the benchmarks that would confirm them. Three claims rest on reasoning rather than execution:
that Groovy wraps all-named argument lists in a `TupleExpression` (H20), that `GroovyAstModel.getAllNodes()` spans
multiple URIs in production (H7, F14), and that INFO logging is enabled by default (F16 severity). Whether any shipped
client config explicitly selects the Core engine was not checked. Test bodies were skimmed, not read.

**Counts.** Finding totals are per reviewer and overlap slightly where two scopes touch the same file (e.g.
`DefinitionResolver` appears in both the navigation and semantics tables); the consolidated High table merges those.
ripgrep counts exclude `build/` directories; one reviewer's first pass also excluded the `build-tool` package path and
was re-run.
