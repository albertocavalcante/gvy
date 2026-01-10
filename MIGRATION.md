# Gradle to Bazel Migration Guide

## Overview

**Source System:**

- Build Tool: Gradle 9.1 with Kotlin DSL
- Language: Kotlin 2.3.0 (JVM target 17)
- Project Structure: Kotlin/TypeScript/Groovy monorepo with 40+ modules
- Dependency Management: Version catalog (libs.versions.toml)

**Target System:**

- Build Tool: Bazel 9.0.0rc3 with bzlmod
- Module System: bzlmod (MODULE.bazel)
- Dependency Management: rules_jvm_external for Maven, aspect_rules_js for NPM

**Project Type:** Language Server implementation with:

- Kotlin JVM libraries and applications
- TypeScript VS Code extension
- Protobuf code generation (Wire)
- Mixed Kotlin-Groovy compilation
- JUnit 5 tests

---

## Migration Journey: 5 Phases

### Phase 1: Repository Analysis

**Objective:** Understand the Gradle project structure completely before touching Bazel.

#### Step 1.1: Analyze Module Structure

**Source:** `/Users/adsc/dev/ws/gvy/main/settings.gradle.kts`

Key patterns discovered:

```kotlin
// Standard modules
include("groovy-common")
include("groovy-lsp")

// Nested modules with explicit path mapping
include("semantics-native")
project(":semantics-native").projectDir = file("semantics/native")

// Multi-level nested modules
include("parser:api")
include("parser:core")
```

**Automation Opportunity:**

- Parse `settings.gradle.kts` with Kotlin DSL parser or regex
- Extract all `include()` statements
- Handle `project().projectDir` remapping
- Generate directory structure map

**Manual Challenge:** Non-standard project structures require custom mapping logic.

#### Step 1.2: Extract Dependency Catalog

**Source:** `/Users/adsc/dev/ws/gvy/main/gradle/libs.versions.toml`

Structure:

```toml
[versions]
kotlin = "2.3.0"
groovy = "4.0.29"

[libraries]
kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }
groovy-core = { module = "org.apache.groovy:groovy", version.ref = "groovy" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

**Transformation needed:**

1. `[libraries]` → `maven.install(artifacts = [...])`
2. Version references must be resolved to concrete versions
3. BOM (Bill of Materials) declarations need special handling
4. Plugin versions are NOT needed in Bazel (handled by rules)

**Automation Opportunity:**

- Parse TOML with standard library
- Resolve version.ref to actual versions
- Generate `maven.install()` artifact list
- Handle special cases: BOMs, classifiers, exclusions

#### Step 1.3: Analyze Build Scripts

**Source:** `/Users/adsc/dev/ws/gvy/main/groovy-common/build.gradle.kts`

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

**Key observations:**

- `api()` → compile-time and runtime dependency (transitive)
- `implementation()` → compile-time dependency (not transitive)
- `testImplementation()` → test-only dependency
- Version catalog references (`libs.`) must be resolved

**Automation Opportunity:**

- Parse build.gradle.kts per module
- Extract dependency declarations
- Map Gradle configurations to Bazel attributes
- Identify internal project dependencies

---

### Phase 2: Bazel Bootstrap

**Objective:** Set up Bazel workspace infrastructure.

#### Step 2.1: Create Core Files

**Files Created:**

1. **`.bazelversion`**
   ```
   9.0.0rc3
   ```

   **Purpose:** Pin exact Bazel version for reproducibility.

   **Automation:** Always create this file with latest stable Bazel version.

2. **`.bazelrc`**
   - 243 lines of configuration
   - Sections: Core Settings, Build Performance, Java/Kotlin/TypeScript Config, Test Config, CI Config

   **Key decisions:**
   - Use hermetic JDK 21 toolchain (for Java 17 bytecode)
   - Enable disk cache and repository cache (shared across worktrees)
   - Configure Kotlin workers for performance
   - Disable CGO for Go/Gazelle (macOS Xcode issues)

   **Automation:** Template-based generation with project-specific customizations.

3. **`.bazelignore`**
   ```
   node_modules
   build
   .gradle
   .idea
   bazel-*
   ```

   **Purpose:** Exclude Gradle/IDE artifacts from Bazel's file system scanning.

   **Automation:** Standard template + project-specific patterns.

4. **`MODULE.bazel`**
   - Declares workspace as a Bazel module
   - Registers rule dependencies (rules_kotlin, rules_java, etc.)
   - Configures Maven and NPM dependencies

   **Structure:**
   ```python
   module(name = "groovy_lsp", version = "0.4.8")

   # Core rules
   bazel_dep(name = "rules_kotlin", version = "2.2.1")
   bazel_dep(name = "rules_java", version = "8.15.2")

   # Dependency management
   maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
   maven.install(artifacts = [...])
   ```

#### Step 2.2: Decision Log

**Why Bazel 9?**

- bzlmod is stable (no WORKSPACE file needed)
- Better performance with persistent workers
- Native support for multiple languages

**Why hermetic JDK?**

- Reproducible builds across machines
- No dependency on local Java installation
- CI/CD consistency

**Why shared cache?**

- Git worktree workflow with multiple branches
- Avoid re-downloading Maven artifacts per worktree
- Significant disk space savings with hardlinks

---

### Phase 3: Dependency Mapping

**Objective:** Translate Gradle dependencies to Bazel format.

#### Step 3.1: Maven Dependencies

**Source:** `libs.versions.toml` + `build.gradle.kts` files

**Transformation:**

Gradle version catalog:

```toml
[libraries]
arrow-core = { module = "io.arrow-kt:arrow-core", version.ref = "arrow" }
```

Gradle dependency:

```kotlin
implementation(libs.arrow.core)
```

Bazel MODULE.bazel:

```python
maven.install(
    artifacts = [
        "io.arrow-kt:arrow-core:2.2.1",
    ]
)
```

Bazel BUILD.bazel:

```python
deps = [
    "@maven//:io_arrow_kt_arrow_core",
]
```

**Label Naming Convention:**

- Gradle: `group:artifact:version`
- Bazel: `@maven//:group_artifact` (colons and hyphens → underscores)

