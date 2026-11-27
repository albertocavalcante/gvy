<!--
SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
SPDX-License-Identifier: Apache-2.0
-->

# concurrent

Thread-safe state primitives named for what they are for: `OnceFlag`, `SharedValue<T>` and `Counter`. The
[Kotlin style guide](../../docs/kotlin-style.md#shared-mutable-state) says when to use each. Issue #2474 tracks
replacing `@Volatile` fields and direct atomics with them.

## Design

- **Backed by the stdlib's `kotlin.concurrent.atomics`**, which is experimental (`@ExperimentalAtomicApi`; stabilization
  is [KT-82228](https://youtrack.jetbrains.com/issue/KT-82228)). This module is the only place gvy opts in. Elsewhere,
  using the package without an opt-in fails to compile, and detekt's `StdlibAtomics` rule reports any use in another
  module, opt-in or not.
- **No behavior risk on the JVM.** There the stdlib atomics compile to `java.util.concurrent.atomic`. An upstream API
  change breaks compilation in this module and nowhere else.
- **Plain final classes**, not inline or value classes, so their representation stays private. A different
  implementation (a changed stdlib API, `expect`/`actual` over JVM atomics, or atomicfu) changes only this module.
- **No raw compare-and-set on `SharedValue`.** It compares by reference, so a boxed number or an equal but distinct
  object would fail silently. `update {}` compares against the value it read.
- **Kotlin Multiplatform with JVM and native targets.** With only a JVM target, common code may call JVM APIs; the
  native targets make the compiler reject them. They are the launcher's targets, and CI compiles and tests them in the
  native launcher job. Bazel builds the common sources for the JVM only, as for `//viz/ast-model`.

## Tests

`commonTest` checks the semantics on every target. `jvmTest` races real threads against each type, because common code
has no thread API; each contention test fails if an update is lost.

```sh
./gradlew :common:concurrent:jvmTest
./gradlew :common:concurrent:linuxX64Test # Linux host with Kotlin/Native
```
