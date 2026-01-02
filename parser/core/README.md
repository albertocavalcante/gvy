# GroovyParser Core

A standalone Groovy parsing library with a JavaParser-inspired API, designed for building IDE tooling, static analyzers,
and code transformation tools.

## Features

- **JavaParser-like API**: Familiar API design for Java/Kotlin developers
- **Custom AST nodes**: Clean, well-documented AST hierarchy with position tracking
- **Visitor pattern**: `VoidVisitor` and `GroovyVisitor<R>` for type-safe AST traversal
- **Comment preservation**: Extract and attach comments (line, block, Javadoc) to AST nodes
- **Multi-version support**: Parse Groovy 2.4 through 5.x
- **File-based parsing**: Parse from `File`, `Path`, `InputStream`, or `Reader`
- **Extension points**: SPI for AST transforms and Jenkins CPS analysis
- **Jenkins CPS support**: Built-in analyzer for detecting CPS compatibility issues
- **Error recovery**: Lenient mode returns partial ASTs with detailed problem reports
- **Type-safe**: Written in Kotlin with null-safety

## Installation

### Gradle (Kotlin DSL)

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/albertocavalcante/gvy")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("com.github.albertocavalcante:groovyparser-core:0.1.0")
}
```

### Gradle (Groovy DSL)

```groovy
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/albertocavalcante/gvy")
        credentials {
            username = project.findProperty("gpr.user") ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.key") ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation 'com.github.albertocavalcante:groovyparser-core:0.1.0'
}
```

## Quick Start

### Basic Parsing

```kotlin
import com.github.albertocavalcante.groovyparser.StaticGroovyParser
import com.github.albertocavalcante.groovyparser.GroovyParser
import com.github.albertocavalcante.groovyparser.ParserConfiguration
import com.github.albertocavalcante.groovyparser.GroovyLanguageLevel

// Quick static parsing
val unit = StaticGroovyParser.parse("class Foo {}")
println(unit.types[0].name) // "Foo"

// Or parse from a file
val unitFromFile = StaticGroovyParser.parse(File("MyClass.groovy"))

// Parse with configuration
val config = ParserConfiguration()
    .setLanguageLevel(GroovyLanguageLevel.GROOVY_4_0)

val parser = GroovyParser(config)
val result = parser.parse("class Bar { String name }")

if (result.isSuccessful) {
    val cu = result.result.get()
    cu.types.forEach { println(it.name) }
} else {
    result.problems.forEach { println(it) }
}
```

### File-Based Parsing

```kotlin
import java.io.File
import java.nio.file.Paths

// Parse from File
val unit1 = StaticGroovyParser.parse(File("src/main/groovy/MyClass.groovy"))

// Parse from Path
val unit2 = StaticGroovyParser.parse(Paths.get("src/main/groovy/MyClass.groovy"))

// Parse from InputStream
FileInputStream("MyClass.groovy").use { stream ->
    val unit3 = StaticGroovyParser.parse(stream)
}

// Parse from Reader
BufferedReader(FileReader("MyClass.groovy")).use { reader ->
    val unit4 = StaticGroovyParser.parse(reader)
}
```

### Accessing AST Nodes

```kotlin
val unit = StaticGroovyParser.parse("""
    package com.example
    
    import java.util.List
    
    @Deprecated
    class Person {
        String name
        int age
        
        void greet() {
            println "Hello, $name!"
        }
    }
""")

// Package declaration
unit.packageDeclaration.ifPresent { pkg ->
    println("Package: ${pkg.name}")
}

// Imports
unit.imports.forEach { import ->
    println("Import: ${import.name}")
}

// Types
unit.types.forEach { type ->
    if (type is ClassDeclaration) {
        println("Class: ${type.name}")
        
        // Check annotations
        type.annotations.forEach { ann ->
            println("  @${ann.name}")
        }
        
        type.fields.forEach { field ->
            println("  Field: ${field.type} ${field.name}")
        }
        
        type.methods.forEach { method ->
            println("  Method: ${method.returnType} ${method.name}")
        }
    }
}
```

## Using Visitors

The visitor pattern provides type-safe AST traversal:

### VoidVisitor (for side effects)

```kotlin
import com.github.albertocavalcante.groovyparser.ast.visitor.VoidVisitorAdapter

