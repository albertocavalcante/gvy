# User Override System

> **Status:** 📋 Draft\
> **Target Version:** 0.5.0\
> **Related:** [Jenkins IntelliSense](../JENKINS_INTELLISENSE_ARCHITECTURE.md),
> [GDSL Execution](GDSL_EXECUTION_ENGINE.md)

---

## Overview

The User Override System allows users to provide custom Jenkins metadata for their specific environment. This is
essential for:

- Internal/private Jenkins plugins not in public metadata
- Custom shared library steps
- Experimental pipeline features
- Corporate Jenkins configurations

---

## Configuration File

### Location

```
project-root/
└── .groovy-lsp/
    └── jenkins.json
```

### Schema

```json
{
  "$schema": "https://groovy-lsp.dev/schemas/jenkins-config.json",
  
  // Target Jenkins version for metadata resolution
  "jenkinsVersion": "2.479.3",
  
  // Custom GDSL file path (relative to workspace)
  "gdslFile": ".jenkins/pipeline.gdsl",
  
  // List of installed plugin IDs
  "plugins": [
    "workflow-aggregator",
    "git",
    "credentials",
    "my-internal-plugin"
  ],
  
  // Direct step metadata overrides
  "metadataOverrides": {
    "myInternalStep": {
      "plugin": "my-internal-plugin",
      "documentation": "My custom internal step",
      "parameters": {
        "config": {
          "type": "String",
          "required": true,
          "documentation": "Configuration file path"
        },
        "dryRun": {
          "type": "boolean",
          "required": false,
          "default": "false",
          "documentation": "Dry run mode"
        }
      }
    }
  },
  
  // Global variable overrides
  "globalVariableOverrides": {
    "myGlobalVar": {
      "type": "com.mycompany.MyGlobalVar",
      "documentation": "Custom global variable"
    }
  },
  
  // Shared library configurations
  "sharedLibraries": [
    {
      "name": "my-shared-lib",
      "path": "${HOME}/dev/my-shared-lib",
      "version": "main"
    }
  ]
}
```

---

## Custom GDSL File

Users can provide a custom GDSL file for maximum flexibility.

### Example: `.jenkins/pipeline.gdsl`

```groovy
// Custom GDSL for internal Jenkins plugins

def ctx = context(scope: scriptScope())

contributor([ctx]) {
    // Internal deploy step
    method(
        name: 'deployToKubernetes',
        type: 'void',
        namedParams: [
            parameter(name: 'cluster', type: 'String', doc: 'Target Kubernetes cluster'),
            parameter(name: 'namespace', type: 'String', doc: 'Deployment namespace'),
            parameter(name: 'manifest', type: 'String', doc: 'Path to manifest file'),
            parameter(name: 'dryRun', type: 'boolean', doc: 'Dry run mode'),
        ],
        doc: 'Deploy application to Kubernetes cluster'
    )
    
    // Internal notification step
    method(
        name: 'notifySlack',
        type: 'void',
        namedParams: [
            parameter(name: 'channel', type: 'String', doc: 'Slack channel'),
            parameter(name: 'message', type: 'String', doc: 'Message text'),
            parameter(name: 'status', type: 'String', doc: 'Build status: SUCCESS, FAILURE, UNSTABLE'),
        ],
        doc: 'Send notification to Slack'
    )
    
    // Custom global variable
    property(
        name: 'artifactory',
        type: 'com.mycompany.ArtifactoryGlobalVar',
        doc: 'Artifactory integration'
    )
}

// Node context steps
def nodeCtx = context(scope: closureScope())
contributor([nodeCtx]) {
    def call = enclosingCall('node')
    if (call) {
        method(
            name: 'internalScan',
            type: 'void',
            params: [path: 'String'],
            doc: 'Run internal security scan'
        )
    }
}
```

---

## Loading Priority

The metadata loading follows this priority order (highest to lowest):

```
1. User Override (metadataOverrides in jenkins.json)
   └─ Explicit step definitions from configuration

2. User GDSL File (gdslFile in jenkins.json)
   └─ Parsed GDSL contributions

3. Stable Step Definitions (StableStepDefinitions.kt)
   └─ Hardcoded core steps (sh, echo, bat, etc.)

4. Versioned Metadata (per jenkinsVersion)
   └─ LTS-specific step definitions

5. Dynamic Classpath Scan
   └─ Extracted from Jenkins plugin JARs

6. Bundled Metadata (fallback)
   └─ Default jenkins-stubs-metadata.json
```

### Merge Strategy

