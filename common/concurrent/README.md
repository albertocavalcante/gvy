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
  using the package without an opt-in fails to compile, and detekt's `ForbiddenImport` keeps other modules from adding
  one.
- **No behavior risk on the JVM.** There the stdlib atomics compile to `java.util.concurrent.atomic`. An upstream API
  change breaks compilation in this module and nowhere else.
- **Plain final classes**, not inline or value classes, so their representation stays private. A different
  implementation (a changed stdlib API, `expect`/`actual` over JVM atomics, or atomicfu) changes only this module.
- **No raw compare-and-set on `SharedValue`.** It compares by reference, so a boxed number or an equal but distinct
  object would fail silently. `update {}` compares against the value it read.
- **`OnceFlag.isSet` means claimed, not finished.** It turns true when `trySet()` wins, before the guarded work runs.
  Use `lazy` for a value computed once, and a latch or a `Deferred` to wait for work to finish.

## Why Kotlin Multiplatform

The module builds for the JVM and for the launcher's native targets (`macosArm64`, `linuxX64`, `mingwX64`). Every
consumer today is a JVM module, and the launcher does not use it. The native targets are a deliberate choice:

- They make the compiler reject JVM-only APIs in `commonMain`. With only a JVM target, common code may call them.
- They adopt `kotlin.concurrent.atomics` early, so gvy can report problems upstream before the API is stable
  ([KT-82228](https://youtrack.jetbrains.com/issue/KT-82228)).
- A native consumer, such as the launcher, can use the module later without a rewrite.

The choice has costs:

- The stdlib atomics are experimental, so this module opts in and must follow API changes until they are stable. A
  JVM-only module could use the stable `java.util.concurrent.atomic` instead.
- The Kotlin Gradle Plugin emits deprecation warnings for multiplatform and Kotlin/Native setup. This module brings them
  into the unit-test job, and each needs an exception in `tools/ci/gradle-diagnostics.json`.
- Bazel's jdeps reports the `kotlin-test-junit5` dependency of the `commonTest` sources as unused, because they reach it
  only through `kotlin.test` typealiases; `tools/ci/bazel-diagnostics.json` holds that exception.
- detekt scans only `src/main` and `src/test` by default, so `build.gradle.kts` points it at all of `src`.
- The native tests add to the launcher job's run time; it is the only CI job that provisions Kotlin/Native.

Bazel builds `commonMain` for the JVM only, as for `//viz/ast-model`, and `//common/concurrent:concurrent_test` runs
both `commonTest` and `jvmTest`.

Going JVM-only over the JDK atomics would change only this module: callers see the `OnceFlag`, `SharedValue` and
`Counter` API, never the atomics behind it.

## Tests

`commonTest` checks the semantics on every target. `jvmTest` races real threads against each type, because common code
has no thread API. Each contention test checks every result callers rely on: one winner per `OnceFlag`, and a distinct
count, updated value or replaced value from every call, so an implementation that hands two callers the same result
fails even when its total is right.

```sh
./gradlew :common:concurrent:jvmTest
./gradlew :common:concurrent:linuxX64Test   # Linux x64 host with Kotlin/Native
./gradlew :common:concurrent:macosArm64Test # macOS on Apple silicon with Kotlin/Native
./gradlew :common:concurrent:mingwX64Test   # Windows x64 host with Kotlin/Native
bazel test //common/concurrent:concurrent_test
```