**Automation Algorithm:**

```
For each library in libs.versions.toml:
  1. Resolve version.ref to concrete version
  2. Format as "group:artifact:version"
  3. Generate @maven label: replace [:.-] with underscore
  4. Add to maven.install(artifacts=[])
```

#### Step 3.2: Internal Project Dependencies

**Gradle:**

```kotlin
dependencies {
    implementation(project(":groovy-common"))
    implementation(project(":parser:core"))
}
```

**Bazel:**

```python
deps = [
    "//dsl/dsld",
    "//parser/core",
]
```

**Transformation Rules:**

- Remove `project()` wrapper
- Replace `:` with `/` for nested modules
- Prefix with `//` for workspace-relative label

**Edge Case:** Custom project directory mapping

```kotlin
// Gradle settings.gradle.kts
include("semantics-native")
project(":semantics-native").projectDir = file("semantics/native")

// Bazel BUILD location
//semantics/native:semantics-native
```

#### Step 3.3: Special Cases

**1. BOM (Bill of Materials):**

Gradle:

```kotlin
dependencies {
    implementation(platform(libs.jackson.bom))
    implementation(libs.jackson.databind) // Version from BOM
}
```

Bazel:

```python
maven.install(
    artifacts = [
        "com.fasterxml.jackson:jackson-bom:2.20.1",
        "com.fasterxml.jackson.core:jackson-databind:2.20.1",  # Must specify version
    ]
)
```

**Challenge:** Bazel doesn't import versions from BOMs automatically. Must specify explicitly.

**2. Gradle Plugin Classpath Dependencies:**

Gradle:

```kotlin
buildscript {
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.8.2")
    }
}
```

Bazel: Not needed! Gradle plugin dependencies are build-time only, not runtime.

**3. Gradle Tooling API:**

Issue: Not available in Maven Central.

Gradle:

```kotlin
repositories {
    maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
}
dependencies {
    implementation("org.gradle:gradle-tooling-api:9.2.1")
}
```

Bazel MODULE.bazel:

```python
maven.install(
    artifacts = ["org.gradle:gradle-tooling-api:9.2.1"],
    repositories = [
        "https://repo1.maven.org/maven2",
        "https://repo.gradle.org/gradle/libs-releases/",  # Custom repository
    ]
)
```

**Automation Challenge:** Detecting custom repositories from Gradle build scripts.

---

### Phase 4: BUILD File Generation

