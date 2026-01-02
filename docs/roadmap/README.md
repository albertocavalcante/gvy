# Groovy LSP Roadmap

> **Current Version:** 0.3.x\
> **Vision:** The definitive Language Server for Apache Groovy and its ecosystem

---

## 🌟 Project Vision

**Groovy LSP** aims to be the most comprehensive, performant, and extensible Language Server for the Groovy ecosystem,
providing first-class IDE support for:

- **Core Groovy** – Full language support with completion, navigation, and refactoring
- **Jenkins Pipelines** – IntelliSense for Declarative & Scripted pipelines
- **Spock Framework** – Test-aware completions and diagnostics
- **Gradle Build Scripts** – Build DSL support with task navigation
- **GDSL/DSLD** – Custom DSL definition support

---

## 📊 Current Status

| Module                             | Maturity  | Description                              |
| ---------------------------------- | --------- | ---------------------------------------- |
| Core LSP (`groovy-lsp`)            | 🟢 Stable | Text sync, completion, hover, navigation |
| Parser (`parser/native`)           | 🟢 Stable | AST parsing with error recovery          |
| Diagnostics (`groovy-diagnostics`) | 🟡 Beta   | CodeNarc integration, compiler errors    |
| Jenkins (`groovy-jenkins`)         | 🟡 Beta   | Step completion, shared libraries        |
| Spock (`groovy-spock`)             | 🟡 Beta   | Block detection, spec support            |
| GDSL (`groovy-gdsl`)               | 🟠 Alpha  | Script execution foundation              |
| Build Tool (`groovy-build-tool`)   | 🟠 Alpha  | BSP/Gradle integration                   |
| Formatter (`groovy-formatter`)     | 🟠 Alpha  | OpenRewrite-based formatting             |

---

## 🎯 Roadmap Overview

### Version 0.4.0 – "Workspace Intelligence"

_Target: Q1 2025_

**Theme:** Production-ready Jenkins support and workspace-wide features

