# Bazel Migration Summary

## Quick Reference Guide

This document summarizes the complete Gradle-to-Bazel migration for the Groovy LSP project and provides a roadmap for
building an automated migration tool.

---

## Documentation Structure

### 📋 MIGRATION.md (Complete Migration Guide)

**Location:** `MIGRATION.md` (repository root)

**Contents:**

- **Phase-by-phase breakdown** of the migration process (5 phases)
- **Detailed transformations** with actual code examples from this project
- **Mapping tables** for Gradle → Bazel concepts
- **Automation opportunities** identified during migration
- **Challenges and lessons learned**

**Use this for:**

- Understanding how the migration was done manually
- Reference when building automation tools
- Training team members on Bazel migration

### 🛠️ tools/migrate/README.md (Tool Design)

**Location:** `tools/migrate/README.md`

**Contents:**

- **Architecture design** for an automated migration tool
- **Component specifications** with interfaces and data structures
- **Implementation plan** with 9-week timeline
- **Technology stack** recommendations
- **CLI interface design**

**Use this for:**

- Building the migration automation tool
- Understanding tool requirements and scope
- Planning development sprints

---

## Key Transformations at a Glance

### Module Structure

| Concept           | Gradle                    | Bazel                  |
| ----------------- | ------------------------- | ---------------------- |
| Module definition | `include("module")`       | `//module:module`      |
| Nested module     | `include("parent:child")` | `//parent/child:child` |
| Project reference | `project(":module")`      | `//module`             |

### Dependencies

| Type     | Gradle                                      | Bazel                                                      |
| -------- | ------------------------------------------- | ---------------------------------------------------------- |
| External | `implementation(libs.arrow.core)`           | `"@maven//:io_arrow_kt_arrow_core"`                        |
| Internal | `implementation(project(":groovy-common"))` | `"//dsl/dsld"`                                             |
| Test     | `testImplementation(libs.kotlin.test)`      | Test target: `"@maven//:org_jetbrains_kotlin_kotlin_test"` |

### Build Targets

| Gradle Plugin   | Bazel Rule        | Target Name Pattern                    |
| --------------- | ----------------- | -------------------------------------- |
| `kotlin("jvm")` | `kt_library()`    | `{module_name}`                        |
| `application`   | `kt_jvm_binary()` | `{app_name}`                           |
| Shadow JAR      | `java_binary()`   | `{app_name}_deploy` (automatic suffix) |

---

## File Inventory

### Bootstrap Files (Created Once)

```
.bazelversion          # Pin Bazel version (9.0.0rc3)
.bazelrc               # Build configuration (243 lines)
.bazelignore           # Exclude Gradle artifacts
MODULE.bazel           # Workspace + dependencies (165 lines)
```

### Per-Module Files (40+ created)

```
{module}/BUILD.bazel   # kt_library + kt_test targets
```

### Custom Build Infrastructure

```
tools/build_defs/kotlin.bzl   # Project-specific macros
tools/BUILD.bazel             # Toolchain registration
```

**Total:** ~50 files created/modified

---

## Automation Coverage

### ✅ Fully Automatable (70-80% of effort)

1. **Module Discovery**
   - Parse `settings.gradle.kts`
   - Map to Bazel labels

2. **Dependency Extraction**
   - Parse `libs.versions.toml`
   - Convert to `maven.install()`
   - Generate Bazel labels

3. **Standard BUILD Files**
   - Kotlin libraries
   - JUnit 5 tests
   - Resource handling

4. **Bootstrap Generation**
   - `.bazelversion`
   - `.bazelignore`
   - Basic `.bazelrc`

### ⚠️ Needs Human Review (20-30% of effort)

1. **Custom Build Logic**
   - Code generation tasks
   - Custom Gradle plugins
   - Resource transformations

2. **Complex Dependencies**
   - BOM resolution
   - Custom repositories
   - Platform-specific deps

3. **Mixed-Language Builds**
   - Kotlin-Groovy interop
   - Compilation order dependencies

4. **Performance Tuning**
   - Worker configuration
   - Cache strategy

---

## Quick Start for Developers

### Understanding the Migration

1. **Read Phase 1-2** of MIGRATION.md (Analysis + Bootstrap)
   - Understand input sources
   - Learn Bazel workspace structure

2. **Study Phase 3** (Dependency Mapping)
   - Master label generation algorithm
   - Understand version catalog transformation