```kotlin
fun loadMerged(config: JenkinsUserConfig): BundledJenkinsMetadata {
    // Load from lowest to highest priority
    val bundled = BundledJenkinsMetadataLoader().load()
    val dynamic = classpathScanner.scan(classpath)
    val versioned = VersionedMetadataLoader().load(config.jenkinsVersion)
    val stable = StableStepDefinitions.asBundledMetadata()
    val userGdsl = config.gdslFile?.let { parseUserGdsl(it) }
    val userOverride = config.metadataOverrides?.toBundledMetadata()
    
    // Merge in priority order (later overrides earlier)
    return MetadataMerger.merge(
        bundled,
        dynamic,
        versioned,
        stable,
        userGdsl,
        userOverride,
    )
}
```

---

## Security Considerations

### Path Traversal Prevention

User-provided paths must be validated:

```kotlin
fun loadUserGdsl(workspaceRoot: Path, gdslPath: String): GdslParseResult? {
    val fullPath = workspaceRoot.resolve(gdslPath).normalize()
    
    // CRITICAL: Prevent path traversal
    if (!fullPath.startsWith(workspaceRoot.normalize())) {
        logger.warn("Path traversal attempt blocked: $gdslPath")
        return null
    }
    
    if (!fullPath.toFile().exists()) {
        logger.info("User GDSL file not found: $fullPath")
        return null
    }
    
    return gdslExecutor.executeAndCapture(fullPath.readText())
}
```

### GDSL Sandbox

User GDSL execution should be sandboxed:

```kotlin
class SandboxedGdslExecutor : GdslExecutor() {
    
    override fun executeAndCapture(content: String): GdslParseResult {
        val config = CompilerConfiguration().apply {
            scriptBaseClass = GdslScript::class.java.name
            
            // Restrict imports
            addCompilationCustomizers(
                SecureASTCustomizer().apply {
                    importsWhitelist = listOf(
                        "groovy.lang.*",
                        "java.util.*",
                    )
                    // Disable system access
                    receiversBlackList = listOf(
                        "java.lang.System",
                        "java.lang.Runtime",
                        "java.io.File",
                    )
                }
            )
        }
        
        return super.executeWithConfig(content, config)
    }
}
```

---

## Implementation

### UserMetadataLoader

```kotlin
class UserMetadataLoader(
    private val workspaceRoot: Path,
    private val gdslExecutor: GdslExecutor = GdslExecutor(),
) {
    companion object {
        private const val CONFIG_DIR = ".groovy-lsp"
        private const val CONFIG_FILE = "jenkins.json"
    }
    
    fun load(): BundledJenkinsMetadata? {
        val configFile = workspaceRoot.resolve(CONFIG_DIR).resolve(CONFIG_FILE)
        
        if (!configFile.exists()) {
            return null
        }
        
        val config = parseConfig(configFile)
        return mergeUserMetadata(config)
    }
    
    private fun parseConfig(file: Path): JenkinsUserConfig {
        val json = Json { ignoreUnknownKeys = true }
        return json.decodeFromString(file.readText())
    }
    
    private fun mergeUserMetadata(config: JenkinsUserConfig): BundledJenkinsMetadata {
        val steps = mutableMapOf<String, JenkinsStepMetadata>()
        val globalVars = mutableMapOf<String, GlobalVariableMetadata>()
        
        // Add metadata overrides
        config.metadataOverrides?.forEach { (name, override) ->
            steps[name] = override.toJenkinsStepMetadata()
        }
        
        // Add GDSL contributions
        config.gdslFile?.let { gdslPath ->
            val gdslResult = loadUserGdsl(workspaceRoot, gdslPath)
            gdslResult?.methods?.forEach { method ->
                steps[method.name] = method.toJenkinsStepMetadata()
            }
            gdslResult?.properties?.forEach { prop ->
                globalVars[prop.name] = prop.toGlobalVariableMetadata()
            }
        }
        
        return BundledJenkinsMetadata(
            steps = steps,
            globalVariables = globalVars,
        )
    }
}
```

---

## VS Code Settings Integration

In addition to `.groovy-lsp/jenkins.json`, settings can come from VS Code:

```json
// .vscode/settings.json
{
  "groovy.jenkins.enabled": true,
  "groovy.jenkins.version": "2.479.3",
  "groovy.jenkins.gdslFile": ".jenkins/pipeline.gdsl",
  "groovy.jenkins.plugins": ["workflow-aggregator", "git"]
}
```

### Settings Merge

