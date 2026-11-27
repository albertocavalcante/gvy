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
- **`OnceFlag.isSet` only says the flag was set.** When `trySet()` guards work, the flag is set before the work runs, so
  `isSet` means the work was claimed, not finished. Use `lazy` for a value computed once, and a latch or a `Deferred` to
  wait for work to finish.

## Why Kotlin Multiplatform

The module builds for the JVM and for the launcher's native targets (`macosArm64`, `linuxX64`, `mingwX64`). Every
consumer today is a JVM module, and the launcher does not use it. The native targets are a deliberate choice:

- They make the compiler reject JVM-only APIs in `commonMain`. With only a JVM target, common code may call them.
- A native consumer, such as the launcher, can use the module later without a rewrite.

The native targets only compile the module and run the single-threaded `commonTest`; the thread races run on the JVM.

The atomics are a separate choice. Common code cannot use `java.util.concurrent.atomic`, so the native targets need a
multiplatform atomics API; gvy uses the stdlib's `kotlin.concurrent.atomics`, which a JVM-only module could adopt too.
gvy chose it to adopt the API early and report problems upstream before it is stable
([KT-82228](https://youtrack.jetbrains.com/issue/KT-82228)).

Multiplatform has costs:

- The native targets rule out the stable JDK atomics and need a multiplatform atomics API. The stdlib's, which gvy uses,
  is experimental, so the module must follow its changes until it is stable.
- Two `Configuration.setVisible` deprecation exceptions in `tools/ci/gradle-diagnostics.json` name this project. The
  Kotlin Gradle Plugin's multiplatform compilation and target setup raises them wherever Gradle configures the project,
  not only in one job. The JVM-only `:build-tool` raises the same compilation-configuration warning, so only the
  target-configuration one comes from multiplatform alone.
- With native targets in the unit-test task graph, Gradle's configuration cache decodes the plugin's
  `KotlinNativeBundleArtifactsTypes` attribute, a plain enum that Gradle 10 will reject, in the unit-test job too.
  `tools/ci/fixtures/gradle-native-codec-consumers-provenance.json` records four of the six configuration-cache codec
  exceptions for that enum in that job once this module is in its task graph.
- All of these Gradle exceptions expire on 2026-12-11 under #1603.
- Bazel's jdeps reports the `kotlin-test-junit5` dependency of the `commonTest` sources as unused, because they reach it
  only through `kotlin.test` typealiases; `tools/ci/bazel-diagnostics.json` holds that exception.
- detekt scans only `src/main` and `src/test` by default, so `build.gradle.kts` points it at all of `src`.
- The native tests add to the launcher job's run time; it is the only CI job that provisions Kotlin/Native.

Bazel builds `commonMain` for the JVM only, as for `//viz/ast-model`, and `//common/concurrent:concurrent_test` runs
both `commonTest` and `jvmTest`.

Callers would not change if the module went JVM-only over the JDK atomics: they see the `OnceFlag`, `SharedValue` and
`Counter` API, never the atomics behind it. Outside this module, the switch would need edits to:

- the native test step of `.github/workflows/ci-launcher.yml`, which runs `":common:concurrent:$NATIVE_TEST_TASK"`;
- the configuration-cache codec exceptions in `tools/ci/gradle-diagnostics.json` that only the unit-test job raises, for
  `LocalFileDependencyBackedArtifactSetCodec.kt:137`, `Combinators.kt:235` and `BeanPropertyExtensions.kt:61`, which
  would likely go stale. The launcher jobs also raise `DefaultTransformCodec.kt:66`, so that one stays;
- `tools/ci/check-gradle-diagnostics.test.mjs` and its `gradle-native-codec-consumers` fixtures, which assert that the
  real exceptions cover the four unit-job codec events;
- this project's target-configuration `setVisible` exception. The compilation-configuration one may survive, because the
  JVM-only `:build-tool` raises the same warning.

## Tests

`commonTest` checks the semantics on every target. `jvmTest` races real threads against each type, because common code
has no thread API. Each contention test checks every result callers rely on: one winner per `OnceFlag`, and a distinct
count, updated value or replaced value from every call, so an implementation that hands two callers the same result is
very likely to fail even when its total is right.

```sh
./gradlew :common:concurrent:jvmTest
./gradlew :common:concurrent:linuxX64Test   # Linux x64 host with Kotlin/Native
./gradlew :common:concurrent:macosArm64Test # macOS on Apple silicon with Kotlin/Native
./gradlew :common:concurrent:mingwX64Test   # Windows x64 host with Kotlin/Native
bazel test //common/concurrent:concurrent_test
```
