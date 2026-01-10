# Gradle to Bazel Migration Tool (Planned)

## Vision

Build a deterministic, automated tool to migrate Gradle projects (Kotlin DSL) to Bazel (bzlmod).

**Target:** 70-80% automation for standard Kotlin/Java projects.

---

## Goals

1. **Speed:** Migrate a 40-module monorepo in minutes, not days
2. **Correctness:** Generated Bazel builds should produce identical outputs
3. **Transparency:** Clear reports on what was automated vs what needs human review
4. **Incremental:** Support partial migrations (one module at a time)
5. **Reproducible:** Same input → same output every time

---

## Architecture Overview

```
┌────────────────┐
│  Gradle Project│
│   (Input)      │
└───────┬────────┘
        │
        ↓
┌────────────────────────────────┐
│   Analysis Phase               │
│  ┌──────────────────────────┐  │
│  │ settings.gradle.kts      │  │
│  │ Parser                   │  │
│  └──────────┬───────────────┘  │
│             │                   │
│  ┌──────────▼───────────────┐  │
│  │ libs.versions.toml       │  │
│  │ Parser                   │  │
│  └──────────┬───────────────┘  │
│             │                   │
│  ┌──────────▼───────────────┐  │
│  │ build.gradle.kts         │  │
│  │ Parser (per module)      │  │
│  └──────────┬───────────────┘  │
│             │                   │
│  ┌──────────▼───────────────┐  │
│  │ Dependency Graph Builder │  │
│  └──────────┬───────────────┘  │
└─────────────┼───────────────────┘
              │
              ↓
┌────────────────────────────────┐
│   Transformation Phase         │
│  ┌──────────────────────────┐  │
│  │ Module Mapper            │  │
│  │ (Gradle → Bazel labels) │  │
│  └──────────┬───────────────┘  │
│             │                   │
│  ┌──────────▼───────────────┐  │
│  │ Dependency Mapper        │  │
│  │ (libs → maven artifacts) │  │
│  └──────────┬───────────────┘  │
│             │                   │
│  ┌──────────▼───────────────┐  │
│  │ Template Engine          │  │
│  │ (BUILD file generation)  │  │
│  └──────────┬───────────────┘  │
└─────────────┼───────────────────┘
              │
              ↓
┌────────────────────────────────┐
│   Generation Phase             │
│  ┌──────────────────────────┐  │
│  │ Bootstrap Files          │  │
│  │ (.bazelversion, etc.)    │  │
│  └──────────┬───────────────┘  │
│             │                   │
│  ┌──────────▼───────────────┐  │
│  │ MODULE.bazel Generator   │  │
│  └──────────┬───────────────┘  │
│             │                   │
│  ┌──────────▼───────────────┐  │
│  │ BUILD.bazel Generator    │  │
│  │ (per module)             │  │
│  └──────────┬───────────────┘  │
│             │                   │
│  ┌──────────▼───────────────┐  │
│  │ Custom Macros Generator  │  │
│  │ (kotlin.bzl, etc.)       │  │
│  └──────────┬───────────────┘  │
└─────────────┼───────────────────┘
              │
              ↓
┌────────────────────────────────┐
│   Validation Phase             │
│  ┌──────────────────────────┐  │
│  │ Bazel Syntax Validator   │  │
│  └──────────┬───────────────┘  │
│             │                   │
│  ┌──────────▼───────────────┐  │
│  │ Build Test (bazel build) │  │
│  └──────────┬───────────────┘  │
│             │                   │
│  ┌──────────▼───────────────┐  │
│  │ Migration Report         │  │
│  │ Generator                │  │
│  └──────────┬───────────────┘  │
└─────────────┼───────────────────┘
              │
              ↓
┌────────────────┐
│ Bazel Project  │
│ + Report       │
│ (Output)       │
└────────────────┘
```

---

## Core Components

### 1. Analysis Module

#### 1.1 Settings Parser (`SettingsParser.kt`)

**Purpose:** Extract module structure from `settings.gradle.kts`.

**Input:**

```kotlin
// settings.gradle.kts
include("groovy-common")
include("parser:core")
include("semantics-native")
project(":semantics-native").projectDir = file("semantics/native")
```

