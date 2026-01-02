# CodeNarc Integration Guide

This document captures critical knowledge about CodeNarc integration, DSL syntax, common pitfalls, and debugging
techniques learned while implementing CodeNarc support in the Groovy LSP.

## Table of Contents

- [CodeNarc DSL Syntax Rules](#codenarc-dsl-syntax-rules)
- [Common Errors and Solutions](#common-errors-and-solutions)
- [Rule Existence and Verification](#rule-existence-and-verification)
- [Ruleset Architecture](#ruleset-architecture)
- [Project-Specific Configurations](#project-specific-configurations)
- [Debugging Techniques](#debugging-techniques)
- [Best Practices](#best-practices)
- [Performance Considerations](#performance-considerations)

## CodeNarc DSL Syntax Rules

### Critical DSL Constraints

CodeNarc's Groovy DSL has **strict syntax rules** that are not well documented but cause fatal errors when violated:

#### ❌ WRONG: Rule Configuration Inside Ruleset Blocks

```groovy
// THIS WILL FAIL with "No such rule named [rule]"
ruleset('rulesets/unused.xml') {
    rule('UnusedVariable') {  // ❌ Cannot configure rules inside ruleset()
        ignoreVariableNames = 'env,params,currentBuild'
    }
}
```

#### ✅ CORRECT: Separate Rule Configuration

```groovy
// First, exclude from bulk import
ruleset('rulesets/unused.xml') {
    exclude 'UnusedVariable'  // ✅ Only include/exclude allowed
}

// Then configure separately
UnusedVariable {  // ✅ Rule name without quotes, outside ruleset block
    ignoreVariableNames = 'env,params,currentBuild'
}
```

#### ❌ WRONG: Standalone Rule Calls

```groovy
// THIS WILL FAIL with "No such rule named [RuleName]"
rule('CatchNullPointerException')  // ❌ Standalone rule() call
rule('TrailingWhitespace')         // ❌ Without proper import/config
```

#### ✅ CORRECT: Proper Rule Import

```groovy
// Import from appropriate ruleset
ruleset('rulesets/exceptions.xml') {
    include 'CatchNullPointerException'  // ✅ Proper include
}

ruleset('rulesets/formatting.xml') {
    include 'TrailingWhitespace'        // ✅ Proper include
}
```

### DSL Structure Rules

1. **Inside `ruleset()` blocks**: Only `include` and `exclude` directives allowed
2. **Outside `ruleset()` blocks**: Individual rule configuration using rule name directly
3. **Rule names**: No quotes when configuring (`RuleName {}`, not `rule('RuleName') {}`)
4. **Nested configuration**: Cannot nest rule configuration inside ruleset includes

## Common Errors and Solutions

### Error: "No such rule named [rule]"

**Cause**: Using `rule()` method inside a `ruleset()` block

**Stack Trace Pattern**:

```
java.lang.AssertionError: No such rule named [rule]
    at org.codenarc.ruleset.RuleSetDelegate.methodMissing(RuleSetBuilder.groovy:149)
```

**Solution**: Move rule configuration outside the ruleset block

### Error: "No such rule named [RuleName]"

**Cause**: The rule doesn't exist in the current CodeNarc version

**Stack Trace Pattern**:

```
java.lang.AssertionError: No such rule named [ScriptNotInClass]
    at org.codenarc.ruleset.TopLevelDelegate.methodMissing(RuleSetBuilder.groovy:112)
```

**Solution**: Verify rule existence and use alternative approaches

## Rule Existence and Verification

### Non-Existent Rules (as of CodeNarc 3.5.0)

> **Note:** This list is accurate as of CodeNarc 3.5.0. Please check the
> [CodeNarc release notes](https://github.com/CodeNarc/CodeNarc/releases) or documentation for updates in newer
> versions. These rules **do not exist** and will cause fatal errors:

```groovy
// ❌ These rules DON'T EXIST
ScriptNotInClass { enabled = false }           // No such rule
VariableTypeRequired { enabled = false }       // No such rule
UnnecessaryGetter { enabled = false }          // No such rule
UnnecessaryPublicModifier { enabled = false }  // No such rule
```

### Rule Verification Techniques

1. **Check CodeNarc Source**: Look at official CodeNarc GitHub repository
2. **Test Incrementally**: Comment out suspected rules and test
3. **Use Existing Rulesets**: Stick to well-documented standard rulesets
4. **Error Message Analysis**: "No such rule named [X]" means rule X doesn't exist

### Safe Alternative Approaches

Instead of non-existent rules, use ruleset exclusions:

```groovy
// ✅ Use ruleset exclusions instead of non-existent rules
ruleset('rulesets/convention.xml') {
    exclude 'CompileStatic'  // Skip static compilation requirements
    exclude 'NoDef'          // Allow 'def' usage in scripts
}

ruleset('rulesets/groovyism.xml') {
    exclude 'ExplicitCallToGetAtMethod'
    exclude 'ExplicitCallToPutAtMethod'
}
```

## Ruleset Architecture

### Standard CodeNarc Rulesets

CodeNarc provides these built-in rulesets (bundled in CodeNarc JAR):

- `rulesets/basic.xml` - Fundamental code quality rules
- `rulesets/imports.xml` - Import organization rules
- `rulesets/unused.xml` - Unused code detection
- `rulesets/exceptions.xml` - Exception handling rules
- `rulesets/formatting.xml` - Code formatting rules
- `rulesets/convention.xml` - Groovy coding conventions
- `rulesets/groovyism.xml` - Groovy-specific best practices
- `rulesets/naming.xml` - Naming convention rules
- `rulesets/size.xml` - Code size and complexity rules
- `rulesets/jenkins.xml` - Jenkins CPS safety rules (7 rules)

### Ruleset Resolution and Fallback

The LSP uses a hierarchical ruleset resolution strategy:

1. **Workspace Configuration Files** (highest priority)
   - `.codenarc` in workspace root
   - `config/codenarc/rules.groovy`
   - `codenarc.groovy`

2. **Custom DSL Rulesets** (project-type specific)
   - `codenarc/rulesets/frameworks/jenkins.groovy` for Jenkins projects
   - `codenarc/rulesets/base/default.groovy` for plain Groovy projects

3. **Bundled CodeNarc Rulesets** (fallback)
   - `rulesets/jenkins.xml` for Jenkins projects
   - `rulesets/basic.xml` for other projects

**Fallback Behavior**: If custom DSL rulesets are missing from the classpath (e.g., when running as fat JAR without
resources), the LSP automatically generates a minimal DSL wrapper that references CodeNarc's bundled XML rulesets. This
prevents `IllegalStateException` crashes and ensures diagnostics continue to work.

**Example Fallback**:

```groovy
// Generated when custom ruleset missing
ruleset {
    description 'Fallback to CodeNarc bundled ruleset'
    ruleset('rulesets/jenkins.xml')  // For Jenkins projects
}
```

### Ruleset Hierarchy

```
Custom Ruleset File (jenkins.groovy)
├── ruleset('rulesets/basic.xml') { exclude 'SomeRule' }
├── ruleset('rulesets/imports.xml') { include 'SpecificRule' }
├── CustomRule { property = value }
└── AnotherCustomRule { enabled = false }
```

### Include vs Exclude Strategies

**Inclusive Strategy** (recommended for minimal rulesets):

```groovy
ruleset('rulesets/exceptions.xml') {
    include 'CatchNullPointerException'
    include 'CatchArrayIndexOutOfBoundsException'
}
```

**Exclusive Strategy** (recommended for comprehensive rulesets):

```groovy
ruleset('rulesets/basic.xml') {
    exclude 'ExplicitHashSetInstantiation'  // Allow new HashSet()
    exclude 'ExplicitArrayListInstantiation' // Allow new ArrayList()
}
```

## Configuration

### Disabling CodeNarc

CodeNarc diagnostics can be disabled via LSP configuration:

```json
{
  "groovy.codenarc.enabled": false
}
```

When disabled, CodeNarc analysis is skipped entirely (no ruleset loading, no diagnostics).

### Configuration Options

| Setting                          | Type    | Default | Description                                    |
| -------------------------------- | ------- | ------- | ---------------------------------------------- |
| `groovy.codenarc.enabled`        | boolean | `true`  | Enable/disable CodeNarc diagnostics            |
| `groovy.codenarc.propertiesFile` | string  | `null`  | Explicit path to CodeNarc properties file      |
| `groovy.codenarc.autoDetect`     | boolean | `true`  | Auto-detect CodeNarc config files in workspace |

## Project-Specific Configurations

### Jenkins Projects

**Automatic Detection**: Jenkins projects are automatically detected by the presence of:

- `Jenkinsfile` in the workspace root
- `vars/` directory (Jenkins shared library)
- `resources/` directory (Jenkins shared library resources)

**Bundled Jenkins Rules**: The LSP includes CodeNarc's bundled `rulesets/jenkins.xml` which provides 7 Jenkins-specific
CPS (Continuation Passing Style) rules:

1. **ClassNotSerializable** - Classes should implement Serializable for CPS transformation
2. **ClosureInGString** - Closures in GStrings cause CPS runtime errors
3. **CpsCallFromNonCpsMethod** - CPS methods cannot be called from non-CPS methods
4. **ExpressionInCpsMethodNotSerializable** - Expressions in CPS methods must be Serializable
5. **ForbiddenCallInCpsMethod** - Non-CPS methods cannot be called with CPS closures
6. **ObjectOverrideOnlyNonCpsMethods** - Overridden Object methods must be @NonCPS
7. **ParameterOrReturnTypeNotSerializable** - Parameters and return types must be Serializable

**Custom Jenkins Ruleset**: The LSP provides a custom Jenkins ruleset at `codenarc/rulesets/frameworks/jenkins.groovy`
that:

- Includes all bundled Jenkins CPS rules
- Adds basic code quality rules (excluding patterns common in Jenkinsfiles)
- Configures `CpsCallFromNonCpsMethodRule` for common Jenkins patterns

**Fallback Mechanism**: If custom rulesets are missing from the classpath, the LSP automatically falls back to
CodeNarc's bundled XML rulesets, preventing crashes.

**Customizing Jenkins Rules** (for custom rulesets):

Jenkins projects require special handling due to DSL patterns and implicit variables:

```groovy
// Jenkins implicit variables that should be ignored
UnusedVariable {
    ignoreVariableNames = 'env,params,currentBuild,BUILD_NUMBER,JOB_NAME,WORKSPACE,NODE_NAME,scm'
}

// Jenkins DSL method patterns
MethodName {
    regex = /^[a-z][a-zA-Z0-9_]*$|^call$|^pipeline$|^agent$|^stages$|^stage$|^steps$|^sh$|^bat$|^script$|^node$|^build$|^checkout$|^git$|^parallel$/
}

// Jenkins variable patterns (BUILD_NUMBER, etc.)
VariableName {
    regex = /^[a-z][a-zA-Z0-9_]*$|^[A-Z][A-Z0-9_]*$/
    ignoreVariableNames = 'env,params,currentBuild,BUILD_NUMBER,JOB_NAME,WORKSPACE,NODE_NAME,BRANCH_NAME,CHANGE_ID'
}
```

### Gradle Projects

```groovy
// Gradle DSL patterns
MethodName {
    regex = /^[a-z][a-zA-Z0-9_]*$|^apply$|^dependencies$|^repositories$|^task$|^gradle$/
}

// Allow Gradle configuration patterns
ruleset('rulesets/basic.xml') {
    exclude 'ExplicitCallToAndMethod'    // Allow gradle.property && gradle.other
    exclude 'ExplicitCallToOrMethod'     // Allow fallback patterns
}
```

### Spock Testing Framework

```groovy
// Spock method naming (given_when_then pattern)
MethodName {
    regex = /^[a-z][a-zA-Z0-9_]*$|^(given|when|then|setup|cleanup|where).*$/
}

// Spock allows def for test methods
ruleset('rulesets/convention.xml') {
    exclude 'NoDef'
}
```

## Debugging Techniques

### LSP Integration Debugging

To debug CodeNarc issues in LSP context:

```bash
# Test LSP initialization with Jenkins project
cd /path/to/jenkins/project
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"rootUri":"file:///path/to/jenkins/project","capabilities":{}}}' | \
  timeout 15s java -jar groovy-lsp.jar 2>&1 | \
  grep -E "(Exception|Error|AssertionError)"
```

### API Discovery and Class Investigation

When integrating with CodeNarc APIs, use these techniques to discover available methods and properties:

#### 1. Examine JAR Contents

```bash
# List all classes in CodeNarc JAR
jar tf ~/.gradle/caches/modules-2/files-2.1/org.codenarc/CodeNarc/3.5.0-groovy-4.0/*/CodeNarc-3.5.0-groovy-4.0.jar | grep -E "\.class$" | head -20

# Find specific classes
jar tf CodeNarc-3.5.0.jar | grep -i "analyzer"
```

#### 2. Inspect Class APIs with javap

```bash
# Examine FilesSourceAnalyzer API
javap -cp build/libs/groovy-lsp.jar org.codenarc.analyzer.FilesSourceAnalyzer | grep -E "public|set"

# Check for property setters and getters
javap -cp CodeNarc.jar org.codenarc.analyzer.FilesSourceAnalyzer | grep -E "(set|get)[A-Z]"

# View all methods and fields
javap -cp CodeNarc.jar -p org.codenarc.analyzer.FilesSourceAnalyzer
```

#### 3. Groovy vs Kotlin Property Access Issues

When Kotlin can't access Groovy property setters directly:

```kotlin
// ❌ FAILS: Direct property assignment
sourceAnalyzer.baseDirectory = tempDir.toString()  // 'val' cannot be reassigned

// ✅ WORKS: Use reflection to access Groovy properties
sourceAnalyzer = FilesSourceAnalyzer().also { analyzer ->
    // Use reflection to set properties since Kotlin can't access Groovy property setters directly
    analyzer.javaClass.getDeclaredField("baseDirectory").apply {
        isAccessible = true
        set(analyzer, tempDir.toString())
    }
    analyzer.javaClass.getDeclaredField("sourceFiles").apply {
        isAccessible = true
        set(analyzer, arrayOf(tempSourceFile.fileName.toString()))
    }
}
```

#### 4. CodeNarc Class Hierarchy Investigation

```bash
# Find all analyzer implementations
jar tf CodeNarc.jar | grep -i analyzer | grep -E "\.class$"

# Find all source analyzer types
javap -cp CodeNarc.jar org.codenarc.analyzer.SourceAnalyzer | grep -E "interface|class"

# Check what FilesSourceAnalyzer extends/implements
javap -cp CodeNarc.jar org.codenarc.analyzer.FilesSourceAnalyzer | head -5
```

#### 5. CodeNarc Runner Configuration Discovery

```bash
# Examine CodeNarcRunner properties and methods
javap -cp CodeNarc.jar org.codenarc.CodeNarcRunner | grep -E "(set|get|public)"

# Find available configuration options
javap -cp CodeNarc.jar -p org.codenarc.CodeNarcRunner | grep -E "private.*[Ss]et"
```

### Incremental Debugging

1. **Start with empty ruleset**:

```groovy
ruleset {
    description 'Minimal test ruleset'
    // Add rules one by one
}
```

2. **Add rulesets incrementally**:

```groovy
// Test each ruleset separately
ruleset('rulesets/basic.xml')     // Test 1
// ruleset('rulesets/imports.xml')   // Test 2 (commented out)
// CustomRule { }                    // Test 3 (commented out)
```

3. **Check specific rules**:

```groovy
// Test individual rule existence
SomeRule {
    enabled = true  // Will fail if rule doesn't exist
}
```

### Error Pattern Analysis

**Syntax Error Pattern**:

```
No such rule named [rule]
    at RuleSetDelegate.methodMissing(RuleSetBuilder.groovy:149)
```

→ **Fix**: Move rule configuration outside ruleset block

**Non-existent Rule Pattern**:

```
No such rule named [ScriptNotInClass]
    at TopLevelDelegate.methodMissing(RuleSetBuilder.groovy:112)
```

→ **Fix**: Remove or replace with existing rule

### Testing Configuration Changes

```bash
# Rebuild with changes
./gradlew shadowJar

# Test specific project type
cd /path/to/test/project
timeout 10s java -jar groovy-lsp.jar < test-input.json 2>&1 | grep -E "(ERROR|Exception)"
```

### MockK Integration for Testing

When writing tests for CodeNarc integration, use MockK for dependency mocking:

```kotlin
// Example: Testing CodeNarc service with mocked configuration
@Test
fun `should handle CodeNarc analysis with custom configuration`() = runBlocking {
    val mockConfig = mockk<ConfigurationProvider> {
        every { getServerConfiguration() } returns ServerConfiguration().apply {
            // Configure test-specific settings
        }
        every { getWorkspaceRoot() } returns Paths.get("/test/workspace")
    }

    val service = CodeNarcService(mockConfig)
    val result = service.analyzeString("def test() { }", URI.create("file:///test.groovy"))

    verify { mockConfig.getServerConfiguration() }
    assertNotNull(result)
}

// Example: Behavior verification for CodeNarc calls
@Test
fun `should call configuration provider methods correctly`() = runBlocking {
    val mockConfig = mockk<ConfigurationProvider>(relaxed = true)

    val service = CodeNarcService(mockConfig)
    service.analyzeString("println 'test'", URI.create("file:///example.groovy"))

    verify(atLeast = 1) { mockConfig.getWorkspaceRoot() }
    verify { mockConfig.getServerConfiguration() }
}
```

### Live Debugging with Real Projects

Test the LSP server directly with real-world projects that had issues:

```bash
# Test with JenkinsPipelineUnit workspace (had VirtualResults issues)
echo '{"method":"initialize","params":{"rootUri":"file:///path/to/workspace/JenkinsPipelineUnit","capabilities":{}}}' | \
  timeout 10s java -jar build/libs/groovy-lsp-0.1.0-SNAPSHOT.jar 2>&1 | \
  grep -E "(VirtualResults|MockClosure\.groovy)" || echo "✅ No VirtualResults errors"

# Test with problematic Gradle projects
echo '{"method":"initialize","params":{"rootUri":"file:///path/to/gradle/project","capabilities":{}}}' | \
  timeout 10s java -jar build/libs/groovy-lsp-0.1.0-SNAPSHOT.jar 2>&1 | \
  grep -E "(Exception|Error|WARN.*failed)"

# Test with Jenkins pipeline files
echo '{"method":"initialize","params":{"rootUri":"file:///path/to/jenkins/workspace","capabilities":{}}}' | \
  timeout 15s java -jar build/libs/groovy-lsp-0.1.0-SNAPSHOT.jar 2>&1 | \
  grep -E "(pipeline|Jenkinsfile|buildfile)"
```

## Best Practices

### 1. Start Conservative

Begin with minimal, well-tested rulesets:

```groovy
ruleset {
    description 'Conservative starter ruleset'

    // Start with basic quality rules only
    ruleset('rulesets/basic.xml') {
        exclude 'ExplicitHashSetInstantiation'  // Common exclusion
    }

    // Add more rulesets gradually
}
```

### 2. Use Exclusion Strategy

Prefer excluding unwanted rules from comprehensive rulesets rather than including specific rules:

```groovy
// ✅ GOOD: Comprehensive with exclusions
ruleset('rulesets/basic.xml') {
    exclude 'RuleA'
    exclude 'RuleB'
    exclude 'RuleC'
}

// ❌ AVOID: Manual inclusion (error-prone)
ruleset('rulesets/basic.xml') {
    include 'Rule1'
    include 'Rule2'
    // Easy to miss important rules
}
```

### 3. Document Rule Decisions

```groovy
ruleset {
    description 'Jenkins Pipeline Ruleset'

    // Basic quality (exclude Jenkins DSL conflicts)
    ruleset('rulesets/basic.xml') {
        exclude 'ExplicitHashSetInstantiation'  // Jenkins: new HashSet() is clear
        exclude 'ExplicitArrayListInstantiation' // Jenkins: new ArrayList() is clear
        exclude 'HardCodedWindowsFileSeparator'  // Jenkins: May be intentional
    }

    // Variables (allow Jenkins implicit variables)
    UnusedVariable {
        // Jenkins provides these variables implicitly in pipeline context
        ignoreVariableNames = 'env,params,currentBuild,BUILD_NUMBER,JOB_NAME,WORKSPACE,NODE_NAME,scm'
    }
}
```

### 4. Project Type Detection

Structure rulesets by project type:

```
src/main/resources/codenarc/rulesets/
├── frameworks/
│   ├── jenkins.groovy     # Jenkins-specific rules
│   ├── gradle.groovy      # Gradle build scripts
│   └── spock.groovy       # Spock testing framework
├── general/
│   ├── strict.groovy      # Strict rules for libraries
│   └── lenient.groovy     # Lenient rules for scripts
└── default.groovy         # Fallback ruleset
```

### 5. Validation Testing

Create test suite for rulesets:

```groovy
// Test that ruleset loads without errors
@Test
void "jenkins ruleset loads successfully"() {
    def ruleset = new GroovyDslRuleSet('codenarc/rulesets/frameworks/jenkins.groovy')
    assert ruleset.rules.size() > 0
    // Should not throw AssertionError
}
```

## Integration Patterns and Architecture

### Kotlin-Groovy Interoperability Challenges

When integrating CodeNarc (written in Groovy) with Kotlin LSP code:

#### Property Access Patterns

```kotlin
// ❌ PROBLEM: Kotlin sees Groovy properties as 'val' (read-only)
val analyzer = FilesSourceAnalyzer()
analyzer.baseDirectory = "/tmp"  // Error: 'val' cannot be reassigned

// ✅ SOLUTION: Use reflection for Groovy property setters
analyzer.javaClass.getDeclaredField("baseDirectory").apply {
    isAccessible = true
    set(analyzer, "/tmp")
}

// ✅ ALTERNATIVE: Use Groovy-style method calls if available
analyzer.setBaseDirectory("/tmp")  // If method exists
```

#### Facade Pattern for Clean Integration

```kotlin
// Create Kotlin-friendly facade over Groovy CodeNarc API
class CodeNarcFacade {
    fun createAnalyzer(baseDir: String, sourceFiles: Array<String>): SourceAnalyzer {
        return FilesSourceAnalyzer().also { analyzer ->
            setProperty(analyzer, "baseDirectory", baseDir)
            setProperty(analyzer, "sourceFiles", sourceFiles)
        }
    }

    private fun setProperty(obj: Any, propertyName: String, value: Any) {
        obj.javaClass.getDeclaredField(propertyName).apply {
            isAccessible = true
            set(obj, value)
        }
    }
}
```

### Service Architecture Pattern

The current implementation uses a layered approach:

```
┌─────────────────────────────────┐
│     GroovyTextDocumentService   │  (LSP4J Integration)
├─────────────────────────────────┤
│        CodeNarcService          │  (Kotlin Service Layer)
├─────────────────────────────────┤
│      CodeAnalysisService        │  (Analysis Orchestration)
├─────────────────────────────────┤
│        CodeAnalyzer             │  (Groovy API Facade)
├─────────────────────────────────┤
│     CodeNarc (Groovy Library)   │  (External Dependency)
└─────────────────────────────────┘
```

### Temporal File Management

CodeNarc requires file-based input, leading to this pattern:

```kotlin
// Pattern: Temporary file lifecycle management
suspend fun analyzeString(source: String, uri: URI): List<Diagnostic> {
    val tempDir = Files.createTempDirectory("codenarc-analysis-")
    return try {
        val tempSourceFile = Files.createTempFile(tempDir, "source-", ".groovy")
        val tempRulesetFile = Files.createTempFile(tempDir, "ruleset-", ".groovy")

        // Write files, analyze, convert results
        Files.write(tempSourceFile, source.toByteArray(StandardCharsets.UTF_8))
        Files.write(tempRulesetFile, rulesetContent.toByteArray(StandardCharsets.UTF_8))

        performAnalysis(tempSourceFile, tempRulesetFile)
    } finally {
        // Cleanup: recursively delete temp directory
        tempDir.toFile().deleteRecursively()
    }
}
```

### Configuration Provider Pattern

Dependency injection for configuration access:

```kotlin
interface ConfigurationProvider {
    fun getServerConfiguration(): ServerConfiguration
    fun getWorkspaceRoot(): Path?
}

// Allows different contexts (LSP, tests, standalone)
class LspConfigurationProvider(
    private val workspaceService: GroovyWorkspaceService
) : ConfigurationProvider {
    override fun getServerConfiguration() = workspaceService.getConfiguration()
    override fun getWorkspaceRoot() = workspaceService.getWorkspaceRoot()
}

// Test implementation
class MockConfigurationProvider(
    private val config: ServerConfiguration = ServerConfiguration(),
    private val workspaceRoot: Path? = null
) : ConfigurationProvider {
    override fun getServerConfiguration() = config
    override fun getWorkspaceRoot() = workspaceRoot
}
```

## Performance Considerations

### Ruleset Size Impact

- **Large rulesets**: Slower analysis but comprehensive coverage
- **Focused rulesets**: Faster analysis but may miss issues
- **Rule complexity**: Some rules are computationally expensive

### Temporary File Performance

The current file-based approach has performance implications:

```kotlin
// Current: File-based (required by CodeNarc)
// - Disk I/O overhead for every analysis
// - Temporary file creation/cleanup
// - File system permissions considerations

// Future optimization possibilities:
// - Rule result caching by source hash
// - Reuse of ruleset compilation
// - Batch analysis for multiple files
```

### Optimization Strategies

1. **Disable expensive rules for large codebases**:

```groovy
CyclomaticComplexity {
    enabled = false  // Expensive for large methods
}

ClassSize {
    maxLines = 2000  // Increase limit to reduce violations
}
```

2. **Use targeted rulesets per file type**:

```kotlin
// In LSP integration
fun selectRuleset(fileType: String): String = when {
    fileType.contains("Jenkinsfile") -> "frameworks/jenkins.groovy"
    fileType.contains("build.gradle") -> "frameworks/gradle.groovy"
    fileType.contains("Spec.groovy") -> "frameworks/spock.groovy"
    else -> "general/default.groovy"
}
```

3. **Cache compiled rulesets**:

```kotlin
class RulesetCache {
    private val cache = ConcurrentHashMap<String, RuleSet>()

    fun getRuleset(path: String): RuleSet =
        cache.computeIfAbsent(path) { loadRuleset(it) }
}
```

### Memory Management

CodeNarc can consume significant memory with large rulesets:

```kotlin
// Configure JVM for CodeNarc
// -Xmx512m minimum for medium projects
// -Xmx1g for large projects with comprehensive rulesets
```

## Troubleshooting Quick Reference

| Error Pattern                                           | Cause                                  | Solution                                     |
| ------------------------------------------------------- | -------------------------------------- | -------------------------------------------- |
| `No such rule named [rule]`                             | Using `rule()` inside `ruleset()`      | Move rule config outside ruleset block       |
| `No such rule named [RuleName]`                         | Rule doesn't exist                     | Verify rule existence, use alternatives      |
| `AssertionError` in `RuleSetDelegate`                   | DSL syntax violation                   | Check ruleset structure                      |
| `NullPointerException` in ruleset loading               | Missing/corrupted ruleset file         | Verify file path and content                 |
| `'val' cannot be reassigned`                            | Kotlin-Groovy property access conflict | Use reflection or setter methods             |
| `Unresolved reference 'FileSourceAnalyzer'`             | Incorrect import/missing dependency    | Use `FilesSourceAnalyzer` (note the 's')     |
| `VirtualResults` exceptions                             | CodeNarc version compatibility         | Ensure proper CodeNarc version (3.5.0+)      |
| `UnsupportedOperationException: virtual results`        | Mock/plugin interference               | Check for test mocks affecting real analysis |
| Long startup times                                      | Too many rules enabled                 | Optimize ruleset size                        |
| Memory issues                                           | Large codebase + comprehensive rules   | Increase heap size, reduce rules             |
| Temp file permission errors                             | File system restrictions               | Ensure temp directory write permissions      |
| `No value passed for parameter 'configurationProvider'` | Missing dependency injection           | Add ConfigurationProvider to constructor     |

## Implementation Lessons Learned

### Common Development Pitfalls

1. **API Discovery Process**

   - Always use `javap` to inspect available methods before assuming API
   - Groovy property names may differ from expected Java getter/setter names
   - CodeNarc documentation may be outdated - verify against actual JAR

2. **Kotlin-Groovy Integration**

   - Kotlin sees Groovy properties as read-only `val` fields
   - Reflection is often necessary for property assignment
   - Test both compilation and runtime behavior

3. **Testing Strategy**

   - Use MockK for dependency injection testing
   - Test with real-world project structures (Jenkins, Gradle)
   - Include both positive and negative test cases

4. **Build Integration**
   - Separate test compilation issues from main compilation issues
   - Use incremental debugging (temporarily disable problematic files)
   - Verify fixes with both unit tests and integration tests

### Successful Resolution Patterns

1. **API Research Flow**

   ```bash
   jar tf codenarc.jar | grep -i "analyzer"          # Find classes
   javap -cp codenarc.jar ClassName | grep "public"  # Check methods
   # Try implementation, debug with reflection if needed
   ```

2. **Test-Driven Integration**

   ```kotlin
   // Start with simple mock
   val mockConfig = mockk<ConfigurationProvider>(relaxed = true)

   // Add specific behavior as needed
   every { mockConfig.getServerConfiguration() } returns config

   // Verify interactions
   verify { mockConfig.getWorkspaceRoot() }
   ```

3. **Incremental Problem Solving**
   - Fix one compilation error at a time
   - Use `mv file.kt file.kt.skip` to isolate issues
   - Test fixes before moving to next issue

This documentation should be updated as new CodeNarc versions are released and new patterns are discovered.