class MethodCollector : VoidVisitorAdapter<MutableList<String>>() {
    override fun visit(n: MethodDeclaration, arg: MutableList<String>) {
        arg.add(n.name)
        super.visit(n, arg) // Continue traversal
    }
}

val methods = mutableListOf<String>()
val collector = MethodCollector()
val compilationUnit = StaticGroovyParser.parse("class Foo { void bar() {} }")
collector.visit(compilationUnit, methods)
println("Found methods: $methods")
```

### GroovyVisitor (with return value)

```kotlin
import com.github.albertocavalcante.groovyparser.ast.visitor.GroovyVisitor

class MethodCounter : GroovyVisitor<Int> {
    override fun visit(n: CompilationUnit, arg: Any?): Int {
        return n.types.sumOf { visit(it, arg) }
    }
    
    override fun visit(n: ClassDeclaration, arg: Any?): Int {
        return n.methods.size
    }
    
    // ... implement other visit methods
}
```

## Language Levels

Configure the parser for specific Groovy versions:

```kotlin
// For Jenkins pipelines (locked to Groovy 2.4)
val jenkinsConfig = ParserConfiguration()
    .setLanguageLevel(GroovyLanguageLevel.JENKINS)

// For Gradle 8.x (Groovy 4.0)
val gradleConfig = ParserConfiguration()
    .setLanguageLevel(GroovyLanguageLevel.GRADLE_8)

// For modern Groovy 5.0
val modernConfig = ParserConfiguration()
    .setLanguageLevel(GroovyLanguageLevel.GROOVY_5_0)
```

## Jenkins CPS Analysis

Built-in support for detecting CPS compatibility issues in Jenkins pipelines:

```kotlin
import com.github.albertocavalcante.groovyparser.spi.DefaultCpsAnalyzer

val analyzer = DefaultCpsAnalyzer()
val unit = StaticGroovyParser.parse("""
    def problematic() {
        [1, 2, 3].each { println it }  // Non-serializable closure!
        Thread.sleep(1000)              // Non-whitelisted method!
    }
""")

val violations = analyzer.getCpsViolations(unit)
violations.forEach { violation ->
    println("CPS violation at line ${violation.position?.line}: ${violation.message}")
}
```

## AST Node Hierarchy

```
Node (base)
├── CompilationUnit
├── PackageDeclaration
├── ImportDeclaration
├── AnnotationExpr
├── body/
│   ├── TypeDeclaration
│   │   └── ClassDeclaration
│   ├── MethodDeclaration
│   ├── FieldDeclaration
│   ├── ConstructorDeclaration
│   └── Parameter
├── stmt/
│   ├── Statement
│   ├── BlockStatement
│   ├── ExpressionStatement
│   ├── IfStatement
│   ├── ForStatement
│   ├── WhileStatement
│   ├── ReturnStatement
│   ├── TryCatchStatement
│   ├── SwitchStatement
│   ├── ThrowStatement
│   ├── AssertStatement
│   ├── BreakStatement
│   └── ContinueStatement
└── expr/
    ├── Expression
    ├── MethodCallExpr
    ├── VariableExpr
    ├── ConstantExpr
    ├── BinaryExpr
    ├── UnaryExpr
    ├── TernaryExpr
    ├── CastExpr
    ├── ClosureExpr
    ├── GStringExpr
    ├── PropertyExpr
    ├── ListExpr
    ├── MapExpr
    ├── RangeExpr
    └── ConstructorCallExpr
```

## Extension Points

### TransformProvider

Implement `TransformProvider` to provide information about AST-transform-generated members:

```kotlin
class ToStringTransformProvider : TransformProvider {
    override fun getGeneratedMethods(annotationName: String, node: Node): List<MethodDeclaration> {
        if (annotationName == "groovy.transform.ToString") {
            return listOf(MethodDeclaration("toString", "String"))
        }
        return emptyList()
    }
    
    override fun getGeneratedFields(annotationName: String, node: Node) = emptyList()
    
