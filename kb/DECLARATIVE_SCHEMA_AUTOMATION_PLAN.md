# Declarative Pipeline Schema Automation Plan

**Status**: Planning\
**Created**: 2026-01-02\
**Related**:
[groovy-jenkins/src/main/resources/schemas/declarative-pipeline-schema.json](../groovy-jenkins/src/main/resources/schemas/declarative-pipeline-schema.json)

## Overview

This document outlines the plan to automate generation of the declarative pipeline schema from the official Jenkins
`pipeline-model-definition-plugin` sources, ensuring version awareness and compliance with specific Jenkins/plugin
releases.

## Current State

### What We Have

1. **Hand-Curated Schema**
   ([declarative-pipeline-schema.json](../groovy-jenkins/src/main/resources/schemas/declarative-pipeline-schema.json))
   - Static JSON listing sections, directives, and completion categories
   - No embedded version metadata
   - Manually maintained; prone to drift from actual Jenkins plugin

2. **Schema Loader**
   ([DeclarativePipelineSchema.kt](../groovy-jenkins/src/main/kotlin/com/github/albertocavalcante/groovyjenkins/metadata/declarative/DeclarativePipelineSchema.kt))
   - Loads JSON once at startup, caches in memory
   - Exposes `getCompletionCategories()`, `containsBlock()`, `getInnerInstructions()`
   - Uses `ignoreUnknownKeys = true` for forward compatibility

3. **Context Detector**
   ([JenkinsContextDetector.kt](../groovy-jenkins/src/main/kotlin/com/github/albertocavalcante/groovyjenkins/completion/JenkinsContextDetector.kt))
   - Regex-based block detection (pipeline, agent, stages, steps, etc.)
   - Brace counting for block stack management
   - Consults schema for block membership

4. **Existing Extractor** ([tools/jenkins-extractor](../tools/jenkins-extractor))
   - Converts GDSL → JSON metadata for steps/global variables
   - Already captures `jenkinsVersion`, `pluginId`, `pluginVersion`, checksums
   - Could be extended to extract declarative schema

### Gaps Identified

| Gap                                                | Impact                                                       | Priority |
| -------------------------------------------------- | ------------------------------------------------------------ | -------- |
| No version metadata in schema                      | Cannot warn when schema doesn't match controller             | High     |
| `when` conditions incomplete                       | Missing `equals`, `triggeredBy`, `isRestartedRun`            | Medium   |
| Agent types hardcoded                              | `docker`, `dockerfile` not from plugin                       | Medium   |
| No auto-generation                                 | Manual updates risk drift                                    | High     |
| Post conditions missing `notBuilt`, `unsuccessful` | Incomplete completions                                       | Low      |
| Declarative options incomplete                     | Missing `disableRestartFromStage`, `parallelsAlwaysFailFast` | Medium   |

## Source of Truth: pipeline-model-definition-plugin

### Key Resources

Located at: local checkout of `pipeline-model-definition-plugin` (for example:
`/path/to/pipeline-model-definition-plugin`)

| Resource                                                                            | Purpose                                             |
| ----------------------------------------------------------------------------------- | --------------------------------------------------- |
| `pipeline-model-api/src/main/resources/ast-schema.json`                             | Official JSON Schema for AST validation (706 lines) |
| `pipeline-model-definition/src/main/java/.../agent/impl/*.java`                     | Agent implementations with `@Symbol`                |
| `pipeline-model-definition/src/main/groovy/.../model/conditions/*.groovy`           | Post conditions with `@Symbol`                      |
| `pipeline-model-definition/src/main/java/.../when/impl/*.java`                      | When conditions with `@Symbol`                      |
| `pipeline-model-definition/src/main/java/.../options/impl/*.java`                   | Declarative options with `@Symbol`                  |
| `pipeline-model-extensions/src/main/java/.../agent/DeclarativeAgentDescriptor.java` | Extension point for agents                          |

### Official AST Schema Structure

The `ast-schema.json` defines:

- `pipeline` (root): requires `stages`, `agent`
- `stage`: has `branches`, `parallel`, `matrix`, `when`, `post`, `input`, `options`, `tools`, `environment`
- `when`: has `beforeAgent`, `beforeInput`, `beforeOptions`, `conditions`
- `post`: has `conditions` array of `buildCondition`
- `matrix`: has `axes`, `excludes`, `stages`

