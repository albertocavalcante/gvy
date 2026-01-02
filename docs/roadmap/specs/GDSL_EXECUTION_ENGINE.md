# GDSL Execution Engine

> **Status:** 📋 Draft\
> **Target Version:** 0.5.0\
> **Related:** [Jenkins IntelliSense](../../JENKINS_INTELLISENSE_ARCHITECTURE.md), [User Overrides](USER_OVERRIDES.md)\
> **Roadmap:** [← Back to Roadmap](../README.md)

---

## Overview

The GDSL (Groovy DSL) Execution Engine provides dynamic completion and type inference by executing GDSL scripts. This is
the same mechanism used by IntelliJ IDEA for custom DSL support.

Unlike text-based regex parsing, execution-based parsing correctly handles all GDSL constructs by leveraging Groovy's
dynamic nature.

---

## Motivation

### Current State

The `groovy-jenkins` module uses regex-based parsing to extract metadata from Jenkins GDSL output:

```kotlin
// Current approach (regex-based)
private val METHOD_PATTERN = Regex("""method\s*\(\s*name:\s*'([^']+)'[^)]*\)""")
```

**Problems:**

- Fragile: Breaks on edge cases (escaped quotes, multi-line, nested structures)
- Incomplete: Can't handle complex GDSL patterns
- Duplicated: IntelliJ's approach is well-tested but we reinvent the wheel

### Target State

Execute GDSL scripts via Groovy runtime, capturing the DSL contributions:

```kotlin
// Target approach (execution-based)
val executor = GdslExecutor()
val result = executor.executeAndCapture(gdslContent)
// result.methods, result.properties, result.closures
```

---

## Design

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      groovy-gdsl                            │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐    ┌─────────────────┐                │
│  │   GdslScript    │    │  GdslExecutor   │                │
│  │   (base class)  │───▶│  (runs scripts) │                │
│  └─────────────────┘    └────────┬────────┘                │
│                                  │                          │
│                                  ▼                          │
│  ┌─────────────────────────────────────────────────────────┤
│  │                 GdslContributor                          │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐    │
│  │  │ method()│  │property()│  │variable()│  │closure() │    │
│  │  └────┬────┘  └────┬────┘  └────┬────┘  └────┬────┘    │
│  └───────┼────────────┼───────────┼────────────┼───────────┤
│          │            │           │            │            │
│          ▼            ▼           ▼            ▼            │
│  ┌─────────────────────────────────────────────────────────┤
│  │                   Descriptors                            │
│  │  MethodDescriptor | PropertyDescriptor | ClosureDescriptor│
│  └─────────────────────────────────────────────────────────┤
│                                  │                          │
│                                  ▼                          │
│  ┌─────────────────────────────────────────────────────────┤
│  │                 GdslParseResult                          │
│  │  - methods: List<MethodDescriptor>                      │
│  │  - properties: List<PropertyDescriptor>                 │
│  │  - closures: List<ClosureDescriptor>                    │
│  │  - contexts: List<ContextDescriptor>                    │
│  └─────────────────────────────────────────────────────────┤
└─────────────────────────────────────────────────────────────┘
```

### Data Model

```kotlin
// groovy-gdsl/src/main/kotlin/descriptors/MethodDescriptor.kt

/**
 * Represents a method contribution from GDSL.
 * Inspired by IntelliJ's MethodDescriptor.
 */
data class MethodDescriptor(
    /** Method name */
    val name: String,
    
    /** Return type (fully qualified) */
    val returnType: String,
    
    /** Positional parameters */
    val parameters: List<ParameterDescriptor>,
    
    /** Named parameters (Groovy named args) */
    val namedParameters: List<NamedParameterDescriptor>,
    
    /** Documentation string */
    val documentation: String?,
    
    /** Documentation URL */
    val documentationUrl: String?,
    
    /** Whether this is a constructor */
    val isConstructor: Boolean = false,
    
    /** Whether this is a static method */
    val isStatic: Boolean = false,
    
    /** Containing class (for category methods) */
    val containingClass: String? = null,
    
    /** Exceptions thrown */
    val throws: List<String> = emptyList(),
)

