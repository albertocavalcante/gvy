# Bazel Migration Documentation

This directory contains comprehensive documentation for the Gradle-to-Bazel migration of the Groovy Language Server
project.

---

## Documentation Index

### 📋 [MIGRATION.md](../MIGRATION.md) - Complete Migration Guide

**77 KB | Detailed technical guide**

The definitive guide to the manual migration process, documenting every transformation from Gradle to Bazel.

**Contents:**

- 5-phase migration process with step-by-step instructions
- Detailed transformation examples from actual project files
- Comprehensive mapping tables (Gradle ↔ Bazel)
- Automation opportunities identified (70-80% automatable)
- Challenges and lessons learned

**Use this when:**

- Performing a manual migration on another project
- Understanding the detailed mechanics of Gradle-to-Bazel conversion
- Designing automation tooling (reference implementation)
- Training team members on migration concepts

---

### 🛠️ [tools/migrate/README.md](../tools/migrate/README.md) - Migration Tool Design

**63 KB | Architecture and implementation plan**

Detailed design for an automated Gradle-to-Bazel migration tool, based on lessons learned from the manual migration.

**Contents:**

- Complete architecture with component diagrams
- Data structure specifications (parsers, transformers, generators)
- 9-week implementation plan with deliverables
- Technology stack recommendations (Kotlin + libraries)
- CLI interface design and configuration file format

**Use this when:**

- Building the migration automation tool
- Understanding automation scope and limitations
- Planning development sprints and milestones
- Estimating effort for new features

---

### 📊 [bazel-migration-summary.md](./bazel-migration-summary.md) - Quick Reference

**37 KB | High-level summary and quick lookup**

Condensed reference guide with examples, patterns, and troubleshooting.

**Contents:**

- Quick transformation tables
- Real-world examples from this project
- Common patterns library (copy-paste templates)
- Decision records (why certain choices were made)
- Troubleshooting guide for common errors

**Use this when:**

- Need quick reference during migration work
- Looking for copy-paste BUILD file patterns
- Debugging build errors (troubleshooting section)
- Understanding high-level migration flow

---

## Quick Links

- **Complete Guide:** [MIGRATION.md](../MIGRATION.md)
- **Tool Design:** [tools/migrate/README.md](../tools/migrate/README.md)
- **Quick Reference:** [bazel-migration-summary.md](./bazel-migration-summary.md)

## External Resources

- [Bazel Documentation](https://bazel.build/)
- [rules_kotlin](https://github.com/bazelbuild/rules_kotlin)
- [rules_jvm_external](https://github.com/bazelbuild/rules_jvm_external)
