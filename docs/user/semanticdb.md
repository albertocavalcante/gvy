# SemanticDB design

This page is an orientation to the semantic model, not a promise that every language feature uses it or works for every
project. The maintained [semantics design](../semantics/design.md) has the fuller design and implementation context.

## Architecture

Semantic analysis represents information about source files for consumers. Actual language-server paths and their limits
evolve independently; consult the [provisional feature matrix](../lsp/feature-support.md).

## What is SemanticDB?

SemanticDB is a model for semantic information about programs. See the
[design](../semantics/design.md#what-is-semanticdb) for context.

### Specification

The [SemanticDB specification](https://scalameta.org/docs/semanticdb/specification.html) describes its data model.

### Core Concepts

Symbols identify program entities; occurrences associate symbols with source locations. Symbol information describes
entities. See the [design](../semantics/design.md) for examples.

## Why SemanticDB?

A shared model can separate producers of semantic information from consumers. This design goal does not guarantee
cross-language navigation in every project.

## Module Structure

See the [repository overview](../overview.md) and [semantics design](../semantics/design.md) for module
responsibilities. Source code is authoritative when the design and implementation differ.

## LSP Integration

Language features may consume semantic information alongside other analysis paths. Availability and correctness vary by
feature and release; consult the [feature matrix](../lsp/feature-support.md) and the
[stable release limitation](../installation.md#stable-release-limitation).

## References

- [gvy semantics design](../semantics/design.md)
- [SemanticDB guide](https://scalameta.org/docs/semanticdb/guide.html)
- [SemanticDB specification](https://scalameta.org/docs/semanticdb/specification.html)
