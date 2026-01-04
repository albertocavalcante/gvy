---
description: Comprehensive codebase review and planning for next improvements
---

// turbo-all

# Next Steps Planning Workflow

Systematic review of codebase health, recent activity, and planning for improvements.

## Phase 0: Choose Scope (Monorepo)

This is a monorepo with **two build systems**:

- **Kotlin/Gradle** (most modules) → `make lint`, `make test`, `make build`
- **VS Code extension** under `editors/code` (Node/pnpm) → `pnpm run lint`, `pnpm run check-types`,
  `pnpm run format:check`, `pnpm run test:all`

Pick the right scope before doing anything else:

- **Component-only**: you’re changing a single module/component (fast loop).
- **PR scope**: you want CI-like confidence for what changed.
- **Repo-wide health**: you’re doing broad cleanup/refactors.

### 0.1 Path-based gating (PR scope)

```bash
git diff --name-only origin/main...HEAD
```

Rules of thumb:

- If changes include `editors/code/**`, include the extension checks.
- If changes include Kotlin, include the Gradle checks.
- If changes include both, run both toolchains.

### 0.2 If the work is "quality" / lint-driven

Use the dedicated deterministic workflows:

- `/lint` for assessment and smell review
- `/lintfix` for fixing findings via small verified loops

If you want the repo’s **pre-commit definition** (cross-toolchain) as your source-of-truth gate, run:

```bash
command -v lefthook
lefthook run pre-commit
```

## Phase 1: Recent Activity Review

### 1.1 Review Latest Commits

```bash
git log --oneline -15 --decorate
```

### 1.2 Check Open PRs and Issues

```bash
gh pr list --limit 10
gh issue list --limit 10 --state open
```

### 1.3 Review Recent CI Status

```bash
gh run list --limit 5
```

---

## Phase 2: Code Quality Analysis

### 2.0 Monorepo Quality Gates (recommended)

For **PR scope** validation:

```bash
# Kotlin/Gradle side
make lint

# Extension side (only if editors/code changed)
cd editors/code && pnpm install --frozen-lockfile
cd editors/code && pnpm run check-types && pnpm run lint && pnpm run format:check
```

### 2.1 SonarCloud Issues (Code Smells, Bugs, Vulnerabilities)

```bash
curl -s "https://sonarcloud.io/api/issues/search?componentKeys=albertocavalcante_groovy-lsp&types=BUG,CODE_SMELL,VULNERABILITY&statuses=OPEN,CONFIRMED&ps=50" | jq '.issues[] | {type, severity, message, component: (.component | split(":")[1]), line}'
```

### 2.2 Overall Project Metrics

```bash
curl -s "https://sonarcloud.io/api/measures/component?component=albertocavalcante_groovy-lsp&metricKeys=coverage,code_smells,bugs,vulnerabilities,duplicated_lines_density,sqale_rating,reliability_rating,security_rating" | jq '.component.measures[] | {metric: .metric, value: .value}'
```

### 2.3 Test Coverage by Module

```bash
curl -s "https://sonarcloud.io/api/measures/component_tree?component=albertocavalcante_groovy-lsp&metricKeys=coverage,uncovered_lines&qualifiers=FIL&ps=50&s=metric,name&metricSort=coverage&asc=true" | jq '.components[:20] | .[] | {name: .name, coverage: (.measures[] | select(.metric=="coverage") | .value)}'
```

---

## Phase 3: Codebase Structure Review

### 3.1 Module Overview

```bash
find . -name "build.gradle.kts" -type f | head -10
```

### 3.2 Source File Distribution

```bash
find . -name "*.kt" -type f | wc -l
find . -path "*/src/test/*" -name "*.kt" | wc -l
find . -path "*/src/main/*" -name "*.kt" | wc -l
```

### 3.3 Largest Files (Potential Refactoring Candidates)

```bash
find . -name "*.kt" -type f -exec wc -l {} + | sort -rn | head -15
```

---

## Phase 4: Dependency & Build Health

### 4.1 Check for Outdated Dependencies

```bash
./gradlew dependencyUpdates 2>/dev/null || echo "Plugin not available - consider adding com.github.ben-manes.versions"
```

### 4.2 Build Status

```bash
./gradlew build --dry-run 2>&1 | tail -20
```

---

## Phase 5: Synthesis and Planning

After gathering data from Phases 1-4, create an implementation plan artifact addressing:

### Categories to Prioritize

| Priority    | Category                                                 | Examples                           |
| ----------- | -------------------------------------------------------- | ---------------------------------- |
| 🔴 Critical | Security vulnerabilities, breaking bugs                  | SonarCloud BLOCKER/CRITICAL issues |
| 🟠 High     | Low test coverage, code smells affecting maintainability | Files <50% coverage, major smells  |
| 🟡 Medium   | Technical debt, non-idiomatic patterns                   | Duplications, complex methods      |
| 🟢 Low      | Polish, documentation, minor improvements                | README updates, comment cleanup    |

### Idiomatic Kotlin Checklist

Review codebase for these patterns:

- [ ] **Null safety**: Using `?.`, `?:`, `!!` appropriately (minimize `!!`)
- [ ] **Data classes**: For DTOs and value objects
- [ ] **Sealed classes**: For type hierarchies
- [ ] **Extension functions**: For utility methods
- [ ] **Scope functions**: `let`, `run`, `apply`, `also`, `with` used idiomatically
- [ ] **Collection operations**: `map`, `filter`, `fold` over loops
- [ ] **Coroutines**: For async operations (if applicable)
- [ ] **Property delegation**: `lazy`, `observable`, custom delegates

### Output: Implementation Plan

Create `/implementation_plan.md` with:

1. **Summary**: Current codebase health (1-2 sentences)
2. **Critical Issues**: Must-fix before next release
3. **Next PR Stack**: Ordered list of 3-5 PRs to ship
4. **Tech Debt Backlog**: Items for future sprints
5. **Metrics Goals**: Target coverage %, smell reduction, etc.

---

## Quick Reference: Common Next Actions

| Finding                 | Recommended Action                               |
| ----------------------- | ------------------------------------------------ |
| Coverage < 80%          | Write tests for uncovered critical paths         |
| BLOCKER issues          | Fix immediately, create hotfix PR                |
| Large files (> 300 LOC) | Extract classes/functions, single responsibility |
| Duplicated code > 3%    | Extract shared utilities                         |
| Missing sealed classes  | Refactor type hierarchies                        |
| Excessive `!!` usage    | Add proper null handling                         |
| No CI for something     | Add GitHub Action workflow                       |

## Quick Reference: Monorepo Commands

Kotlin/Gradle:

```bash
make lint
make test
make build
```

VS Code extension (from repo root):

```bash
cd editors/code && pnpm install --frozen-lockfile
cd editors/code && pnpm run check-types
cd editors/code && pnpm run lint
cd editors/code && pnpm run format:check
cd editors/code && pnpm run test:all
```