**Output:**

```kotlin
data class GradleModule(
    val name: String,              // "groovy-common"
    val path: String,              // ":groovy-common"
    val directory: File,           // File("groovy-common")
    val bazelLabel: String,        // "//dsl/dsld"
)

fun parseSettings(settingsFile: File): List<GradleModule>
```

**Implementation Strategy:**

- **Option A:** Regex-based parsing (simple, fast, may miss edge cases)
- **Option B:** Kotlin Script parsing (accurate, slower, requires Kotlin compiler)
- **Recommended:** Start with regex, fall back to Kotlin parser if regex fails

**Key Challenges:**

- Handling custom `projectDir` remapping
- Dynamic `include()` statements (loops, conditionals)
- Multi-line statements

#### 1.2 Version Catalog Parser (`VersionCatalogParser.kt`)

**Purpose:** Parse `libs.versions.toml` into structured data.

**Input:**

```toml
[versions]
kotlin = "2.3.0"
arrow = "2.2.1"

[libraries]
kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }
arrow-core = { module = "io.arrow-kt:arrow-core", version.ref = "arrow" }
```

**Output:**

```kotlin
data class VersionCatalog(
    val versions: Map<String, String>,
    val libraries: Map<String, Library>,
    val plugins: Map<String, Plugin>,
)

data class Library(
    val name: String,              // "arrow-core"
    val group: String,             // "io.arrow-kt"
    val artifact: String,          // "arrow-core"
    val version: String,           // "2.2.1" (resolved)
    val mavenCoordinate: String,   // "io.arrow-kt:arrow-core:2.2.1"
    val bazelLabel: String,        // "@maven//:io_arrow_kt_arrow_core"
)
```

**Implementation:**

- Use existing TOML parser library (e.g., `com.fasterxml.jackson.dataformat:jackson-dataformat-toml`)
- Resolve `version.ref` references
- Generate Bazel labels automatically

#### 1.3 Build Script Parser (`BuildScriptParser.kt`)

**Purpose:** Extract dependencies from `build.gradle.kts` per module.

**Input:**

```kotlin
// build.gradle.kts
dependencies {
    api(libs.arrow.core)
    implementation(libs.kotlin.coroutines.core)
    implementation(project(":groovy-common"))

    testImplementation(libs.kotlin.test)
}
```

**Output:**

```kotlin
data class ModuleDependencies(
    val module: GradleModule,
    val dependencies: List<Dependency>,
)

sealed class Dependency {
    data class External(
        val configuration: String,  // "api", "implementation", "testImplementation"
        val library: Library,       // From version catalog
    ) : Dependency()

    data class Internal(
        val configuration: String,
        val targetModule: GradleModule,
    ) : Dependency()
}
```

**Implementation Strategy:**

- Parse Kotlin DSL (regex or Kotlin compiler)
- Resolve `libs.*` references using VersionCatalog
- Resolve `project()` references using GradleModule list
- Handle special cases: `platform()`, `testFixtures()`, etc.

#### 1.4 Dependency Graph Builder (`DependencyGraphBuilder.kt`)

**Purpose:** Build a directed acyclic graph (DAG) of module dependencies.

**Output:**

```kotlin
data class DependencyGraph(
    val modules: Map<String, ModuleDependencies>,
    val topologicalOrder: List<GradleModule>,  // Build order
)

fun buildGraph(
    modules: List<GradleModule>,
    dependencies: List<ModuleDependencies>,
): DependencyGraph
```

**Use Cases:**

- Determine build order (leaf modules first)
- Detect circular dependencies (error)
- Validate that all internal dependencies exist

---

### 2. Transformation Module

#### 2.1 Module Mapper (`ModuleMapper.kt`)

**Purpose:** Convert Gradle module paths to Bazel labels.

**Examples:**

```kotlin
fun toBazelLabel(gradlePath: String): String {
    // ":groovy-common" → "//dsl/dsld"
    // ":parser:core" → "//parser/core"
    return gradlePath.removePrefix(":").replace(":", "/").let { "//$it" }
}
```