### Symbol Annotations Found

**Agents** (core plugin):

- `any`, `none`, `label`/`node`

**Agents** (external plugins):

- `kubernetes` (kubernetes-plugin)
- `docker`, `dockerfile` (docker-workflow-plugin)

**Post Conditions**:

- `always`, `success`, `failure`, `unstable`, `changed`, `fixed`, `regression`, `aborted`, `cleanup`, `notBuilt`,
  `unsuccessful`

**When Conditions**:

- `branch`, `buildingTag`, `changeset`, `changelog`, `expression`, `tag`, `changeRequest`, `allOf`, `anyOf`, `not`,
  `environment`, `equals`, `triggeredBy`, `isRestartedRun`

**Declarative Options**:

- `skipDefaultCheckout`, `skipStagesAfterUnstable`, `checkoutToSubdirectory`, `quietPeriod`, `parallelsAlwaysFailFast`,
  `disableRestartFromStage`

## Proposed Architecture

### Phase 1: Version-Aware Schema (Short-term)

Add version metadata to the schema without changing generation:

```json
{
  "$schema": "https://groovy-lsp.dev/schemas/declarative-pipeline-schema-v1.json",
  "schemaVersion": "1.0.0",
  "sourcePlugin": {
    "artifactId": "pipeline-model-definition",
    "version": "2.2277.v00573e73ddf1",
    "jenkinsBaseline": "2.479"
  },
  "generatedAt": "2026-01-02T00:00:00Z",
  "sections": [...],
  "directives": [...]
}
```

Update `DeclarativePipelineSchema.kt` to expose version info:

```kotlin
object DeclarativePipelineSchema {
    val schemaVersion: String get() = schema.schemaVersion
    val sourcePluginVersion: String? get() = schema.sourcePlugin?.version
    
    fun isCompatibleWith(jenkinsVersion: String): Boolean {
        // Compare against baseline
    }
}
```

### Phase 2: Schema Generator Tool (Medium-term)

Create `tools/declarative-schema-generator`:

```
tools/declarative-schema-generator/
├── build.gradle.kts
├── src/main/kotlin/
│   └── com/github/albertocavalcante/groovyjenkins/generator/
│       ├── DeclarativeSchemaGenerator.kt   # Main entry point
│       ├── AstSchemaParser.kt              # Parse ast-schema.json
│       ├── SymbolScanner.kt                # Scan @Symbol annotations
│       └── SchemaWriter.kt                 # Write output JSON
└── src/test/kotlin/
```

**Input Sources**:

1. `ast-schema.json` from plugin resources
2. Classpath scan for `@Symbol` annotations on:
   - `DeclarativeAgentDescriptor` subclasses
   - `BuildCondition` subclasses
   - `DeclarativeStageConditional` subclasses
   - `DeclarativeOption` subclasses

**Algorithm**:

```kotlin
fun generateSchema(pluginJar: Path, jenkinsVersion: String): DeclarativeSchema {
    // 1. Load ast-schema.json from JAR
    val astSchema = loadAstSchema(pluginJar)
    
    // 2. Scan for @Symbol annotations
    val agents = scanSymbols<DeclarativeAgentDescriptor>(pluginJar)
    val conditions = scanSymbols<BuildCondition>(pluginJar)
    val whenConditions = scanSymbols<DeclarativeStageConditional>(pluginJar)
    val options = scanSymbols<DeclarativeOption>(pluginJar)
    
    // 3. Build sections from ast-schema structure
    val sections = buildSections(astSchema, agents, conditions)
    
    // 4. Add metadata
    return DeclarativeSchema(
        schemaVersion = "1.0.0",
        sourcePlugin = PluginInfo(
            artifactId = "pipeline-model-definition",
            version = extractVersion(pluginJar)
        ),
        sections = sections,
        directives = buildDirectives(astSchema)
    )
}
```

### Phase 3: CI Integration (Long-term)

GitHub Actions workflow:

