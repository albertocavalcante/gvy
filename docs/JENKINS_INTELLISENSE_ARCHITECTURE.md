# Jenkins IntelliSense Architecture

> Comprehensive versioned metadata architecture for Jenkins IntelliSense, combining GDSL extraction from a containerized
> Jenkins instance with static analysis.

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Metadata Sources](#metadata-sources)
- [Versioned Metadata Structure](#versioned-metadata-structure)
- [Stability Classification](#stability-classification)
- [User Configuration](#user-configuration)
- [Context-Aware Completions](#context-aware-completions)
- [CI/CD Extraction Pipeline](#cicd-extraction-pipeline)
- [Contributing](#contributing)

---

## Overview

The Jenkins IntelliSense system provides comprehensive auto-completion, hover documentation, and parameter hints for
Jenkinsfiles. It uses a multi-layer metadata architecture that combines:

1. **Stable Step Definitions** - Hardcoded core steps that rarely change (sh, echo, bat)
2. **GDSL-Extracted Metadata** - Authoritative step definitions from Jenkins runtime
3. **Versioned Metadata** - Per-LTS-version step definitions
4. **Dynamic Classpath Scanning** - User's actual plugin JARs
5. **User Overrides** - Custom GDSL or metadata for internal plugins

### Key Discovery: Jenkins GDSL Endpoint

Jenkins exposes a `/pipeline-syntax/gdsl` endpoint that generates a complete GDSL file dynamically based on installed
plugins. This is the **authoritative source** because:

- Uses `getFunctionName()` to correctly map class names to step names (e.g., `ShellStep` → `sh`)
- Uses `DescribableModel` for exact parameter extraction
- Plugin-aware: only includes steps from installed plugins
- Handles edge cases: parallel steps, meta-steps, closures

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Build-Time Generation                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│   ┌─────────────┐     ┌──────────────────┐     ┌────────────────────┐       │
│   │ plugins.txt │ ──▶ │ Jenkins Docker   │ ──▶ │ /pipeline-syntax/  │       │
│   │  manifest   │     │   Container      │     │   gdsl endpoint    │       │
│   └─────────────┘     └──────────────────┘     └─────────┬──────────┘       │
│                                                          │                   │
│                                              ┌───────────▼───────────┐       │
│                                              │    GDSL Parser        │       │
│                                              │   (Kotlin tool)       │       │
│                                              └───────────┬───────────┘       │
│                                                          │                   │
│                                              ┌───────────▼───────────┐       │
│                                              │  metadata/lts-2.479/  │       │
│                                              │  workflow-basic-*.json│       │
│                                              └───────────────────────┘       │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                              LSP Runtime                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│   ┌──────────────────┐   ┌──────────────────┐   ┌──────────────────┐        │
│   │ StableStepDefs   │   │ VersionedLoader  │   │ ClasspathScanner │        │
│   │   (hardcoded)    │   │ (bundled JSON)   │   │   (runtime)      │        │
│   └────────┬─────────┘   └────────┬─────────┘   └────────┬─────────┘        │
│            │                      │                      │                   │
│            └──────────────────────┼──────────────────────┘                   │
│                                   │                                          │
│                       ┌───────────▼───────────┐                              │
│                       │   MetadataMerger      │                              │
│                       │  (priority ordering)  │                              │
│                       └───────────┬───────────┘                              │
│                                   │                                          │
│                       ┌───────────▼───────────┐                              │
│                       │  ContextDetector      │                              │
│                       │ (env., post{}, etc.)  │                              │
│                       └───────────┬───────────┘                              │
│                                   │                                          │
│                       ┌───────────▼───────────┐                              │
│                       │ CompletionProvider    │                              │
│                       └───────────────────────┘                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Metadata Sources

### 1. Stable Step Definitions

Core Jenkins steps that haven't changed in years and are unlikely to change:

```kotlin
// StableStepDefinitions.kt
object StableStepDefinitions {
    val STEPS = mapOf(
        "sh" to JenkinsStepMetadata(
            name = "sh",
            plugin = "workflow-durable-task-step",
            documentation = "Execute a shell script",
            parameters = mapOf(
                "script" to StepParameter(type = "String", required = true),
                "returnStdout" to StepParameter(type = "boolean", required = false, default = "false"),
                "returnStatus" to StepParameter(type = "boolean", required = false, default = "false"),
                "encoding" to StepParameter(type = "String", required = false),
                "label" to StepParameter(type = "String", required = false),
            )
        ),
        "echo" to JenkinsStepMetadata(...),
        "bat" to JenkinsStepMetadata(...),
        // ... ~25 core steps
    )
}
```

### 2. GDSL-Extracted Metadata

Extracted from a running Jenkins instance via the `/pipeline-syntax/gdsl` endpoint:

```groovy
// Example GDSL output from Jenkins
def ctx = context(scope: scriptScope())
contributor(ctx) {
    method(name: 'sh', type: 'Object', namedParams: [
        parameter(name: 'script', type: 'String'),
        parameter(name: 'returnStdout', type: 'boolean'),
        parameter(name: 'returnStatus', type: 'boolean'),
        parameter(name: 'encoding', type: 'String'),
        parameter(name: 'label', type: 'String'),
    ], doc: 'Shell Script')
}
```

### 3. Dynamic Classpath Scanning

Uses ClassGraph to scan project dependencies for Jenkins plugin JARs:

```kotlin
// JenkinsClasspathScanner.kt
ClassGraph()
    .overrideClasspath(classpath)
    .enableAnnotationInfo()
    .enableMethodInfo()
    .scan()
    .use { scanResult ->
        // Find @Symbol, @DataBoundConstructor, @DataBoundSetter
    }
```

---

## Versioned Metadata Structure

### Directory Layout

```
groovy-jenkins/src/main/resources/metadata/
├── index.json                              # Version index and plugin mappings
├── lts-2.479/                              # Jenkins LTS 2.479.x
│   ├── manifest.json                       # Plugin versions for this LTS
│   ├── core.json                           # Core steps (always present)
│   ├── workflow-basic-steps-1098.json      # Per-plugin metadata
│   ├── workflow-durable-task-step-1464.json
│   └── ...
├── lts-2.492/                              # Jenkins LTS 2.492.x
│   └── ...
└── default/                                # Fallback for unknown versions
    └── merged.json                         # All common plugins merged
```

### Index Schema

```json
{
  "versions": {
    "lts-2.479": {
      "jenkinsVersion": "2.479.3",
      "releaseDate": "2024-10-15",
      "plugins": {
        "workflow-basic-steps": "1098.v808b_fd7f8cf4",
        "workflow-durable-task-step": "1464.v2d3f5c68f84c",
        "pipeline-stage-step": "309.v8c3455326cd7"
      }
    }
  },
  "stableSteps": ["sh", "bat", "echo", "node", "stage", "dir", "pwd"],
  "lastUpdated": "2024-12-21"
}
```

### Per-Plugin Metadata Schema

```json
{
  "plugin": {
    "artifactId": "workflow-durable-task-step",
    "version": "1464.v2d3f5c68f84c",
    "jenkinsVersion": "2.479",
    "extractedAt": "2024-12-21T10:00:00Z",
    "source": "gdsl"
  },
  "steps": {
    "sh": {
      "name": "sh",
      "displayName": "Shell Script",
      "documentation": "Execute a shell script on Unix-like systems",
      "requiresNode": true,
      "takesBlock": false,
      "parameters": {
        "script": {
          "type": "String",
          "required": true,
          "documentation": "The shell script to execute"
        }
      },
      "namedParameters": {
        "script": { "type": "String", "required": true },
        "returnStdout": { "type": "boolean", "required": false, "default": "false" },
        "returnStatus": { "type": "boolean", "required": false, "default": "false" },
        "encoding": { "type": "String", "required": false },
        "label": { "type": "String", "required": false }
      }
    }
  }
}
```

---

## Stability Classification

Steps are classified by how frequently they change:

| Level           | Description                     | Refresh Frequency | Examples                   |
| --------------- | ------------------------------- | ----------------- | -------------------------- |
| **Stable**      | Core steps, unchanged for years | Never (hardcoded) | sh, bat, echo, node, stage |
| **Semi-Stable** | Rarely changes                  | Yearly            | timeout, retry, parallel   |
| **Active**      | Occasionally updated            | Per LTS release   | withCredentials, junit     |
| **Volatile**    | Frequently changing             | Monthly           | Third-party plugins        |

### Resolution Order

When loading metadata, sources are merged in this priority order:

1. **User overrides** (highest priority)
2. **Dynamic classpath scan**
3. **Stable step definitions**
4. **Versioned metadata** (for configured Jenkins version)
5. **Default bundled metadata** (fallback)

---

## User Configuration

Users can configure Jenkins IntelliSense via `.groovy-lsp/jenkins.json` in their workspace:

```json
{
  "jenkinsVersion": "2.479.3",
  "gdslFile": ".jenkins/pipeline.gdsl",
  "plugins": {
    "workflow-durable-task-step": "1464.v2d3f5c68f84c",
    "custom-internal-plugin": "local"
  },
  "metadataOverrides": {
    "myCustomStep": {
      "plugin": "custom-internal-plugin",
      "documentation": "Our custom deployment step",
      "parameters": {
        "environment": { "type": "String", "required": true },
        "version": { "type": "String", "required": false }
      }
    }
  }
}
```

### User GDSL File

Users can download their Jenkins instance's GDSL and include it:

```bash
# Download from your Jenkins
curl https://jenkins.example.com/pipeline-syntax/gdsl > .jenkins/pipeline.gdsl
```

The LSP will parse this GDSL and merge it with bundled metadata.

---

## Context-Aware Completions

The LSP detects the current context to provide relevant completions:

### Context Types

| Context          | Pattern                | Completions Provided                                 |
| ---------------- | ---------------------- | ---------------------------------------------------- |
| `env.`           | `env.\w*$`             | Environment variables (BUILD_NUMBER, JOB_NAME, etc.) |
| `post {}`        | Inside post block      | Post conditions (always, success, failure, etc.)     |
| `options {}`     | Inside options block   | Declarative options (timeout, timestamps, etc.)      |
| `agent {}`       | Inside agent block     | Agent types (any, none, docker, kubernetes, etc.)    |
| `properties([])` | Inside properties call | Job properties (disableConcurrentBuilds, etc.)       |
| Step parameters  | Inside step call       | Step-specific parameters                             |

### Example

```groovy
pipeline {
    agent any
    options {
        // Context: options block
        // Completions: timestamps, timeout, disableConcurrentBuilds, ...
        timestamps()
        disableConcurrentBuilds(abortPrevious: true)
    }
    stages {
        stage('Build') {
            steps {
                // Context: steps block
                sh script: 'make', returnStdout: true
                //         ^^^^^^  ^^^^^^^^^^^^
                //         Context: sh step parameters
            }
        }
    }
    post {
        // Context: post block
        // Completions: always, success, failure, unstable, ...
        always {
            cleanWs()
        }
    }
}
```

---

## CI/CD Extraction Pipeline

### GitHub Actions Workflow

```yaml
# .github/workflows/extract-jenkins-metadata.yml
name: Extract Jenkins Metadata

on:
  schedule:
    - cron: '0 0 1 */3 *'  # Quarterly for LTS releases
  workflow_dispatch:
    inputs:
      jenkins_version:
        description: 'Jenkins LTS version'
        required: true
        default: '2.479.3'

jobs:
  extract:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      
      - name: Build Jenkins extractor image
        run: docker build -t jenkins-extractor tools/jenkins-extractor
      
      - name: Start Jenkins and extract GDSL
        run: ./tools/jenkins-extractor/extract.sh
        
      - name: Convert GDSL to JSON
        run: ./gradlew :tools:jenkins-extractor:run
        
      - name: Commit metadata
        run: |
          git config user.name "github-actions[bot]"
          git config user.email "github-actions[bot]@users.noreply.github.com"
          git add groovy-jenkins/src/main/resources/metadata/
          git commit -m "chore: update Jenkins metadata for ${{ inputs.jenkins_version }}"
          git push
```

### Extraction Tool

The extraction tool:

1. Starts a Jenkins Docker container with target plugins
2. Waits for Jenkins to become ready
3. Fetches `/pipeline-syntax/gdsl` and `/pipeline-syntax/globals`
4. Parses GDSL into structured JSON
5. Generates per-plugin metadata files

---

## Contributing

### Adding a New Stable Step

1. Verify the step hasn't changed in the last 3+ years
2. Add to `StableStepDefinitions.kt`:

```kotlin
"newStep" to JenkinsStepMetadata(
    name = "newStep",
    plugin = "plugin-id",
    documentation = "Description from Jenkins docs",
    parameters = mapOf(
        "param1" to StepParameter(type = "String", required = true),
    )
)
```

3. Add test in `StableStepDefinitionsTest.kt`
4. Submit PR

### Updating Versioned Metadata

Metadata is automatically updated quarterly via CI. For manual updates:

1. Run the extraction tool locally:
   ```bash
   ./tools/jenkins-extractor/extract.sh
   ```

2. Review generated JSON files
3. Submit PR with updated metadata

### Adding Context Detection

1. Add pattern to `JenkinsContextDetector.kt`
2. Add corresponding completions
3. Add tests following TDD
4. Submit PR

---

## Related Documents

- [JENKINS_SMART_COMPLETION_PROPOSAL.md](../JENKINS_SMART_COMPLETION_PROPOSAL.md) - Original proposal
- [groovy-jenkins README](../groovy-jenkins/README.md) - Module documentation
- [Jenkins Pipeline Syntax](https://www.jenkins.io/doc/book/pipeline/syntax/) - Official docs