3. **Review Phase 4** (BUILD File Generation)
   - Learn BUILD file patterns
   - Understand macro design

### Building the Tool

1. **Start with Core Parsers** (tools/migrate/README.md § Core Components)
   - `SettingsParser`: Extract modules
   - `VersionCatalogParser`: Extract dependencies
   - `BuildScriptParser`: Extract per-module deps

2. **Add Transformation Logic**
   - `ModuleMapper`: Gradle paths → Bazel labels
   - `DependencyMapper`: libs.* → @maven labels

3. **Implement Generators**
   - `BootstrapGenerator`: Create workspace files
   - `ModuleFileGenerator`: Generate MODULE.bazel
   - `BuildFileGenerator`: Generate BUILD.bazel per module

4. **Add Validation**
   - Syntax checking
   - Build testing (`bazel build`)
   - Report generation

---

## Real-World Examples from This Project

### Example 1: Simple Library

**Gradle:** `groovy-common/build.gradle.kts` (from main branch)

```kotlin
plugins {
    kotlin("jvm")
}

dependencies {
    api(libs.arrow.core)
    implementation(libs.kotlin.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
```

**Bazel:** `groovy-common/BUILD.bazel`

```python
load("//tools/build_defs:kotlin.bzl", "kt_library", "kt_test")

kt_library(
    name = "dsld",
    srcs = glob(["src/main/kotlin/**/*.kt"]),
    visibility = ["//visibility:public"],
    deps = [
        "@maven//:io_arrow_kt_arrow_core",
        "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_core",
        "@maven//:org_slf4j_slf4j_api",
    ],
)

kt_test(
    name = "groovy-common_test",
    srcs = glob(["src/test/kotlin/**/*.kt"]),
    deps = [
        ":groovy-common",
        "@maven//:org_jetbrains_kotlin_kotlin_test",
        "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_test",
    ],
)
```

**Key Transformations:**