```yaml
name: Update Declarative Schema

on:
  schedule:
    - cron: '0 0 * * 1'  # Weekly
  workflow_dispatch:
    inputs:
      plugin_version:
        description: 'Plugin version to extract'
        required: false

jobs:
  update-schema:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Download plugin
        run: |
          mvn dependency:copy \
            -Dartifact=org.jenkinsci.plugins:pipeline-model-definition:${{ inputs.plugin_version || 'RELEASE' }}:hpi \
            -DoutputDirectory=./plugins
      
      - name: Generate schema
        run: ./gradlew :tools:declarative-schema-generator:run --args="./plugins/*.hpi"
      
      - name: Create PR if changed
        uses: peter-evans/create-pull-request@v5
        with:
          title: "chore(jenkins): Update declarative schema to ${{ env.PLUGIN_VERSION }}"
          branch: chore/update-declarative-schema
```

## Implementation Roadmap

### Milestone 1: Schema Versioning (Week 1)

- [ ] Add version metadata to `declarative-pipeline-schema.json`
- [ ] Update `DeclarativePipelineSchema.kt` to expose version info
- [ ] Add completeness fixes for missing symbols
- [ ] Write tests for version checking

### Milestone 2: Manual Generator (Weeks 2-3)

- [ ] Create `tools/declarative-schema-generator` module
- [ ] Implement `AstSchemaParser` for `ast-schema.json`
- [ ] Implement `SymbolScanner` using ASM or reflection
- [ ] Generate schema from local plugin clone
- [ ] Validate output against current hand-curated schema

### Milestone 3: Plugin-Aware Generation (Weeks 4-5)

- [ ] Support scanning external plugins (kubernetes, docker-workflow)
- [ ] Merge agent types from multiple sources
- [ ] Add documentation URLs from plugin metadata
- [ ] Handle plugin dependencies

### Milestone 4: CI Automation (Week 6)

- [ ] Create GitHub Actions workflow
- [ ] Set up Maven dependency resolution
- [ ] Implement PR creation on schema changes
- [ ] Add version matrix for LTS releases

## Technical Considerations

### Plugin JAR Scanning

Use ASM for bytecode analysis (already in classpath via Groovy):

```kotlin
fun scanSymbols(jar: Path): Map<String, SymbolInfo> {
    val symbols = mutableMapOf<String, SymbolInfo>()
    JarFile(jar.toFile()).use { jarFile ->
        jarFile.entries().asSequence()
            .filter { it.name.endsWith(".class") }
            .forEach { entry ->
                val reader = ClassReader(jarFile.getInputStream(entry))
                reader.accept(SymbolAnnotationVisitor(symbols), 0)
            }
    }
    return symbols
}
```

### Version Compatibility Matrix

| Jenkins LTS | pipeline-model-definition | Notable Changes      |
| ----------- | ------------------------- | -------------------- |
| 2.479.x     | 2.2277.v...               | isRestartedRun added |
| 2.462.x     | 2.2214.v...               | matrix support       |
| 2.426.x     | 2.2175.v...               | baseline             |

### External Plugin Coordination

Agent types from external plugins:

- **docker-workflow**: `docker`, `dockerfile`
- **kubernetes**: `kubernetes`

These should be extracted separately and merged, with the source plugin recorded:

```json
{
  "name": "kubernetes",
  "source": "kubernetes:4273.v1e..."
}
```

## Risks and Mitigations

| Risk                             | Mitigation                                             |
| -------------------------------- | ------------------------------------------------------ |
| Plugin API changes break scanner | Pin to specific plugin versions; test against multiple |
| External plugins not installed   | Make external agents optional; document requirements   |
| Schema drift between updates     | Weekly CI runs; alerts on PR creation                  |
| Performance of JAR scanning      | Cache results; only rescan on version change           |

## Success Criteria

1. Schema contains all symbols from `pipeline-model-definition` v2.2277+
2. Version metadata allows runtime compatibility checking
3. Automated updates via CI create PRs on plugin releases
4. External agent types (docker, kubernetes) correctly attributed
5. Zero manual edits to schema after automation is complete

## References

- [pipeline-model-definition-plugin](https://github.com/jenkinsci/pipeline-model-definition-plugin)
- [Jenkins Pipeline Syntax](https://www.jenkins.io/doc/book/pipeline/syntax/)
- [ast-schema.json](https://github.com/jenkinsci/pipeline-model-definition-plugin/blob/master/pipeline-model-api/src/main/resources/ast-schema.json)
- [Declarative Directive Generator](https://www.jenkins.io/doc/book/pipeline/getting-started/#directive-generator)