data class ParameterDescriptor(
    val name: String,
    val type: String,
    val documentation: String? = null,
)

data class NamedParameterDescriptor(
    val name: String,
    val type: String,
    val documentation: String? = null,
    val required: Boolean = false,
    val defaultValue: String? = null,
)

data class PropertyDescriptor(
    val name: String,
    val type: String,
    val documentation: String? = null,
    val isStatic: Boolean = false,
)

data class ClosureDescriptor(
    /** Enclosing method name (e.g., "node" for node { }) */
    val methodName: String,
    
    /** Parameter types of the enclosing method */
    val methodParameterTypes: List<String>,
    
    /** Parameters available inside the closure */
    val parameters: List<ParameterDescriptor>,
    
    /** Delegate type for the closure */
    val delegateType: String? = null,
)

data class ContextDescriptor(
    /** Context filter (scope, ctype, filetypes) */
    val filter: ContextFilter,
    
    /** Contributions in this context */
    val contributions: List<Descriptor>,
)

sealed class ContextFilter {
    data class ScriptScope(val filetypes: List<String>) : ContextFilter()
    data class ClosureScope(val enclosingCall: String?) : ContextFilter()
    data class ClassScope(val ctype: String) : ContextFilter()
}
```

### GdslScript Base Class

```kotlin
// groovy-gdsl/src/main/kotlin/GdslScript.kt

abstract class GdslScript : Script() {
    
    internal val contributions = mutableListOf<ContextContribution>()
    
    /**
     * Define a context filter for contributions.
     */
    fun context(args: Map<String, Any?>): GdslContext {
        return GdslContext(
            scope = args["scope"] as? Map<String, Any?>,
            ctype = args["ctype"] as? String,
            filetypes = (args["filetypes"] as? List<*>)?.filterIsInstance<String>(),
        )
    }
    
    /**
     * Define contributions to a context.
     */
    fun contributor(contexts: Any?, closure: Closure<*>) {
        val contextList = when (contexts) {
            is List<*> -> contexts.filterIsInstance<GdslContext>()
            is GdslContext -> listOf(contexts)
            else -> return
        }
        
        contextList.forEach { ctx ->
            val contributor = GdslContributor(ctx)
            closure.delegate = contributor
            closure.resolveStrategy = Closure.DELEGATE_FIRST
            closure.call()
            
            contributions.add(ContextContribution(ctx, contributor.getDescriptors()))
        }
    }
    
