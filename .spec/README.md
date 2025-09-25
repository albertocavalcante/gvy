# Groovy Language Server Protocol Implementation - Technical Specification

## Executive Summary

This document captures the comprehensive research, architectural decisions, and implementation strategy for building a
modern Language Server Protocol (LSP) implementation for Apache Groovy. After analyzing existing implementations and
evaluating multiple approaches, we've chosen to build a Kotlin-based LSP using LSP4J as the initial protocol layer, with
a clear migration path toward a more idiomatic Kotlin implementation.

## Table of Contents

1. [Project Goals](#project-goals)
2. [Research Analysis](#research-analysis)
3. [Architecture Decisions](#architecture-decisions)
4. [Technical Tradeoffs](#technical-tradeoffs)
5. [Implementation Strategy](#implementation-strategy)
6. [Dependency Management](#dependency-management)
7. [Lessons Learned](#lessons-learned)

## Project Goals

### Primary Objectives

- Build a modern, performant LSP for Apache Groovy
- Provide comprehensive IDE support across all LSP-compliant editors
- Leverage Groovy's AST for accurate language analysis
- Create a maintainable, extensible codebase

### Target Features

- Code completion with context awareness
- Go-to definition and references
- Hover documentation
- Real-time diagnostics
- Symbol search (document and workspace)
- Refactoring support (rename, extract method)
- Code formatting
- Signature help

## Research Analysis

### Existing Groovy LSP (prominic/groovy-language-server)

**Architecture:**

- Language: Java 8 (outdated)
- Protocol: Eclipse LSP4J
- Size: ~4,500 lines of code
- Dependencies: Minimal (LSP4J, Groovy compiler, Gson)

**Strengths:**

- Lightweight implementation
- Direct Groovy AST integration
- Clean separation of concerns (providers pattern)
- Fully open source

**Weaknesses:**

- Limited feature set
- Minimal test coverage (only 5 test files)
- Legacy Java 8 constraints
- No modern language features
- Basic AST visitor pattern without abstraction

**Key Insights:**

- AST visitor patterns are essential for Groovy analysis
- CompilationUnit management is critical
- Provider separation works well for LSP features

### JetBrains Kotlin LSP

**Architecture:**

- Language: Kotlin
- Protocol: Custom implementation (not LSP4J)
- Foundation: Full IntelliJ IDEA platform
- Status: Partially closed-source

**Strengths:**

- Full IntelliJ analysis capabilities
- Advanced features (semantic highlighting, refactoring)
- Production-quality implementation
- Coroutine-based async handling

**Weaknesses:**

- Massive dependency footprint (500MB+ memory)
- Requires 75+ JVM module opens
- Not reusable as a library
- Overly complex for standalone LSP

**Key Insights:**

- IntelliJ platform is overkill for LSP
- Kotlin coroutines excellent for async operations
- Context receivers provide clean API design

### Alternative: fwcd/kotlin-language-server

**Important Discovery:**

- Started with LSP4J, migrated to custom protocol implementation
- Pure Kotlin with custom JSON-RPC layer
- 2K+ GitHub stars, active community
- **Critical Learning:** They migrated away from LSP4J for better Kotlin integration

## Architecture Decisions

### Language Choice: Kotlin

**Why Kotlin over alternatives:**

#### vs Java

- ✅ Modern language features (coroutines, sealed classes, data classes)
- ✅ Null safety
- ✅ More concise code
- ✅ Better DSL capabilities
- ✅ Full Java interop for Groovy compiler

#### vs Rust/Go (Native)

- ✅ Direct Groovy compiler integration (no IPC needed)
- ✅ Shared memory space with Groovy AST
- ✅ Access to Groovy runtime features
- ✅ Simpler debugging
- ❌ Higher memory usage (100-300MB vs 10-30MB)
- ❌ JVM startup time (1-3 seconds)

**Critical Factor:** Groovy's compiler and AST are JVM-only. Native implementation would require:

```
LSP Client <-> Rust/Go LSP <-> IPC <-> JVM Groovy Analyzer
```

This adds complexity without eliminating the JVM requirement.

### Protocol Implementation: LSP4J (Phase 1)

**Initial Choice Rationale:**

- ✅ Battle-tested (used by Metals, Java LSP, others)
- ✅ Complete protocol coverage
- ✅ Type-safe protocol definitions
- ✅ Faster time to market (2-3 weeks saved)
- ✅ Automatic JSON-RPC handling
- ❌ Java-style APIs (CompletableFuture vs coroutines)
- ❌ Potential version lag behind LSP spec

**Migration Strategy:**

1. **Phase 1**: Use LSP4J directly for MVP
2. **Phase 2**: Wrap with Kotlin-idiomatic layer
3. **Phase 3**: Consider custom implementation if needed

## Technical Tradeoffs

### JVM vs Native Implementation

| Aspect                 | JVM (Kotlin)      | Native (Rust/Go)                 |
| ---------------------- | ----------------- | -------------------------------- |
| **Groovy Integration** | Direct, zero-cost | Complex IPC required             |
| **Memory Usage**       | 100-300MB         | 10-30MB + JVM subprocess         |
| **Startup Time**       | 1-3 seconds       | <100ms + JVM startup             |
| **Development Speed**  | Fast              | Slow (cross-language complexity) |
| **Debugging**          | Simple            | Complex (distributed system)     |
| **Distribution**       | Requires JRE      | Still requires JRE for Groovy    |

**Decision:** JVM implementation is mandatory due to Groovy's architecture.

### SDK vs Custom Protocol

| Approach   | Time to Market | Maintenance | Flexibility | Learning |
| ---------- | -------------- | ----------- | ----------- | -------- |
| **LSP4J**  | 2-3 weeks      | Low         | Limited     | Low      |
| **Custom** | 2-3 months     | High        | Full        | High     |
| **Hybrid** | 4-6 weeks      | Medium      | Good        | Medium   |

**Decision:** Start with LSP4J, evolve to hybrid approach.

## Implementation Strategy

### Phase 1: Foundation (Weeks 1-4)

- ✅ Project setup with Gradle and Kotlin
- ✅ Basic LSP server with LSP4J
- ✅ Simple completions and hover
- ✅ Test infrastructure
- 🔲 Groovy AST integration
- 🔲 Basic diagnostics from Groovy compiler

### Phase 2: Core Features (Weeks 5-8)

- 🔲 Full completion support with type inference
- 🔲 Go-to definition using AST
- 🔲 Find references
- 🔲 Document and workspace symbols
- 🔲 Incremental parsing

### Phase 3: Advanced Features (Weeks 9-12)

- 🔲 Refactoring support (rename)
- 🔲 Code actions and quick fixes
- 🔲 Formatting
- 🔲 Gradle build file support
- 🔲 Performance optimization

### Phase 4: Production Ready (Weeks 13-16)

- 🔲 Comprehensive test suite
- 🔲 Performance profiling
- 🔲 Documentation
- 🔲 VS Code extension
- 🔲 Release pipeline

## Dependency Management

### Core Dependencies

| Dependency     | Version     | Rationale                            |
| -------------- | ----------- | ------------------------------------ |
| **Kotlin**     | 2.0.20      | Latest stable, modern features       |
| **LSP4J**      | 0.24.0      | Latest protocol support              |
| **Groovy**     | 4.0.28      | Stable 4.x for Java 8+ compatibility |
| **Coroutines** | 1.10.2      | Async operations                     |
| **Gradle**     | 9.1.0       | Latest build tooling                 |
| **Shadow**     | 9.0.0-beta2 | Fat JAR creation                     |

### Version Selection Methodology

```bash
# Automated version checking via Maven Central API
curl -s "https://search.maven.org/solrsearch/select?q=g:org.eclipse.lsp4j+AND+a:org.eclipse.lsp4j&core=gav&rows=1&wt=json" | jq -r '.response.docs[0].v'
```

### Groovy Version Strategy

- **5.0.x**: Requires Java 11+ (future)
- **4.0.x**: Java 8+ compatibility (current choice)
- Migration path planned for Groovy 5.0 when adoption increases

## Lessons Learned

### What Works

1. **Provider Pattern** (from existing Groovy LSP)

   - Clean separation of LSP features
   - Easy to test individual providers
   - Scalable architecture

2. **AST Visitor Pattern** (from both implementations)

   - Essential for Groovy analysis
   - Must handle dynamic typing
   - Need position mapping for LSP

3. **Coroutines** (from Kotlin LSP)
   - Natural fit for LSP async operations
   - Better than CompletableFuture
   - Structured concurrency benefits

### What to Avoid

1. **IntelliJ Platform Dependency**

   - Too heavy for LSP needs
   - Complex initialization
   - Licensing concerns

2. **Java 8 Target**

   - Missing modern features
   - Limited library support
   - Security concerns

3. **Ignoring Migration Path**
   - fwcd/kotlin-language-server proved migration is needed
   - Plan for evolution from day one

### Best Practices Discovered

1. **Start Simple, Evolve**

   - MVP with basic features
   - Gather user feedback early
   - Iterate based on real usage

2. **Test Infrastructure First**

   - Unit tests for providers
   - Integration tests for LSP protocol
   - Performance benchmarks early

3. **Document Decisions**
   - Architecture Decision Records (ADRs)
   - API documentation
   - User guides

## Performance Targets

### Baseline Metrics

| Operation  | Target | Current |
| ---------- | ------ | ------- |
| Startup    | <2s    | ~1.5s   |
| Completion | <100ms | TBD     |
| Hover      | <50ms  | TBD     |
| Definition | <100ms | TBD     |
| Memory     | <200MB | ~150MB  |

### Optimization Strategies

1. Lazy AST loading
2. Incremental compilation
3. Symbol indexing
4. Cache frequently accessed data
5. Consider GraalVM native image (future)

## Risk Analysis

### Technical Risks

1. **Groovy Dynamic Features**: May limit static analysis

   - Mitigation: Runtime type inference where possible

2. **LSP4J Limitations**: May constrain Kotlin idioms

   - Mitigation: Wrapper layer, migration plan

3. **Performance**: JVM overhead
   - Mitigation: Profiling, caching, lazy loading

### Project Risks

1. **Scope Creep**: Trying to match IntelliJ features

   - Mitigation: Clear MVP definition, phased approach

2. **Maintenance Burden**: Keeping up with Groovy/LSP changes
   - Mitigation: Automated testing, version automation

## Success Criteria

### Short Term (3 months)

- ✅ Working LSP with basic features
- 🔲 50+ GitHub stars
- 🔲 VS Code extension published
- 🔲 5+ active users

### Medium Term (6 months)

- 🔲 Feature parity with existing Groovy LSP
- 🔲 200+ GitHub stars
- 🔲 Support for Gradle projects
- 🔲 20+ active users

### Long Term (12 months)

- 🔲 Advanced refactoring support
- 🔲 1000+ GitHub stars
- 🔲 Multiple editor integrations
- 🔲 100+ active users
- 🔲 Corporate adoption

## Conclusion

Building a Groovy LSP in Kotlin with LSP4J provides the optimal balance of:

- **Development velocity**: Leverage existing protocol implementation
- **Maintainability**: Modern language features and patterns
- **Performance**: Direct JVM integration with Groovy
- **Flexibility**: Clear migration path to native Kotlin implementation

The phased approach allows us to deliver value quickly while maintaining the flexibility to evolve based on user needs
and technical requirements.

## References

- [Language Server Protocol Specification](https://microsoft.github.io/language-server-protocol/)
- [Eclipse LSP4J](https://github.com/eclipse-lsp4j/lsp4j)
- [Apache Groovy AST Documentation](http://groovy-lang.org/metaprogramming.html#_ast_transformations)
- [Existing Groovy LSP](https://github.com/prominic/groovy-language-server)
- [fwcd/kotlin-language-server](https://github.com/fwcd/kotlin-language-server)
- [JetBrains Kotlin LSP](https://github.com/JetBrains/kotlin)

---

_Document Version: 1.0.0_ _Last Updated: September 2024_ _Author: Alberto Cavalcante_