#### 2.2 Dependency Mapper (`DependencyMapper.kt`)

**Purpose:** Convert Gradle dependencies to Bazel deps.

**Rules:**

```kotlin
fun mapDependency(dep: Dependency, isTest: Boolean): String {
    return when (dep) {
        is Dependency.External -> dep.library.bazelLabel
        is Dependency.Internal -> dep.targetModule.bazelLabel
    }
}

fun filterByConfiguration(
    deps: List<Dependency>,
    configurations: Set<String>,
): List<String> {
    return deps
        .filter { it.configuration in configurations }
        .map { mapDependency(it, isTest = false) }
}

// Main dependencies
val mainDeps = filterByConfiguration(deps, setOf("api", "implementation"))

// Test dependencies
val testDeps = filterByConfiguration(deps, setOf("testImplementation"))
```

#### 2.3 Template Engine (`TemplateEngine.kt`)

**Purpose:** Generate Bazel files from templates.

**Templates:**

1. **MODULE.bazel.template**

```python
module(
    name = "{{project_name}}",
    version = "{{version}}",
)

{{#rules}}
bazel_dep(name = "{{name}}", version = "{{version}}")
{{/rules}}

maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
maven.install(
    artifacts = [
        {{#artifacts}}
        "{{coordinate}}",
        {{/artifacts}}
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
        {{#custom_repos}}
        "{{url}}",
        {{/custom_repos}}
    ],
)
use_repo(maven, "maven")
```

2. **BUILD.bazel.template**

```python
load("//tools/build_defs:kotlin.bzl", "kt_library", "kt_test")

kt_library(
    name = "{{module_name}}",
    srcs = glob(["src/main/kotlin/**/*.kt"]),
    {{#has_resources}}
    resources = glob(["src/main/resources/**/*"]),
    {{/has_resources}}
    visibility = ["//visibility:public"],
    deps = [
        {{#deps}}
        "{{label}}",
        {{/deps}}
    ],
)

{{#has_tests}}
kt_test(
    name = "{{module_name}}_test",
    srcs = glob(["src/test/kotlin/**/*.kt"]),
    deps = [
        ":{{module_name}}",
        {{#test_deps}}
        "{{label}}",
        {{/test_deps}}
    ],
)
{{/has_tests}}
```

**Implementation:**

- Use Mustache or similar templating library
- Support conditionals and loops
- Validate template syntax before generation

---

### 3. Generation Module

#### 3.1 Bootstrap Files Generator (`BootstrapGenerator.kt`)

**Generates:**

1. `.bazelversion` - Pin Bazel version (e.g., "9.0.0")
2. `.bazelrc` - Build configuration (use standard template)
3. `.bazelignore` - Exclude Gradle artifacts

```kotlin
fun generateBootstrapFiles(
    outputDir: File,
    bazelVersion: String = "9.0.0",
)
```

#### 3.2 MODULE.bazel Generator (`ModuleFileGenerator.kt`)

**Responsibilities:**

1. Extract all unique external dependencies from all modules
2. Deduplicate (same artifact, different versions → pick one)
3. Sort alphabetically for consistency
4. Detect custom repositories
5. Generate MODULE.bazel from template

```kotlin
fun generateModuleFile(
    projectName: String,
    projectVersion: String,
    dependencies: List<ModuleDependencies>,
    versionCatalog: VersionCatalog,
    outputDir: File,
)
```

#### 3.3 BUILD.bazel Generator (`BuildFileGenerator.kt`)

**Responsibilities:**

1. Generate one BUILD.bazel per module
2. Detect source directories (src/main/kotlin, src/main/java)
3. Detect resource directories (src/main/resources)
4. Detect test directories (src/test/kotlin)
5. Apply template
6. Handle special cases:
   - Application modules → add kt_jvm_binary
   - Mixed Kotlin-Groovy → special compilation rules
   - Code generation → custom genrules

```kotlin
fun generateBuildFile(
    module: GradleModule,
    dependencies: ModuleDependencies,
    outputDir: File,
): GenerationResult

sealed class GenerationResult {
    data class Success(val file: File) : GenerationResult()
    data class NeedsManualReview(
        val file: File,
        val reasons: List<String>,
    ) : GenerationResult()
    data class Failed(val error: String) : GenerationResult()
}
```

