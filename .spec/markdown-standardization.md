# Spec: Markdown Standardization & Module Extraction

## Goal
Decouple markdown generation logic from the LSP engine into a dedicated high-performance, zero-dependency module. Standardize all ad-hoc string building into a unified DSL.

## Module Structure

The new module will be located at the root as `markdown/`.

```
markdown/
├── README.md
├── build.gradle.kts
└── dsl/
    ├── src/main/kotlin/com/github/albertocavalcante/markdown/dsl/
    │   ├── MarkdownContent.kt    # Sealed classes (Pure Kotlin)
    │   ├── MarkdownBuilder.kt    # DSL logic
    │   └── MarkdownRenderer.kt   # Renderer (Pure Kotlin)
    └── README.md                 # DSL documentation
```

### Gradle Setup
- New entry in `settings.gradle.kts`: `include(":markdown")`
- `markdown/build.gradle.kts` uses `groovy-lsp.kotlin-library` convention.
- Zero external dependencies.

## Phased Implementation Plan

### Phase 0: Infrastructure & Workflow (Ship PR)
- [ ] Rename `/issue-solve` → `/solve`
- [ ] PR for `.agent/workflows/solve.md`

### Phase 1: Module Setup (size/S)
- [ ] Create `markdown/` folder structure
- [ ] Configure Gradle
- [ ] Extract `MarkdownContent`, `MarkdownBuilder` (base), and `MarkdownRenderer`
- [ ] Ensure **zero** dependencies on `lsp4j` or `groovyparser` in this module.

### Phase 2: DSL Enhancement (size/M)
- [ ] Implement `codeBlock`, `header`, `table`, `link`, `inlineCode` in `MarkdownBuilder`.
- [ ] Add unit tests for the DSL.

### Phase 3: Migration (size/L)
- [ ] Update `groovy-lsp` to depend on `:markdown`.
- [ ] Move/Refactor `HoverNodeConverters` to use the new DSL types.
- [ ] Replace `buildString { append("```groovy\n") ... }` across all providers.

## Verification
- Module build: `./gradlew :markdown:build`
- Coverage: 100% logic coverage for `MarkdownRenderer`.
- Integration: Full E2E hover test pass.
