# Versioned Jenkins Metadata

> **Status:** 📋 Draft\
> **Target Version:** 0.5.0\
> **Related:** [Jenkins IntelliSense](../JENKINS_INTELLISENSE_ARCHITECTURE.md),
> [GDSL Execution](GDSL_EXECUTION_ENGINE.md)

---

## Overview

This specification defines how Groovy LSP provides accurate Jenkins step metadata based on the target Jenkins LTS
version. Different Jenkins versions expose different steps and parameters, so version-aware metadata ensures accurate
IntelliSense.

---

## Motivation

### Problem

Jenkins evolves with each LTS release:

- New steps are added (e.g., `publishChecks` in recent versions)
- Step parameters change (new options, deprecations)
- Plugin versions affect available functionality

A single bundled metadata file cannot accurately represent all Jenkins versions.

### Solution

Provide versioned metadata that:

1. Maps Jenkins LTS versions to step definitions
2. Falls back gracefully when exact version unavailable
3. Allows user override for custom environments

---

## Design

### Directory Structure

```
groovy-jenkins/src/main/resources/metadata/
├── index.json                    # Version index
├── default/
│   └── merged.json               # Fallback metadata
├── lts-2.479/
│   └── merged.json               # Jenkins 2.479.x
├── lts-2.462/
│   └── merged.json               # Jenkins 2.462.x
└── lts-2.440/
    └── merged.json               # Jenkins 2.440.x
```

### Index Schema

```json
// metadata/index.json
{
  "versions": {
    "2.479": "/metadata/lts-2.479/merged.json",
    "2.462": "/metadata/lts-2.462/merged.json",
    "2.440": "/metadata/lts-2.440/merged.json"
  },
  "default": "/metadata/default/merged.json",
  "generated": "2025-01-15T00:00:00Z"
}
```

### Metadata Schema

```json
// metadata/lts-2.479/merged.json
{
  "jenkinsVersion": "2.479.3",
  "generatedAt": "2025-01-15T00:00:00Z",
  "steps": {
    "sh": {
      "name": "sh",
      "plugin": "workflow-durable-task-step",
      "pluginVersion": "1313.v6a_15807937e9",
      "documentation": "Execute a shell script",
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
          "default": "false"
        }
      },
      "requiresNode": true
    }
  },
  "globalVariables": {
    "env": {
      "name": "env",
      "type": "org.jenkinsci.plugins.workflow.cps.EnvActionImpl",
      "documentation": "Environment variables"
    }
  }
}
```

---

## Version Resolution

### Algorithm

```
Input: User-specified Jenkins version (e.g., "2.479.3")
Output: Metadata for that version

1. Extract LTS version: "2.479.3" → "2.479"
2. Check index.versions["2.479"]
3. If found → Load that metadata
4. If not found → Find closest lower version
5. If no lower version → Use default
6. Merge with stable definitions (StableStepDefinitions)
7. Apply user overrides
```

### Implementation

```kotlin
class VersionedMetadataLoader(
    private val bundledLoader: BundledJenkinsMetadataLoader,
) {
    private val index: MetadataIndex by lazy { loadIndex() }
    
    fun load(jenkinsVersion: String?): BundledJenkinsMetadata {
        val ltsVersion = jenkinsVersion?.extractLtsVersion()
        val metadataPath = resolveMetadataPath(ltsVersion)
        return loadFromResource(metadataPath)
    }
    
    fun loadMerged(jenkinsVersion: String?): BundledJenkinsMetadata {
        val bundled = bundledLoader.load()
        val versioned = load(jenkinsVersion)
        val stable = StableStepDefinitions.asBundledMetadata()
        
        // Priority: stable > versioned > bundled
        return MetadataMerger.merge(bundled, versioned, stable)
    }
    
    private fun resolveMetadataPath(ltsVersion: String?): String {
        return when {
            ltsVersion == null -> index.default
            index.versions.containsKey(ltsVersion) -> index.versions[ltsVersion]!!
            else -> findClosestVersion(ltsVersion) ?: index.default
        }
    }
    
    private fun findClosestVersion(target: String): String? {
        return index.versions.keys
            .filter { it < target }
            .maxOrNull()
            ?.let { index.versions[it] }
    }
}
```

---

## Metadata Generation Pipeline

### CI Workflow