    // Scope helpers
    fun scriptScope(args: Map<String, Any?> = emptyMap()) = mapOf("type" to "script") + args
    fun closureScope(args: Map<String, Any?> = emptyMap()) = mapOf("type" to "closure") + args
    fun classScope(args: Map<String, Any?> = emptyMap()) = mapOf("type" to "class") + args
}
```

### GdslContributor

````kotlin
// groovy-gdsl/src/main/kotlin/GdslContributor.kt

class GdslContributor(private val context: GdslContext) {
    
    private val methods = mutableListOf<MethodDescriptor>()
    private val properties = mutableListOf<PropertyDescriptor>()
    private val closures = mutableListOf<ClosureDescriptor>()
    
    /**
     * Define a method contribution.
     * 
     * Usage in GDSL:
     * ```
     * method(name: 'echo', type: 'void', params: [message: 'String'], doc: 'Print message')
     * ```
     */
    fun method(args: Map<String, Any?>) {
        val name = args["name"]?.toString() ?: return
        val type = stringifyType(args["type"])
        val params = parseParams(args["params"])
        val namedParams = parseNamedParams(args["namedParams"])
        val doc = args["doc"]?.toString()
        
        methods.add(MethodDescriptor(
            name = name,
            returnType = type,
            parameters = params,
            namedParameters = namedParams,
            documentation = doc,
            isConstructor = args["constructor"] == true,
            isStatic = args["isStatic"] == true,
        ))
    }
    
    /**
     * Define a property contribution.
     */
    fun property(args: Map<String, Any?>) {
        val name = args["name"]?.toString() ?: return
        val type = stringifyType(args["type"])
        val doc = args["doc"]?.toString()
        
        properties.add(PropertyDescriptor(
            name = name,
            type = type,
            documentation = doc,
        ))
    }
    
    /**
     * Define a named parameter (for use in method definitions).
     */
    fun parameter(args: Map<String, Any?>): NamedParameterDescriptor {
        return NamedParameterDescriptor(
            name = args["name"]?.toString() ?: "",
            type = stringifyType(args["type"]),
            documentation = args["doc"]?.toString(),
        )
    }
    
    /**
     * Define closure contributions (what's available inside a closure).
     */
    fun closureInMethod(args: Map<String, Any?>) {
        val methodArgs = args["method"] as? Map<*, *> ?: return
        val params = parseParams(args["params"])
        
        closures.add(ClosureDescriptor(
            methodName = methodArgs["name"]?.toString() ?: "",
            methodParameterTypes = emptyList(), // TODO: Parse from methodArgs
            parameters = params,
        ))
    }
    
    fun getDescriptors(): List<Descriptor> {
        return methods + properties + closures
    }
    
    private fun stringifyType(type: Any?): String = when (type) {
        null -> "java.lang.Object"
        is Class<*> -> type.name
        is Closure<*> -> "groovy.lang.Closure"
        is Map<*, *> -> "java.util.Map"
        else -> type.toString()
    }
    
    private fun parseParams(params: Any?): List<ParameterDescriptor> {
        val map = params as? Map<*, *> ?: return emptyList()
        return map.entries.mapNotNull { (key, value) ->
            if (value is List<*>) null // Skip named params block
            else ParameterDescriptor(key.toString(), stringifyType(value))
        }
    }
    
    private fun parseNamedParams(params: Any?): List<NamedParameterDescriptor> {
        val list = params as? List<*> ?: return emptyList()
        return list.filterIsInstance<NamedParameterDescriptor>()
    }
}
````

### GdslExecutor

```kotlin
// groovy-gdsl/src/main/kotlin/GdslExecutor.kt

class GdslExecutor {
    
    private val logger = LoggerFactory.getLogger(GdslExecutor::class.java)
    
    /**
     * Execute a GDSL script and capture all contributions.
     */
    fun executeAndCapture(gdslContent: String, scriptName: String = "script.gdsl"): GdslParseResult {
        return try {
            val config = CompilerConfiguration().apply {
                scriptBaseClass = GdslScript::class.java.name
            }
            
            val shell = GroovyShell(javaClass.classLoader, config)
            val script = shell.parse(gdslContent, scriptName) as GdslScript
            script.run()
            
            val contributions = script.contributions
            
            GdslParseResult(
                methods = contributions.flatMap { it.descriptors.filterIsInstance<MethodDescriptor>() },
                properties = contributions.flatMap { it.descriptors.filterIsInstance<PropertyDescriptor>() },
                closures = contributions.flatMap { it.descriptors.filterIsInstance<ClosureDescriptor>() },
                contexts = contributions.map { ContextDescriptor(it.context.toFilter(), it.descriptors) },
                success = true,
            )
        } catch (e: Exception) {
            logger.error("Failed to execute GDSL script: $scriptName", e)
            GdslParseResult(
                methods = emptyList(),
                properties = emptyList(),
                closures = emptyList(),
                contexts = emptyList(),
                success = false,
                error = e.message,
            )
        }
    }
}

data class GdslParseResult(
    val methods: List<MethodDescriptor>,
    val properties: List<PropertyDescriptor>,
    val closures: List<ClosureDescriptor>,
    val contexts: List<ContextDescriptor>,
    val success: Boolean,
    val error: String? = null,
)
```

---

## Integration with Jenkins Module

The `groovy-jenkins` module will use `groovy-gdsl` for parsing Jenkins GDSL:

```kotlin
// groovy-jenkins/src/main/kotlin/.../JenkinsGdslParser.kt