#### 3.4 Custom Macros Generator (`MacrosGenerator.kt`)

**Generates:** `tools/build_defs/kotlin.bzl`

**Purpose:** Project-specific wrappers around Bazel rules.

```kotlin
fun generateKotlinMacros(
    commonTestDeps: List<String>,  // e.g., JUnit, AssertJ
    outputDir: File,
)
```

**Template:**

```python
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library", "kt_jvm_test")

def kt_library(name, srcs, deps = None, **kwargs):
    kt_jvm_library(
        name = name,
        srcs = srcs,
        deps = deps if deps != None else [],
        **kwargs
    )

def kt_test(name, srcs, deps = None, **kwargs):
    kt_jvm_test(
        name = name,
        srcs = srcs,
        deps = (deps if deps != None else []) + [
            {{#common_test_deps}}
            "{{label}}",
            {{/common_test_deps}}
        ],
        **kwargs
    )
```

---

### 4. Validation Module

#### 4.1 Syntax Validator (`BazelSyntaxValidator.kt`)

**Purpose:** Validate generated Bazel files before writing.

```kotlin
fun validateBazelFile(content: String, file: File): ValidationResult {
    // 1. Check for syntax errors (unbalanced brackets, quotes)
    // 2. Validate label references (//path:target format)
    // 3. Check for duplicate target names
    // 4. Warn about potential issues
}

data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String>,
)
```

#### 4.2 Build Tester (`BazelBuildTester.kt`)

**Purpose:** Run `bazel build` and `bazel test` to validate migration.

```kotlin
fun testBuild(
    bazelWorkspace: File,
    targets: List<String>,
): BuildTestResult {
    // 1. Run: bazel build {targets}
    // 2. Capture stdout/stderr
    // 3. Parse errors
    // 4. Return results
}

data class BuildTestResult(
    val success: Boolean,
    val output: String,
    val errors: List<BuildError>,
)

data class BuildError(
    val target: String,
    val message: String,
    val file: File?,
    val line: Int?,
)
```

#### 4.3 Migration Report Generator (`MigrationReportGenerator.kt`)

**Purpose:** Generate a detailed report of the migration.

**Report Sections:**

1. **Summary**
   - Total modules migrated
   - Automation success rate
   - Manual intervention required

2. **Module Status**
   - ✅ Fully automated (builds successfully)
   - ⚠️ Generated but needs review (special cases detected)
   - ❌ Failed to generate (complex logic)

3. **Manual Actions Required**
   - Custom Gradle tasks to migrate
   - Code generation tasks to configure
   - Complex dependencies to resolve

4. **Build Validation Results**
   - Modules that build successfully
   - Modules with build errors
   - Test results

5. **Next Steps**
   - Commands to run
   - Files to review
   - Known issues

**Output:** `MIGRATION_REPORT.md`

---

## Implementation Plan

### Phase 1: Core Parser (Weeks 1-2)

**Goal:** Parse Gradle files into structured data.

- [ ] Implement `SettingsParser` (regex-based)
- [ ] Implement `VersionCatalogParser` (TOML library)
- [ ] Implement `BuildScriptParser` (regex-based)
- [ ] Unit tests for each parser
- [ ] Integration test: Parse groovy-lsp project

**Deliverable:** Library that can parse Gradle projects into Kotlin data classes.

### Phase 2: Transformation Engine (Weeks 3-4)

**Goal:** Convert Gradle data to Bazel format.

- [ ] Implement `ModuleMapper`
- [ ] Implement `DependencyMapper`
- [ ] Implement `DependencyGraphBuilder`
- [ ] Create Bazel file templates (Mustache)
- [ ] Implement `TemplateEngine`
- [ ] Unit tests for transformation logic

**Deliverable:** Library that can transform Gradle data to Bazel templates.

### Phase 3: File Generation (Weeks 5-6)

**Goal:** Generate working Bazel files.

