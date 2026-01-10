# Gradle to Bazel Migration Guide

**Goal:** This document captures lessons learned from migrating the Groovy LSP project from Gradle to Bazel, with the
ultimate objective of building a deterministic, automated migration tool.

**Status:** Migration completed for 40+ modules. This document reflects actual implementation patterns, not theoretical
approaches.

---

## Table of Contents

1. [Input Analysis](#1-input-analysis)
2. [Mapping Rules](#2-mapping-rules)
3. [Transformation Steps](#3-transformation-steps)
4. [Edge Cases & Gotchas](#4-edge-cases--gotchas)
5. [Automation Opportunities](#5-automation-opportunities)
6. [Tool Design Sketch](#6-tool-design-sketch)

---

## 1. Input Analysis

### What Information Do We Extract from Gradle?

#### 1.1 Project Structure (`settings.gradle.kts`)

**Purpose:** Discover all modules and their directory layout.

**Example Input:**

```kotlin
rootProject.name = "groovy-lsp-root"

include("groovy-formatter")
include("markdown")
include("parser:api")
include("parser:native")
include("parser:core")
include("groovy-common")
include("groovy-lsp")

// Custom directory mappings
include("semantics-native")
project(":semantics-native").projectDir = file("semantics/native")
```

**What We Extract:**

- List of module names (e.g., `groovy-formatter`, `parser:api`)
- Module paths (e.g., `parser:api` → `parser/api/`)
- Custom directory mappings (e.g., `:semantics-native` → `semantics/native/`)
- Root project name

**Transformation to Bazel:**

- Each `include()` becomes a directory with a `BUILD.bazel` file
- Nested modules (e.g., `parser:api`) use `/` instead of `:` for paths
- Custom `projectDir` mappings must be tracked and applied

---

#### 1.2 Dependencies (`build.gradle.kts`)

**Purpose:** Extract dependency graph and plugin configurations.

**Example Input:**

```kotlin
plugins {
    kotlin("jvm")
    groovy
    id("com.gradleup.shadow")
    application
    kotlin("plugin.serialization")
}

dependencies {
    // External libraries
    implementation(libs.lsp4j)
    implementation(libs.groovy.core)

    // Internal modules
    implementation(project(":groovy-common"))
    implementation(project(":parser:core"))

    // Test dependencies
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
```

**What We Extract:**

| Gradle Pattern                       | Extracted Information      | Bazel Equivalent              |
| ------------------------------------ | -------------------------- | ----------------------------- |
| `implementation(libs.foo.bar)`       | External Maven dependency  | `@maven//:group_artifact`     |
| `implementation(project(":module"))` | Internal project reference | `//module`                    |
| `testImplementation(...)`            | Test-scoped dependency     | Add to `kt_test.deps`         |
| `testRuntimeOnly(...)`               | Test runtime dependency    | Add to `kt_test.runtime_deps` |
| `api(...)`                           | Exported API dependency    | Same as `deps` in Bazel       |

**Critical Distinction:**

- **Gradle:** `implementation` vs `api` controls transitive visibility
- **Bazel:** `deps` are always private; use `exports` for transitive visibility

---

#### 1.3 Version Catalog (`gradle/libs.versions.toml`)

**Purpose:** Centralized dependency version management.

**Example Input:**

```toml
[versions]
kotlin = "2.3.0"
groovy = "4.0.29"
lsp4j = "0.24.0"
coroutines = "1.10.2"

[libraries]
groovy-core = { module = "org.apache.groovy:groovy", version.ref = "groovy" }
lsp4j = { module = "org.eclipse.lsp4j:org.eclipse.lsp4j", version.ref = "lsp4j" }
kotlin-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
shadow = { id = "com.gradleup.shadow", version = "9.3.1" }
```

**Transformation to Bazel:**

The version catalog becomes Maven artifacts in `MODULE.bazel`:

```starlark
maven.install(
    artifacts = [
        "org.apache.groovy:groovy:4.0.29",
        "org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
)
```

**Parsing Logic:**

1. Extract `[versions]` section for variable substitution
2. Parse `[libraries]` and resolve `version.ref` to actual versions
3. Format as `group:artifact:version` Maven coordinates
4. Ignore `[plugins]` (Bazel uses different plugin system)

---

#### 1.4 Build Configuration (`gradle.properties`)

**Purpose:** JVM settings, build flags, feature toggles.

**Example Input:**

```properties
org.gradle.jvmargs=-Xmx4g -Xms1g
kotlin.code.style=official
kotlin.incremental=true
```

**Bazel Equivalent:**

- Most settings go in `.bazelrc` or `BUILD.bazel` directly
- JVM settings: `jvm_flags = ["-Xmx4g"]` in `kt_jvm_library`
- Feature toggles: Use Bazel `select()` or config_setting

---

## 2. Mapping Rules

### 2.1 Dependency Configuration Mapping

| Gradle Configuration      | Purpose                       | Bazel Equivalent                    |
| ------------------------- | ----------------------------- | ----------------------------------- |
| `implementation(...)`     | Compile + runtime, private    | `deps = [...]`                      |
| `api(...)`                | Compile + runtime, transitive | `deps = [...], exports = [...]`     |
| `compileOnly(...)`        | Compile-only, not packaged    | `deps = [...]` with `neverlink = 1` |
| `runtimeOnly(...)`        | Runtime-only                  | `runtime_deps = [...]`              |
| `testImplementation(...)` | Test compile + runtime        | `kt_test(deps = [...])`             |
| `testRuntimeOnly(...)`    | Test runtime-only             | `kt_test(runtime_deps = [...])`     |

**Maven Coordinate Transformation:**

```python
# Gradle libs.versions.toml reference
implementation(libs.groovy.core)

# Bazel Maven label
@maven//:org_apache_groovy_groovy

# Transformation rule:
# 1. Replace dots with underscores in group ID
# 2. Replace dots/dashes with underscores in artifact ID
# 3. Format: @maven//{group}_{artifact}
```

**Python Implementation:**

```python
def gradle_lib_to_bazel_label(group: str, artifact: str) -> str:
    """Convert Maven coordinates to Bazel @maven label."""
    group_normalized = group.replace(".", "_").replace("-", "_")
    artifact_normalized = artifact.replace(".", "_").replace("-", "_")
    return f"@maven//:{group_normalized}_{artifact_normalized}"

# Examples:
# "org.apache.groovy:groovy" -> "@maven//:org_apache_groovy_groovy"
# "io.ktor:ktor-client-core" -> "@maven//:io_ktor_ktor_client_core"
# "org.eclipse.lsp4j:org.eclipse.lsp4j" -> "@maven//:org_eclipse_lsp4j_org_eclipse_lsp4j"
```

**Project Reference Transformation:**

```python
def gradle_project_to_bazel_label(gradle_ref: str) -> str:
    """Convert Gradle project reference to Bazel label."""
    # Remove leading ':' and replace with '//'
    # Replace nested ':' with '/'
    return "//" + gradle_ref.lstrip(":").replace(":", "/")

# Examples:
# project(":groovy-common") -> "//dsl/dsld"
# project(":parser:core") -> "//parser/core"
# project(":semantics-native") -> "//semantics/native" (if custom mapping exists)
```

---

### 2.2 Plugin Mapping

| Gradle Plugin                    | Purpose                  | Bazel Equivalent                        |
| -------------------------------- | ------------------------ | --------------------------------------- |
| `kotlin("jvm")`                  | Kotlin/JVM compilation   | `kt_jvm_library` from `@rules_kotlin`   |
| `java-library`                   | Java library             | `java_library` from `@rules_java`       |
| `groovy`                         | Groovy compilation       | Mix Groovy + Kotlin (Groovy first)      |
| `application`                    | Executable application   | `kt_jvm_binary` or `java_binary`        |
| `com.gradleup.shadow`            | Fat JAR creation         | `_deploy.jar` suffix (built-in)         |
| `kotlin("plugin.serialization")` | Kotlinx serialization    | Add compiler plugin to `kt_jvm_library` |
| `com.squareup.wire`              | Protobuf code generation | `proto_library` + `kt_proto_library`    |

**Bazel Load Statements:**

```starlark
load("@rules_java//java:defs.bzl", "java_binary", "java_library")
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_binary", "kt_jvm_library", "kt_jvm_test")
load("@rules_proto//proto:defs.bzl", "proto_library")
```

---

### 2.3 Task Mapping

| Gradle Task               | Purpose               | Bazel Command                      |
| ------------------------- | --------------------- | ---------------------------------- |
| `./gradlew build`         | Build all modules     | `bazel build //...`                |
| `./gradlew test`          | Run all tests         | `bazel test //...`                 |
| `./gradlew :module:build` | Build specific module | `bazel build //module`             |
| `./gradlew :module:test`  | Test specific module  | `bazel test //module:module_test`  |
| `./gradlew shadowJar`     | Create fat JAR        | `bazel build //gls:gls_deploy.jar` |
| `./gradlew clean`         | Clean build           | `bazel clean`                      |

**Shadow JAR Mapping:**

```kotlin
// Gradle: shadowJar task creates fat JAR
tasks.shadowJar {
    archiveBaseName = "gls"
    archiveClassifier = "all"
}
```

```starlark
# Bazel: Use java_binary for deploy JAR
java_binary(
    name = "gls_deploy",
    main_class = "com.github.albertocavalcante.groovylsp.MainKt",
    runtime_deps = [":gls-lib"],
)
# Produces: bazel-bin/groovy-lsp/gls_deploy.jar (fat JAR)
```

---

### 2.4 Source Set Mapping

Gradle uses flexible source sets; Bazel has conventions:

| Gradle Source Set    | Path                | Bazel Equivalent                                |
| -------------------- | ------------------- | ----------------------------------------------- |
| `src/main/kotlin`    | Main Kotlin sources | `glob(["src/main/kotlin/**/*.kt"])`             |
| `src/main/java`      | Main Java sources   | `glob(["src/main/java/**/*.java"])`             |
| `src/main/groovy`    | Main Groovy sources | `glob(["src/main/groovy/**/*.groovy"])`         |
| `src/main/resources` | Resources           | `resources = glob(["src/main/resources/**/*"])` |
| `src/test/kotlin`    | Test Kotlin sources | `glob(["src/test/kotlin/**/*.kt"])`             |
| `src/test/resources` | Test resources      | `kt_test(..., resources = [...])`               |

**Protobuf Special Case:**

```kotlin
// Gradle: Wire plugin generates code in build/generated/source/wire
sourceSets.main {
    kotlin.srcDir(layout.buildDirectory.dir("generated/source/wire"))
}
```

```starlark
# Bazel: Use proto_library + language-specific rules
proto_library(
    name = "scip_proto",
    srcs = ["src/main/proto/scip.proto"],
)

kt_proto_library(
    name = "scip_kt_proto",
    deps = [":scip_proto"],
)
```

---

## 3. Transformation Steps

### Step-by-Step Migration Process

#### Step 1: Extract Project Structure

**Input:** `settings.gradle.kts`

**Process:**

1. Parse `include()` statements to get module list
2. Parse custom `projectDir` mappings
3. Build module path map: `{":parser:api": "parser/api"}`

**Output:** List of modules with physical paths

**Python Implementation:**

```python
import re
from pathlib import Path

def parse_settings_gradle(content: str) -> dict[str, Path]:
    """Extract module name -> path mapping from settings.gradle.kts."""
    modules = {}

    # Parse include() statements
    for match in re.finditer(r'include\("([^"]+)"\)', content):
        module_name = match.group(1)
        # Default path: replace ':' with '/'
        default_path = Path(module_name.replace(":", "/"))
        modules[f":{module_name}"] = default_path

    # Parse custom projectDir mappings
    for match in re.finditer(
        r'project\("([^"]+)"\)\.projectDir\s*=\s*file\("([^"]+)"\)',
        content
    ):
        module_name = match.group(1)
        custom_path = Path(match.group(2))
        modules[module_name] = custom_path

    return modules

# Example usage:
settings = Path("settings.gradle.kts").read_text()
modules = parse_settings_gradle(settings)
# {':parser:api': Path('parser/api'), ':semantics-native': Path('semantics/native')}
```

---

#### Step 2: Parse Version Catalog

**Input:** `gradle/libs.versions.toml`

**Process:**

1. Parse TOML to extract `[versions]`, `[libraries]`, `[plugins]`
2. Resolve `version.ref` indirection to actual versions
3. Build Maven coordinate map: `{libs.groovy.core: "org.apache.groovy:groovy:4.0.29"}`

**Output:** Resolved dependency map

**Python Implementation:**

```python
import tomllib  # Python 3.11+ (or use `tomli` for older versions)
from dataclasses import dataclass

@dataclass
class MavenCoordinate:
    group: str
    artifact: str
    version: str

    def to_bazel_label(self) -> str:
        group_norm = self.group.replace(".", "_").replace("-", "_")
        artifact_norm = self.artifact.replace(".", "_").replace("-", "_")
        return f"@maven//:{group_norm}_{artifact_norm}"

    def to_maven_string(self) -> str:
        return f"{self.group}:{self.artifact}:{self.version}"

def parse_version_catalog(toml_path: Path) -> dict[str, MavenCoordinate]:
    """Parse Gradle version catalog and return resolved Maven coordinates."""
    with open(toml_path, "rb") as f:
        catalog = tomllib.load(f)

    versions = catalog.get("versions", {})
    libraries = catalog.get("libraries", {})

    resolved = {}
    for key, lib_def in libraries.items():
        module = lib_def["module"]
        group, artifact = module.split(":")

        # Resolve version reference
        if "version" in lib_def:
            version = lib_def["version"]
        elif "version.ref" in lib_def:
            version_key = lib_def["version.ref"]
            version = versions[version_key]
        else:
            raise ValueError(f"No version for {key}")

        gradle_ref = f"libs.{key.replace('-', '.')}"
        resolved[gradle_ref] = MavenCoordinate(group, artifact, version)

    return resolved

# Example:
catalog = parse_version_catalog(Path("gradle/libs.versions.toml"))
# {"libs.groovy.core": MavenCoordinate("org.apache.groovy", "groovy", "4.0.29")}
```

---

#### Step 3: Parse Module Dependencies

**Input:** Each module's `build.gradle.kts`

**Process:**

1. Parse `plugins {}` block to determine module type (library, binary, test)
2. Parse `dependencies {}` block
3. Classify dependencies by scope (implementation, api, testImplementation, etc.)
4. Resolve `libs.*` references using version catalog
5. Transform `project(":foo")` to Bazel labels

**Output:** Dependency lists per scope

**AST-Based Parsing (Recommended):**

For robust parsing, use Gradle's Tooling API or Kotlin AST parser:

```kotlin
// Pseudo-code: Use Gradle Tooling API
import org.gradle.tooling.GradleConnector

fun extractDependencies(projectDir: File): DependencyInfo {
    val connector = GradleConnector.newConnector()
        .forProjectDirectory(projectDir)
        .connect()

    connector.use { connection ->
        val model = connection.getModel(IdeaProject::class.java)
        // Extract dependency graph from model
    }
}
```

**Regex-Based Parsing (Quick & Dirty):**

```python
import re
from enum import Enum

class DepScope(Enum):
    IMPLEMENTATION = "implementation"
    API = "api"
    TEST_IMPLEMENTATION = "testImplementation"
    TEST_RUNTIME_ONLY = "testRuntimeOnly"

def parse_build_gradle_deps(content: str, catalog: dict) -> dict[DepScope, list[str]]:
    """Extract dependencies from build.gradle.kts."""
    deps = {scope: [] for scope in DepScope}

    # Match: implementation(libs.foo.bar)
    for match in re.finditer(r'(\w+)\(libs\.([a-z.]+)\)', content):
        scope_str = match.group(1)
        lib_ref = f"libs.{match.group(2)}"

        if scope_str in [s.value for s in DepScope]:
            scope = DepScope(scope_str)
            maven_coord = catalog.get(lib_ref)
            if maven_coord:
                deps[scope].append(maven_coord.to_bazel_label())

    # Match: implementation(project(":foo"))
    for match in re.finditer(r'(\w+)\(project\("([^"]+)"\)\)', content):
        scope_str = match.group(1)
        project_ref = match.group(2)

        if scope_str in [s.value for s in DepScope]:
            scope = DepScope(scope_str)
            bazel_label = "//" + project_ref.lstrip(":").replace(":", "/")
            deps[scope].append(bazel_label)

    return deps
```

---

#### Step 4: Generate `MODULE.bazel`

**Purpose:** Central dependency declaration for the workspace.

**Input:** Parsed version catalog

**Output:** `MODULE.bazel` file

**Template:**

```starlark
"""
Groovy Language Server - Bazel Module Definition
"""

module(
    name = "groovy_lsp",
    version = "0.4.8",
)

# Core Bazel dependencies
bazel_dep(name = "bazel_skylib", version = "1.7.1")
bazel_dep(name = "rules_java", version = "8.9.0")
bazel_dep(name = "rules_kotlin", version = "2.2.2")
bazel_dep(name = "rules_jvm_external", version = "6.9")

# Maven Dependencies
maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")

maven.install(
    artifacts = [
        {artifacts}
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
    fetch_sources = True,
)
use_repo(maven, "maven")
```

**Python Code Generation:**

```python
def generate_module_bazel(catalog: dict[str, MavenCoordinate], output: Path):
    """Generate MODULE.bazel from version catalog."""
    artifacts = [f'        "{coord.to_maven_string()}",' for coord in catalog.values()]
    artifacts_str = "\n".join(artifacts)

    template = f'''"""
Groovy Language Server - Bazel Module Definition
"""

module(
    name = "groovy_lsp",
    version = "0.4.8",
)

bazel_dep(name = "rules_java", version = "8.9.0")
bazel_dep(name = "rules_kotlin", version = "2.2.2")
bazel_dep(name = "rules_jvm_external", version = "6.9")

maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
maven.install(
    artifacts = [
{artifacts_str}
    ],
    repositories = [
        "https://repo1.maven.org/maven2",
    ],
)
use_repo(maven, "maven")
'''
    output.write_text(template)
```

---

#### Step 5: Generate `BUILD.bazel` for Each Module

**Purpose:** Define build targets for each module.

**Input:** Module dependencies from Step 3

**Output:** `BUILD.bazel` file per module

**Template (Library):**

```starlark
load("//tools/build_defs:kotlin.bzl", "kt_library", "kt_test")

kt_library(
    name = "{module_name}",
    srcs = glob(["src/main/kotlin/**/*.kt"]),
    resources = glob(["src/main/resources/**/*"]),
    visibility = ["//visibility:public"],
    deps = {deps},
)

kt_test(
    name = "{module_name}_test",
    srcs = glob(["src/test/kotlin/**/*.kt"]),
    deps = [
        ":{module_name}",
        {test_deps}
    ],
)
```

**Template (Binary):**

```starlark
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_binary")
load("@rules_java//java:defs.bzl", "java_binary")
load("//tools/build_defs:kotlin.bzl", "kt_library")

kt_library(
    name = "{module_name}-lib",
    srcs = glob(["src/main/kotlin/**/*.kt"]),
    deps = {deps},
)

kt_jvm_binary(
    name = "{module_name}",
    main_class = "{main_class}",
    runtime_deps = [":{module_name}-lib"],
)

# Deploy JAR (fat JAR)
java_binary(
    name = "{module_name}_deploy",
    main_class = "{main_class}",
    runtime_deps = [":{module_name}-lib"],
)
```

**Python Code Generation:**

```python
from typing import Optional

def generate_build_bazel(
    module_name: str,
    deps: list[str],
    test_deps: list[str],
    main_class: Optional[str] = None,
    output: Path = None,
):
    """Generate BUILD.bazel for a module."""
    deps_str = "[\n        " + ",\n        ".join(f'"{d}"' for d in deps) + ",\n    ]"
    test_deps_str = ",\n        ".join(f'"{d}"' for d in test_deps)

    if main_class:
        # Binary module
        content = f'''load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_binary")
load("@rules_java//java:defs.bzl", "java_binary")
load("//tools/build_defs:kotlin.bzl", "kt_library")

kt_library(
    name = "{module_name}-lib",
    srcs = glob(["src/main/kotlin/**/*.kt"]),
    resources = glob(["src/main/resources/**/*"]),
    visibility = ["//visibility:public"],
    deps = {deps_str},
)

kt_jvm_binary(
    name = "{module_name}",
    main_class = "{main_class}",
    visibility = ["//visibility:public"],
    runtime_deps = [":{module_name}-lib"],
)

java_binary(
    name = "{module_name}_deploy",
    main_class = "{main_class}",
    visibility = ["//visibility:public"],
    runtime_deps = [":{module_name}-lib"],
)
'''
    else:
        # Library module
        content = f'''load("//tools/build_defs:kotlin.bzl", "kt_library", "kt_test")

kt_library(
    name = "{module_name}",
    srcs = glob(["src/main/kotlin/**/*.kt"]),
    resources = glob(["src/main/resources/**/*"]),
    visibility = ["//visibility:public"],
    deps = {deps_str},
)

kt_test(
    name = "{module_name}_test",
    srcs = glob(["src/test/kotlin/**/*.kt"]),
    deps = [
        ":{module_name}",
        {test_deps_str}
    ],
)
'''

    if output:
        output.write_text(content)
    return content
```

---

#### Step 6: Handle Special Cases

**Protobuf Code Generation:**

```starlark
load("@rules_proto//proto:defs.bzl", "proto_library")

proto_library(
    name = "scip_proto",
    srcs = ["src/main/proto/scip.proto"],
    visibility = ["//visibility:public"],
)

# Use language-specific proto rules
# For Kotlin: kt_proto_library (requires setup)
# For Java: java_proto_library
```

**Multi-Language Modules (Kotlin + Groovy):**

```starlark
# Groovy-Kotlin interop requires careful ordering
# In Gradle: Groovy compiles first, then Kotlin
# In Bazel: Use separate targets or custom rules

load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")

kt_jvm_library(
    name = "mixed-module",
    srcs = glob([
        "src/main/kotlin/**/*.kt",
        "src/main/groovy/**/*.groovy",
    ]),
    deps = ["@maven//:org_apache_groovy_groovy"],
)
```

**Resources:**

```starlark
kt_library(
    name = "module",
    srcs = glob(["src/main/kotlin/**/*.kt"]),
    resources = glob(["src/main/resources/**/*"]),  # Include all resources
)
```

---

## 4. Edge Cases & Gotchas

### 4.1 Version Mismatches

**Problem:** Gradle version catalog may use versions not available in Maven Central or BCR.

**Example:**

```toml
[libraries]
gradle-tooling-api = { module = "org.gradle:gradle-tooling-api", version = "9.2.1" }
```

**Issue:** `gradle-tooling-api` is not in Maven Central; it's in Gradle's custom repository.

**Solutions:**

1. **Add custom Maven repository in MODULE.bazel:**
   ```starlark
   maven.install(
       artifacts = ["org.gradle:gradle-tooling-api:9.2.1"],
       repositories = [
           "https://repo1.maven.org/maven2",
           "https://repo.gradle.org/gradle/libs-releases",  # Custom repo
       ],
   )
   ```

2. **Exclude problematic dependencies:**
   - If the dependency is optional, exclude it and add a TODO comment
   - Implement alternative functionality

**Mitigation in Tool:**

- Maintain a blocklist of problematic artifacts
- Warn user and suggest alternatives
- Support custom repository URLs

---

### 4.2 Missing Artifacts in Maven Central

**Problem:** Some Gradle plugins pull in artifacts from non-standard repositories.

**Example:**

```kotlin
plugins {
    id("com.squareup.wire")
}
```

The Wire plugin may use Gradle-specific artifacts not available in Bazel's `@maven`.

**Solution:**

- Use manual code generation instead of plugins
- Find equivalent Bazel rules (e.g., `rules_proto`)

---

### 4.3 Gradle-Specific Features

**Shadow Plugin (Fat JARs):**

Gradle's Shadow plugin merges dependencies into a single JAR with advanced features:

- Service file merging
- Dependency minimization
- Class relocation

Bazel equivalent:

```starlark
java_binary(
    name = "app_deploy",
    main_class = "com.example.Main",
    runtime_deps = [":app-lib"],
)
# Produces: bazel-bin/app_deploy.jar (includes all dependencies)
```

**Limitations:**

- No class relocation (use `java_plugin` for bytecode manipulation)
- No automatic minimization (ProGuard not built-in)

---

### 4.4 Test Fixtures

**Gradle:** `java-test-fixtures` plugin creates reusable test utilities.

```kotlin
// In module A:
plugins {
    `java-test-fixtures`
}

// In module B:
dependencies {
    testImplementation(testFixtures(project(":moduleA")))
}
```

**Bazel Equivalent:**

Create a separate test library:

```starlark
# In moduleA/BUILD.bazel
kt_library(
    name = "moduleA-test-fixtures",
    srcs = glob(["src/testFixtures/kotlin/**/*.kt"]),
    visibility = ["//visibility:public"],
)

# In moduleB/BUILD.bazel
kt_test(
    name = "moduleB_test",
    deps = [
        "//moduleA:moduleA-test-fixtures",
    ],
)
```

---

### 4.5 Resource Bundling

**Gradle:** Resources are automatically included from `src/main/resources`.

**Bazel:** Must explicitly declare:

```starlark
kt_library(
    name = "lib",
    srcs = ["src/main/kotlin/Main.kt"],
    resources = glob(["src/main/resources/**/*"]),  # Explicit!
)
```

**Gotcha:** Forgetting `resources` leads to `FileNotFoundException` at runtime.

---

### 4.6 Dynamic Versioning

**Gradle:** Supports dynamic versions like `1.0.+` or `latest.release`.

**Bazel:** Requires pinned versions.

**Solution:** Resolve dynamic versions during migration using Gradle's dependency resolution.

---

### 4.7 Plugin-Generated Code

**Problem:** Gradle plugins generate code at build time (e.g., Kotlin serialization, Protobuf).

**Gradle Example:**

```kotlin
plugins {
    kotlin("plugin.serialization")
}
```

**Bazel Solution:**

1. **For Kotlin serialization:** Add compiler plugin to `kt_jvm_library`:
   ```starlark
   kt_jvm_library(
       name = "lib",
       srcs = ["Main.kt"],
       plugins = ["@rules_kotlin//kotlin/compiler:serialization_plugin"],
   )
   ```

2. **For Protobuf:** Use `proto_library` + language rules:
   ```starlark
   proto_library(name = "proto", srcs = ["schema.proto"])
   kt_proto_library(name = "proto_kt", deps = [":proto"])
   ```

---

### 4.8 Transitive Dependencies

**Gradle:** `api` vs `implementation` controls transitive visibility.

**Bazel:** All `deps` are private by default; use `exports` for transitive visibility.

**Example:**

```kotlin
// Gradle
dependencies {
    api(libs.arrow.core)  // Exposes Arrow to consumers
}
```

```starlark
# Bazel
kt_library(
    name = "lib",
    deps = ["@maven//:io_arrow_kt_arrow_core"],
    exports = ["@maven//:io_arrow_kt_arrow_core"],  # Make transitive
)
```

---

## 5. Automation Opportunities

### 5.1 Parseable Inputs

**Easy to Automate:**

- `settings.gradle.kts` → Extract module list (regex or AST)
- `gradle/libs.versions.toml` → Parse TOML (standard library)
- Maven coordinates → Transform to Bazel labels (simple string manipulation)

**Medium Complexity:**

- `build.gradle.kts` → Kotlin DSL parsing (use Gradle Tooling API)
- Plugin detection → Map to Bazel rules
- Dependency resolution → Use Gradle API

**Hard to Automate:**

- Custom tasks → No direct equivalent
- Dynamic configuration → Requires runtime analysis
- Plugin-specific logic → Case-by-case handling

---

### 5.2 Code Generation Targets

**Fully Automatable:**

1. Generate `MODULE.bazel` from version catalog
2. Generate `BUILD.bazel` for standard library modules
3. Transform Maven coordinates to Bazel labels
4. Map project references to Bazel labels

**Partially Automatable:**

1. Binary modules (need main class detection)
2. Test modules (need test framework detection)
3. Multi-language modules (need compilation order)

**Manual Review Required:**

1. Custom plugins
2. Advanced features (Shadow, ProGuard)
3. Non-standard directory layouts
4. Dynamic versioning

---

### 5.3 Validation Steps

**Post-Migration Checks:**

1. **Dependency completeness:** All `libs.*` references resolved?
2. **Module completeness:** All `include()` statements have `BUILD.bazel`?
3. **Syntax validation:** `bazel query //...` succeeds?
4. **Build validation:** `bazel build //...` succeeds?
5. **Test validation:** `bazel test //...` passes?

**Automated Validation:**

```python
def validate_migration(workspace: Path) -> list[str]:
    """Validate Bazel migration."""
    errors = []

    # Check MODULE.bazel exists
    if not (workspace / "MODULE.bazel").exists():
        errors.append("Missing MODULE.bazel")

    # Check all modules have BUILD.bazel
    settings = parse_settings_gradle((workspace / "settings.gradle.kts").read_text())
    for module_path in settings.values():
        build_file = workspace / module_path / "BUILD.bazel"
        if not build_file.exists():
            errors.append(f"Missing BUILD.bazel in {module_path}")

    # Validate Bazel syntax
    result = subprocess.run(
        ["bazel", "query", "//..."],
        cwd=workspace,
        capture_output=True,
    )
    if result.returncode != 0:
        errors.append(f"Bazel query failed: {result.stderr.decode()}")

    return errors
```

---

## 6. Tool Design Sketch

### 6.1 Command-Line Interface

```bash
gradle-to-bazel migrate \
    --input=/path/to/gradle/project \
    --output=/path/to/bazel/workspace \
    --dry-run  # Preview changes without writing files

gradle-to-bazel validate \
    --workspace=/path/to/bazel/workspace  # Validate migration

gradle-to-bazel diff \
    --gradle=/path/to/gradle/project \
    --bazel=/path/to/bazel/workspace  # Compare dependency graphs
```

---

### 6.2 Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    gradle-to-bazel Tool                     │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌───────────────┐   ┌────────────────┐   ┌─────────────┐ │
│  │  Input Parser │──>│ Transformation │──>│ Code Writer │ │
│  └───────────────┘   └────────────────┘   └─────────────┘ │
│         │                     │                    │        │
│         ▼                     ▼                    ▼        │
│  ┌───────────────────────────────────────────────────────┐ │
│  │               Validation Engine                       │ │
│  └───────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

**Components:**

1. **Input Parser:**
   - Parse `settings.gradle.kts` → Module list
   - Parse `gradle/libs.versions.toml` → Version catalog
   - Parse `build.gradle.kts` (per module) → Dependencies
   - Use Gradle Tooling API for robust parsing

2. **Transformation Engine:**
   - Map Gradle dependencies → Bazel labels
   - Map Gradle plugins → Bazel rules
   - Build dependency graph
   - Detect special cases (protobuf, multi-language, etc.)

3. **Code Generator:**
   - Generate `MODULE.bazel`
   - Generate `BUILD.bazel` (per module)
   - Generate `tools/build_defs/kotlin.bzl` (macros)
   - Generate `.bazelrc` (configuration)

4. **Validation Engine:**
   - Check syntax: `bazel query //...`
   - Check build: `bazel build //...`
   - Check tests: `bazel test //...`
   - Report missing dependencies

---

### 6.3 Implementation Sketch (Python)

```python
#!/usr/bin/env python3
"""gradle-to-bazel: Automated Gradle to Bazel migration tool."""

from dataclasses import dataclass
from pathlib import Path
import subprocess
import tomllib
import re

@dataclass
class MigrationConfig:
    gradle_root: Path
    bazel_root: Path
    dry_run: bool = False

class GradleParser:
    """Parse Gradle project structure."""

    def parse_settings(self, settings_file: Path) -> dict[str, Path]:
        """Extract module paths from settings.gradle.kts."""
        content = settings_file.read_text()
        modules = {}

        for match in re.finditer(r'include\("([^"]+)"\)', content):
            module_name = match.group(1)
            modules[f":{module_name}"] = Path(module_name.replace(":", "/"))

        return modules

    def parse_version_catalog(self, catalog_file: Path) -> dict:
        """Parse gradle/libs.versions.toml."""
        with open(catalog_file, "rb") as f:
            return tomllib.load(f)

    def parse_build_gradle(self, build_file: Path) -> dict:
        """Parse build.gradle.kts dependencies."""
        # TODO: Use Gradle Tooling API for robust parsing
        content = build_file.read_text()

        deps = {"implementation": [], "testImplementation": []}
        for match in re.finditer(r'(\w+)\(libs\.([a-z.]+)\)', content):
            scope = match.group(1)
            lib_ref = f"libs.{match.group(2)}"
            if scope in deps:
                deps[scope].append(lib_ref)

        return deps

class BazelGenerator:
    """Generate Bazel build files."""

    def generate_module_bazel(self, catalog: dict, output: Path):
        """Generate MODULE.bazel from version catalog."""
        artifacts = []
        for key, lib in catalog.get("libraries", {}).items():
            module = lib["module"]
            version = self._resolve_version(lib, catalog)
            artifacts.append(f'        "{module}:{version}",')

        content = f'''module(name = "project", version = "1.0.0")

bazel_dep(name = "rules_kotlin", version = "2.2.2")
bazel_dep(name = "rules_jvm_external", version = "6.9")

maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
maven.install(
    artifacts = [
{chr(10).join(artifacts)}
    ],
    repositories = ["https://repo1.maven.org/maven2"],
)
use_repo(maven, "maven")
'''
        output.write_text(content)

    def generate_build_bazel(self, module: str, deps: list, output: Path):
        """Generate BUILD.bazel for a module."""
        deps_str = ",\n        ".join(f'"{d}"' for d in deps)

        content = f'''load("//tools/build_defs:kotlin.bzl", "kt_library", "kt_test")

kt_library(
    name = "{module}",
    srcs = glob(["src/main/kotlin/**/*.kt"]),
    visibility = ["//visibility:public"],
    deps = [
        {deps_str}
    ],
)

kt_test(
    name = "{module}_test",
    srcs = glob(["src/test/kotlin/**/*.kt"]),
    deps = [":{module}"],
)
'''
        output.write_text(content)

    def _resolve_version(self, lib: dict, catalog: dict) -> str:
        """Resolve version reference."""
        if "version" in lib:
            return lib["version"]
        elif "version.ref" in lib:
            return catalog["versions"][lib["version.ref"]]
        raise ValueError(f"No version for {lib}")

class MigrationOrchestrator:
    """Main migration orchestration."""

    def __init__(self, config: MigrationConfig):
        self.config = config
        self.parser = GradleParser()
        self.generator = BazelGenerator()

    def migrate(self):
        """Perform full migration."""
        print(f"Migrating {self.config.gradle_root} → {self.config.bazel_root}")

        # Step 1: Parse Gradle project
        modules = self.parser.parse_settings(
            self.config.gradle_root / "settings.gradle.kts"
        )
        catalog = self.parser.parse_version_catalog(
            self.config.gradle_root / "gradle" / "libs.versions.toml"
        )

        # Step 2: Generate Bazel files
        if not self.config.dry_run:
            self.config.bazel_root.mkdir(parents=True, exist_ok=True)

        # Generate MODULE.bazel
        module_bazel = self.config.bazel_root / "MODULE.bazel"
        self.generator.generate_module_bazel(catalog, module_bazel)
        print(f"✓ Generated {module_bazel}")

        # Generate BUILD.bazel for each module
        for module_name, module_path in modules.items():
            build_file = self.config.bazel_root / module_path / "BUILD.bazel"

            if not self.config.dry_run:
                build_file.parent.mkdir(parents=True, exist_ok=True)

            # Parse module dependencies
            gradle_build = self.config.gradle_root / module_path / "build.gradle.kts"
            if gradle_build.exists():
                deps = self.parser.parse_build_gradle(gradle_build)
                # TODO: Transform deps to Bazel labels
                self.generator.generate_build_bazel(
                    module_name.lstrip(":").replace(":", "-"),
                    [],
                    build_file
                )
                print(f"✓ Generated {build_file}")

    def validate(self):
        """Validate generated Bazel workspace."""
        print("Validating Bazel workspace...")

        result = subprocess.run(
            ["bazel", "query", "//..."],
            cwd=self.config.bazel_root,
            capture_output=True,
        )

        if result.returncode == 0:
            print("✓ Validation succeeded")
        else:
            print(f"✗ Validation failed:\n{result.stderr.decode()}")

def main():
    import argparse

    parser = argparse.ArgumentParser(description="Migrate Gradle to Bazel")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--dry-run", action="store_true")

    args = parser.parse_args()

    config = MigrationConfig(
        gradle_root=args.input,
        bazel_root=args.output,
        dry_run=args.dry_run,
    )

    orchestrator = MigrationOrchestrator(config)
    orchestrator.migrate()

    if not args.dry_run:
        orchestrator.validate()

if __name__ == "__main__":
    main()
```

---

### 6.4 Required Features

**Must-Have:**

- ✅ Parse `settings.gradle.kts` → Module list
- ✅ Parse `gradle/libs.versions.toml` → Version catalog
- ✅ Transform Maven coordinates → Bazel labels
- ✅ Generate `MODULE.bazel`
- ✅ Generate `BUILD.bazel` (per module)
- ✅ Validate syntax: `bazel query //...`

**Should-Have:**

- ⚠️ Use Gradle Tooling API for robust parsing
- ⚠️ Detect binary modules (main class detection)
- ⚠️ Handle protobuf code generation
- ⚠️ Support custom Maven repositories
- ⚠️ Generate macro files (`tools/build_defs/kotlin.bzl`)

**Nice-to-Have:**

- ❓ Interactive mode (ask user for ambiguous cases)
- ❓ Diff mode (compare Gradle vs Bazel dependency graphs)
- ❓ Incremental migration (support partial migration)
- ❓ Rollback functionality

---

## 7. Lessons Learned

### 7.1 Key Insights

1. **Start with `MODULE.bazel`:** Get all dependencies declared before writing `BUILD.bazel` files.

2. **Use macros for consistency:** Create `kt_library` and `kt_test` macros to reduce boilerplate.

3. **Test frequently:** Run `bazel build //...` after each module to catch errors early.

4. **Version catalog is gold:** The TOML file is the single source of truth; prioritize parsing it correctly.

5. **Bazel is stricter:** Gradle allows implicit dependencies; Bazel requires everything declared explicitly.

6. **Custom repositories are tricky:** Not all Gradle dependencies are in Maven Central; plan for custom repos.

---

### 7.2 Common Pitfalls

1. **Forgetting `resources`:** Leads to runtime `FileNotFoundException`.

2. **Incorrect label syntax:** `@maven//:org.apache.groovy.groovy` (wrong) vs `@maven//:org_apache_groovy_groovy`
   (correct).

3. **Missing test dependencies:** JUnit 5 requires both API and Engine artifacts.

4. **Transitive dependencies:** Forgetting `exports` when migrating `api` dependencies.

5. **Glob patterns:** Bazel's `glob()` doesn't cross directory boundaries; use `**` carefully.

---

### 7.3 Success Metrics

**Migration Completeness:**

- ✅ All modules have `BUILD.bazel`
- ✅ `bazel build //...` succeeds
- ✅ `bazel test //...` passes
- ✅ Binary artifacts can be built (e.g., `gls_deploy.jar`)

**Performance:**

- ⏱️ Incremental builds are faster than Gradle
- ⏱️ Parallel builds utilize all cores
- ⏱️ Remote caching reduces CI build time

**Developer Experience:**

- 📖 Clear error messages when dependencies are missing
- 📖 Consistent naming conventions
- 📖 Easy to add new modules

---

## Conclusion

This guide documents the complete journey of migrating a 40+ module Kotlin/Groovy project from Gradle to Bazel. The
patterns, code snippets, and lessons learned here form the foundation for building an automated migration tool.

**Next Steps:**

1. Implement the Python tool skeleton (Section 6.3)
2. Add Gradle Tooling API integration for robust parsing
3. Test on multiple projects to refine heuristics
4. Open-source the tool for community contributions

**Questions? Found a bug in this guide?** File an issue or submit a PR!
