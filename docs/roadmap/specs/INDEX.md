# Technical Specifications Index

> **Location:** `docs/roadmap/specs/`\
> **Parent:** [Roadmap](../README.md)

This directory contains detailed technical specifications for Groovy LSP features.

## Specification Format

Each specification follows this structure:

```markdown
# Feature Name

## Overview

Brief description of the feature.

## Motivation

Why this feature is needed.

## Design

### Data Model

### API Design

### Integration Points

## Implementation

### Phase 1: ...

### Phase 2: ...

## Testing Strategy

### Unit Tests

### Integration Tests

### E2E Tests

## References

- Related specs
- External documentation
```

---

## Core Specifications

| Specification                                     | Status   | Description                                 |
| ------------------------------------------------- | -------- | ------------------------------------------- |
| [GDSL Execution Engine](GDSL_EXECUTION_ENGINE.md) | 📋 Draft | Execute GDSL scripts for dynamic completion |
| [Classpath Completion](CLASSPATH_COMPLETION.md)   | 📋 Draft | Import suggestions from classpath           |
| [Workspace Navigation](WORKSPACE_NAVIGATION.md)   | 📋 Draft | Cross-file symbol navigation                |

## Jenkins Specifications

| Specification                                               | Status   | Description                   |
| ----------------------------------------------------------- | -------- | ----------------------------- |
| [Jenkins Library Resolution](JENKINS_LIBRARY_RESOLUTION.md) | 📋 Draft | @Library annotation support   |
| [Versioned Metadata](VERSIONED_METADATA.md)                 | 📋 Draft | LTS version-aware metadata    |
| [Plugin Introspection](PLUGIN_INTROSPECTION.md)             | 📋 Draft | Extract metadata from plugins |
| [User Overrides](USER_OVERRIDES.md)                         | 📋 Draft | Custom GDSL configuration     |

## Refactoring Specifications

| Specification                                 | Status   | Description                 |
| --------------------------------------------- | -------- | --------------------------- |
| [Rename Refactoring](RENAME_REFACTORING.md)   | 📋 Draft | Symbol rename across files  |
| [Extract Refactoring](EXTRACT_REFACTORING.md) | 📋 Draft | Extract method/variable     |
| [Quick Fixes](QUICK_FIXES.md)                 | 📋 Draft | Code action implementations |

## UI/UX Specifications

| Specification                         | Status   | Description                |
| ------------------------------------- | -------- | -------------------------- |
| [Semantic Tokens](SEMANTIC_TOKENS.md) | 📋 Draft | Syntax highlighting tokens |
| [Inlay Hints](INLAY_HINTS.md)         | 📋 Draft | Inline parameter hints     |
| [Call Hierarchy](CALL_HIERARCHY.md)   | 📋 Draft | Incoming/outgoing calls    |
| [Type Hierarchy](TYPE_HIERARCHY.md)   | 📋 Draft | Class/interface hierarchy  |

## Ecosystem Specifications

| Specification                         | Status   | Description                 |
| ------------------------------------- | -------- | --------------------------- |
| [Gradle DSL](GRADLE_DSL.md)           | 📋 Draft | Gradle build script support |
| [DSLD Support](DSLD_SUPPORT.md)       | 📋 Draft | Eclipse DSL Descriptors     |
| [Multi-Root Workspace](MULTI_ROOT.md) | 📋 Draft | Multiple project roots      |

## Infrastructure Specifications

| Specification                                           | Status   | Description                  |
| ------------------------------------------------------- | -------- | ---------------------------- |
| [File Watching](FILE_WATCHING.md)                       | 📋 Draft | File system change detection |
| [Workspace Config Refresh](WORKSPACE_CONFIG_REFRESH.md) | 📋 Draft | Configuration hot reload     |

---

## Status Legend

| Icon           | Meaning                           |
| -------------- | --------------------------------- |
| 📋 Draft       | Initial design, subject to change |
| 🔄 In Review   | Under technical review            |
| ✅ Approved    | Ready for implementation          |
| 🚧 In Progress | Implementation underway           |
| ✔️ Complete     | Implemented and tested            |
