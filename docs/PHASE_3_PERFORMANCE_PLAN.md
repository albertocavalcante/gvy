# Phase 3: Performance Hardening & Verification

## Overview

This phase focuses on verifying and optimizing the performance of the new `RecursiveAstVisitor` before it replaces the
legacy `NodeVisitorDelegate`. We specifically target **memory allocation rate** due to the closure-based tracking
design.

## Objectives

1. **Establish Performance Baseline**: Measure throughput and memory allocation of the legacy `NodeVisitorDelegate`.
2. **Measure Regression**: Quantify the impact of `RecursiveAstVisitor`'s object allocation (`track { ... }` lambdas).
3. **Optimize**: Refactor `RecursiveAstVisitor` to achieve "zero-allocation" parity with the baseline.
4. **Infrastructure**: Establish a permanent JMH benchmark suite to prevent future regressions.

## Architecture

We will use **JMH (Java Microbenchmark Harness)** via the `me.champeau.jmh` Gradle plugin.

- **Location**: `parser/native/src/jmh` (Separate source set)
- **Visibility**: Can access `internal` classes of `parser/native` (White-box testing).
- **Metrics**:
  - `Throughput` (ops/sec)
  - `gc.alloc.rate.norm` (B/op) - _Primary Success Metric_

## Scenarios

### 1. Visitor Traversal (Microbenchmark)

- **Setup**: Parse a complex Groovy file (e.g., a large Jenkinsfile or Gradle script) into a `ModuleNode` AST _once_.
- **Benchmark**: Run the visitor against the pre-parsed AST.
- **Why**: Isolates visitor performance from parsing I/O and tokenization noise.

### 2. Full Parse (Macrobenchmark)

- **Benchmark**: `parser.parse(text)`
- **Why**: Validates the end-to-end impact on the user (latency).

## Implementation Plan

### Step 1: Infrastructure (1 Hour)

- Add `me.champeau.jmh` plugin to `parser/native/build.gradle.kts`.
- Configure `jmh` source set.
- Create `VisitorBenchmark.kt`.

### Step 2: Baseline & Measurement (2 Hours)

- Implement `LegacyVisitorBenchmark` (NodeVisitorDelegate).
- Implement `RecursiveVisitorBenchmark` (RecursiveAstVisitor).
- Run initial comparison.
- **Hypothesis**: Recursive visitor will show significantly higher `gc.alloc.rate` (~48 bytes per node visited).

### Step 3: Optimization (Iterative)

- **Attempt 1 (Inline)**: Mark `track` and `visitWithTracking` as `inline`.
  - _Risk_: JVM might not handle inlining well with private methods in anonymous inner classes.
- **Attempt 2 (Unroll)**: Manually unroll the `track` helper back to imperative `push/try/pop` logic if inlining fails.
- **Verify**: Re-run benchmark to confirm reduced allocation.

### Step 4: Finalize (1 Hour)

- Document results in `docs/BENCHMARK_RESULTS.md`.
- Commit benchmarks as permanent codebase infrastructure.

## Success Criteria

| Metric            | Target                      |
| :---------------- | :-------------------------- |
| **Throughput**    | Recursive >= 95% of Legacy  |
| **Memory (B/op)** | Recursive <= 110% of Legacy |

If Recursive meets these targets, we proceed to **Phase 4 (Switch Default)**.
