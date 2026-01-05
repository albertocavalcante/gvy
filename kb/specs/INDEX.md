# Technical Specifications Index

This directory contains drafted technical specifications. If a spec is not listed here, it has not been written yet.

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

## Drafted Specifications

| Specification                                     | Status | Description                                 |
| ------------------------------------------------- | ------ | ------------------------------------------- |
| [GDSL Execution Engine](GDSL_EXECUTION_ENGINE.md) | Draft  | Execute GDSL scripts for dynamic completion |
| [Versioned Metadata](VERSIONED_METADATA.md)       | Draft  | LTS version-aware Jenkins metadata          |
| [User Overrides](USER_OVERRIDES.md)               | Draft  | Custom Jenkins/GDSL configuration           |

## Related Design Docs

- Jenkins IntelliSense Architecture: `../JENKINS_INTELLISENSE_ARCHITECTURE.md`
- Spock support plan: `../SPOCK_SUPPORT.md`
- CodeNarc integration notes: `../CODENARC.md`

---

## Status Legend

| Status      | Meaning                           |
| ----------- | --------------------------------- |
| Draft       | Initial design, subject to change |
| In review   | Under technical review            |
| Approved    | Ready for implementation          |
| In progress | Implementation underway           |
| Complete    | Implemented and tested            |