```yaml
# .github/workflows/extract-jenkins-metadata.yml
name: Extract Jenkins Metadata

on:
  schedule:
    - cron: '0 0 1 1,4,7,10 *'  # Quarterly
  workflow_dispatch:
    inputs:
      jenkins_version:
        description: 'Jenkins LTS version'
        required: true

jobs:
  extract:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Start Jenkins Container
        run: |
          docker run -d --name jenkins \
            -p 8080:8080 \
            jenkins/jenkins:${{ inputs.jenkins_version }}-lts
      
      - name: Wait for Jenkins
        run: ./tools/jenkins-extractor/wait-for-jenkins.sh
      
      - name: Extract GDSL
        run: |
          curl -s http://localhost:8080/pipeline-syntax/gdsl \
            > tools/jenkins-extractor/output/gdsl-output.groovy
      
      - name: Parse to JSON
        run: ./gradlew :groovy-jenkins:parseGdsl
      
      - name: Commit Metadata
        run: |
          VERSION=$(echo ${{ inputs.jenkins_version }} | cut -d. -f1,2)
          mkdir -p groovy-jenkins/src/main/resources/metadata/lts-$VERSION
          cp output/merged.json groovy-jenkins/src/main/resources/metadata/lts-$VERSION/
          git add .
          git commit -m "chore: update Jenkins metadata for LTS $VERSION"
```

### Extraction Process

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│ Jenkins Docker  │ ──▶ │  GDSL Endpoint  │ ──▶ │  Raw GDSL Text  │
│   Container     │     │ /pipeline-syntax│     │                 │
└─────────────────┘     │     /gdsl       │     └────────┬────────┘
                        └─────────────────┘              │
                                                         ▼
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  merged.json    │ ◀── │   GdslParser    │ ◀── │  GdslExecutor   │
│ (versioned)     │     │                 │     │                 │
└─────────────────┘     └─────────────────┘     └─────────────────┘
```

---

## User Configuration

### Specifying Jenkins Version

```json
// .groovy-lsp/jenkins.json
{
  "jenkinsVersion": "2.479.3"
}
```

### Version Detection (Future)

Potential automatic detection sources:

1. `plugins.txt` header comments
2. Jenkins REST API (if accessible)
3. Gradle plugin versions
4. Maven dependency versions

---

## Testing Strategy

### Unit Tests

```kotlin
@Test
fun `loads metadata for specific LTS version`() {
    val loader = VersionedMetadataLoader()
    val metadata = loader.load("2.479.3")
    
    assertNotNull(metadata.steps["sh"])
    assertEquals("workflow-durable-task-step", metadata.steps["sh"]?.plugin)
}

@Test
fun `falls back to closest lower version`() {
    val loader = VersionedMetadataLoader()
    val metadata = loader.load("2.500.1") // Future version
    
    // Should fall back to highest available (e.g., 2.479)
    assertNotNull(metadata.steps["echo"])
}

@Test
fun `merges stable over versioned over bundled`() {
    val loader = VersionedMetadataLoader()
    val merged = loader.loadMerged("2.479.3")
    
    // Stable definitions should have all 5 'sh' parameters
    assertEquals(5, merged.steps["sh"]?.parameters?.size)
}
```

### Integration Tests

```kotlin
@Test
fun `version resolution matches expected behavior`() {
    val testCases = mapOf(
        "2.479.3" to "lts-2.479",
        "2.479.1" to "lts-2.479",
        "2.462.5" to "lts-2.462",
        "2.450.1" to "lts-2.440",  // Closest lower
        "2.300.1" to "default",     // No match, use default
        null to "default",
    )
    
    testCases.forEach { (input, expected) ->
        val path = loader.resolveMetadataPath(input?.extractLtsVersion())
        assertTrue(path.contains(expected))
    }
}
```

---

## Migration Path

### From Current Implementation

1. Current: Single `jenkins-stubs-metadata.json` bundled file
2. Add: `metadata/index.json` and `metadata/default/merged.json`
3. Deprecate: Direct `jenkins-stubs-metadata.json` loading
4. Generate: Version-specific metadata files via CI

### Backward Compatibility

- If `index.json` not found, fall back to legacy `jenkins-stubs-metadata.json`
- User-specified version is optional; null uses default

---

## Future Enhancements

1. **Delta Updates**: Only store diffs between versions
2. **Plugin-Level Versioning**: Track per-plugin version changes
3. **Auto-Detection**: Detect Jenkins version from project files
4. **Remote Metadata**: Fetch metadata from CDN/registry

---

## References

- [Jenkins LTS Release Notes](https://www.jenkins.io/changelog-stable/)
- [Jenkins Update Center](https://updates.jenkins.io/)
- [Pipeline Steps Reference](https://www.jenkins.io/doc/pipeline/steps/)

---

_Last updated: December 21, 2025_