    override fun handles(annotationName: String) = 
        annotationName == "groovy.transform.ToString"
}
```

### CpsAnalyzer

Implement `CpsAnalyzer` for custom Jenkins CPS compatibility analysis:

```kotlin
class CustomCpsAnalyzer : CpsAnalyzer {
    override fun isCpsCompatible(node: Node): Boolean {
        return getCpsViolations(node).isEmpty()
    }
    
    override fun getCpsViolations(node: Node): List<CpsViolation> {
        // Custom CPS violation detection
        return emptyList()
    }
    
    override fun isNonCps(node: Node): Boolean {
        // Check for @NonCPS annotation
        return node.annotations.any { it.name == "NonCPS" }
    }
}
```

## Position Tracking

All AST nodes include source position information:

```kotlin
val unit = StaticGroovyParser.parse(code)
unit.types.forEach { type ->
    type.range.ifPresent { range ->
        println("${type.name} spans lines ${range.begin.line} to ${range.end.line}")
    }
}
```

## Comment Preservation

Comments are automatically extracted and attached to AST nodes:

```kotlin
val code = """
    /** This is a Javadoc comment for Person. */
    class Person {
        /** The person's name. */
        String name
        
        // Regular line comment
        void greet() {
            println "Hello!"
        }
    }
""".trimIndent()

val unit = StaticGroovyParser.parse(code)
val personClass = unit.types[0] as ClassDeclaration

// Get the class's attached comment
personClass.comment?.let { comment ->
    println("Class doc: ${comment.content}")
    if (comment is JavadocComment) {
        println("(It's a Javadoc!)")
    }
}

// Get all comments in the AST
val allComments = unit.getAllContainedComments()
allComments.forEach { println(it) }
```

### Comment Types

- `LineComment`: Single-line comments starting with `//`
- `BlockComment`: Multi-line comments enclosed in `/* ... */`
- `JavadocComment`: Documentation comments starting with `/** ... */`

### Disabling Comment Attribution

Comment extraction can be disabled for performance:

```kotlin
val config = ParserConfiguration()
    .setAttributeComments(false)
val parser = GroovyParser(config)
```

## Error Recovery

The parser operates in lenient mode by default, attempting to recover from errors and return partial ASTs:

```kotlin
val code = """
    class Foo {
        def bar() {
            // Missing closing braces...
"""

val parser = GroovyParser()
val result = parser.parse(code)

// Check for errors
if (result.hasErrors) {
    result.errors.forEach { error ->
        println("${error.severity}: ${error.message} at ${error.position}")
    }
}

// Even with errors, you may get a partial AST
result.result.ifPresent { unit ->
    println("Partial parse found ${unit.types.size} types")
}

// Strict mode fails immediately on errors
val strictConfig = ParserConfiguration().setLenientMode(false)
val strictParser = GroovyParser(strictConfig)
```

### Problem Severity Levels

- `ERROR`: Fatal errors that prevent parsing
- `WARNING`: Non-fatal issues (when `collectWarnings` is enabled)
- `INFO`: Informational messages
- `HINT`: Suggestions and style hints

## API Reference

### Main Classes

| Class                 | Description                              |
| --------------------- | ---------------------------------------- |
| `StaticGroovyParser`  | Static utility for quick parsing         |
| `GroovyParser`        | Configurable parser instance             |
| `ParserConfiguration` | Parser settings (language level, etc.)   |
| `ParseResult<T>`      | Result wrapper with success/failure info |
| `CompilationUnit`     | Root AST node for a parsed file          |

### Visitor Classes

| Class                   | Description                         |
| ----------------------- | ----------------------------------- |
| `VoidVisitor<A>`        | Visitor interface for side effects  |
| `GroovyVisitor<R>`      | Visitor interface with return value |
| `VoidVisitorAdapter<A>` | Default traversal implementation    |

## Requirements

- **JDK 17+** (compiled with JDK 17 target)
- **Kotlin 1.9+** (for Kotlin projects)

## Related Projects

- [JavaParser](https://github.com/javaparser/javaparser) - Inspiration for this API
- [Groovy Language Server](https://github.com/albertocavalcante/gvy) - LSP using this parser

## License

Apache License 2.0 - See [LICENSE](../../LICENSE) for details.
