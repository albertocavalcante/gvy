# Jenkinsfile First-Class Support - Complete Technical Specification

**Version**: 1.0 **Date**: 2025-12-03 **Status**: Approved - Implementation in Progress **Author**: Development Team +
Architectural Review Board

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Problem Statement](#problem-statement)
3. [Requirements Analysis](#requirements-analysis)
4. [Architectural Showstoppers & Solutions](#architectural-showstoppers--solutions)
5. [Implementation Phases](#implementation-phases)
6. [Technical Decisions](#technical-decisions)
7. [Success Criteria](#success-criteria)
8. [Beta Testing Strategy](#beta-testing-strategy)
9. [Timeline & Milestones](#timeline--milestones)
10. [Risk Assessment](#risk-assessment)
11. [References](#references)

---

## Executive Summary

This specification defines a comprehensive strategy to achieve first-class IDE support for Jenkinsfiles in the Groovy
LSP. The implementation follows a phased approach over 13-20 weeks, balancing completeness with pragmatic UX delivery.

### Key Goals

1. **Zero-Configuration Experience**: Works immediately with bundled Jenkins SDK stubs
2. **Intelligent Completions**: Named parameters, Map key inference, closure-aware suggestions
3. **Remote Library Support**: Automatic Git cloning of shared libraries
4. **Plugin Awareness**: Support for Jenkins controller plugins via metadata dump
5. **Production Quality**: Comprehensive testing, diagnostics, and performance optimization

### Solution Architecture

**Three-Pillar Approach**:

1. **Bundled Stubs**: Jenkins SDK JAR with top 10-20 plugins (Phase 0)
2. **Controller Metadata**: JSON dump from user's Jenkins instance (Phase 2.5)
3. **Git Cache**: Auto-clone shared libraries to local cache (Phase 1.5)

### Architectural Revisions

This plan addresses two critical showstoppers identified during architectural review:

1. **The "Where are the JARs?" Problem** - Users don't have plugin JARs locally
2. **The Remote Shared Library Problem** - `@Library` references aren't cloned locally

---

## Problem Statement

### Current State

The Groovy LSP has basic completion support but lacks Jenkins-specific intelligence:

- ❌ No named parameter completion (`library(identifier:|`)
- ❌ No plugin step completion (`sh`, `docker.image()`, etc.)
- ❌ No shared library global variable completion (`vars/` directory)
- ❌ No awareness of declarative vs scripted pipeline modes
- ❌ No CPS blacklist warnings
- ❌ Manual JAR management required (unrealistic UX)

### Target Jenkinsfile Patterns

From real-world analysis, typical Jenkinsfiles use:

```groovy
#!groovy
@Library('globalLibrary@main') _

properties([buildDiscarder(logRotator(numToKeepStr: '15'))])

library identifier: "shared-lib@main",
        retriever: modernSCM(
            $class: 'GitSCMSource',
            remote: "https://github.com/org/repo.git",
            credentialsId: "CREDENTIALS_ID"
        )

pipeline_step {
    emailAddresses = "ci.noreply@example.invalid"
}
```

**Critical patterns to support**:

1. `@Library('name@version') _` - versioned library imports
2. Deeply nested named parameters
3. Closure-based DSL blocks with property assignments
4. Global methods from shared libraries
5. Jenkins built-in steps and properties

---

## Requirements Analysis

### Critical Gaps

#### 1. Named Parameter Completion (CRITICAL)

**Current State**: Named parameters are parsed but not extracted for completion.

**Impact**: Cannot complete:

- `library(identifier: "...", retriever: modernSCM(...))`
- `buildDiscarder(logRotator(numToKeepStr: '15'))`

**Requirements**:

- Extract parameter names from method signatures
- Support Map-based methods (`def step(Map args)`)
- Filter already-specified parameters
- Provide type hints and documentation

#### 2. Library Symbol Indexing (CRITICAL)

**Current State**: Jenkins libraries added to classpath but symbols not indexed.

**Impact**: Cannot complete custom steps from shared libraries or `vars/` global variables.

**Requirements**:

- Index symbols from library JARs
- Scan `vars/` directory for global variables
- Extract `call()` method signatures
- Support both local and remote libraries

#### 3. Plugin Awareness (CRITICAL)

**Current State**: No awareness of Jenkins plugins.

**Impact**: Cannot complete plugin steps like `sh`, `docker.image()`, `kubernetes {}`.

**Requirements**:

- Bundle metadata for top 10-20 plugins
- Support controller-specific metadata
- Provide accurate parameter completions
- Validate plugin dependencies

#### 4. GDSL Integration (HIGH PRIORITY)

**Current State**: GDSL loading exists but no completion integration.

**Requirements**:

- Store GDSL metadata in memory
- Query by context (file type, scope, qualifier)
- Support `delegatesTo` hints
- Enrich completions with GDSL data

#### 5. Closure Context Inference (MEDIUM PRIORITY)

**Current State**: Minimal closure delegate type inference.

**Requirements**:

- Detect declarative vs scripted modes
- Infer delegate types for closures
- Provide context-aware completions
- Support structural validation (declarative)

#### 6. Global Variables (HIGH PRIORITY)

**Current State**: No global variable support.

**Requirements**:

- Support `env`, `params`, `currentBuild`
- Index library `vars/` as globals
- Support plugin-provided globals (`docker`, `kubernetes`)

---

## Architectural Showstoppers & Solutions

### Showstopper 1: The "Where are the JARs?" Problem

#### Problem Description

Most Jenkins developers:

- Don't have plugin `.hpi` or `.jar` files locally
- Won't manually download 50+ plugin files
- Work from a Jenkinsfile in Git, not a Jenkins development environment

**Impact**: Without JARs, `LibrarySymbolExtractor` has nothing to scan.

#### Solution: Multi-Pronged Approach

##### 1. Bundled Jenkins SDK Stubs (Phase 0)

**Concept**: Bundle a `jenkins-lsp-sdk.jar` with stubs for top 20 plugins.

**Implementation**:

- Stubs = interfaces/classes with signatures, empty bodies
- Include `@StepDescriptor` annotations
- Preserve parameter names via ASM
- Bundle `jenkins-stubs-metadata.json` with parameter details

**Plugins to Include**:

- Core: `workflow-api`, `workflow-cps`, `workflow-basic-steps`, `workflow-durable-task-step`
- SCM: `git`, `scm-api`, `github`, `github-branch-source`
- Containers: `docker-workflow`, `docker-commons`, `kubernetes`, `kubernetes-credentials`
- Pipeline: `pipeline-model-definition`, `pipeline-model-api`, `pipeline-stage-view`
- Utilities: `credentials`, `credentials-binding`, `ssh-agent`, `timestamper`

**Metadata JSON Format**:

```json
{
  "steps": {
    "sh": {
      "name": "sh",
      "plugin": "workflow-durable-task-step:1400.v7fd76b_8b_6b_9a",
      "parameters": {
        "script": {
          "name": "script",
          "type": "String",
          "required": true,
          "documentation": "Shell script to execute"
        },
        "returnStdout": {
          "name": "returnStdout",
          "type": "boolean",
          "required": false,
          "default": "false",
          "documentation": "Return standard output"
        }
      },
      "documentation": "Execute a shell command"
    }
  },
  "globalVars": {
    "env": {
      "name": "env",
      "type": "org.jenkinsci.plugins.workflow.cps.EnvActionImpl",
      "documentation": "Environment variables"
    }
  }
}
```

##### 2. JSON Metadata Dump from Controller (Phase 2.5)

**Concept**: Users run a Groovy script on their Jenkins controller to generate `jenkins-lsp-metadata.json`.

**Script Capabilities**:

- Introspect `Jenkins.instance.pluginManager.plugins`
- Extract all steps via `StepDescriptor.all()`
- Extract global variables via `GlobalVariable.ALL`
- Generate JSON with 100% accuracy to user's instance

**Benefits**:

- No JAR parsing required
- Works with custom/proprietary plugins
- Version-specific metadata
- One-time setup

**Output Format**:

```json
{
  "jenkinsVersion": "2.414.3",
  "plugins": [
    {
      "name": "docker-workflow",
      "version": "580.vc0c340686b_54",
      "steps": [
        {
          "name": "docker.image",
          "className": "org.jenkinsci.plugins.docker.workflow.Docker",
          "parameters": {
            "name": {"type": "String", "required": true, "doc": "Image name"}
          },
          "doc": "Creates a Docker image DSL object"
        }
      ],
      "globalVars": [
        {
          "name": "docker",
          "className": "org.jenkinsci.plugins.docker.workflow.Docker",
          "methods": ["image", "build", "withRegistry"]
        }
      ]
    }
  ]
}
```

##### 3. Virtual Classpath

**Concept**: LSP uses bundled stubs by default, overlays controller metadata if configured.

**Loading Strategy**:

1. Always load bundled stubs (baseline)
2. Overlay controller metadata (if configured)
3. Controller metadata takes precedence
4. Merge gracefully with warnings for conflicts

**User Experience**:

- Works immediately (bundled stubs)
- Optional enhancement (controller metadata)
- No JAR management

### Showstopper 2: The Remote Shared Library Problem

#### Problem Description

`@Library('globalLibrary@main')` references:

- Code in Git repository
- Not locally cloned
- Cannot index `vars/` directory without local copy

**Impact**: Missing completions for custom pipeline steps from shared libraries.

#### Solution: Automatic Library Cache (Phase 1.5)

##### 1. Library Reference Parser

**Extract from Jenkinsfile**:

- `@Library('name@version')` annotations
- `library(...)` method calls with `retriever:` parameter
- Git URL from `modernSCM(remote: "...")`

**Parse Result**:

```kotlin
data class LibraryReference(
    val name: String,
    val version: String,
    val scmUrl: String? = null
)
```

##### 2. Git Clone Cache Manager

**Cache Structure**:

```
~/.groovy-lsp/cache/
  libraries/
    shared-library/
      main/
        .git/
        vars/
        src/
        .groovy-lsp-meta.json
      v1.0/
        ...
```

**Clone Strategy**:

- Shallow clone: `git clone --depth 1 --branch <version> <url>`
- Semantic versions → use Git tags
- SHA versions → checkout specific commit
- Named branches → clone branch

**Cache Metadata** (`.groovy-lsp-meta.json`):

```json
{
  "cloneTimestamp": "2025-12-03T10:30:00Z",
  "sourceUrl": "https://github.com/example/lib.git",
  "version": "main",
  "contentHash": "sha256:abc123..."
}
```

**TTL & Refresh**:

- Default TTL: 24 hours
- Auto-refresh on stale cache
- Manual command: `groovy.refreshLibraryCache`
- Incremental update: `git fetch && git reset --hard`

##### 3. Configuration Mapping

**User Config** (`settings.json`):

```json
{
  "groovy.jenkins.libraries": {
    "shared-library": {
      "scm": "https://github.com/example/shared-library.git",
      "defaultBranch": "main"
    }
  }
}
```

**Resolution Priority**:

1. SCM URL from `library(retriever:)` call
2. User configuration mapping
3. Fail with warning, prompt configuration

##### 4. Error Handling

**Network Unavailable**:

- Use stale cache with warning
- Don't fail LSP initialization

**Clone Failure**:

- Skip library, log error
- Show notification to user
- Continue with other libraries

**No SCM Config**:

- Prompt: "Configure SCM URL for library 'X'?"
- Provide quick fix command

**UX Win Over IntelliJ**: IntelliJ doesn't auto-clone libraries. This seamless experience is a competitive advantage.

---

## Implementation Phases

### Phase 0: Prerequisites (1 week)

**Goal**: Bundle Jenkins SDK stubs to avoid "Where are the JARs?" problem.

#### 0.1 Create Jenkins SDK Stub JAR

**Deliverables**:

- `jenkins-lsp-sdk.jar` with stub classes
- `jenkins-stubs-metadata.json` with parameter data
- Gradle task: `generateJenkinsStubs`

**Implementation Details**:

**Stub Generation**:

```kotlin
// StubGenerator.kt
class StubGenerator {
    fun generateStub(plugin: PluginDescriptor): StubClass {
        // Extract public API from plugin JAR
        // Generate stub with empty bodies
        // Preserve parameter names via ASM
    }
}
```

**Metadata Extraction**:

- Parse `@StepDescriptor` annotations
- Extract parameter types from signatures
- Include JavaDoc/GroovyDoc as documentation

**Files to Create**:

- `groovy-jenkins/build-tools/StubGenerator.kt`
- `groovy-jenkins/src/main/resources/jenkins-lsp-sdk.jar` (generated)
- `groovy-jenkins/src/main/resources/jenkins-stubs-metadata.json` (generated)

**Testing**:

- Verify JAR is loadable
- Validate JSON schema
- Test metadata loading

### Phase 1: Foundation Infrastructure (2-3 weeks)

**Goal**: Build plumbing for all subsequent features + handle architectural showstoppers.

#### 1.1 Map Key Inference System (CRITICAL FIX)

**Problem**: Jenkins uses `def step(Map args)` heavily. ASM extracts `args`, not the valid map keys.

**Solution**: Map Key Metadata Resolver

**Implementation**:

1. **Map Parameter Detector**

```kotlin
fun isMapBasedMethod(method: Method): Boolean {
    val firstParam = method.parameters.firstOrNull()
    return firstParam?.type in listOf(
        Map::class.java,
        LinkedHashMap::class.java,
        "java.util.Map"
    )
}
```

2. **Map Key Metadata Source Priority**
   - Primary: Bundled `jenkins-stubs-metadata.json`
   - Secondary: User `jenkins-lsp-metadata.json`
   - Tertiary: GDSL definitions
   - Fallback: Empty (no hints)

3. **Map Key Completion Provider**

```kotlin
fun provideMapKeyCompletions(
    methodName: String,
    alreadySpecified: Set<String>
): List<CompletionItem> {
    val stepMetadata = metadataResolver.getStep(methodName) ?: return emptyList()
    val availableKeys = stepMetadata.parameters.keys - alreadySpecified

    return availableKeys.map { key ->
        val param = stepMetadata.parameters[key]!!
        CompletionItem(
            label = "$key:",
            kind = CompletionItemKind.Property,
            detail = param.type,
            documentation = param.documentation
        )
    }
}
```

4. **Singleton Map Pattern Handling**
   - `echo "hello"` (positional) vs `echo(message: "hello")` (named)
   - Offer both patterns in completion

**Files**:

- `groovy-parser/.../metadata/MapKeyMetadataResolver.kt`
- `groovy-lsp/.../providers/completion/MapKeyCompletionProvider.kt`
- `groovy-parser/.../metadata/ParameterMetadataExtractor.kt`

**Tests**:

```kotlin
@Test
fun `should complete map keys for git step`() {
    val completions = provider.getCompletions("git(ur|")
    assertContains(completions, "url:")
    assertContains(completions, "branch:")
}

@Test
fun `should exclude already specified keys`() {
    val completions = provider.getCompletions("git(url: '...', br|")
    assertContains(completions, "branch:")
    assertNotContains(completions, "url:")
}
```

#### 1.2 ASM-Based Parameter Extraction

**Goal**: Extract parameter names from compiled bytecode.

**Implementation**:

```kotlin
class AsmParameterExtractor {
    fun extractParameters(method: Method): List<ParameterInfo> {
        val classReader = ClassReader(method.declaringClass.name)
        val visitor = ParameterVisitor(method.name)
        classReader.accept(visitor, 0)
        return visitor.parameters
    }

    private class ParameterVisitor(val targetMethod: String) : ClassVisitor(ASM9) {
        val parameters = mutableListOf<ParameterInfo>()

        override fun visitMethod(...): MethodVisitor {
            return object : MethodVisitor(ASM9) {
                override fun visitLocalVariable(name: String, desc: String, ...) {
                    // Extract from LocalVariableTable
                    if (name != "this") {
                        parameters.add(ParameterInfo(name, desc))
                    }
                }
            }
        }
    }
}
```

**Fallback Strategy**:

1. ASM (LocalVariableTable) - Primary
2. Java Parameter API - Secondary
3. `arg0`, `arg1` - Last resort

**Files**:

- `groovy-parser/.../metadata/AsmParameterExtractor.kt`
- `groovy-parser/.../metadata/ParameterMetadataExtractor.kt`

#### 1.3 Non-Blocking Initialization (CRITICAL FIX)

**Problem**: Original plan blocked LSP initialization - causes VS Code timeout.

**Solution**: Progressive Enhancement

**Implementation**:

```kotlin
override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
    // IMMEDIATE return, DO NOT BLOCK
    return CompletableFuture.completedFuture(
        InitializeResult(ServerCapabilities().apply {
            completionProvider = CompletionOptions(true, listOf(".", ":"))
            // ... other capabilities
        })
    )
}

override fun initialized(params: InitializedParams) {
    // Start async indexing HERE
    coroutineScope.launch {
        // Phase 1: Index bundled stubs (fast, ~100ms)
        val bundledProgress = createProgress("Indexing bundled stubs...")
        indexBundledStubs()
        bundledProgress.done()

        // Phase 2: Index user libraries (slower, ~1-2s)
        val libProgress = createProgress("Indexing libraries...")
        indexUserLibraries()
        libProgress.done()

        // Phase 3: Clone and index remote libraries (slowest, ~5-10s)
        val remoteProgress = createProgress("Cloning remote libraries...")
        indexRemoteLibraries()
        remoteProgress.done()
    }
}
```

**Progressive Completion Enhancement**:

- T=0ms: Basic keyword completions
- T=100ms: Jenkins core step completions (bundled stubs)
- T=1-2s: Shared library completions (user libraries)
- T=5-10s: Full completions (remote libraries)

**Loading Indicator**:

```kotlin
while (indexingInProgress) {
    completions.add(CompletionItem(
        label = "⏳ Indexing...",
        kind = CompletionItemKind.Event,
        documentation = "Jenkins completions are still loading"
    ))
}
```

**Files**:

- `groovy-lsp/.../GroovyLanguageServer.kt`
- `groovy-lsp/.../indexing/SymbolIndexingService.kt`
- `groovy-lsp/.../indexing/ProgressReporter.kt`

#### 1.4 GDSL Metadata Storage

**Goal**: Connect GDSL loading infrastructure to completion system.

**Implementation**:

```kotlin
class GdslMetadataStore {
    private val methods = ConcurrentHashMap<ContextKey, List<GdslMethod>>()
    private val properties = ConcurrentHashMap<ContextKey, List<GdslProperty>>()

    fun storeMethod(context: ContextKey, method: GdslMethod) {
        methods.compute(context) { _, existing ->
            (existing ?: emptyList()) + method
        }
    }

    fun queryMethods(context: ContextKey): List<GdslMethod> {
        return methods[context] ?: emptyList()
    }
}

data class ContextKey(
    val fileType: String,
    val scopeType: String,
    val qualifierType: String?
)
```

**Files**:

- `groovy-gdsl/.../GdslMetadataStore.kt`
- `groovy-lsp/.../providers/completion/GdslCompletionProvider.kt`
- `groovy-gdsl/.../GdslContributor.kt`

#### 1.5 Sample Jenkinsfile Test Resource

**Goal**: Validate infrastructure with real patterns.

**Sample Content**:

```groovy
#!groovy
@Library('shared-library@1.0') _

properties([
    buildDiscarder(logRotator(numToKeepStr: '15'))
])

library identifier: "common-pipeline@main",
        retriever: modernSCM(
            $class: 'GitSCMSource',
            remote: "https://github.com/example/pipeline-lib.git",
            credentialsId: "GITHUB_CREDENTIALS"
        )

customPipelineStep {
    emailAddresses = "ci.noreply@example.invalid"
}
```

**Files**:

- `groovy-jenkins/src/test/resources/sample-jenkinsfile.groovy`
- `groovy-jenkins/src/test/kotlin/.../SampleJenkinsfileCompletionTest.kt`

**Phase 1 Deliverables**:

- ✅ Map key inference for Jenkins steps
- ✅ ASM-based parameter extraction
- ✅ Non-blocking LSP initialization
- ✅ GDSL metadata storage
- ✅ Sample test resource

### Phase 1.5: Remote Library Retrieval (1-2 weeks)

**Goal**: Auto-clone shared libraries for `vars/` indexing.

#### 1.5.1 Library Reference Resolution

**Implementation**:

```kotlin
data class LibraryReference(
    val name: String,
    val version: String,
    val scmUrl: String? = null
)

class LibraryResolver(private val config: LibraryConfiguration) {
    fun resolve(reference: LibraryReference): ResolvedLibrary? {
        // Priority 1: SCM URL in reference
        val scmUrl = reference.scmUrl
            // Priority 2: User configuration
            ?: config.libraries[reference.name]?.scm
            // Priority 3: Fail
            ?: return null

        return ResolvedLibrary(
            name = reference.name,
            version = reference.version,
            scmUrl = scmUrl
        )
    }
}
```

**Files**:

- `groovy-jenkins/.../LibraryParser.kt`
- `groovy-jenkins/.../LibraryResolver.kt`
- `groovy-jenkins/.../LibraryConfiguration.kt`

#### 1.5.2 Git Clone Cache Manager

**Implementation**:

```kotlin
class GitCloneManager(private val cacheRoot: Path) {
    suspend fun cloneOrUpdate(library: ResolvedLibrary): Path {
        val cachePath = cacheRoot / library.name / library.version

        if (cachePath.exists()) {
            // Check if stale
            val meta = loadMetadata(cachePath)
            if (meta.isStale()) {
                updateCache(cachePath, library)
            }
            return cachePath
        } else {
            // Fresh clone
            cloneLibrary(library, cachePath)
            return cachePath
        }
    }

    private suspend fun cloneLibrary(library: ResolvedLibrary, target: Path) {
        val command = listOf(
            "git", "clone",
            "--depth", "1",
            "--branch", library.version,
            library.scmUrl,
            target.toString()
        )
        executeCommand(command)
        saveMetadata(target, library)
    }
}
```

**Cache Metadata**:

```kotlin
data class LibraryCacheMetadata(
    val cloneTimestamp: Instant,
    val sourceUrl: String,
    val version: String,
    val contentHash: String
) {
    fun isStale(ttl: Duration = 24.hours): Boolean {
        return Instant.now() - cloneTimestamp > ttl
    }
}
```

**Files**:

- `groovy-jenkins/.../GitCloneManager.kt`
- `groovy-jenkins/.../LibraryCacheManager.kt`
- `groovy-jenkins/.../LibraryCacheMetadata.kt`

#### 1.5.3 Library Symbol Indexing

**Vars Directory Scanning**:

```kotlin
class LibraryVarsScanner {
    fun scanVars(libraryPath: Path): List<GlobalVariableMetadata> {
        val varsDir = libraryPath / "vars"
        if (!varsDir.exists()) return emptyList()

        return varsDir.listFiles { it.extension == "groovy" }
            .map { file ->
                val varName = file.nameWithoutExtension
                val callSignature = extractCallSignature(file)

                GlobalVariableMetadata(
                    name = varName,
                    type = "groovy.lang.Closure",
                    signature = callSignature,
                    documentation = extractDoc(file)
                )
            }
    }

    private fun extractCallSignature(file: File): String {
        // Parse file, find def call(...) method
        val ast = parser.parse(file.readText())
        val callMethod = ast.methods.find { it.name == "call" }
        return callMethod?.signature ?: "()"
    }
}
```

**Example**:

```groovy
// vars/buildJava.groovy
def call(Map config) {
    // Implementation
}
```

→ Creates global: `buildJava(config: [...])`

**Files**:

- `groovy-jenkins/.../LibraryVarsScanner.kt`
- `groovy-jenkins/.../GlobalVariableIndex.kt`
- `groovy-lsp/.../providers/completion/CompletionProvider.kt`

#### 1.5.4 User Experience

**Commands**:

- `groovy.refreshLibraryCache` - Manual refresh all
- `groovy.clearLibraryCache` - Clear cache
- `groovy.configureLibrary` - Add library SCM URL

**Notifications**:

- On first `@Library`: "Configure SCM URL for auto-completion?"
- On clone failure: "Failed to clone library 'X': <error>"
- On success: "Indexed library 'X' (5 global variables found)"

**Files**:

- `groovy-lsp/.../commands/LibraryCacheCommands.kt`

**Phase 1.5 Deliverables**:

- ✅ Automatic Git clone
- ✅ Library cache management
- ✅ Vars directory scanning
- ✅ **UX win over IntelliJ**

### Phase 2: Core Jenkins Features (2-3 weeks)

**Goal**: Essential Jenkins-specific completions.

#### 2.1 Library Symbol Indexing

**JAR Symbol Extraction**:

```kotlin
class LibrarySymbolExtractor {
    fun extractSymbols(jarPath: Path): LibrarySymbols {
        val scanResult = ClassGraph()
            .overrideClasspath(jarPath)
            .enableAllInfo()
            .scan()

        val classes = scanResult.allClasses.map { classInfo ->
            ClassSymbol(
                name = classInfo.simpleName,
                fullName = classInfo.name,
                methods = classInfo.methodInfo.map { extractMethod(it) },
                fields = classInfo.fieldInfo.map { extractField(it) }
            )
        }

        return LibrarySymbols(classes)
    }
}
```

**Files**:

- `groovy-jenkins/.../LibrarySymbolExtractor.kt`
- `groovy-jenkins/.../GlobalVariableIndex.kt`
- `groovy-jenkins/.../JenkinsContext.kt`

#### 2.2 Built-in Jenkins DSL Completions

**Hardcoded Step Metadata**:

```kotlin
object JenkinsDslProvider {
    val builtInSteps = mapOf(
        "echo" to JenkinsStepMetadata(
            name = "echo",
            plugin = "workflow-basic-steps",
            parameters = mapOf(
                "message" to StepParameter(
                    name = "message",
                    type = "String",
                    required = true
                )
            )
        ),
        "error" to JenkinsStepMetadata(
            name = "error",
            plugin = "workflow-basic-steps",
            parameters = mapOf(
                "message" to StepParameter(
                    name = "message",
                    type = "String",
                    required = true
                )
            )
        )
        // ... more steps
    )
}
```

**Files**:

- `groovy-jenkins/.../JenkinsDslProvider.kt`
- `groovy-jenkins/src/main/resources/jenkins-dsl-metadata.json`

#### 2.3 Declarative vs Scripted Pipeline Modes

**Mode Detection**:

```kotlin
class PipelineModeDetector {
    fun detectMode(source: String): PipelineMode {
        val ast = parser.parse(source)

        // Declarative: starts with `pipeline {`
        if (ast.statements.any { it is MethodCall && it.methodName == "pipeline" }) {
            return PipelineMode.DECLARATIVE
        }

        // Scripted: uses `node {`, `stage {}`
        if (ast.statements.any { it is MethodCall && it.methodName in listOf("node", "stage") }) {
            return PipelineMode.SCRIPTED
        }

        return PipelineMode.UNKNOWN
    }
}
```

**Declarative Schema**:

```kotlin
object DeclarativePipelineSchema {
    val pipelineBlock = BlockSchema(
        allowedChildren = setOf(
            "agent", "stages", "options", "environment",
            "parameters", "triggers", "tools", "post"
        )
    )

    val stageBlock = BlockSchema(
        allowedChildren = setOf(
            "steps", "agent", "environment", "when", "post"
        )
    )

    fun validateContext(blockName: String, cursor: String): Set<String> {
        return when (blockName) {
            "pipeline" -> pipelineBlock.allowedChildren
            "stage" -> stageBlock.allowedChildren
            else -> emptySet()
        }
    }
}
```

**Files**:

- `groovy-jenkins/.../DeclarativePipelineSchema.kt`
- `groovy-jenkins/.../PipelineModeDetector.kt`
- `groovy-jenkins/.../DeclarativeCompletionProvider.kt`

#### 2.4 CPS Blacklist Diagnostics

**Blacklist Definition**:

```kotlin
object CpsBlacklist {
    val blacklistedClasses = setOf(
        "java.io.File",
        "java.io.FileInputStream",
        "java.io.FileOutputStream",
        "java.io.FileReader",
        "java.io.FileWriter"
    )

    val blacklistedMethods = setOf(
        "java.lang.System.exit",
        "java.lang.Runtime.exec",
        "java.lang.Thread.sleep"
    )

    val alternatives = mapOf(
        "java.io.File" to "Use readFile(), writeFile(), or fileExists()",
        "java.lang.System.exit" to "Use error() or currentBuild.result = 'FAILURE'"
    )
}
```

**Diagnostic Provider**:

```kotlin
class CpsBlacklistProvider : DiagnosticProvider {
    override fun provideDiagnostics(uri: URI, ast: ASTNode): List<Diagnostic> {
        val violations = mutableListOf<Diagnostic>()

        ast.visit(object : ClassCodeVisitorSupport() {
            override fun visitConstructorCallExpression(call: ConstructorCallExpression) {
                if (call.type.name in CpsBlacklist.blacklistedClasses) {
                    violations.add(createDiagnostic(call))
                }
                super.visitConstructorCallExpression(call)
            }
        })

        return violations
    }
}
```

**Files**:

- `groovy-jenkins/.../diagnostics/CpsBlacklistProvider.kt`
- `groovy-jenkins/.../CpsBlacklist.kt`
- `groovy-lsp/.../services/DiagnosticsService.kt`

**Phase 2 Deliverables**:

- ✅ Library symbol completion
- ✅ Global variable completion
- ✅ Built-in step completion
- ✅ Declarative/Scripted mode detection
- ✅ CPS diagnostics

### Phase 2.5: Plugin Awareness (2-3 weeks)

**Goal**: Controller-specific plugin metadata without requiring JARs.

#### 2.5.1 Controller Metadata Dump Script

**Script Template** (`docs/jenkins-metadata-dumper.groovy`):

```groovy
// Run in Jenkins Script Console
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.steps.*
import org.jenkinsci.plugins.workflow.cps.GlobalVariable

def jenkins = Jenkins.instance
def metadata = [
    jenkinsVersion: jenkins.version,
    plugins: []
]

// Extract plugins
jenkins.pluginManager.plugins.each { plugin ->
    def pluginMeta = [
        name: plugin.shortName,
        version: plugin.version,
        steps: [],
        globalVars: []
    ]

    // Extract steps
    StepDescriptor.all().findAll { it.pluginName == plugin.shortName }.each { step ->
        pluginMeta.steps << [
            name: step.functionName,
            className: step.class.name,
            parameters: extractParameters(step),
            doc: step.displayName
        ]
    }

    // Extract global variables
    GlobalVariable.all().findAll { it.pluginName == plugin.shortName }.each { gv ->
        pluginMeta.globalVars << [
            name: gv.name,
            className: gv.class.name,
            doc: gv.description
        ]
    }

    metadata.plugins << pluginMeta
}

return groovy.json.JsonOutput.toJson(metadata)
```

**Distribution**:

- Bundle script in LSP docs
- VSCode command: `groovy.generateJenkinsMetadataScript`
- Instructions in README

**Files**:

- `docs/jenkins-metadata-dumper.groovy`
- `groovy-jenkins/.../ControllerMetadataLoader.kt`

#### 2.5.2 Metadata Integration

**Loading & Merging**:

```kotlin
class MetadataMerger {
    fun merge(
        bundled: BundledJenkinsMetadata,
        controller: ControllerMetadata?
    ): MergedMetadata {
        if (controller == null) return MergedMetadata(bundled.steps, bundled.globalVars)

        // Controller metadata wins for conflicts
        val mergedSteps = bundled.steps + controller.steps
        val mergedGlobals = bundled.globalVars + controller.globalVars

        return MergedMetadata(mergedSteps, mergedGlobals)
    }
}
```

**Configuration**:

```json
{
  "groovy.jenkins.controllerMetadata": "/path/to/jenkins-metadata.json"
}
```

**Files**:

- `groovy-jenkins/.../MetadataMerger.kt`
- `groovy-jenkins/.../JenkinsContext.kt`

#### 2.5.3 Plugin Step Indexing

**Step Extractor**:

```kotlin
class PluginStepExtractor {
    fun extractSteps(metadata: ControllerMetadata): Map<String, PluginStep> {
        return metadata.plugins.flatMap { plugin ->
            plugin.steps.map { step ->
                step.name to PluginStep(
                    name = step.name,
                    plugin = plugin.name,
                    version = plugin.version,
                    parameters = step.parameters.map { (name, param) ->
                        StepParameter(
                            name = name,
                            type = param.type,
                            required = param.required,
                            defaultValue = param.default,
                            documentation = param.doc
                        )
                    },
                    documentation = step.doc
                )
            }
        }.toMap()
    }
}
```

**Files**:

- `groovy-jenkins/.../PluginStepExtractor.kt`
- `groovy-jenkins/.../PluginGlobalVarExtractor.kt`
- `groovy-jenkins/.../JenkinsDslProvider.kt`

#### 2.5.4 Plugin-Aware Diagnostics

**Step Validation**:

```kotlin
class PluginStepDiagnosticProvider {
    fun validate(stepUsage: MethodCallExpression): Diagnostic? {
        val stepName = stepUsage.methodAsString
        val stepMeta = metadataStore.getStep(stepName)

        if (stepMeta == null) {
            return Diagnostic(
                range = stepUsage.range,
                severity = DiagnosticSeverity.Warning,
                message = "Unknown step '$stepName'. May require plugin configuration."
            )
        }

        if (!isPluginConfigured(stepMeta.plugin)) {
            return Diagnostic(
                range = stepUsage.range,
                severity = DiagnosticSeverity.Warning,
                message = "Step '$stepName' requires plugin '${stepMeta.plugin}'"
            )
        }

        return null
    }
}
```

**Files**:

- `groovy-jenkins/.../PluginStepDiagnosticProvider.kt`
- `groovy-lsp/.../services/DiagnosticsService.kt`

**Phase 2.5 Deliverables**:

- ✅ Controller metadata dump script
- ✅ Metadata loading & merging
- ✅ Plugin step indexing
- ✅ Plugin-aware diagnostics

### Phase 3: Advanced Features (2-3 weeks)

**Goal**: Polish with validation and smart suggestions.

#### 3.1 Named Parameter Validation

**Requirement Analysis**:

```kotlin
class ParameterRequirementAnalyzer {
    fun analyze(step: JenkinsStepMetadata): ParameterRequirements {
        val required = step.parameters.filter { it.value.required }.keys
        val optional = step.parameters.filter { !it.value.required }.keys

        return ParameterRequirements(required, optional)
    }
}
```

**Diagnostic Provider**:

```kotlin
class NamedParameterDiagnosticProvider {
    override fun provideDiagnostics(call: MethodCallExpression): List<Diagnostic> {
        val stepMeta = metadataResolver.getStep(call.methodAsString) ?: return emptyList()
        val requirements = analyzer.analyze(stepMeta)
        val provided = extractProvidedParameters(call)

        val diagnostics = mutableListOf<Diagnostic>()

        // Check required parameters
        val missing = requirements.required - provided
        if (missing.isNotEmpty()) {
            diagnostics.add(Diagnostic(
                range = call.range,
                severity = DiagnosticSeverity.Error,
                message = "Missing required parameters: ${missing.joinToString()}"
            ))
        }

        // Check unknown parameters
        val unknown = provided - (requirements.required + requirements.optional)
        unknown.forEach { param ->
            val suggestion = findClosestMatch(param, requirements.required + requirements.optional)
            diagnostics.add(Diagnostic(
                range = call.range,
                severity = DiagnosticSeverity.Warning,
                message = "Unknown parameter '$param'. Did you mean '$suggestion'?"
            ))
        }

        return diagnostics
    }
}
```

**Files**:

- `groovy-lsp/.../providers/diagnostics/NamedParameterDiagnosticProvider.kt`
- `groovy-lsp/.../services/DiagnosticsService.kt`

#### 3.2 Smart Import Suggestions

**Unresolved Symbol Detection**:

```kotlin
class UnresolvedSymbolDetector {
    fun detectUnresolved(ast: ASTNode): List<UnresolvedSymbol> {
        val undefined = mutableListOf<UnresolvedSymbol>()

        ast.visit(object : ClassCodeVisitorSupport() {
            override fun visitMethodCallExpression(call: MethodCallExpression) {
                if (!isResolved(call)) {
                    // Check if available in libraries
                    val library = findLibraryWithSymbol(call.methodAsString)
                    if (library != null) {
                        undefined.add(UnresolvedSymbol(
                            name = call.methodAsString,
                            type = SymbolType.METHOD,
                            availableIn = library
                        ))
                    }
                }
                super.visitMethodCallExpression(call)
            }
        })

        return undefined
    }
}
```

**Quick Fix Provider**:

```kotlin
class ImportLibraryAction : CodeActionProvider {
    override fun provideCodeActions(diagnostic: Diagnostic): List<CodeAction> {
        if (diagnostic.code != "UNRESOLVED_SYMBOL") return emptyList()

        val library = diagnostic.data["library"] as? String ?: return emptyList()

        return listOf(
            CodeAction(
                title = "Import library '$library'",
                kind = CodeActionKind.QuickFix,
                edit = WorkspaceEdit(changes = mapOf(
                    uri to listOf(
                        TextEdit(
                            range = Range(0, 0, 0, 0),
                            newText = "@Library('$library') _\n"
                        )
                    )
                ))
            )
        )
    }
}
```

**Files**:

- `groovy-lsp/.../providers/codeaction/ImportLibraryAction.kt`
- `groovy-lsp/.../providers/codeaction/ImportAction.kt`

#### 3.3 Enhanced GDSL Processing

**Full GDSL Features**:

```kotlin
class GdslContextMatcher {
    fun matches(context: GdslContext, current: CompletionContext): Boolean {
        // Match scriptSuperClass
        if (context.scriptSuperClass != null) {
            if (current.scriptClass?.superClass?.name != context.scriptSuperClass) {
                return false
            }
        }

        // Match enclosingCall
        if (context.enclosingCall != null) {
            if (current.enclosingMethod?.name != context.enclosingCall) {
                return false
            }
        }

        // Match file type
        if (context.fileType != null) {
            if (!current.fileName.matches(context.fileType.toRegex())) {
                return false
            }
        }

        return true
    }
}
```

**Files**:

- `groovy-gdsl/.../GdslScript.kt`
- `groovy-gdsl/.../GdslContributor.kt`
- `groovy-gdsl/.../GdslContextMatcher.kt`

#### 3.4 Signature Help

**Enhanced Signature Help**:

```kotlin
class JenkinsSignatureHelpEnhancer {
    fun enhanceSignatureHelp(
        baseHelp: SignatureHelp,
        method: MethodSymbol
    ): SignatureHelp {
        // Add named parameter hints
        val stepMeta = metadataResolver.getStep(method.name)
        if (stepMeta != null) {
            baseHelp.activeSignature = 0
            baseHelp.signatures = listOf(
                SignatureInformation(
                    label = buildSignatureLabel(stepMeta),
                    documentation = stepMeta.documentation,
                    parameters = stepMeta.parameters.map { (name, param) ->
                        ParameterInformation(
                            label = "$name: ${param.type}",
                            documentation = param.documentation
                        )
                    }
                )
            )
        }

        return baseHelp
    }
}
```

**Files**:

- `groovy-lsp/.../providers/SignatureHelpProvider.kt`
- `groovy-jenkins/.../JenkinsSignatureHelpEnhancer.kt`

**Phase 3 Deliverables**:

- ✅ Named parameter validation
- ✅ Smart import suggestions
- ✅ Full GDSL support
- ✅ Enhanced signature help

### Phase 4: Polish & Performance (1-2 weeks)

**Goal**: Production hardening and optimization.

#### 4.1 Performance Optimization

**Targets**:

- Index 10MB JAR: < 2s
- Completion response: < 100ms (p95)
- Memory per library: < 50MB
- Startup impact: < 1s

**Optimizations**:

1. **Prefix Tree for Symbol Lookup**

```kotlin
class SymbolIndexPrefixTree {
    private val root = TrieNode()

    fun insert(symbol: String, metadata: Any) {
        var node = root
        for (char in symbol) {
            node = node.children.computeIfAbsent(char) { TrieNode() }
        }
        node.metadata = metadata
    }

    fun findByPrefix(prefix: String): List<Any> {
        var node = root
        for (char in prefix) {
            node = node.children[char] ?: return emptyList()
        }
        return collectAll(node)
    }
}
```

2. **Lazy Loading**

```kotlin
class LazyLibraryIndex(private val jarPath: Path) {
    private val classes by lazy { indexClasses() }
    private val methods = ConcurrentHashMap<String, List<Method>>()

    fun getMethods(className: String): List<Method> {
        return methods.computeIfAbsent(className) { loadMethods(className) }
    }
}
```

**Files**:

- `groovy-lsp/.../indexing/SymbolIndexPrefixTree.kt`
- `groovy-jenkins/.../LibrarySymbolExtractor.kt`
- `groovy-lsp/.../providers/completion/CompletionProvider.kt`

#### 4.2 Hover Documentation

**Enhanced Hover**:

```kotlin
class JenkinsDocumentationProvider {
    fun provideHover(symbol: Symbol): Hover? {
        val stepMeta = metadataResolver.getStep(symbol.name)
        if (stepMeta != null) {
            val markdown = buildString {
                appendLine("### ${stepMeta.name}")
                appendLine()
                appendLine("**Plugin**: ${stepMeta.plugin}")
                appendLine()
                appendLine(stepMeta.documentation ?: "No documentation available")
                appendLine()
                appendLine("#### Parameters")
                stepMeta.parameters.forEach { (name, param) ->
                    val required = if (param.required) " (required)" else ""
                    appendLine("- `$name`: ${param.type}$required")
                    if (param.documentation != null) {
                        appendLine("  ${param.documentation}")
                    }
                }
            }

            return Hover(
                contents = MarkupContent(MarkupKind.MARKDOWN, markdown)
            )
        }

        return null
    }
}
```

**Files**:

- `groovy-lsp/.../providers/HoverProvider.kt`
- `groovy-jenkins/.../JenkinsDocumentationProvider.kt`

#### 4.3 Comprehensive Testing

**Test Coverage Target**: 80% for new code

**Test Suites**:

1. **Integration Tests**

```kotlin
@Test
fun `should provide completions for real Jenkinsfile`() {
    val jenkinsfile = loadResource("sample-jenkinsfile.groovy")
    val completions = lsp.complete(jenkinsfile, 10, 15)

    assertContains(completions, "sh")
    assertContains(completions, "docker.image")
    assertContains(completions, "buildJava") // from shared library
}
```

2. **Performance Tests**

```kotlin
@Test
fun `should index 10MB library in under 2s`() {
    val start = System.currentTimeMillis()
    indexer.index(largeLibraryJar)
    val elapsed = System.currentTimeMillis() - start

    assertThat(elapsed).isLessThan(2000)
}
```

3. **GDSL Integration Tests**

```kotlin
@Test
fun `should load Jenkins GDSL and provide completions`() {
    val gdsl = loadResource("jenkins.gdsl")
    gdslLoader.load(gdsl)

    val completions = lsp.complete("stage('Build') { | }", 1, 20)
    assertContains(completions, "sh")
    assertContains(completions, "echo")
}
```

**Files**:

- `groovy-jenkins/.../test/.../JenkinsCompletionIntegrationTest.kt`
- `groovy-jenkins/.../test/.../JenkinsLibraryIndexingTest.kt`
- `groovy-gdsl/.../test/.../GdslIntegrationTest.kt`

**Phase 4 Deliverables**:

- ✅ Performance targets met
- ✅ Enhanced hover docs
- ✅ Test coverage >80%
- ✅ Production ready

---

## Technical Decisions

### 1. Parameter Name Extraction Strategy

**Decision**: ASM bytecode analysis as primary

**Approach**:

1. ASM (LocalVariableTable)
2. Java Parameter API
3. Positional hints (arg0, arg1)

**Rationale**: ASM is reliable and doesn't require `-parameters` compiler flag.

### 2. GDSL Metadata Storage

**Decision**: In-memory with optional disk cache

**Structure**:

```kotlin
data class GdslMetadata(
    val methods: List<GdslMethod>,
    val properties: List<GdslProperty>,
    val contexts: List<GdslContext>
)
```

### 3. Library Symbol Indexing

**Decision**: Complete upfront indexing

**Rationale**: User preference for comprehensive completions immediately.

### 4. Completion Priority

**Order**:

1. GDSL-defined symbols
2. Library global variables
3. Local symbols
4. Library classes/methods
5. GDK methods
6. JDK methods
7. Keywords

### 5. Indexing Strategy

**Decision**: Non-blocking progressive enhancement

**Flow**:

1. LSP initializes immediately
2. Background indexing with progress
3. Completions improve over time
4. Disk cache for fast subsequent startups

### 6. Error Handling

**Philosophy**: Graceful degradation

**Strategies**:

- Missing parameter names → positional hints
- Failed GDSL → skip, log, continue
- Library error → index others, report
- Unknown delegate → fall back to Object

### 7. Plugin Metadata Strategy

**Decision**: Bundled stubs + controller dump

**Approach**:

1. Bundled stubs (baseline)
2. Controller metadata (instance-specific)
3. Merge with precedence

**UX**: Works immediately, enhances optionally.

### 8. Remote Library Strategy

**Decision**: Auto-clone with cache

**Approach**:

- Parse `@Library`
- Auto-clone to `~/.groovy-lsp/cache/`
- TTL-based refresh (24h)
- User config mapping (optional)

**UX Win**: Beats IntelliJ

### 9. Declarative vs Scripted

**Decision**: Dual-mode completion

**Implementation**:

- Mode detection by structure
- Declarative: structural schema
- Scripted: closure delegates
- Dynamic per-file

### 10. CPS Blacklist

**Decision**: Proactive diagnostics

**Blacklist**: `java.io.File`, `System.exit`, `Runtime.exec`

**Diagnostics**: Error-level with alternatives

---

## Success Criteria

### Overall Success

**First-class support means**:

- ✅ All patterns from screenshot work
- ✅ Completion feels like IntelliJ
- ✅ No blocking/performance issues
- ✅ Clear error messages
- ✅ Easy configuration

### Phase-Specific Criteria

**Phase 0**:

- ✅ Bundled metadata loads
- ✅ Tests pass
- ✅ LSP distributes JAR

**Phase 1**:

- ✅ Map key completions work
- ✅ Non-blocking init
- ✅ GDSL integrated

**Phase 1.5**:

- ✅ Libraries auto-clone
- ✅ Vars/ indexed
- ✅ Seamless UX

**Phase 2**:

- ✅ All screenshot patterns complete
- ✅ Declarative/scripted modes
- ✅ CPS diagnostics

**Phase 2.5**:

- ✅ Controller metadata works
- ✅ Plugin steps complete
- ✅ Instance-accurate

**Phase 3**:

- ✅ Validation diagnostics
- ✅ Smart imports
- ✅ Signature help

**Phase 4**:

- ✅ Performance targets met
- ✅ >80% test coverage
- ✅ Production quality

---

## Beta Testing Strategy

### Scope

**Test Plugins** (10 most common):

1. pipeline-model-definition
2. workflow-cps
3. docker-workflow
4. kubernetes
5. git
6. credentials-binding
7. ssh-agent
8. email-ext
9. slack
10. junit

### Plan

**Beta 1** (Phase 0-1.5):

- Bundle stubs for 10 plugins
- Test real Jenkinsfiles
- Validate Map key completions
- Measure performance

**Beta 2** (Phase 2-2.5):

- Test controller metadata dump
- Validate accuracy (3-5 Jenkins instances)
- Test declarative/scripted modes
- Collect UX feedback

### Success Criteria

- ✅ All 10 plugins complete accurately
- ✅ Map key inference >90% success
- ✅ Remote library auto-clone works
- ✅ Controller dump captures all steps
- ✅ Completion < 100ms
- ✅ Positive user feedback

### Refinement

Post-beta:

1. Expand to top 20 plugins
2. Fix edge cases
3. Improve error messages
4. Optimize performance
5. Document setup

---

## Timeline & Milestones

### Revised Estimate

- **Phase 0**: 1 week (Bundled stubs)
- **Phase 1**: 2-3 weeks (Foundation)
- **Phase 1.5**: 1-2 weeks (Remote libs)
- **Beta 1**: 1 week (Validation)
- **Phase 2**: 2-3 weeks (Core features)
- **Phase 2.5**: 2-3 weeks (Plugin awareness)
- **Beta 2**: 1 week (Validation)
- **Phase 3**: 2-3 weeks (Advanced)
- **Phase 4**: 1-2 weeks (Polish)

**Total**: 13-20 weeks

### Critical Path

1. Phase 0 (prerequisite)
2. Phase 1.1 (Map keys)
3. Phase 1.3 (Non-blocking)
4. Phase 1.5 (Remote libs)
5. Beta 1
6. Phase 2+ (incremental)

---

## Risk Assessment

### High Risk

1. **Remote library cloning complexity**
   - Mitigation: Comprehensive error handling
   - Contingency: Prompt user configuration

2. **Controller metadata script compatibility**
   - Mitigation: Test across Jenkins versions
   - Contingency: Fallback to bundled stubs

3. **Performance degradation**
   - Mitigation: Async indexing, caching
   - Contingency: Lazy loading

### Medium Risk

1. **Map key inference accuracy**
   - Mitigation: Multiple metadata sources
   - Contingency: Positional hints

2. **GDSL parsing failures**
   - Mitigation: Comprehensive error handling
   - Contingency: Skip and continue

### Low Risk

1. **Bundled stub size**
   - Mitigation: Minimal stubs only
   - Impact: Acceptable for top 20 plugins

---

## References

### Internal Documents

- Approved plan: Maintainers' internal plan "calm-wandering-lollipop" (request access if needed)
- Architectural review feedback (embedded in this spec)
- TODO expansion: `groovy-jenkins/BUNDLED_STUBS_TODO.md`

### External References

- Jenkins Plugin Development Guide: https://www.jenkins.io/doc/developer/
- LSP Specification: https://microsoft.github.io/language-server-protocol/
- kotlinx.serialization: https://github.com/Kotlin/kotlinx.serialization
- ASM Library: https://asm.ow2.io/

---

**Document End**

This specification is comprehensive and approved for implementation. All phases, technical decisions, and success
criteria have been validated through architectural review.

**Next Action**: Proceed with Phase 0 implementation following TDD principles.