class JenkinsGdslParser(
    private val gdslExecutor: GdslExecutor = GdslExecutor()
) {
    
    /**
     * Parse Jenkins GDSL and convert to Jenkins-specific metadata.
     */
    fun parse(gdslContent: String): JenkinsMetadata {
        val result = gdslExecutor.executeAndCapture(gdslContent, "jenkins-pipeline.gdsl")
        
        if (!result.success) {
            logger.warn("GDSL parsing failed, falling back to bundled metadata: ${result.error}")
            return JenkinsMetadata.EMPTY
        }
        
        return JenkinsMetadata(
            steps = result.methods.map { it.toJenkinsStep() },
            globalVariables = result.properties.map { it.toGlobalVariable() },
        )
    }
    
    private fun MethodDescriptor.toJenkinsStep(): JenkinsStepMetadata {
        return JenkinsStepMetadata(
            name = this.name,
            plugin = "unknown", // Can be enriched from other sources
            parameters = this.namedParameters.associate { 
                it.name to StepParameter(it.name, it.type, !it.required, it.defaultValue, it.documentation)
            },
            documentation = this.documentation ?: "",
        )
    }
}
```

---

## Implementation Plan

### Phase 1: Core Descriptors (1-2 days)

- [ ] Create `MethodDescriptor`, `PropertyDescriptor`, `ClosureDescriptor` in `groovy-gdsl`
- [ ] Add comprehensive tests for descriptor serialization

### Phase 2: GdslContributor Enhancement (2-3 days)

- [ ] Implement `method()`, `property()`, `parameter()` capturing
- [ ] Handle `closureInMethod()` for closure delegate support
- [ ] Add `enclosingCall()` support for context detection

### Phase 3: GdslExecutor (1-2 days)

- [ ] Implement `executeAndCapture()` method
- [ ] Add error handling and logging
- [ ] Test with real Jenkins GDSL output

### Phase 4: Jenkins Integration (2-3 days)

- [ ] Create `JenkinsGdslParser` that uses `GdslExecutor`
- [ ] Migrate from regex-based parsing
- [ ] Update `UserMetadataLoader` to use new parser

### Phase 5: Context Support (2-3 days)

- [ ] Implement context filters (script scope, closure scope)
- [ ] Support `enclosingCall()` for node context detection
- [ ] Filter contributions by current context

---

## Testing Strategy

### Unit Tests

```kotlin
class GdslExecutorTest {
    
    @Test
    fun `executes simple method contribution`() {
        val gdsl = """
            contributor([context()]) {
                method(name: 'echo', type: 'void', params: [message: 'String'], doc: 'Print message')
            }
        """.trimIndent()
        
        val result = GdslExecutor().executeAndCapture(gdsl)
        
        assertTrue(result.success)
        assertEquals(1, result.methods.size)
        assertEquals("echo", result.methods[0].name)
        assertEquals("void", result.methods[0].returnType)
    }
    
    @Test
    fun `captures named parameters`() {
        val gdsl = """
            contributor([context()]) {
                method(name: 'sh', type: 'Object', namedParams: [
                    parameter(name: 'script', type: 'String'),
                    parameter(name: 'returnStdout', type: 'boolean'),
                ])
            }
        """.trimIndent()
        
        val result = GdslExecutor().executeAndCapture(gdsl)
        
        assertEquals(2, result.methods[0].namedParameters.size)
    }
}
```

### Integration Tests

```kotlin
class JenkinsGdslIntegrationTest {
    
    @Test
    fun `parses real Jenkins GDSL output`() {
        val gdslContent = javaClass.getResourceAsStream("/jenkins-gdsl-sample.groovy")!!
            .bufferedReader().readText()
        
        val parser = JenkinsGdslParser()
        val metadata = parser.parse(gdslContent)
        
        assertNotNull(metadata.steps["sh"])
        assertNotNull(metadata.steps["echo"])
        assertNotNull(metadata.globalVariables["env"])
    }
}
```

---

## References

- [IntelliJ GDSL Implementation](https://github.com/JetBrains/intellij-community/tree/master/plugins/groovy/groovy-psi/src/org/jetbrains/plugins/groovy/dsl)
- [Jenkins IntelliSense Architecture](../JENKINS_INTELLISENSE_ARCHITECTURE.md)
- [DSLD Reference](https://github.com/groovy/groovy-eclipse/wiki/DSL-Descriptors)

---

_Last updated: December 21, 2025_
