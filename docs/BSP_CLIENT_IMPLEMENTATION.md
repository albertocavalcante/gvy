# BSP Client Implementation - Design Document

> Session Date: December 21, 2025\
> PR:
> [#253 - feat: add BSP client support for Bazel, sbt, and Mill](https://github.com/albertocavalcante/groovy-lsp/pull/253)

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Problem Statement](#problem-statement)
3. [Research & Analysis](#research--analysis)
4. [Architecture Decision](#architecture-decision)
5. [Implementation Details](#implementation-details)
6. [Testing Strategy](#testing-strategy)
7. [PR Review & Iterations](#pr-review--iterations)
8. [Future Considerations](#future-considerations)
9. [References](#references)

---

## Executive Summary

This document captures the research, analysis, and implementation work for adding Build Server Protocol (BSP) client
support to the Groovy Language Server. The goal is to enable dependency resolution for build tools beyond the existing
Gradle and Maven support.

### Key Outcomes

- ✅ Added BSP client support for Bazel, sbt, and Mill
- ✅ Maintained existing direct integrations for Gradle and Maven
- ✅ 18 unit tests for BSP components
- ✅ Clean, idiomatic Kotlin implementation using kotlinx.serialization

---

## Problem Statement

### Current State

The Groovy LSP had direct integrations for:

- **Gradle** via Tooling API
- **Maven** via Maven Embedder

### Gap

No support for other popular JVM build tools:

- **Bazel** (increasingly popular for large monorepos)
- **sbt** (dominant in Scala ecosystem, but used for mixed Groovy/Scala projects)
- **Mill** (modern, fast Scala/Java build tool)

### Options Considered

| Option                   | Pros                                    | Cons                                |
| ------------------------ | --------------------------------------- | ----------------------------------- |
| Direct API for each tool | Full control, optimal performance       | High maintenance, N implementations |
| BSP client (chosen)      | Single protocol, reuse existing servers | Depends on external BSP servers     |
| Custom build server      | Full control                            | Massive effort, reinventing wheel   |

---

## Research & Analysis

### Build Server Protocol (BSP)

BSP is a protocol for communication between IDEs and build tools, similar to how LSP standardizes editor-language server
communication.

```
┌─────────────┐     BSP (JSON-RPC)     ┌─────────────┐
│  IDE/Editor │ ◄──────────────────────► │ Build Server │
│  (BSP Client)│                        │ (bazel-bsp)  │
└─────────────┘                        └─────────────┘
```

### Industry Analysis

#### Microsoft's Approach ([Build Server for Gradle](https://devblogs.microsoft.com/java/new-build-server-for-gradle/))

Microsoft created `build-server-for-gradle` - a dedicated BSP server that wraps Gradle's Tooling API. Key insights:

- BSP solves output directory consistency issues
- BSP enables better code generation support (annotation processing)
- Architecture: `IDE → BSP Client → BSP Server → Gradle Tooling API`

#### RedHat's VSCode Java ([Gradle Support Wiki](https://github.com/redhat-developer/vscode-java/wiki/gradle-support))

Uses Eclipse Buildship (direct Gradle Tooling API), not BSP. Known limitations:

- No Android support
- Limited build file validation
- Java-only compilation (no cross-language)

#### IntelliJ IDEA LSP Support

As of
[IntelliJ IDEA 2025.2](https://blog.jetbrains.com/platform/2025/09/the-lsp-api-is-now-available-to-all-intellij-idea-users-and-plugin-developers/),
LSP API is available to all plugin developers:

- `ProjectWideLspServerDescriptor` for defining LSP servers
- `LspServerSupportProvider` for initiating LSP servers
- Simplifies distribution: single LSP server can serve VSCode, IntelliJ, and other editors

### BSP Ecosystem

| Build Tool | BSP Server                                                                                  | Maturity   |
| ---------- | ------------------------------------------------------------------------------------------- | ---------- |
| Bazel      | [bazel-bsp](https://github.com/JetBrains/bazel-bsp) (JetBrains)                             | Production |
| sbt        | Built-in (sbt 1.4+)                                                                         | Production |
| Mill       | Built-in                                                                                    | Production |
| Gradle     | [build-server-for-gradle](https://github.com/microsoft/build-server-for-gradle) (Microsoft) | Preview    |
| Maven      | None mature                                                                                 | N/A        |

---

## Architecture Decision

### Chosen Approach: Hybrid Strategy

```
               ┌─────────────────────────────────────┐
               │        BuildToolManager             │
               │   (Detection Order)                 │
               └─────────────────────────────────────┘
                               │
         ┌─────────────────────┼─────────────────────┐
         ▼                     ▼                     ▼
┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐
│  BspBuildTool   │   │ GradleBuildTool │   │ MavenBuildTool  │
│  (NEW)          │   │ (Existing)      │   │ (Existing)      │
└─────────────────┘   └─────────────────┘   └─────────────────┘
         │                     │                     │
         ▼                     ▼                     ▼
┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐
│   BSP Server    │   │  Gradle         │   │  Maven          │
│ (bazel-bsp,sbt) │   │  Tooling API    │   │  Embedder       │
└─────────────────┘   └─────────────────┘   └─────────────────┘
```

### Detection Priority

```kotlin
buildToolManager = BuildToolManager(
    listOf(
        BspBuildTool(),     // Check for .bsp/ directory first
        GradleBuildTool(),  // Then Gradle (build.gradle*)
        MavenBuildTool(),   // Then Maven (pom.xml)
    ),
)
```

### Rationale

| Build Tool | Strategy           | Rationale                                   |
| ---------- | ------------------ | ------------------------------------------- |
| **Gradle** | Direct Tooling API | Already implemented, mature, fast           |
| **Maven**  | Direct Embedder    | Already implemented, no BSP server exists   |
| **Bazel**  | BSP Client         | No direct API, bazel-bsp is mature          |
| **sbt**    | BSP Client         | Built-in BSP in sbt 1.4+, standard approach |
| **Mill**   | BSP Client         | Built-in BSP, no direct API                 |

---

## Implementation Details

### New Files

```
groovy-build-tool/src/main/kotlin/.../bsp/
├── BspConnectionDetails.kt   # Parse .bsp/*.json files
├── BspClient.kt              # JSON-RPC client using bsp4j
└── BspBuildTool.kt           # BuildTool interface implementation

groovy-build-tool/src/test/kotlin/.../bsp/
├── BspConnectionDetailsTest.kt  # 11 tests
└── BspBuildToolTest.kt          # 7 tests
```

### Dependencies Added

```toml
# gradle/libs.versions.toml
bsp4j = "2.1.1"
kotlin-serialization-json = "1.7.3"

# groovy-build-tool/build.gradle.kts
implementation(libs.bsp4j)
implementation(libs.kotlin.serialization.json)
```

### Key Components

#### 1. BspConnectionDetails

Parses BSP connection files from `.bsp/*.json`:

```kotlin
@Serializable
private data class BspConnectionFile(
    val name: String,
    val version: String,
    @SerialName("bspVersion") val bspVersion: String,
    val languages: List<String> = emptyList(),
    val argv: List<String>,
)

// Example .bsp/bazelbsp.json:
{
  "name": "Bazel BSP",
  "version": "3.2.0",
  "bspVersion": "2.1.0",
  "languages": ["scala", "java", "kotlin"],
  "argv": ["bazel-bsp"]
}
```

#### 2. BspClient

JSON-RPC client for BSP communication:

```kotlin
class BspClient(
    private val connection: BspConnectionDetails,
    private val workspaceRoot: Path,
) : Closeable {
    
    fun connect(onProgress: ((String) -> Unit)? = null): Boolean
    fun workspaceBuildTargets(): WorkspaceBuildTargetsResult?
    fun buildTargetSources(targetIds: List<BuildTargetIdentifier>): SourcesResult?
    fun buildTargetDependencyModules(targetIds: List<BuildTargetIdentifier>): DependencyModulesResult?
}
```

Key features:

- Spawns BSP server process from `argv`
- Captures stderr and logs via SLF4J
- Uses bsp4j for protocol handling
- Proper resource cleanup via `Closeable`

#### 3. BspBuildTool

Integrates BSP into the build tool detection:

```kotlin
class BspBuildTool : BuildTool {
    override val name: String = "BSP"

    override fun canHandle(workspaceRoot: Path): Boolean {
        val bspDir = workspaceRoot.resolve(".bsp")
        if (!bspDir.exists()) return false
        return BspConnectionDetails.findConnectionFiles(workspaceRoot).isNotEmpty()
    }

    override fun resolve(workspaceRoot: Path, onProgress: ((String) -> Unit)?): WorkspaceResolution {
        // Connect to BSP server, fetch targets, sources, and dependencies
    }
}
```

---

## Testing Strategy

### Unit Tests (18 total)

#### BspConnectionDetailsTest (11 tests)

- `parseJson parses valid bazel-bsp connection file`
- `parseJson parses valid sbt connection file`
- `parseJson parses valid mill connection file`
- `parseJson returns null for missing required fields`
- `parseJson returns null for empty argv`
- `parseJson handles empty languages array`
- `findConnectionFiles returns empty list when no bsp directory`
- `findConnectionFiles finds json files in bsp directory`
- `findFirst returns first valid connection`
- `findFirst returns null when no valid connections`
- `parse returns null for non-existent file`

#### BspBuildToolTest (7 tests)

- `name returns BSP`
- `canHandle returns false when no bsp directory exists`
- `canHandle returns false when bsp directory is empty`
- `canHandle returns true when valid bsp connection file exists`
- `canHandle returns false when bsp directory only has non-json files`
- `resolve returns empty resolution when no bsp connection`
- `resolve returns empty resolution when connection file is invalid`

### Coverage Strategy

- `BspConnectionDetails` and `BspBuildTool`: Full unit test coverage
- `BspClient`: Excluded from coverage (requires real BSP server for integration tests)

```kotlin
// groovy-build-tool/build.gradle.kts
kover {
    reports {
        filters {
            excludes {
                classes("*BspClient*")
            }
        }
    }
}
```

---

## PR Review & Iterations

### Round 1 - Initial Implementation

| Priority    | Issue                            | Resolution                              |
| ----------- | -------------------------------- | --------------------------------------- |
| 🔴 Critical | Regex JSON parsing is fragile    | Replaced with `kotlinx.serialization`   |
| 🟠 High     | `process!!` null-safety risk     | Use local variable `newProcess`         |
| 🟡 Medium   | Magic strings "artifacts", "uri" | Extracted to companion object constants |

### Round 2 - Additional Feedback

| Priority  | Issue                                  | Resolution                              |
| --------- | -------------------------------------- | --------------------------------------- |
| 🟡 Medium | Should check `dataKind` before parsing | Added `MAVEN_DATA_KIND` check           |
| 🟡 Medium | stderr should be logged via SLF4J      | Async stderr logging instead of INHERIT |

### Final Code Quality

```kotlin
// Constants for BSP data extraction
private companion object {
    private const val MAVEN_DATA_KIND = "maven"
    private const val ARTIFACTS_FIELD = "artifacts"
    private const val URI_FIELD = "uri"
}

// Robust dependency extraction with dataKind check
?.flatMap { module ->
    if (module.dataKind != MAVEN_DATA_KIND) {
        logger.debug("Skipping dependency module of kind '{}', expected '{}'", 
            module.dataKind, MAVEN_DATA_KIND)
        return@flatMap emptyList<String>()
    }
    // ... parse maven data
}

// Proper stderr handling
CompletableFuture.runAsync {
    newProcess.errorStream.bufferedReader().useLines { lines ->
        lines.forEach { logger.warn("[BSP stderr] {}", it) }
    }
}
```

---

## Future Considerations

### 1. Gradle Build Server Integration

> **Analysis Date**: December 21, 2025\
> **Status**: Investigated - Architecture incompatibility discovered\
> **References**: [Microsoft Build Server for Gradle](https://devblogs.microsoft.com/java/new-build-server-for-gradle/),
> [vscode-gradle](https://github.com/microsoft/vscode-gradle)

#### ⚠️ Critical Finding: No `.bsp/` Folder Created

**The build-server-for-gradle does NOT use standard BSP discovery!**

Unlike bazel-bsp, sbt, and Mill (which generate `.bsp/*.json` files), Microsoft's Gradle Build Server uses a **custom
architecture** with named pipes:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Microsoft's Actual Architecture                           │
│                                                                              │
│  ┌──────────────────┐      Named Pipe      ┌──────────────────┐             │
│  │ vscode-gradle    │◄───────────────────►│ BspProxy         │             │
│  │ (spawns server)  │                     │ (Node.js bridge) │             │
│  └──────────────────┘                     └────────┬─────────┘             │
│           │                                        │                        │
│           │ JAR spawn                              │ Named Pipe             │
│           ▼                                        ▼                        │
│  ┌──────────────────┐                     ┌──────────────────┐             │
│  │ build-server-for │                     │ JDT.LS Importer  │             │
│  │ gradle (JAR)     │                     │ (Eclipse plugin) │             │
│  └──────────────────┘                     └──────────────────┘             │
│                                                                              │
│  ❌ NO .bsp/gradle.json is created!                                         │
│  ❌ NOT compatible with standard BSP client discovery                       │
│  ❌ Requires JDT.LS (Java Language Server) integration                      │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Key code references from vscode-gradle:**

- `BspProxy.ts` - Node.js bridge creating named pipes
- `GradleBuildServerProjectImporter.java` - JDT.LS plugin connecting via pipes
- `BuildServerConnector.ts` - Named pipe server setup

#### Integration Options

| Option                       | Description                                                         | Effort | Recommendation                                                                        |
| ---------------------------- | ------------------------------------------------------------------- | ------ | ------------------------------------------------------------------------------------- |
| **A: Extension-Mediated**    | Our VSCode extension starts build server, passes pipe to groovy-lsp | Medium | ✅ Best short-term                                                                    |
| **B: Direct Named Pipe**     | groovy-lsp spawns build-server-for-gradle JAR and manages pipes     | High   | Consider long-term                                                                    |
| **C: Wait for Standard BSP** | Request Microsoft add `.bsp/gradle.json` generation                 | N/A    | Track [build-server-for-gradle](https://github.com/microsoft/build-server-for-gradle) |

#### Current Status: Feature Flag Implemented (Phase 1)

The `GradleBuildStrategy` feature flag is useful for:

- ✅ Bazel projects (via bazel-bsp)
- ✅ sbt projects (built-in BSP)
- ✅ Mill projects (built-in BSP)
- ❌ **NOT** for build-server-for-gradle (requires named pipe integration)

```kotlin
enum class GradleBuildStrategy {
    AUTO,           // Use BSP if .bsp/*.json exists (default)
    BSP_PREFERRED,  // Prefer BSP when available
    NATIVE_ONLY,    // Always use Gradle Tooling API, skip BSP
}
```

Configuration:

```json
{
  "groovy.gradle.buildStrategy": "auto"  // auto | bsp | native
}
```

### 2. Gradle Tooling API Improvements

**Learning from vscode-gradle's `GradleProjectModelBuilder`:**

| Feature                        | vscode-gradle              | groovy-lsp           | Priority |
| ------------------------------ | -------------------------- | -------------------- | -------- |
| Dependency tree (hierarchical) | ✅ `GradleDependencyNode`  | ❌ Flat `List<Path>` | Medium   |
| Plugin closure extraction      | ✅ For DSL completion      | ❌ Not implemented   | Low      |
| Task debugging metadata        | ✅ JavaExec/Test detection | ❌ Not needed        | N/A      |
| Source/Javadoc JARs            | ✅ Via artifact resolution | ✅ Via BSP           | Done     |

**Potential enhancement** - Add hierarchical dependency model:

```kotlin
sealed class DependencyNode {
    data class Project(val name: String, val children: List<DependencyNode>)
    data class Configuration(val name: String, val children: List<DependencyNode>)
    data class Artifact(val gav: String, val path: Path, val children: List<DependencyNode>)
}
```

### 3. Code Sharing (Kotlin Multiplatform vs JSON Schema)

For sharing types between the Kotlin LSP server and TypeScript VSCode extension:

| Approach        | Use Case              | Recommendation                    |
| --------------- | --------------------- | --------------------------------- |
| **JSON Schema** | Configuration files   | ✅ Use for `settings.json` schema |
| **Protobuf**    | Custom LSP extensions | Consider for complex data models  |
| **Kotlin/JS**   | Core type sharing     | Overkill for thin VSCode client   |

### 4. IntelliJ Plugin

With IntelliJ's new LSP API (2025.2+), distribution strategy:

```
┌─────────────────────────────────────────────────────────┐
│                 groovy-lsp (Server)                     │
│              Single Kotlin/JVM implementation           │
└─────────────────────────────────────────────────────────┘
                           │
        ┌──────────────────┼──────────────────┐
        ▼                  ▼                  ▼
┌───────────────┐  ┌───────────────┐  ┌───────────────┐
│ VSCode Plugin │  │IntelliJ Plugin│  │ Other Editors │
│  (TypeScript) │  │   (Kotlin)    │  │   (LSP)       │
└───────────────┘  └───────────────┘  └───────────────┘
```

### 5. Integration Tests

For full BSP client testing, consider:

- Docker-based integration tests with real BSP servers
- CI matrix testing against bazel-bsp, sbt, Mill
- Manual testing guide for developers

### 6. BSP Server Selection

Currently uses first valid connection. Future enhancement:

- User-configurable BSP server preference
- Multi-language workspace support (pick best server per language)

---

## References

### Specifications

- [Build Server Protocol](https://build-server-protocol.github.io/)
- [Language Server Protocol](https://microsoft.github.io/language-server-protocol/)

### Tools & Libraries

- [bsp4j](https://github.com/build-server-protocol/bsp4j) - Java BSP implementation
- [bazel-bsp](https://github.com/JetBrains/bazel-bsp) - JetBrains' BSP server for Bazel
- [build-server-for-gradle](https://github.com/microsoft/build-server-for-gradle) - Microsoft's BSP server

### Articles

- [Microsoft: New Build Server for Gradle](https://devblogs.microsoft.com/java/new-build-server-for-gradle/)
- [JetBrains: LSP API for IntelliJ](https://blog.jetbrains.com/platform/2025/09/the-lsp-api-is-now-available-to-all-intellij-idea-users-and-plugin-developers/)
- [RedHat: VSCode Java Gradle Support](https://github.com/redhat-developer/vscode-java/wiki/gradle-support)

---

## Appendix: Commit History

```
feat: add BSP client support for Bazel, sbt, and Mill
├── Add bsp4j 2.1.1 dependency
├── Create BspConnectionDetails, BspClient, BspBuildTool
├── Prioritize BSP detection in BuildToolManager
└── Add 18 unit tests

fix: address PR review feedback for BSP client
├── Replace regex JSON parsing with kotlinx.serialization
├── Fix null-safety: use local variable instead of !!
├── Extract magic strings to companion object constants
└── Add kotlin-serialization plugin

fix: address additional PR review feedback
├── Check module.dataKind for 'maven' before parsing
└── Capture BSP server stderr and log via SLF4J
```