- [ ] Implement `BootstrapGenerator`
- [ ] Implement `ModuleFileGenerator`
- [ ] Implement `BuildFileGenerator`
- [ ] Implement `MacrosGenerator`
- [ ] Integration test: Generate Bazel files for groovy-lsp

**Deliverable:** Tool that generates complete Bazel workspace.

### Phase 4: Validation & Reporting (Weeks 7-8)

**Goal:** Ensure generated files are correct and usable.

- [ ] Implement `BazelSyntaxValidator`
- [ ] Implement `BazelBuildTester`
- [ ] Implement `MigrationReportGenerator`
- [ ] CLI interface
- [ ] End-to-end test: Migrate groovy-lsp and build with Bazel

**Deliverable:** Production-ready migration tool.

### Phase 5: Polish & Documentation (Week 9)

**Goal:** Make tool user-friendly and documented.

- [ ] Comprehensive error messages
- [ ] Progress indicators
- [ ] Configuration file support (for customization)
- [ ] User documentation
- [ ] Example migrations
- [ ] Release v1.0

---

## CLI Interface Design

```bash
# Basic usage
gradle2bazel --source=/path/to/gradle/project --output=/path/to/bazel/project

# Advanced options
gradle2bazel \
  --source=/path/to/gradle/project \
  --output=/path/to/bazel/project \
  --bazel-version=9.0.0 \
  --dry-run \
  --validate \
  --report=MIGRATION_REPORT.md

# Incremental mode (migrate specific modules)
gradle2bazel \
  --source=/path/to/gradle/project \
  --output=/path/to/bazel/project \
  --modules=groovy-common,parser:core

# Interactive mode (prompt for decisions)
gradle2bazel --source=/path/to/gradle/project --interactive
```

**Flags:**

- `--source`: Path to Gradle project root
- `--output`: Path to output Bazel workspace
- `--bazel-version`: Bazel version to use (default: latest stable)
- `--dry-run`: Generate files in memory, don't write to disk
- `--validate`: Run `bazel build //...` after generation
- `--report`: Path to migration report (default: MIGRATION_REPORT.md)
- `--modules`: Comma-separated list of modules to migrate (default: all)
- `--interactive`: Prompt for decisions on edge cases
- `--verbose`: Detailed logging

**Exit Codes:**

- 0: Success (all modules migrated, builds pass)
- 1: Partial success (some modules need manual review)
- 2: Failure (critical errors, no files generated)

---

## Technology Stack

### Language: Kotlin

**Why Kotlin?**

1. Native parsing of Gradle Kotlin DSL
2. Strong type system for data modeling
3. Excellent tooling (IntelliJ IDEA)
4. Interop with Java libraries
5. Coroutines for concurrent parsing

### Libraries

**Required:**

```kotlin
dependencies {
    // TOML parsing
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-toml:2.18.0")

    // Templating
    implementation("com.github.spullara.mustache.java:compiler:0.9.14")

    // CLI
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("com.github.ajalt.mordant:mordant:3.0.2")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.2")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.mockk:mockk:1.14.7")
}
```

**Optional (for advanced parsing):**

```kotlin
dependencies {
    // Gradle Tooling API (for precise dependency resolution)
    implementation("org.gradle:gradle-tooling-api:9.2.1")

    // Kotlin Compiler (for DSL parsing)
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.3.0")
}
```

---

## Testing Strategy

### Unit Tests

- Each parser function
- Each transformation function
- Template rendering
- Label generation

### Integration Tests

- End-to-end: Parse groovy-lsp Gradle project
- End-to-end: Generate Bazel files for groovy-lsp
- Validate: Build groovy-lsp with generated Bazel files

### Test Projects

- Simple single-module project
- Multi-module project with internal dependencies
- Project with custom repositories
- Project with code generation (protobuf, Wire)
- Project with TypeScript (NPM dependencies)

---

## Configuration File Support

Allow users to customize behavior via `gradle2bazel.yml`:

