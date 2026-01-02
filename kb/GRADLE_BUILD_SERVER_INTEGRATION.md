# Gradle Build Server Integration - Deep Analysis

> **Created**: December 21, 2025\
> **Status**: Research Complete, Pending Implementation Decisions\
> **Authors**: groovy-lsp team

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Investigation Findings](#investigation-findings)
3. [Architecture Comparison](#architecture-comparison)
4. [Feature Requests for Microsoft](#feature-requests-for-microsoft)
5. [VSCode Extension Integration Plan](#vscode-extension-integration-plan)
6. [Native Gradle Improvements](#native-gradle-improvements)
7. [Decision Matrix](#decision-matrix)
8. [Implementation Roadmap](#implementation-roadmap)
9. [References](#references)

---

## Executive Summary

### The Problem

We investigated integrating with Microsoft's
[build-server-for-gradle](https://github.com/microsoft/build-server-for-gradle) to provide an optional enhanced Gradle
experience. The goal was to allow users who have the Gradle Build Server installed to benefit from its features (better
annotation processing, consistent output directories) while maintaining our existing native Gradle Tooling API
integration.

### Key Finding

**Microsoft's Gradle Build Server does NOT follow standard BSP discovery conventions.**

Unlike bazel-bsp, sbt, and Mill (which generate `.bsp/*.json` connection files), build-server-for-gradle uses a
proprietary named pipe architecture that requires:

1. The vscode-gradle extension to spawn the server
2. A Node.js proxy (`BspProxy`) to bridge named pipes
3. The JDT.LS Eclipse plugin to act as the BSP client

### Impact

- ❌ Our generic `BspBuildTool` cannot auto-detect Gradle Build Server
- ❌ No zero-configuration integration possible
- ✅ Our native Gradle Tooling API remains the best option for standalone use
- ✅ BSP integration works for Bazel, sbt, and Mill

---

## Investigation Findings

### Microsoft's Architecture Deep Dive

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                           vscode-gradle + build-server-for-gradle                        │
│                                                                                          │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐│
│  │                              VSCode Extension Host                                   ││
│  │  ┌──────────────────┐                              ┌──────────────────────────────┐ ││
│  │  │  vscode-gradle   │──────spawns───────────────►  │   build-server-for-gradle    │ ││
│  │  │  (TypeScript)    │                              │   (Java JAR)                 │ ││
│  │  └────────┬─────────┘                              └──────────────┬───────────────┘ ││
│  │           │                                                       │                  ││
│  │           │ creates                                               │ connects via     ││
│  │           ▼                                                       │ --pipe=<path>    ││
│  │  ┌──────────────────┐                                             │                  ││
│  │  │    BspProxy      │◄────────────────────────────────────────────┘                  ││
│  │  │   (Node.js)      │                                                                ││
│  │  │                  │                                                                ││
│  │  │  • Creates 2 named pipes                                                          ││
│  │  │  • Forwards JSON-RPC between server ↔ JDT.LS                                      ││
│  │  └────────┬─────────┘                                                                ││
│  └───────────│──────────────────────────────────────────────────────────────────────────┘│
│              │                                                                           │
│              │ named pipe                                                                │
│              ▼                                                                           │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐│
│  │                              Java Language Server (JDT.LS)                           ││
│  │  ┌────────────────────────────────────────────────────────────────────────────────┐ ││
│  │  │  GradleBuildServerProjectImporter (Eclipse Plugin)                             │ ││
│  │  │                                                                                │ ││
│  │  │  • Receives pipe path via _gradle.onWillImporterConnect command                │ ││
│  │  │  • Connects to BspProxy as BSP client                                          │ ││
│  │  │  • Imports project structure into Eclipse workspace model                      │ ││
│  │  └────────────────────────────────────────────────────────────────────────────────┘ ││
│  └─────────────────────────────────────────────────────────────────────────────────────┘│
│                                                                                          │
│  ❌ NO .bsp/gradle.json file is created                                                  │
│  ❌ NOT discoverable by standard BSP clients                                             │
│  ❌ Tightly coupled to vscode-java ecosystem                                             │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

### Source Code Evidence

**From `vscode-gradle/extension/src/bs/BspProxy.ts`:**

```typescript
export class BspProxy {
    private buildServerConnector: BuildServerConnector;
    private jdtlsImporterConnector: JdtlsImporterConnector;
    
    // Creates TWO named pipe servers - one for build server, one for JDT.LS
    public prepareToStart(): boolean {
        return this.buildServerConnector.setupBuildServerPipeStream();
    }
}
```

**From `GradleBuildServerProjectImporter.java`:**

```java
// Connection via pipes, NOT .bsp/ discovery
public static final String BUILD_GRADLE_DESCRIPTOR = "build.gradle";
// No BspConnectionDetails or .bsp folder handling
```

**From `build-server-for-gradle/Launcher.java`:**

```java
// Server accepts --pipe=<pipeName> argument, NOT standard BSP socket
String pipePath = params.get("pipe");
if (StringUtils.isNotBlank(pipePath)) {
    launcher = createLauncherUsingPipe(pipePath);
} else {
    launcher = createLauncherUsingStdIo();  // Fallback to stdio
}
```

### Comparison with Standard BSP Servers

| Feature                   | bazel-bsp | sbt | Mill | build-server-for-gradle |
| ------------------------- | --------- | --- | ---- | ----------------------- |
| Creates `.bsp/*.json`     | ✅        | ✅  | ✅   | ❌                      |
| Standard BSP discovery    | ✅        | ✅  | ✅   | ❌                      |
| Stdio transport           | ✅        | ✅  | ✅   | ⚠️ Fallback only         |
| Named pipe transport      | ❌        | ❌  | ❌   | ✅ Primary              |
| Standalone usage          | ✅        | ✅  | ✅   | ❌                      |
| Works with any BSP client | ✅        | ✅  | ✅   | ❌ JDT.LS only          |

---

## Architecture Comparison

### Standard BSP Flow (What We Support)

```
┌──────────────┐     .bsp/bazelbsp.json     ┌──────────────┐
│  groovy-lsp  │ ◄─────discovers──────────► │  bazel-bsp   │
│  BspBuildTool│ ◄─────stdio/socket───────► │  (spawned)   │
└──────────────┘                            └──────────────┘
```

### Microsoft's Flow (What We Can't Auto-Detect)

```
┌──────────────┐                            ┌──────────────┐
│  groovy-lsp  │         ???                │  build-srv   │
│  BspBuildTool│ ◄─────no discovery───────► │  -for-gradle │
└──────────────┘                            └──────────────┘
                    Need named pipe path!
```

---

## Feature Requests for Microsoft

### FR-1: Add Standard BSP Discovery Support

**Repository**: [microsoft/build-server-for-gradle](https://github.com/microsoft/build-server-for-gradle)

**Title**: Support standard BSP connection discovery via .bsp/gradle.json

**Description**:

````markdown
## Summary

Add support for generating a `.bsp/gradle.json` connection file to enable standard BSP client discovery, making the
Gradle Build Server usable by any BSP-compatible tool (not just JDT.LS).

## Motivation

Currently, the Gradle Build Server only works with the JDT.LS importer via named pipes. Other BSP clients (IntelliJ,
Metals, custom LSP servers) cannot discover or connect to the server.

The [BSP specification](https://build-server-protocol.github.io/) defines a standard discovery mechanism via
`.bsp/*.json` files containing connection details.

## Proposed Solution

Add a command/option to generate `.bsp/gradle.json`:

```json
{
  "name": "Gradle Build Server",
  "version": "0.3.0",
  "bspVersion": "2.1.0",
  "languages": ["java", "kotlin", "groovy", "scala"],
  "argv": ["java", "-jar", "/path/to/gradle-build-server.jar"]
}
```
````

## Alternatives Considered

1. Named pipe-only: Current approach, limits adoption
2. Socket-based connection: Would also work but requires running server

## Additional Context

- [BSP Connection Discovery Spec](https://build-server-protocol.github.io/docs/server-discovery)
- Projects that would benefit: groovy-lsp, kotlin-language-server, etc.

````
### FR-2: Support Stdio Transport as Primary Option

**Title**: Make stdio transport a first-class option alongside named pipes

**Description**:
```markdown
## Summary
Currently stdio transport is a fallback when no --pipe argument is provided.
Make it a documented, supported transport method for broader compatibility.

## Motivation
Stdio is the standard BSP transport and works across all platforms without 
platform-specific named pipe handling.

## Proposed Solution
- Document stdio usage explicitly
- Add --transport=stdio|pipe CLI argument
- Test and support stdio as production transport
````

### FR-3: Publish Standalone Distribution

**Title**: Provide standalone JAR distribution for non-VSCode usage

**Description**:

```markdown
## Summary

Publish the Gradle Build Server JAR to Maven Central or GitHub Releases with documentation for standalone usage.

## Current State

The server JAR is bundled inside the vscode-gradle extension and not easily accessible for other tools.

## Proposed Solution

- Publish to Maven Central: `com.microsoft.java:gradle-build-server:0.3.0`
- Or publish to GitHub Releases with clear versioning
- Document standalone launch command
```

---

## VSCode Extension Integration Plan

### Option A: Extension-Mediated Integration (Recommended)

Our VSCode extension acts as coordinator between groovy-lsp and the Gradle Build Server.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        groovy-lsp VSCode Extension                           │
│                                                                              │
│  ┌────────────────────┐      ┌─────────────────────┐     ┌───────────────┐  │
│  │ Extension          │      │ Gradle Build Server │     │  groovy-lsp   │  │
│  │ (TypeScript)       │      │ (spawned by us)     │     │  (LSP Server) │  │
│  │                    │      │                     │     │               │  │
│  │ 1. Detect Gradle   │      │                     │     │               │  │
│  │ 2. Spawn server    │─────►│ --transport=stdio   │     │               │  │
│  │ 3. Proxy BSP msgs  │◄────►│                     │────►│ Dependencies  │  │
│  │                    │      │                     │     │ via custom    │  │
│  │                    │      │                     │     │ LSP request   │  │
│  └────────────────────┘      └─────────────────────┘     └───────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Implementation Steps

**Phase 1: Extension Changes**

```typescript
// extension/src/gradle/GradleBuildServerManager.ts

import { spawn, ChildProcess } from 'child_process';
import * as rpc from 'vscode-jsonrpc/node';

export class GradleBuildServerManager {
    private serverProcess: ChildProcess | null = null;
    private connection: rpc.MessageConnection | null = null;
    
    async startServer(workspaceRoot: string): Promise<void> {
        // Find the server JAR (bundled with our extension or separately installed)
        const serverJar = await this.findServerJar();
        if (!serverJar) {
            return; // Fall back to native Gradle in groovy-lsp
        }
        
        // Spawn with stdio transport
        this.serverProcess = spawn('java', [
            '-jar', serverJar,
            `-Dplugin.dir=${this.getPluginDir()}`,
            // Use stdio, not named pipes
        ], {
            cwd: workspaceRoot,
            stdio: ['pipe', 'pipe', 'pipe'],
        });
        
        // Create JSON-RPC connection
        this.connection = rpc.createMessageConnection(
            new rpc.StreamMessageReader(this.serverProcess.stdout!),
            new rpc.StreamMessageWriter(this.serverProcess.stdin!)
        );
        
        this.connection.listen();
        
        // Initialize BSP session
        await this.initializeBsp(workspaceRoot);
    }
    
    async getDependencies(): Promise<string[]> {
        // Query build targets
        const targets = await this.connection?.sendRequest('workspace/buildTargets');
        
        // Query dependency modules
        const deps = await this.connection?.sendRequest('buildTarget/dependencyModules', {
            targets: targets.targets.map(t => t.id)
        });
        
        return this.extractJarPaths(deps);
    }
}
```

**Phase 2: Custom LSP Extension**

Add a custom LSP request to pass resolved dependencies to groovy-lsp:

```typescript
// In extension activation
const groovyLspClient = await startGroovyLsp();

// When Gradle Build Server resolves dependencies
gradleBuildServerManager.onDependenciesResolved((deps) => {
    groovyLspClient.sendRequest('groovy/updateDependencies', {
        source: 'gradle-build-server',
        dependencies: deps.map(d => d.uri),
        sourceDirectories: deps.flatMap(d => d.sources),
    });
});
```

**Phase 3: groovy-lsp Server Changes**

Handle the custom request:

```kotlin
// GroovyTextDocumentService.kt or new handler

@JsonRequest("groovy/updateDependencies")
fun updateDependencies(params: UpdateDependenciesParams): CompletableFuture<Unit> {
    logger.info("Received dependencies from ${params.source}: ${params.dependencies.size} JARs")
    
    compilationService.updateWorkspaceModel(
        workspaceRoot = currentWorkspaceRoot,
        dependencies = params.dependencies.map { Paths.get(URI.create(it)) },
        sourceDirectories = params.sourceDirectories.map { Paths.get(URI.create(it)) },
    )
    
    // Refresh diagnostics
    textDocumentService.refreshOpenDocuments()
    
    return CompletableFuture.completedFuture(Unit)
}
```

### Option B: Direct Integration (More Complex)

groovy-lsp directly spawns and manages the Gradle Build Server.

**Pros:**

- No extension dependency
- Works in any editor with LSP support

**Cons:**

- Need to bundle or locate server JAR
- Platform-specific named pipe handling
- Duplicates vscode-gradle functionality

### Option C: Configuration-Based Fallback

Allow users to manually specify the BSP server connection.

```json
// settings.json
{
  "groovy.gradle.bspServer": {
    "enabled": true,
    "command": ["java", "-jar", "/path/to/gradle-build-server.jar"],
    "transport": "stdio"
  }
}
```

---

## Native Gradle Improvements

While pursuing BSP integration, we should also improve our native Gradle Tooling API support by learning from
vscode-gradle's implementation.

### From `GradleProjectModelBuilder.java`

#### 1. Plugin Closure Extraction (DSL Completion)

```java
private List<GradleClosure> getPluginClosures(Project project) {
    project.getExtensions().getExtensionsSchema().getElements().forEach(schema -> {
        TypeOf<?> publicType = schema.getPublicType();
        Class<?> concreteClass = publicType.getConcreteClass();
        
        // Extract methods
        for (Method method : concreteClass.getMethods()) {
            methods.add(new DefaultGradleMethod(
                method.getName(),
                parameterTypes,
                isDeprecated(method)
            ));
        }
        
        // Extract managed properties (abstract getters)
        if (name.startsWith("get") && Modifier.isAbstract(modifiers)) {
            fields.add(new DefaultGradleField(propertyName, isDeprecated(method)));
        }
    });
}
```

**groovy-lsp improvement**: Extract plugin DSL schemas for `build.gradle` completion.

#### 2. Hierarchical Dependency Tree

```java
private GradleDependencyNode generateDefaultGradleDependencyNode(Project project) {
    ConfigurationContainer configurationContainer = project.getConfigurations();
    for (String configName : configurationContainer.getNames()) {
        Configuration config = configurationContainer.getByName(configName);
        ResolutionResult resolutionResult = config.getIncoming().getResolutionResult();
        // Build tree structure...
    }
}
```

**groovy-lsp improvement**: Add optional dependency tree for UI display.

#### 3. Task Debugging Detection

```java
if ((task instanceof JavaExec || task instanceof Test)) {
    ((DefaultGradleTask) gradleTask).setDebuggable(true);
}
```

**groovy-lsp improvement**: Could expose this for future debugging features.

### Proposed Improvements

| Feature               | Current        | Proposed           | Priority |
| --------------------- | -------------- | ------------------ | -------- |
| Dependency resolution | ✅ Flat list   | ⬆️ Optional tree    | Low      |
| Plugin DSL schema     | ❌ None        | ✅ For completion  | Medium   |
| Source/Javadoc JARs   | ⚠️ Via BSP only | ✅ Via Tooling API | High     |
| Build file validation | ❌ None        | ✅ Basic checks    | Medium   |

---

## Decision Matrix

### Integration Approach Selection

| Criteria              | Option A (Extension) | Option B (Direct) | Option C (Config) |
| --------------------- | -------------------- | ----------------- | ----------------- |
| Implementation effort | Medium               | High              | Low               |
| User experience       | Best                 | Good              | Manual            |
| Maintenance burden    | Medium               | High              | Low               |
| Works standalone      | ❌                   | ✅                | ⚠️                 |
| Auto-detection        | ✅                   | ❌                | ❌                |
| **Recommendation**    | ✅ Primary           | Consider later    | Fallback          |

### Timeline Estimate

| Phase | Task                                       | Effort    | Dependencies    |
| ----- | ------------------------------------------ | --------- | --------------- |
| 1     | File FR-1 with Microsoft                   | 1 day     | None            |
| 2     | Implement Option C (config fallback)       | 3 days    | None            |
| 3     | Implement Option A (extension integration) | 1-2 weeks | Phase 2         |
| 4     | Native Gradle improvements                 | 2 weeks   | None (parallel) |

---

## Implementation Roadmap

### Phase 1: Foundation (Current Sprint)

- [x] Research build-server-for-gradle architecture
- [x] Document findings (this document)
- [x] Implement `GradleBuildStrategy` feature flag
- [ ] File feature requests with Microsoft

### Phase 2: Configuration Fallback (Next Sprint)

- [ ] Add `groovy.gradle.bspServer` configuration option
- [ ] Implement manual BSP server spawn with stdio transport
- [ ] Add documentation for manual setup

### Phase 3: Extension Integration (Future)

- [ ] Create `GradleBuildServerManager` in VSCode extension
- [ ] Implement `groovy/updateDependencies` LSP extension
- [ ] Add auto-detection of Gradle Build Server availability
- [ ] User settings for BSP vs native preference

### Phase 4: Native Improvements (Parallel Track)

- [ ] Add source/Javadoc JAR resolution to native Gradle
- [ ] Extract plugin DSL schemas for completion
- [ ] Improve build file error diagnostics

---

## References

### Microsoft Repositories

- [build-server-for-gradle](https://github.com/microsoft/build-server-for-gradle) - BSP server implementation
- [vscode-gradle](https://github.com/microsoft/vscode-gradle) - VSCode extension with BSP integration
- [Blog: New Build Server for Gradle](https://devblogs.microsoft.com/java/new-build-server-for-gradle/)

### Key Source Files

| File                                                      | Purpose            |
| --------------------------------------------------------- | ------------------ |
| `vscode-gradle/extension/src/bs/BspProxy.ts`              | Named pipe bridge  |
| `vscode-gradle/extension/src/bs/BuildServerConnector.ts`  | Server spawn       |
| `vscode-gradle/.../GradleBuildServerProjectImporter.java` | JDT.LS integration |
| `build-server-for-gradle/.../Launcher.java`               | Server entry point |
| `build-server-for-gradle/.../GradleBuildServer.java`      | BSP implementation |

### BSP Specification

- [Build Server Protocol](https://build-server-protocol.github.io/)
- [BSP Connection Discovery](https://build-server-protocol.github.io/docs/server-discovery)
- [bsp4j](https://github.com/build-server-protocol/bsp4j) - Java BSP library

### Related Projects

- [bazel-bsp](https://github.com/JetBrains/bazel-bsp) - Standard BSP implementation example
- [Metals](https://scalameta.org/metals/) - Scala LSP with BSP support
- [IntelliJ BSP](https://plugins.jetbrains.com/plugin/21950-build-server-protocol-bsp-) - IDE BSP client

---

## Appendix: Feature Request Templates

### GitHub Issue Template for FR-1

```markdown
---
name: Feature Request
about: Support standard BSP connection discovery
labels: enhancement
---

**Is your feature request related to a problem?** The Gradle Build Server currently only works with JDT.LS via named
pipes. Other BSP-compatible tools cannot discover or connect to the server.

**Describe the solution you'd like** Generate a `.bsp/gradle.json` file when the server starts, following the
[BSP connection discovery specification](https://build-server-protocol.github.io/docs/server-discovery).

**Describe alternatives you've considered**

1. Named pipe discovery file (non-standard)
2. Configuration file with pipe path
3. Current approach (JDT.LS only)

**Additional context** This would enable tools like groovy-lsp, kotlin-language-server, and any BSP-compatible IDE to
use the Gradle Build Server.
```

---

_Last updated: December 21, 2025_