```
Priority:
1. .groovy-lsp/jenkins.json (project-specific)
2. .vscode/settings.json (workspace settings)
3. User settings.json (global VS Code settings)
```

---

## Testing Strategy

### Unit Tests

```kotlin
class UserMetadataLoaderTest {
    
    @TempDir
    lateinit var tempDir: Path
    
    @Test
    fun `loads user GDSL file when configured`() {
        setupConfig("""
            { "gdslFile": ".jenkins/pipeline.gdsl" }
        """)
        
        setupGdslFile("""
            contributor([context()]) {
                method(name: 'myStep', type: 'void', params: [:])
            }
        """)
        
        val loader = UserMetadataLoader(tempDir)
        val metadata = loader.load()
        
        assertNotNull(metadata?.steps?.get("myStep"))
    }
    
    @Test
    fun `prevents path traversal in GDSL path`() {
        setupConfig("""
            { "gdslFile": "../../etc/passwd" }
        """)
        
        val loader = UserMetadataLoader(tempDir)
        val metadata = loader.load()
        
        // Should not load anything, path traversal blocked
        assertTrue(metadata?.steps?.isEmpty() ?: true)
    }
    
    @Test
    fun `user overrides take precedence`() {
        setupConfig("""
            {
                "metadataOverrides": {
                    "sh": {
                        "plugin": "user-override",
                        "parameters": { "custom": { "type": "String" } }
                    }
                }
            }
        """)
        
        val loader = UserMetadataLoader(tempDir)
        val userMeta = loader.load()
        
        val merged = MetadataMerger.merge(
            StableStepDefinitions.asBundledMetadata(),
            userMeta,
        )
        
        // User override should win
        assertEquals("user-override", merged.steps["sh"]?.plugin)
        assertTrue(merged.steps["sh"]?.parameters?.containsKey("custom") == true)
    }
}
```

---

## Error Handling

### Invalid Configuration

```kotlin
sealed class ConfigLoadResult {
    data class Success(val config: JenkinsUserConfig) : ConfigLoadResult()
    data class ParseError(val message: String) : ConfigLoadResult()
    data class FileNotFound(val path: Path) : ConfigLoadResult()
    data class SecurityViolation(val message: String) : ConfigLoadResult()
}

fun loadConfig(workspaceRoot: Path): ConfigLoadResult {
    val configPath = workspaceRoot.resolve(".groovy-lsp/jenkins.json")
    
    if (!configPath.exists()) {
        return ConfigLoadResult.FileNotFound(configPath)
    }
    
    return try {
        val config = Json.decodeFromString<JenkinsUserConfig>(configPath.readText())
        
        // Validate GDSL path
        config.gdslFile?.let { gdslPath ->
            val fullPath = workspaceRoot.resolve(gdslPath).normalize()
            if (!fullPath.startsWith(workspaceRoot.normalize())) {
                return ConfigLoadResult.SecurityViolation(
                    "GDSL file path must be within workspace: $gdslPath"
                )
            }
        }
        
        ConfigLoadResult.Success(config)
    } catch (e: SerializationException) {
        ConfigLoadResult.ParseError("Invalid JSON: ${e.message}")
    }
}
```

### Diagnostic Reporting

Invalid configurations should report diagnostics:

```kotlin
fun reportConfigDiagnostics(result: ConfigLoadResult): List<Diagnostic> {
    return when (result) {
        is ConfigLoadResult.ParseError -> listOf(
            Diagnostic(
                range = Range(Position(0, 0), Position(0, 0)),
                severity = DiagnosticSeverity.Error,
                source = "groovy-lsp",
                message = "Invalid jenkins.json: ${result.message}"
            )
        )
        is ConfigLoadResult.SecurityViolation -> listOf(
            Diagnostic(
                range = Range(Position(0, 0), Position(0, 0)),
                severity = DiagnosticSeverity.Error,
                source = "groovy-lsp",
                message = "Security violation: ${result.message}"
            )
        )
        else -> emptyList()
    }
}
```

---

## Future Enhancements

1. **JSON Schema Validation**: Provide JSON schema for IDE support
2. **Hot Reload**: Watch config files and reload on change
3. **Config Generation**: Generate template from installed plugins
4. **Remote Config**: Fetch config from corporate registry

---

## References

- [IntelliJ IDEA External Annotations](https://www.jetbrains.com/help/idea/external-annotations.html)
- [VS Code Language Server Configuration](https://code.visualstudio.com/api/language-extensions/language-server-extension-guide)

---

_Last updated: December 21, 2025_