```yaml
# Migration configuration
migration:
  # Bazel version to use
  bazel_version: "9.0.0"

  # Custom module mappings (override automatic detection)
  module_overrides:
    ":semantics-native": "//semantics/native"

  # Custom dependency mappings
  dependency_overrides:
    "org.gradle:gradle-tooling-api":
      repository: "https://repo.gradle.org/gradle/libs-releases/"

  # Visibility strategy
  visibility:
    default: "//visibility:public"
    test_utilities:
      - "groovy-test-utils"
      - "groovy-testing"

  # Rules to use
  rules:
    kotlin_version: "2.2.1"
    java_version: "8.15.2"
    rules_jvm_external_version: "6.9"

  # Test framework detection
  test_frameworks:
    - junit5
    - spock

  # Custom templates (optional)
  templates:
    build_file: "custom/BUILD.bazel.template"
    module_file: "custom/MODULE.bazel.template"

  # Validation settings
  validation:
    run_build: true
    run_tests: true
    fail_on_warnings: false
```

---

## Future Enhancements

### v1.1: Advanced Parsing

- Use Gradle Tooling API for precise dependency resolution
- Handle Gradle composite builds
- Support Gradle plugins with custom DSLs

### v1.2: More Languages

- Support Groovy source files
- Support Java-only projects
- Support Scala projects

### v1.3: Migration Assistance

- Interactive mode with suggestions
- Automatically fix common issues
- Learning from previous migrations

### v1.4: Reverse Migration

- Bazel → Gradle (for comparison/validation)
- Bidirectional sync (experimental)

---

## Open Questions

1. **Gradle Tooling API vs Regex Parsing?**
   - Tooling API: Accurate, requires Gradle installation
   - Regex: Fast, may miss edge cases
   - **Proposal:** Start with regex, add Tooling API as optional enhancement

2. **How to handle version conflicts?**
   - Gradle auto-resolves to latest version
   - Bazel requires explicit versions
   - **Proposal:** Pick highest version, warn user

3. **What about custom Gradle plugins?**
   - No generic solution
   - **Proposal:** Detect and report, require manual migration

4. **TypeScript/JavaScript support?**
   - Different ecosystem (NPM vs Maven)
   - **Proposal:** Separate module for NPM migration

5. **Incremental migration strategy?**
   - Migrate one module at a time
   - **Proposal:** Support `--modules` flag, generate partial Bazel workspace

---

## Success Metrics

**v1.0 Goals:**

1. **70% automation rate** for standard Kotlin/Java projects
2. **Complete migration in < 5 minutes** for 40-module monorepo
3. **90% build success rate** (bazel build //... passes)
4. **100% test parity** (same tests pass in Gradle and Bazel)

**User Satisfaction:**

- Clear migration reports (users know what to do next)
- Minimal manual intervention (only complex edge cases)
- Fast feedback (validate quickly)

---

## Getting Started (Developer Guide)

### Prerequisites

- JDK 21+
- Kotlin 2.3.0+
- Bazel 9.0+ (for testing)

### Build

```bash
./gradlew build
```

### Run

```bash
./gradlew run --args="--source=/path/to/project --output=/tmp/migrated"
```

### Test

```bash
./gradlew test
```

### Example

```bash
# Migrate groovy-lsp project
./gradlew run --args="--source=../main --output=/tmp/groovy-lsp-bazel --validate"

# Check migration report
cat /tmp/groovy-lsp-bazel/MIGRATION_REPORT.md

# Test Bazel build
cd /tmp/groovy-lsp-bazel
bazel build //...
bazel test //...
```

---

## References

- [Bazel Documentation](https://bazel.build/)
- [rules_kotlin](https://github.com/bazelbuild/rules_kotlin)
- [rules_jvm_external](https://github.com/bazelbuild/rules_jvm_external)
- [Gradle Kotlin DSL](https://docs.gradle.org/current/userguide/kotlin_dsl.html)
- [Gradle Tooling API](https://docs.gradle.org/current/userguide/tooling_api.html)
- [This Migration (MIGRATION.md)](../MIGRATION.md)

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development guidelines.

**Key Principles:**

1. **Determinism:** Same input → same output
2. **Transparency:** Clear reporting of all decisions
3. **Validation:** Test everything, fail fast
4. **Extensibility:** Easy to add new patterns/rules
5. **User-Friendliness:** Clear errors, helpful suggestions
