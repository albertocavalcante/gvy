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

## Current Limitations (as of 2026-01-11)

### Kotlin Version Constraint: 2.2.21

**Problem:** `rules_kotlin 2.2.2` has a **hard dependency** on Kotlin 2.2.21.

**Root Cause Architecture:**

```
rules_kotlin 2.2.2
└── kotlinbuilder (pre-compiled JAR)
    └── Built against Kotlin 2.2.21
    └── Compiler plugins load INTO kotlinbuilder's classloader
        └── Plugin versions MUST match kotlinbuilder's Kotlin version
```

**Why `kotlinc_version` extension doesn't help:**

- The `kotlinc_version` extension only overrides the **compiler download**
- It does NOT rebuild `kotlinbuilder` (the compilation wrapper)
- Compiler plugins (like kotlinx-serialization) are loaded by kotlinbuilder
- Plugin class compatibility is checked against kotlinbuilder's bundled Kotlin

**To use Kotlin 2.3.0:**

- Requires `rules_kotlin` with commit `f08e668` ("Update Kotlin to 2.3.0")
- As of 2026-01-11, this commit is NOT in any released version
- Monitor: https://github.com/bazelbuild/rules_kotlin/releases

**Why git_override / archive_override doesn't work:**

```
# This FAILS - kotlinbuilder built from source is missing Dagger shading:
git_override(
    module_name = "rules_kotlin",
    remote = "https://github.com/bazelbuild/rules_kotlin.git",
    commit = "f08e66864553f61836d68f780620e42b5a09e003",
)
# Error: java.lang.NoClassDefFoundError: dagger/internal/Preconditions
```

The released versions work because they include **pre-built kotlinbuilder JARs** with all dependencies properly shaded.
When overriding from source, Bazel tries to build kotlinbuilder from source, but the rules_kotlin build process doesn't
properly shade Dagger dependencies.

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

The following Kotlin 2.2+ features are used in source but NOT available with Kotlin 2.2.21:

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

### Workaround Strategy

**Option A: Fix source code** (recommended for production)

- Rewrite Kotlin 2.2 features to 2.1-compatible syntax
- Update Mordant API calls
- Create stubs or conditionally compile missing module references

**Option B: Wait for rules_kotlin update**

- Monitor rules_kotlin releases for Kotlin 2.3.0 support
- Once released, update `bazel_dep(name = "rules_kotlin", version = "X.Y.Z")`

**Option C: Build rules_kotlin from HEAD** (advanced)

- Use `git_override` in MODULE.bazel to point to rules_kotlin HEAD
- Risk: Unstable, may break with updates

---

## Version History

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