**Objective:** Create BUILD.bazel for each module.

#### Step 4.1: Pattern: Kotlin Library

**Gradle build.gradle.kts:**

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

**Bazel BUILD.bazel:**

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

1. `api()` and `implementation()` both map to `deps = [...]` (Bazel doesn't distinguish)
2. `testImplementation()` → test target's `deps = [...]`
3. Source paths: Gradle convention `src/main/kotlin` → Bazel glob
4. Target naming: module name for library, `{name}_test` for tests
5. Visibility: Default private, explicit `["//visibility:public"]` for libraries used by others

**Automation Steps:**

1. Parse `dependencies {}` block
2. Map `api/implementation` → main target deps
3. Map `testImplementation` → test target deps
4. Resolve version catalog references
5. Convert to Bazel labels
6. Generate BUILD.bazel from template

#### Step 4.2: Pattern: Kotlin JVM Binary (Application)

**Gradle build.gradle.kts:**

```kotlin
plugins {
    kotlin("jvm")
    application
}

application {
    mainClass = "com.github.albertocavalcante.groovylsp.MainKt"
}

dependencies {
    implementation(project(":groovy-common"))
    implementation(libs.lsp4j)
}
```

**Bazel BUILD.bazel:**

```python
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_binary")
load("//tools/build_defs:kotlin.bzl", "kt_library")

kt_library(
    name = "gls-lib",
    srcs = glob(["src/main/kotlin/**/*.kt"]),
    deps = [
        "//dsl/dsld",
        "@maven//:org_eclipse_lsp4j_org_eclipse_lsp4j",
    ],
)

kt_jvm_binary(
    name = "gls",
    main_class = "com.github.albertocavalcante.groovylsp.MainKt",
    runtime_deps = [":gls-lib"],
)
```

**Key Differences:**

- Gradle `application` plugin → Bazel `kt_jvm_binary`
- Binary depends on library via `runtime_deps` (cleaner separation)
- Main class specified in binary target, not library

**Uber JAR (Shadow JAR) equivalent:**

```python
load("@rules_java//java:defs.bzl", "java_binary")

java_binary(
    name = "gls_deploy",
    main_class = "com.github.albertocavalcante.groovylsp.MainKt",
    runtime_deps = [":gls-lib"],
)
# Build with: bazel build //gls:gls_deploy_deploy.jar
# Automatically creates uber JAR with _deploy.jar suffix
```

**Automation:** Detect `application` plugin → generate both `kt_jvm_binary` and `java_binary` targets.

#### Step 4.3: Pattern: Resource Handling

**Gradle:** Automatic inclusion of `src/main/resources`

**Bazel:**

```python
kt_library(
    name = "gls-lib",
    srcs = glob(["src/main/kotlin/**/*.kt"]),
    resources = glob(["src/main/resources/**/*"]),  # Explicit
    deps = [...],
)
```

**Automation:** Always add `resources = glob(["src/main/resources/**/*"])` if directory exists.

#### Step 4.4: Pattern: Visibility Mapping

**Gradle:** All modules can depend on each other by default.

**Bazel:** Explicit visibility required.

Strategy:

- Internal libraries: `visibility = ["//visibility:public"]` (most libraries in a monorepo)
- Test utilities: `visibility = ["//visibility:public"]` (or restrict to specific packages)
- Binary targets: Usually don't need visibility (not depended upon)

**Automation:** Default to `["//visibility:public"]` for libraries, omit for binaries.

#### Step 4.5: Pattern: Custom Build Macros

**Created:** `/Users/adsc/dev/ws/gvy/feat-bazel-setup/tools/build_defs/kotlin.bzl`

Purpose: Project-specific defaults to reduce boilerplate.

```python
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library", "kt_jvm_test")

def kt_library(name, srcs, deps = None, runtime_deps = None, visibility = None, **kwargs):
    """Kotlin library with project defaults."""
    kt_jvm_library(
        name = name,
        srcs = srcs,
        deps = deps if deps != None else [],
        runtime_deps = runtime_deps if runtime_deps != None else [],
        visibility = visibility,
        **kwargs
    )

def kt_test(name, srcs, deps = None, test_class = None, **kwargs):
    """Kotlin test with JUnit 5."""
    kt_jvm_test(
        name = name,
        srcs = srcs,
        deps = (deps if deps != None else []) + [
            "@maven//:org_junit_jupiter_junit_jupiter",
            "@maven//:org_junit_jupiter_junit_jupiter_api",
            "@maven//:org_assertj_assertj_core",
        ],
        runtime_deps = [
            "@maven//:org_junit_jupiter_junit_jupiter_engine",
            "@maven//:org_junit_platform_junit_platform_launcher",
        ],
        test_class = test_class,
        **kwargs
    )
```

**Benefits:**

- Centralized JUnit 5 configuration
- Consistent test dependencies across all modules
- Easier to update (change macro, all tests updated)

**Automation:** Generate project-specific macros based on common patterns in Gradle builds.

---

### Phase 5: TypeScript/JavaScript Migration

**Objective:** Migrate VS Code extension (TypeScript + NPM).

#### Step 5.1: NPM Dependencies

**Source:** `/Users/adsc/dev/ws/gvy/main/editors/code/package.json`

Key dependencies:

```json
{
  "dependencies": {
    "vscode-languageclient": "^9.0.0",
    "jdk-utils": "^0.5.0"
  },
  "devDependencies": {
    "typescript": "^5.9.2",
    "esbuild": "^0.27.0",
    "@types/vscode": "^1.103.0"
  }
}
```

**Bazel MODULE.bazel:**

```python
npm = use_extension("@aspect_rules_js//npm:extensions.bzl", "npm")
npm.npm_translate_lock(
    name = "npm",
    pnpm_lock = "//editors/code:pnpm-lock.yaml",
    verify_node_modules_ignored = "//:.bazelignore",
)
use_repo(npm, "npm")
```

**Key points:**

- Uses `pnpm-lock.yaml` for reproducible builds
- Must have `node_modules` in `.bazelignore`
- NPM packages referenced as `//:node_modules/@types/vscode`

**Automation:**

1. Detect `package.json` presence
2. Generate `pnpm-lock.yaml` if missing: `pnpm install --lockfile-only`
3. Add `npm_translate_lock()` to MODULE.bazel
4. Add `node_modules` to `.bazelignore`

#### Step 5.2: TypeScript Compilation

**Gradle equivalent:** Not used (TypeScript compiled via npm scripts)

**Bazel BUILD.bazel:**

```python
load("@aspect_rules_ts//ts:defs.bzl", "ts_project")

ts_project(
    name = "client_lib",
    srcs = glob(["src/**/*.ts"], exclude = ["src/test/**"]),
    tsconfig = ":tsconfig.json",
    composite = True,
    declaration = True,
    source_map = True,
    out_dir = "out",
    deps = [
        "//:node_modules/@types/vscode",
        "//:node_modules/vscode-languageclient",
        "//:node_modules/@types/node",
    ],
)
```

**Key transformations:**

- Glob TypeScript sources
- Reference existing `tsconfig.json`
- NPM dependencies use `//:node_modules/` prefix
- Output directory matches TypeScript convention

**Automation:**

1. Detect `tsconfig.json`
2. Parse `include`/`exclude` patterns
3. Extract dependencies from `package.json`
4. Generate `ts_project()` target

#### Step 5.3: esbuild Bundling

**Gradle equivalent:** npm script `"package-build": "node esbuild.js --production"`

**Bazel BUILD.bazel:**

```python
load("@aspect_rules_esbuild//esbuild:defs.bzl", "esbuild")

esbuild(
    name = "extension_bundle",
    srcs = [":client_lib"],
    entry_point = "src/extension.ts",
    output = "extension.js",
    platform = "node",
    target = "es2020",
    format = "cjs",
    external = ["vscode"],
    minify = select({
        "//:production": True,
        "//conditions:default": False,
    }),
    sourcemap = select({
        "//:production": False,
        "//conditions:default": True,
    }),
)
```

**Key features:**

- Uses compiled TypeScript as input
- `external = ["vscode"]` → don't bundle VS Code API
- `select()` for production vs development builds
- Format: CommonJS for Node.js compatibility

**Automation:**

1. Detect esbuild usage in `package.json` scripts
2. Parse esbuild config file if exists
3. Generate `esbuild()` target with detected settings
4. Handle multiple entry points (extension + webview)

#### Step 5.4: NPM Package Linking

**Bazel BUILD.bazel (root of TypeScript package):**

```python
load("@aspect_rules_js//npm:defs.bzl", "npm_link_all_packages")

npm_link_all_packages(name = "node_modules")
```

**Purpose:** Creates Bazel targets for all NPM packages in `pnpm-lock.yaml`.

**Automation:** Always add to root BUILD.bazel of any directory with `package.json`.

---

## Comprehensive Mapping Tables

### Gradle → Bazel Dependency Configuration Mapping

| Gradle Configuration    | Bazel Attribute                    | Notes                               |
| ----------------------- | ---------------------------------- | ----------------------------------- |
| `api()`                 | `deps = [...]`                     | Compile-time + runtime (transitive) |
| `implementation()`      | `deps = [...]`                     | Compile-time dependency             |
| `runtimeOnly()`         | `runtime_deps = [...]`             | Runtime-only dependency             |
| `compileOnly()`         | Manual handling                    | Provided scope (e.g., servlet API)  |
| `testImplementation()`  | Test target `deps = [...]`         | Test compile dependency             |
| `testRuntimeOnly()`     | Test target `runtime_deps = [...]` | Test runtime dependency             |
| `annotationProcessor()` | `plugins = [...]`                  | Annotation processor plugins        |

**Note:** Bazel doesn't distinguish between `api` and `implementation` at the rule level. Use project conventions to
document intended transitivity.

### Gradle → Bazel Plugin Mapping

| Gradle Plugin                               | Bazel Equivalent                 | Notes                          |
| ------------------------------------------- | -------------------------------- | ------------------------------ |
| `kotlin("jvm")`                             | `@rules_kotlin//kotlin:jvm.bzl`  | kt_jvm_library, kt_jvm_binary  |
| `application`                               | `kt_jvm_binary` or `java_binary` | Executable target              |
| `java`                                      | `@rules_java//java:defs.bzl`     | java_library, java_binary      |
| `com.gradleup.shadow`                       | `java_binary` + `_deploy.jar`    | Uber JAR automatically created |
| `org.jetbrains.kotlin.plugin.serialization` | Not needed                       | Runtime library only           |
| `com.squareup.wire`                         | Manual task or `proto_library`   | Code generation                |
| `com.diffplug.spotless`                     | `@aspect_rules_lint`             | Linting framework              |
| `io.gitlab.arturbosch.detekt`               | `@aspect_rules_lint`             | Kotlin linter                  |
| `org.jetbrains.kotlinx.kover`               | Built-in coverage                | `bazel coverage` command       |

### Gradle → Bazel Command Mapping

| Gradle Command            | Bazel Equivalent                         | Notes                 |
| ------------------------- | ---------------------------------------- | --------------------- |
| `./gradlew build`         | `bazel build //...`                      | Build all targets     |
| `./gradlew :module:build` | `bazel build //module`                   | Build specific module |
| `./gradlew test`          | `bazel test //...`                       | Run all tests         |
| `./gradlew :module:test`  | `bazel test //module:module_test`        | Run module tests      |
| `./gradlew clean`         | `bazel clean`                            | Clean build outputs   |
| `./gradlew dependencies`  | `bazel query 'deps(//module)'`           | Dependency tree       |
| `./gradlew projects`      | `bazel query 'kind(rule, //...)'`        | List all targets      |
| `./gradlew shadowJar`     | `bazel build //module:module_deploy.jar` | Build uber JAR        |

### Gradle → Bazel Project Dependency Mapping

| Gradle                     | Bazel                        |
| -------------------------- | ---------------------------- |
| `project(":module")`       | `//module`                   |
| `project(":parent:child")` | `//parent/child`             |
| Custom directory mapping   | Match actual filesystem path |

### Gradle Version Catalog → Bazel Maven Artifact

**Example transformation:**

Gradle `libs.versions.toml`:

```toml
[versions]
arrow = "2.2.1"

[libraries]
arrow-core = { module = "io.arrow-kt:arrow-core", version.ref = "arrow" }
```

Bazel `MODULE.bazel`:

```python
maven.install(
    artifacts = [
        "io.arrow-kt:arrow-core:2.2.1",
    ]
)
```

Bazel `BUILD.bazel`:

```python
deps = [
    "@maven//:io_arrow_kt_arrow_core",
]
```

**Label naming algorithm:**

```python
def gradle_to_bazel_label(group, artifact):
    # "io.arrow-kt:arrow-core" → "@maven//:io_arrow_kt_arrow_core"
    label = group + "_" + artifact
    label = label.replace(".", "_").replace("-", "_").replace(":", "_")
    return "@maven//:" + label
```

---

## Automation Opportunities

### What Can Be Fully Automated

1. **Module Discovery**
   - Parse `settings.gradle.kts` for `include()` statements
   - Generate directory structure map
   - Handle project directory remapping

2. **Version Catalog Conversion**
   - Parse `libs.versions.toml`
   - Resolve version references
   - Generate `maven.install(artifacts=[...])`
   - Generate Bazel labels from coordinates

3. **Basic BUILD File Generation**
   - Detect source directories (src/main/kotlin, src/test/kotlin)
   - Generate `kt_library` and `kt_test` targets
   - Add standard glob patterns
   - Include resources if directory exists

4. **Simple Dependency Mapping**
   - `implementation()` → `deps = [...]`
   - `testImplementation()` → test target deps
   - Internal project dependencies → Bazel labels
   - Maven dependencies → `@maven//` labels

5. **Bootstrap Files**
   - Generate `.bazelversion` with pinned version
   - Create `.bazelignore` from standard patterns
   - Generate `.bazelrc` from template

### What Requires Human Review

1. **Custom Build Logic**
   - Gradle tasks with non-standard behavior
   - Code generation tasks (Wire, ANTLR, etc.)
   - Custom resource processing
   - Multi-stage builds

2. **Complex Dependencies**
   - Conditional dependencies (different per platform)
   - BOM imports (must resolve manually)
   - Custom repository configurations
   - Exclusions and substitutions

3. **Plugin Migrations**
   - Custom Gradle plugins → equivalent Bazel rules
   - Plugin configurations → rule attributes
   - Complex plugin chains

4. **Mixed-Language Builds**
   - Kotlin-Groovy interop (compilation order)
   - Java-Kotlin mixed projects
   - Custom compilation configurations

5. **Visibility Strategy**
   - Which modules should be public vs private
   - Package-level visibility rules
   - Test utility visibility

6. **Build Performance Tuning**
   - Worker configuration
   - Cache strategy
   - Remote build execution setup

### Semi-Automatable (Needs Guidance)

1. **Dependency Configuration Mapping**
   - `api()` vs `implementation()` → both become `deps`, but document intent
   - `compileOnly()` → manual handling based on use case
   - Custom configurations → needs analysis

2. **Application Targets**
   - Detect `application` plugin
   - Extract `mainClass`
   - Generate both `kt_jvm_binary` and uber JAR targets
   - Requires validation of main class

3. **Test Configuration**
   - JUnit version detection
   - Test framework detection (JUnit 4 vs 5, Spock, etc.)
   - Test resource handling
   - Test parallelization settings

4. **TypeScript/JavaScript**
   - Parse `package.json` for dependencies
   - Generate `pnpm-lock.yaml`
   - Detect build scripts (esbuild, webpack, etc.)
   - Requires review of bundler config

---

## Challenges for Full Automation

### 1. Custom Gradle Plugins

**Example:**

```kotlin
plugins {
    id("com.company.custom-plugin") version "1.0.0"
}
```

**Challenge:** No way to know what this plugin does without documentation.

**Solution:** Manual analysis → equivalent Bazel rule or custom Starlark macro.

### 2. Dynamic Dependencies

**Example:**

```kotlin
dependencies {
    if (System.getProperty("os.name").contains("Mac")) {
        implementation("com.apple:cocoa:1.0")
    }
}
```

**Challenge:** Conditionals require Bazel `select()` configuration.

**Solution:** Detect patterns, suggest `select()`, require human review.

### 3. Source Code Generation

**Example:**

```kotlin
val generateProtos by tasks.registering(JavaExec::class) {
    classpath = wireCompiler
    mainClass.set("com.squareup.wire.WireCompiler")
    args("--proto_path=...", "--kotlin_out=...")
}
```

**Challenge:** Custom task logic doesn't map to Bazel directly.

**Solution:**

- Detect code generation tasks
- Map to equivalent Bazel rules if available (`proto_library`, `wire_library`)
- Generate custom Starlark rules if needed
- Require human validation

### 4. Multi-Module Coordination

**Example:** Root `build.gradle.kts` applying plugins to all subprojects.

```kotlin
subprojects {
    apply(plugin = "kotlin")
    apply(plugin = "detekt")

    dependencies {
        detektPlugins(libs.detekt.formatting)
    }
}
```

**Challenge:** Bazel doesn't have "apply to all" concept.

**Solution:**

- Create shared build_defs macros
- Generate consistent BUILD files
- Document conventions

### 5. Resource Filtering/Processing

**Example:**

```kotlin
tasks.processResources {
    filesMatching("version.properties") {
        expand("version" to project.version)
    }
}
```

**Challenge:** Dynamic resource transformation.

**Solution:**

- Detect resource processing tasks
- Generate Bazel `genrule` for transformations
- May require custom Starlark rules

---

## Files Created/Modified During Migration

### Root Directory

```
.bazelversion          # Pin Bazel version (9.0.0rc3)
.bazelrc               # Build configuration (243 lines)
.bazelignore           # Exclude Gradle artifacts
MODULE.bazel           # Workspace definition + dependencies (165 lines)
```

### Custom Build Rules

```
tools/build_defs/kotlin.bzl    # Project-specific kt_library/kt_test macros
tools/BUILD.bazel              # Toolchain registration
```

### Per-Module BUILD Files (40+ created)

Pattern for each Gradle module:

```
{module}/BUILD.bazel           # kt_library + kt_test targets
```

Examples:

```
groovy-common/BUILD.bazel
groovy-lsp/BUILD.bazel
parser/core/BUILD.bazel
parser/native/BUILD.bazel
indexer/scip/BUILD.bazel
editors/code/BUILD.bazel       # TypeScript compilation
editors/code/client/BUILD.bazel # VS Code extension bundle
```

### Special Cases

```
indexer/scip/src/main/proto/BUILD.bazel   # Protobuf code generation (Wire)
gradle/BUILD.bazel                        # Empty placeholder (reserved namespace)
```

### Configuration Files (Reference Only, Not Modified)

```
editors/code/package.json      # NPM dependencies (referenced by npm_translate_lock)
editors/code/pnpm-lock.yaml    # NPM lockfile (referenced by Bazel)
editors/code/tsconfig.json     # TypeScript config (referenced by ts_project)
editors/code/eslint.config.mjs # Linting config (referenced by rules_lint)
```

**Total:** ~45 BUILD.bazel files + 4 root config files + 1 custom macro file

---

## Migration Workflow Summary

```
┌─────────────────────────────────────────────────────────────────┐
│ Phase 1: ANALYSIS (Read-Only)                                   │
├─────────────────────────────────────────────────────────────────┤
│ 1. Parse settings.gradle.kts → Extract module list              │
│ 2. Parse libs.versions.toml → Extract dependency catalog        │
│ 3. Parse build.gradle.kts (per module) → Extract dependencies   │
│ 4. Identify special cases: code gen, multi-language, etc.       │
└─────────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────────┐
│ Phase 2: BOOTSTRAP (Create Bazel Infrastructure)                │
├─────────────────────────────────────────────────────────────────┤
│ 1. Create .bazelversion                                          │
│ 2. Create .bazelrc (from template + customizations)             │
│ 3. Create .bazelignore                                           │
│ 4. Create MODULE.bazel header                                    │
│ 5. Register bazel_dep() for rules (kotlin, java, js, etc.)      │
└─────────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────────┐
│ Phase 3: DEPENDENCIES (Maven + NPM)                             │
├─────────────────────────────────────────────────────────────────┤
│ 1. Convert libs.versions.toml → maven.install(artifacts=[])     │
│ 2. Add custom repositories if needed                             │
│ 3. For TypeScript: generate pnpm-lock.yaml                       │
│ 4. Add npm_translate_lock() to MODULE.bazel                      │
│ 5. Test: bazel fetch //...                                       │
└─────────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────────┐
│ Phase 4: BUILD FILES (Per-Module Generation)                    │
├─────────────────────────────────────────────────────────────────┤
│ For each module:                                                 │
│   1. Create BUILD.bazel                                          │
│   2. Generate kt_library() target                                │
│   3. Map Gradle dependencies → Bazel deps                        │
│   4. Generate kt_test() target if tests exist                    │
│   5. Add resources = glob() if src/main/resources exists         │
│   6. Handle special cases: applications, code gen, etc.          │
│   7. Test: bazel build //{module}                                │
└─────────────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────────────┐
│ Phase 5: VALIDATION & ITERATION                                 │
├─────────────────────────────────────────────────────────────────┤
│ 1. bazel build //...     → Fix compilation errors                │
│ 2. bazel test //...      → Fix test failures                     │
│ 3. Compare outputs: Gradle JAR vs Bazel JAR                      │
│ 4. Performance tuning: workers, caching, etc.                    │
│ 5. Document manual steps for complex cases                       │
└─────────────────────────────────────────────────────────────────┘
```

---

## Lessons Learned

### What Worked Well

1. **Incremental Migration:** Start with leaf modules (no dependencies), then work up the tree.
2. **Custom Macros:** `tools/build_defs/kotlin.bzl` reduced boilerplate significantly.
3. **Version Pinning:** `.bazelversion` + `pnpm-lock.yaml` = reproducible builds.
4. **Shared Cache:** Saved ~10GB disk space with worktree setup.

### What Was Difficult

1. **Maven Coordinates:** Some artifacts have non-obvious coordinates (gradle-tooling-api).
2. **BOM Resolution:** Bazel doesn't resolve versions from BOMs automatically.
3. **Groovy-Kotlin Interop:** Required careful ordering (Groovy first, then Kotlin).
4. **Test Discovery:** Gradle auto-discovers tests, Bazel requires explicit targets.

### What Would Be Different Next Time

1. **Script More:** Automate BUILD file generation from day 1.
2. **Validate Early:** Run `bazel build` on each module immediately after creating BUILD.bazel.
3. **Document Decisions:** Record why certain mappings were chosen (helps with debugging).

---

## Recommended Tool Architecture

For a deterministic Gradle-to-Bazel migration tool:

### Input Processing

1. Parse `settings.gradle.kts` (Kotlin DSL parser or regex)
2. Parse `gradle/libs.versions.toml` (TOML parser)
3. Parse per-module `build.gradle.kts` (Kotlin DSL parser)
4. Optional: Use Gradle Tooling API for dependency resolution

### Transformation Engine

1. Module graph construction
2. Dependency resolution (internal + external)
3. Template-based BUILD file generation
4. Validation and conflict detection

### Output Generation

1. `.bazelversion`, `.bazelrc`, `.bazelignore`
2. `MODULE.bazel` with all dependencies
3. Per-module `BUILD.bazel` files
4. Custom macro files (e.g., `kotlin.bzl`)
5. Migration report (manual steps, blockers, warnings)

### Validation

1. `bazel fetch //...` (can Bazel resolve all deps?)
2. `bazel build //...` (does everything compile?)
3. `bazel test //...` (do tests pass?)
4. Compare: Gradle outputs vs Bazel outputs

### User Workflow

```bash
# 1. Generate Bazel build files
migrate-gradle-to-bazel --gradle-root=/path/to/project --output=/path/to/bazel-project

# 2. Review migration report
cat bazel-project/MIGRATION_REPORT.md

# 3. Validate
cd bazel-project
bazel build //...
bazel test //...

# 4. Fix any issues reported in migration report
```

---

## Conclusion

This migration demonstrated that **Gradle-to-Bazel conversion is largely automatable** for standard Kotlin/Java
projects, with manual intervention required for:

1. Custom Gradle plugins and tasks
2. Complex build logic (conditional deps, code generation)
3. Non-standard project structures
4. Performance tuning and optimization

**Automation Coverage Estimate:**

- **70-80%** of standard Kotlin/Java builds can be automated
- **50-60%** of TypeScript/JavaScript builds (requires npm → Bazel rules knowledge)
- **30-40%** of builds with custom plugins or complex logic

The key to successful automation is:

1. **Template-based generation** for common patterns
2. **Human-in-the-loop** for edge cases
3. **Validation at every step** (fail fast)
4. **Clear migration reports** (document what needs manual intervention)

**Next Steps:**

1. Build a prototype tool based on this migration experience
2. Test on multiple projects to identify more edge cases
3. Build a library of BUILD file templates for common patterns
4. Create a "migration difficulty score" to set expectations