- `api()` + `implementation()` → `deps = [...]` (Bazel doesn't distinguish)
- `testImplementation()` → separate `kt_test` target
- `libs.arrow.core` → `@maven//:io_arrow_kt_arrow_core`
- Implicit source paths → explicit globs

### Example 2: Application with Dependencies

**Gradle:** `groovy-lsp/build.gradle.kts` (from main branch, simplified)

```kotlin
plugins {
    kotlin("jvm")
    application
    id("com.gradleup.shadow")
}

application {
    mainClass = "com.github.albertocavalcante.groovylsp.MainKt"
}

dependencies {
    implementation(project(":groovy-common"))
    implementation(project(":groovy-formatter"))
    implementation(libs.lsp4j)
    implementation(libs.groovy.core)
}
```

**Bazel:** `groovy-lsp/BUILD.bazel`

```python
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_binary")
load("@rules_java//java:defs.bzl", "java_binary")
load("//tools/build_defs:kotlin.bzl", "kt_library")

kt_library(
    name = "gls-lib",
    srcs = glob(["src/main/kotlin/**/*.kt"]),
    deps = [
        "//dsl/dsld",
        "//fmt",
        "@maven//:org_eclipse_lsp4j_org_eclipse_lsp4j",
        "@maven//:org_apache_groovy_groovy",
    ],
)

kt_jvm_binary(
    name = "gls",
    main_class = "com.github.albertocavalcante.groovylsp.MainKt",
    runtime_deps = [":gls-lib"],
)

# Uber JAR (equivalent to Shadow JAR)
java_binary(
    name = "gls_deploy",
    main_class = "com.github.albertocavalcante.groovylsp.MainKt",
    runtime_deps = [":gls-lib"],
)
# Build with: bazel build //gls:gls_deploy_deploy.jar
```

**Key Transformations:**

- `application` plugin → `kt_jvm_binary()`
- Shadow JAR → `java_binary()` (auto-creates `_deploy.jar`)
- `project()` references → `//module` labels
- Library code separated into `kt_library` for reuse

### Example 3: TypeScript Compilation

**Gradle equivalent:** npm script in `package.json`

```json
{
  "scripts": {
    "compile": "tsc",
    "package-build": "node esbuild.js --production"
  }
}
```

**Bazel:** `editors/code/client/BUILD.bazel`

```python
load("@aspect_rules_ts//ts:defs.bzl", "ts_project")
load("@aspect_rules_esbuild//esbuild:defs.bzl", "esbuild")

ts_project(
    name = "client_lib",
    srcs = glob(["src/**/*.ts"], exclude = ["src/test/**"]),
    tsconfig = ":tsconfig.json",
    deps = [
        "//:node_modules/@types/vscode",
        "//:node_modules/vscode-languageclient",
    ],
)

esbuild(
    name = "extension_bundle",
    srcs = [":client_lib"],
    entry_point = "src/extension.ts",
    output = "extension.js",
    platform = "node",
    format = "cjs",
    external = ["vscode"],
    minify = True,
)
```

**Key Transformations:**

- `tsc` compilation → `ts_project()`
- `esbuild` bundling → `esbuild()`
- NPM deps: `//:node_modules/{package}`

---

## Common Patterns Library

### Pattern: Basic Kotlin Library

```python
load("//tools/build_defs:kotlin.bzl", "kt_library", "kt_test")

kt_library(
    name = "{module_name}",
    srcs = glob(["src/main/kotlin/**/*.kt"]),
    visibility = ["//visibility:public"],
    deps = [
        # External dependencies
        "@maven//:group_artifact",
        # Internal dependencies
        "//other-module",
    ],
)

kt_test(
    name = "{module_name}_test",
    srcs = glob(["src/test/kotlin/**/*.kt"]),
    deps = [
        ":{module_name}",
        # Test-specific dependencies auto-added by macro
    ],
)
```

### Pattern: Application Binary

```python
kt_jvm_binary(
    name = "{app_name}",
    main_class = "com.example.MainKt",
    runtime_deps = [":{app_name}-lib"],
)

# Uber JAR
java_binary(
    name = "{app_name}_deploy",
    main_class = "com.example.MainKt",
    runtime_deps = [":{app_name}-lib"],
)
```

### Pattern: Protobuf Code Generation

```python
load("@com_google_protobuf//bazel:proto_library.bzl", "proto_library")

proto_library(
    name = "{module}_proto",
    srcs = glob(["src/main/proto/**/*.proto"]),
)

# For Wire specifically
wire_library(
    name = "{module}_wire",
    protos = [":{module}_proto"],
)
```

---

## Decision Records

### Why bzlmod over WORKSPACE?

- **Reason:** bzlmod is the future (stable in Bazel 7+)
- **Benefit:** Cleaner dependency management, better version resolution
- **Trade-off:** Requires Bazel 7+ (acceptable given our version)

### Why Hermetic JDK?

- **Reason:** Reproducible builds across machines/CI
- **Benefit:** No dependency on local Java installation
- **Trade-off:** Slightly larger initial download

### Why Shared Cache?

- **Reason:** Using git worktrees for multiple branches
- **Benefit:** Avoid re-downloading Maven artifacts (saves ~10GB)
- **Trade-off:** Small complexity in `.bazelrc` config

### Why Custom Macros (kotlin.bzl)?

- **Reason:** Reduce boilerplate in 40+ BUILD files
- **Benefit:** Central place to update common patterns
- **Trade-off:** One more layer of indirection

---

## Troubleshooting Guide

### Build Error: "Target not found"

**Symptom:** `ERROR: no such target '//module:module'`

**Solutions:**

1. Check BUILD.bazel exists in module directory
2. Verify target name matches `name = "..."` in BUILD.bazel
3. Run `bazel query //...` to list all targets

### Build Error: "Label not found"

**Symptom:** `ERROR: no such package '@maven//:io_arrow_kt_arrow_core'`

**Solutions:**

1. Check artifact in `MODULE.bazel` → `maven.install(artifacts=[...])`
2. Verify label naming: `group:artifact` → `group_artifact`
3. Run `bazel fetch //...` to download Maven deps

### Build Error: "Circular dependency"

**Symptom:** `ERROR: cycle in dependency graph`

**Solutions:**

1. Review module dependencies in BUILD.bazel files
2. Break circular dependency by extracting common code
3. Use `bazel query --notool_deps "allpaths(//a, //b)"` to find path

### Test Failure: "No tests found"

**Symptom:** Tests pass in Gradle but fail in Bazel

**Solutions:**

1. Check test source glob matches actual files
2. Verify JUnit version (5 vs 4 requires different runners)
3. Check test runtime_deps include JUnit engine

---

## Performance Optimization Tips

### 1. Enable Disk Cache

```bash
# In .bazelrc
common --disk_cache=~/.cache/bazel
```

### 2. Use Workers for Kotlin

```bash
# In .bazelrc
build --strategy=KotlinCompile=worker
build --worker_max_multiplex_instances=KotlinCompile=4
```

### 3. Limit Resource Usage

```bash
# In .bazelrc
build --local_cpu_resources=HOST_CPUS-1
build --local_ram_resources=HOST_RAM*.75
```

### 4. Remote Caching (Advanced)

```bash
# In .bazelrc
build:remote --remote_cache=grpcs://remote.buildbuddy.io
build:remote --remote_upload_local_results
```

---

## Next Steps

### For Understanding the Migration

1. Read `MIGRATION.md` phases 1-5
2. Compare Gradle files (main branch) with Bazel files (feat-bazel-setup branch)
3. Run both builds to see differences:
   ```bash
   # Gradle (from main branch/worktree)
   ./gradlew build

   # Bazel (from feat-bazel-setup branch/worktree)
   bazel build //...
   ```

### For Building the Automation Tool

1. Read `tools/migrate/README.md` architecture section
2. Set up Kotlin project with dependencies
3. Implement Phase 1 (parsers) first
4. Test with groovy-lsp as reference project

### For Applying to Other Projects

1. Use `MIGRATION.md` as a manual playbook
2. Start with simple modules (no custom logic)
3. Generate MODULE.bazel first (bootstrap)
4. Generate BUILD files incrementally
5. Validate frequently (`bazel build` after each module)

---

## Resources

### Documentation

- **Complete Migration Guide:** `MIGRATION.md`
- **Tool Design:** `tools/migrate/README.md`
- **Bazel Official Docs:** https://bazel.build/
- **rules_kotlin:** https://github.com/bazelbuild/rules_kotlin
- **rules_jvm_external:** https://github.com/bazelbuild/rules_jvm_external

### Example Files

- **MODULE.bazel:** `MODULE.bazel` (repository root)
- **BUILD.bazel examples:** `groovy-common/BUILD.bazel`, `groovy-lsp/BUILD.bazel`
- **Custom macros:** `tools/build_defs/kotlin.bzl`

### Contact

For questions about this migration, see project maintainers in main repository.

---

## Official Kotlin rules (2026-11-11)

The build uses the official `rules_kotlin` **2.3.0** prebuilt release from the Bazel Central Registry. It keeps the
Kotlin compiler, serialization plugin and standard library at **2.3.0**. The custom toolchain retains language/API 2.3,
JVM target 17, ABI jars, multiplex workers, strict direct dependencies and gated unused-dependency diagnostics.

The temporary `v2.3.0-fork.1` archive supplied Kotlin 2.3 tooling before an official release was available. Upstream
[published v2.3.0 on 2026-02-17](https://github.com/bazel-contrib/rules_kotlin/releases/tag/v2.3.0). The
[BCR source descriptor](https://github.com/bazelbuild/bazel-central-registry/blob/main/modules/rules_kotlin/2.3.0/source.json)
selects its prebuilt archive with SHA256 `6d008622fc15bc56f9eba3eebef28af63abf0d07b97cb3971401e7a6d7c15227`. This
retirement deliberately preserves compiler-version parity; a later Kotlin upgrade is a separate change.

The official module also requires `rules_jvm_external` 6.10. Its existing Coursier GMM patches remain in use; the
repeated-option patch is refreshed for that release so ordered `--variant` flags keep their separate values. The
migration also corrects an existing unconditional Jackson dependency skip. The isolated
`//tools/ci/jackson:jackson_runtime_test` uses only `jackson-module-kotlin` as its runtime root, registers the Kotlin
module and round-trips a value to verify that required transitive runtime jars remain reachable.
`//tools/ci/jackson:jackson_yaml_runtime_test` separately checks YAML round trips from only `jackson-dataformat-yaml`,
including its SnakeYAML dependency.

With OpenRewrite 8.79.0, the selected Jackson Gradle Module Metadata (`runtimeElements`, with platform BOM constraints
excluded) declares:

| Artifact                       | Version | Runtime dependencies                                  |
| ------------------------------ | ------- | ----------------------------------------------------- |
| jackson-annotations            | 2.21    | None                                                  |
| jackson-core                   | 2.21.2  | None                                                  |
| jackson-databind               | 2.21.2  | jackson-core, jackson-annotations                     |
| jackson-module-kotlin          | 2.21.2  | jackson-databind, jackson-annotations, kotlin-reflect |
| jackson-dataformat-yaml        | 2.21.2  | jackson-databind, jackson-core, SnakeYAML             |
| jackson-dataformat-smile       | 2.21.2  | jackson-databind, jackson-core                        |
| jackson-datatype-jsr310        | 2.21.2  | jackson-databind, jackson-core, jackson-annotations   |
| jackson-module-parameter-names | 2.21.2  | jackson-databind, jackson-core                        |

For example, the published metadata for
[core](https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-core/2.21.2/jackson-core-2.21.2.module),
[databind](https://repo1.maven.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.21.2/jackson-databind-2.21.2.module),
[Kotlin](https://repo1.maven.org/maven2/com/fasterxml/jackson/module/jackson-module-kotlin/2.21.2/jackson-module-kotlin-2.21.2.module)
and
[YAML](https://repo1.maven.org/maven2/com/fasterxml/jackson/dataformat/jackson-dataformat-yaml/2.21.2/jackson-dataformat-yaml-2.21.2.module)
distinguishes real dependencies from platform constraints. Coursier's expanded BOM edges incorrectly make core depend on
databind, creating a cycle. The patch keeps core/annotations as leaves, retains the real core dependencies of other
modules and preserves non-Jackson dependencies such as Kotlin reflection and SnakeYAML. The existing cross-extension
filter is still needed for phantom BOM edges; none of these eight selected modules declares a real cross-extension
Jackson dependency. This is evidence for this validated graph, not proof for other artifacts or future versions.
Validate the metadata and isolated runtimes again when upgrading. Resolver replacement remains tracked in
[#1029](https://github.com/albertocavalcante/gvy/issues/1029).

The selected Kotlin module declares kotlin-reflect 2.1.21, resolved to 2.2.21 in the current Bazel graph; YAML declares
SnakeYAML 2.5, resolved to 2.6. The isolated runtime roots above pass with these selected versions and Jackson 2.21.2.
This validates the current closure without adding direct dependencies that could conceal missing edges.

The fork's tagged source is an ancestor of v2.3.0, but the old publishing helper built moving upstream `master` while
using a separate release tag. That tag alone cannot prove the old tarball's build source. The helper has been removed;
historical fork releases and assets remain available to old commits.

Using only `kotlinc_version` with rules_kotlin 2.2.2 is not the same migration. Upstream
[PR #1410](https://github.com/bazel-contrib/rules_kotlin/pull/1410) also changed internal compiler plugins and
build-tool compatibility. The official prebuilt release supplies those changes together.

### Validation and remaining investigations

Build and test the required targets with `--lockfile_mode=error`. For this migration, the fetched
`external/rules_kotlin+` repository identifies module/compiler 2.3.0, and the downloaded prebuilt archive's SHA256
matches the BCR descriptor above. Inspect the fetched repository under the task's Bazel output base and compare the
cached archive bytes with that published digest. The CI diagnostic gate reviews all advisories rather than hiding them
in a successful build.

`bazel mod show_repo @rules_kotlin` currently prints the expected archive but exits nonzero: eager module inspection
also evaluates the pnpm extension, whose 10.27.0 pin is absent from aspect_rules_js 2.8.3's version table. Treat that
partial output as a failed inspection. Build tooling maintainers track the declaration fix in
[#1644](https://github.com/albertocavalcante/gvy/issues/1644); completion requires successful module inspection plus the
affected JavaScript/Bazel checks. The focused locked build/test and archive checksum provide the current fallback.

- [#1601](https://github.com/albertocavalcante/gvy/issues/1601) tracks metadata-only unused dependencies in KMP common
  tests. Retain its exact expiring exception until the compiler accounting case is demonstrably fixed.
- [#1416](https://github.com/albertocavalcante/gvy/issues/1416) tracks intermittent worker/ABI compilation failures. A
  passing migration build does not meet its separate requirement for ten consecutive clean CI builds.
- [#1563](https://github.com/albertocavalcante/gvy/issues/1563) tracks missing Bazel server provenance. The deploy JAR
  starts, but lacks `version.properties` and reports an unknown version/commit. Build tooling maintainers must add
  workspace-status stamping and verify the built JAR reports its SHA; unstamped builds must identify that state.

### Rollback

If the official Kotlin tooling regresses, roll back only the `rules_kotlin` module selection to the previous declaration
and exact archive override below. Preserve `rules_jvm_external` 6.10, the corrected repeated-option/Jackson patches and
the isolated Jackson runtime regressions; keep the obsolete publisher removed. That Maven setup and its patches are
validated by this migration independently of the compiler selection.

```starlark
bazel_dep(name = "rules_kotlin", version = "2.2.2")

archive_override(
    module_name = "rules_kotlin",
    integrity = "sha256-9vOI0nezePk0rrm7HaQ9h+MVvgo3H6h71Cl37UMZp1A=",
    urls = ["https://github.com/albertocavalcante/fork-rules_kotlin/releases/download/v2.3.0-fork.1/rules_kotlin_release.tgz"],
)
```

Regenerate `MODULE.bazel.lock`, then validate the restored compiler path, original failing scenario, server package,
isolated Jackson runtimes, applicable tests and diagnostic gate before landing the rollback. Keep language/API 2.3 and
JVM target 17. A separate rollback to rules_jvm_external 6.9 would also require rebasing the corrected patches onto that
source and validating the resulting closed runtime graph; the migration's 6.10 patch context cannot be assumed
compatible with 6.9.

Do not republish the historical tag or replace its asset. Existing commits depend on its pinned bytes.

## Historical migration limitations (2026-01-11)

The following observations describe the initial migration snapshot and are retained as history, not current setup
instructions. Check the current `BUILD.bazel` files and CI evidence for module readiness.

### Disabled Modules

| Module           | Reason                                    | Fix Required            |
| ---------------- | ----------------------------------------- | ----------------------- |
| `groovy-lsp`     | Kotlin 2.2 features + missing deps        | See below               |
| `viz/ast-model`  | Compose Multiplatform not configured      | Add rules_compose       |
| `viz/desktop`    | Compose Multiplatform not configured      | Add rules_compose       |
| `indexer/scip`   | Protobuf (Wire) generation not configured | Add wire_library rules  |
| `editors/code/*` | Missing `@types/vscode`                   | Run `pnpm install`      |
| `tests/e2e_test` | Depends on groovy-lsp                     | Enable groovy-lsp first |

### Source Code Incompatibilities

The initial report listed the following source/API migration concerns:

1. **Multi-dollar string interpolation** (Kotlin 2.2)
   - Location: `groovy-lsp/.../CompletionBuilders.kt:210`
   - Code: `$$"${$${index + 1}:$$param}"`
   - Fix: Rewrite as `"\${${index + 1}:$param}"`

2. **Nested type aliases** (Kotlin 2.2 experimental)
   - Location: `groovy-lsp/.../GroovySemanticTokenProvider.kt:43`
   - Code: `typealias SemanticToken = JenkinsSemanticTokenProvider.SemanticToken`
   - Fix: Import directly instead of type alias

3. **Mordant 3.x API changes** (library version issue)
   - Location: `groovy-lsp/.../ValidateCommand.kt`
   - Code: `terminal.theme.warning("text")` → no longer exists
   - Fix: Update to Mordant 3.x API (`TextColors`, `TextStyles`)

### Missing Module Dependencies

`groovy-lsp` requires modules that don't have BUILD files yet:

- `parser/rewrite` → `RewriteParserProvider` class
- `groovy-diagnostics/sarif` → SARIF support

The initial workarounds considered rewriting language features, waiting for an upstream release or building upstream
HEAD. The official prebuilt release above supersedes that Kotlin release-availability decision.

---

## Version History

- **v1.2 (2026-11-11):** Retired the temporary fork in favor of official rules_kotlin 2.3.0, documented rollback and
  retained the initial limitations as historical evidence.

- **v1.1 (2026-01-11):** Added Current Limitations section
  - Documented Kotlin 2.2.21 constraint from rules_kotlin architecture
  - Listed disabled modules with reasons and fixes
  - Cataloged source code incompatibilities
- **v1.0 (2026-01-10):** Initial migration documentation
  - Migrated 40+ modules from Gradle 9.1 to Bazel 9.0.0rc3
  - Created comprehensive guide and tool design
  - Validated builds and tests

---

## License

Documentation released under the same license as the main project (Apache 2.0).