| Priority | Feature                                                    | Spec                                          |
| -------- | ---------------------------------------------------------- | --------------------------------------------- |
| P1       | [Jenkins @Library Resolution](#jenkins-library-resolution) | [→ Spec](specs/JENKINS_LIBRARY_RESOLUTION.md) |
| P1       | [Workspace Config Refresh](#workspace-config-refresh)      | [→ Spec](specs/WORKSPACE_CONFIG_REFRESH.md)   |
| P1       | [Classpath-Aware Completion](#classpath-aware-completion)  | [→ Spec](specs/CLASSPATH_COMPLETION.md)       |
| P1       | [Workspace-Wide Navigation](#workspace-wide-navigation)    | [→ Spec](specs/WORKSPACE_NAVIGATION.md)       |
| P2       | [File Change Watching](#file-change-watching)              | [→ Spec](specs/FILE_WATCHING.md)              |
| P2       | [Type Hierarchy](#type-hierarchy)                          | [→ Spec](specs/TYPE_HIERARCHY.md)             |

[📋 Detailed v0.4.0 Plan](../ROADMAP-0.4.0.md)

---

### Version 0.5.0 – "Jenkins IntelliSense"

_Target: Q2 2025_

**Theme:** Full Jenkins pipeline IntelliSense with context-aware completions

| Priority | Feature                                                                 | Spec                                              |
| -------- | ----------------------------------------------------------------------- | ------------------------------------------------- |
| P1       | [Context-Aware Jenkins Completions](#context-aware-jenkins-completions) | [→ Spec](../JENKINS_INTELLISENSE_ARCHITECTURE.md) |
| P1       | [GDSL Execution Engine](#gdsl-execution-engine)                         | [→ Spec](specs/GDSL_EXECUTION_ENGINE.md)          |
| P1       | [Versioned Jenkins Metadata](#versioned-jenkins-metadata)               | [→ Spec](specs/VERSIONED_METADATA.md)             |
| P2       | [Plugin Introspection](#plugin-introspection)                           | [→ Spec](specs/PLUGIN_INTROSPECTION.md)           |
| P2       | [User Override System](#user-override-system)                           | [→ Spec](specs/USER_OVERRIDES.md)                 |

---

### Version 0.6.0 – "Refactoring & Intelligence"

_Target: Q3 2025_

**Theme:** Advanced refactoring and code intelligence features

| Priority | Feature                                         | Spec                                   |
| -------- | ----------------------------------------------- | -------------------------------------- |
| P1       | [Rename Refactoring](#rename-refactoring)       | [→ Spec](specs/RENAME_REFACTORING.md)  |
| P1       | [Extract Method/Variable](#extract-refactoring) | [→ Spec](specs/EXTRACT_REFACTORING.md) |
| P1       | [Semantic Tokens](#semantic-tokens)             | [→ Spec](specs/SEMANTIC_TOKENS.md)     |
| P2       | [Inlay Hints](#inlay-hints)                     | [→ Spec](specs/INLAY_HINTS.md)         |
| P2       | [Call Hierarchy](#call-hierarchy)               | [→ Spec](specs/CALL_HIERARCHY.md)      |

---

### Version 1.0.0 – "Production Ready"

_Target: Q4 2025_

**Theme:** Stability, performance, and ecosystem completeness

| Priority | Feature                                               | Spec                                     |
| -------- | ----------------------------------------------------- | ---------------------------------------- |
| P1       | [Gradle DSL Support](#gradle-dsl-support)             | [→ Spec](specs/GRADLE_DSL.md)            |
| P1       | [Performance Optimization](#performance-optimization) | [→ Spec](../PHASE_3_PERFORMANCE_PLAN.md) |
| P1       | [DSLD Support](#dsld-support)                         | [→ Spec](specs/DSLD_SUPPORT.md)          |
| P2       | [Multi-Root Workspace](#multi-root-workspace)         | [→ Spec](specs/MULTI_ROOT.md)            |
| P2       | [Debug Adapter Protocol](#debug-adapter)              | [→ Spec](specs/DAP.md)                   |

---

## 📋 Feature Details

### Core Language Features

#### Completion

| Feature                       | Status     | Spec                               |
| ----------------------------- | ---------- | ---------------------------------- |
| Keyword completion            | ✅ Done    | –                                  |
| Local variable completion     | ✅ Done    | –                                  |
| Method completion             | ✅ Done    | –                                  |
| Import completion             | ✅ Done    | –                                  |
| Classpath completion          | 🔲 Planned | [→](specs/CLASSPATH_COMPLETION.md) |
| Smart completion (type-aware) | 🔲 Planned | [→](specs/SMART_COMPLETION.md)     |
| Postfix completion            | 🔲 Planned | [→](specs/POSTFIX_COMPLETION.md)   |

#### Navigation

| Feature               | Status     | Spec                         |
| --------------------- | ---------- | ---------------------------- |
| Go to Definition      | ✅ Done    | –                            |
| Go to Type Definition | ✅ Done    | –                            |
| Find References       | ✅ Done    | –                            |
| Document Symbols      | ✅ Done    | –                            |
| Workspace Symbols     | ✅ Done    | –                            |
| Go to Implementation  | 🔲 Planned | [→](specs/TYPE_HIERARCHY.md) |
| Call Hierarchy        | 🔲 Planned | [→](specs/CALL_HIERARCHY.md) |
| Type Hierarchy        | 🔲 Planned | [→](specs/TYPE_HIERARCHY.md) |

#### Diagnostics

| Feature           | Status         | Spec                      |
| ----------------- | -------------- | ------------------------- |
| Compiler errors   | ✅ Done        | –                         |
| CodeNarc linting  | ✅ Done        | [→](../CODENARC.md)       |
| Jenkins CPS rules | ✅ Done        | [→](../CODENARC.md)       |
| Unused imports    | ✅ Done        | –                         |
| Quick fixes       | ⏳ In Progress | [→](specs/QUICK_FIXES.md) |

#### Refactoring

| Feature          | Status         | Spec                              |
| ---------------- | -------------- | --------------------------------- |
| Rename symbol    | ⏳ In Progress | [→](specs/RENAME_REFACTORING.md)  |
| Extract variable | 🔲 Planned     | [→](specs/EXTRACT_REFACTORING.md) |
| Extract method   | 🔲 Planned     | [→](specs/EXTRACT_REFACTORING.md) |
| Inline variable  | 🔲 Planned     | –                                 |
| Organize imports | 🔲 Planned     | –                                 |

---

### Jenkins Pipeline Support

| Feature                        | Status         | Spec                                         |
| ------------------------------ | -------------- | -------------------------------------------- |
| Jenkinsfile detection          | ✅ Done        | –                                            |
| Step completion                | ✅ Done        | –                                            |
| Global variable completion     | ✅ Done        | –                                            |
| Shared library vars completion | ✅ Done        | –                                            |
| @Library resolution            | 🔲 Planned     | [→](specs/JENKINS_LIBRARY_RESOLUTION.md)     |
| Context-aware completion       | 🔲 Planned     | [→](../JENKINS_INTELLISENSE_ARCHITECTURE.md) |
| Plugin metadata extraction     | 🔲 Planned     | [→](specs/PLUGIN_INTROSPECTION.md)           |
| CPS safety diagnostics         | ✅ Done        | [→](../CODENARC.md)                          |
| Declarative pipeline support   | ⏳ In Progress | [→](../JENKINS_INTELLISENSE_ARCHITECTURE.md) |

---

### Framework Support

#### Spock Testing Framework

| Feature                           | Status     | Spec                         |
| --------------------------------- | ---------- | ---------------------------- |
| Spec class detection              | ✅ Done    | [→](../SPOCK_SUPPORT.md)     |
| Block detection (given/when/then) | ✅ Done    | [→](../SPOCK_AST_SUPPORT.md) |
| Data table completion             | 🔲 Planned | [→](../SPOCK_NEXT_STEPS.md)  |
| Mock/Stub support                 | 🔲 Planned | [→](../SPOCK_NEXT_STEPS.md)  |

#### Gradle Build Scripts

| Feature               | Status     | Spec                     |
| --------------------- | ---------- | ------------------------ |
| build.gradle parsing  | 🔲 Planned | [→](specs/GRADLE_DSL.md) |
| Task completion       | 🔲 Planned | [→](specs/GRADLE_DSL.md) |
| Dependency completion | 🔲 Planned | [→](specs/GRADLE_DSL.md) |
| Plugin DSL support    | 🔲 Planned | [→](specs/GRADLE_DSL.md) |

---

### DSL Support

| Feature                 | Status         | Spec                                         |
| ----------------------- | -------------- | -------------------------------------------- |
| GDSL script execution   | ⏳ In Progress | [→](specs/GDSL_EXECUTION_ENGINE.md)          |
| GDSL text parsing       | ✅ Done        | [→](../JENKINS_INTELLISENSE_ARCHITECTURE.md) |
| DSLD (Eclipse format)   | 🔲 Planned     | [→](specs/DSLD_SUPPORT.md)                   |
| Custom DSL registration | 🔲 Planned     | [→](specs/USER_OVERRIDES.md)                 |

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        groovy-lsp                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │  Providers   │  │   Services   │  │   Protocol   │          │
│  │  - Completion│  │  - Workspace │  │  - LSP4J     │          │
│  │  - Hover     │  │  - Document  │  │  - JSON-RPC  │          │
│  │  - Navigation│  │  - Index     │  │              │          │
│  └──────┬───────┘  └──────┬───────┘  └──────────────┘          │
│         │                 │                                     │
│         ▼                 ▼                                     │
│  ┌─────────────────────────────────────────────────────────────┤
│  │                    Core Services                             │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │  │ groovy-parser│  │groovy-common │  │groovy-formatter│      │
│  │  │  - AST       │  │  - Utils     │  │  - OpenRewrite│       │
│  │  │  - Visitors  │  │  - Models    │  │               │       │
│  │  └──────────────┘  └──────────────┘  └───────────────┘       │
│  └──────────────────────────────────────────────────────────────┤
└─────────────────────────────────────────────────────────────────┘
         │                    │                    │
         ▼                    ▼                    ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│ groovy-jenkins  │  │  groovy-spock   │  │ groovy-build-tool│
│ - Step metadata │  │ - Block detect  │  │ - BSP client    │
│ - Shared libs   │  │ - Data tables   │  │ - Gradle        │
│ - CPS rules     │  │                 │  │                 │
└─────────────────┘  └─────────────────┘  └─────────────────┘
         │
         ▼
┌─────────────────┐  ┌─────────────────┐
│   groovy-gdsl   │  │groovy-diagnostics│
│ - Script exec   │  │ - CodeNarc      │
│ - Descriptor    │  │ - Compiler      │
│   parsing       │  │   errors        │
└─────────────────┘  └─────────────────┘
```

[→ Full Architecture Documentation](../ARCHITECTURE.md)

---

## 📚 Specification Documents

### Core Specifications

| Document                                                                    | Description                 |
| --------------------------------------------------------------------------- | --------------------------- |
| [LSP Implementation Guide](../../LSP_SPECIFICATION_IMPLEMENTATION_GUIDE.md) | LSP 3.17 compliance details |
| [Architecture](../ARCHITECTURE.md)                                          | Module structure and design |
| [Performance Plan](../PHASE_3_PERFORMANCE_PLAN.md)                          | Optimization strategies     |

### Feature Specifications

| Document                                                        | Description                     |
| --------------------------------------------------------------- | ------------------------------- |
| [Jenkins IntelliSense](../JENKINS_INTELLISENSE_ARCHITECTURE.md) | Jenkins completion architecture |
| [Spock Support](../SPOCK_SUPPORT.md)                            | Spock framework integration     |
| [CodeNarc Integration](../CODENARC.md)                          | Linting and diagnostics         |
| [BSP Integration](../BSP_CLIENT_IMPLEMENTATION.md)              | Build Server Protocol           |
| [Gradle Integration](../GRADLE_BUILD_SERVER_INTEGRATION.md)     | Gradle Build Server             |

### Technical Specifications

| Document                                          | Description                    |
| ------------------------------------------------- | ------------------------------ |
| [Specs Index](specs/INDEX.md)                     | All technical specifications   |
| [GDSL Execution](specs/GDSL_EXECUTION_ENGINE.md)  | GDSL script execution          |
| [Versioned Metadata](specs/VERSIONED_METADATA.md) | Jenkins version-aware metadata |
| [User Overrides](specs/USER_OVERRIDES.md)         | Custom configuration system    |

---

## 🧪 Quality Standards

### Testing Requirements

- **Unit Tests:** ≥80% coverage for new code
- **Integration Tests:** LSP protocol compliance
- **E2E Tests:** Real editor scenarios
- **Property Tests:** Edge case coverage

### Performance Targets

| Metric                 | Target       | Current |
| ---------------------- | ------------ | ------- |
| Startup time           | <2s          | ~1.5s   |
| Completion latency     | <100ms       | ~80ms   |
| Memory (idle)          | <200MB       | ~150MB  |
| Large file (10K lines) | <500ms parse | ~400ms  |

### CI/CD

- All PRs require passing tests
- Automatic releases via release-please
- Coverage reports via Kover
- Static analysis via Detekt

---

## 🤝 Contributing

See [CONTRIBUTING.md](../../CONTRIBUTING.md) for guidelines.

### Priority Labels

- **P0-critical:** Blocker bugs, security issues
- **P1-must:** Required for release
- **P2-should:** Important improvements
- **P3-nice:** Enhancements if time permits

### Size Labels

- **size/XS:** <1 hour
- **size/S:** 1-4 hours
- **size/M:** 1-3 days
- **size/L:** 1 week
- **size/XL:** >1 week

---

## 📅 Release Schedule

| Version | Target  | Theme                      |
| ------- | ------- | -------------------------- |
| 0.4.0   | Q1 2025 | Workspace Intelligence     |
| 0.5.0   | Q2 2025 | Jenkins IntelliSense       |
| 0.6.0   | Q3 2025 | Refactoring & Intelligence |
| 1.0.0   | Q4 2025 | Production Ready           |

---

## 📬 Feedback

- **Issues:** [GitHub Issues](https://github.com/albertocavalcante/groovy-lsp/issues)
- **Discussions:** [GitHub Discussions](https://github.com/albertocavalcante/groovy-lsp/discussions)

---

_Last updated: December 21, 2025_
