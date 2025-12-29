# GroovyParser Core

A standalone Groovy parsing library with a JavaParser-inspired API.

## Features

- **JavaParser-like API**: Familiar API design for Java/Kotlin developers
- **Custom AST nodes**: Clean, well-documented AST hierarchy
- **Multi-version support**: Parse Groovy 2.4 through 5.x
- **Extension points**: SPI for AST transforms and Jenkins CPS analysis
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
    implementation("com.github.albertocavalcante:groovyparser-core:VERSION")
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
    implementation 'com.github.albertocavalcante:groovyparser-core:VERSION'
}
```

## Usage

### Basic Parsing

```kotlin
// Using the static API
val unit = StaticGroovyParser.parse("class Foo {}")
println(unit.types[0].name) // "Foo"

// Using an instance with configuration
val config = ParserConfiguration()
    .setLanguageLevel(GroovyLanguageLevel.GROOVY_4_0)

val parser = GroovyParser(config)
val result = parser.parse("class Bar {}")

if (result.isSuccessful) {
    val cu = result.result.get()
    cu.types.forEach { println(it.name) }
} else {
    result.problems.forEach { println(it) }
}
```

### Accessing AST Nodes

```kotlin
val unit = StaticGroovyParser.parse("""
    package com.example
    
    import java.util.List
    
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
        
        type.fields.forEach { field ->
            println("  Field: ${field.type} ${field.name}")
        }
        
        type.methods.forEach { method ->
            println("  Method: ${method.returnType} ${method.name}")
        }
    }
}
```

### Language Levels

```kotlin
// For Jenkins pipelines (locked to Groovy 2.4)
val jenkinsConfig = ParserConfiguration()
    .setLanguageLevel(GroovyLanguageLevel.JENKINS)

// For Gradle 8.x (Groovy 4.0)
val gradleConfig = ParserConfiguration()
    .setLanguageLevel(GroovyLanguageLevel.GRADLE_8)
```

## AST Node Hierarchy

```
Node (base)
├── CompilationUnit
├── PackageDeclaration
├── ImportDeclaration
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
│   └── ReturnStatement
└── expr/
    ├── Expression
    ├── MethodCallExpr
    ├── VariableExpr
    ├── ConstantExpr
    ├── BinaryExpr
    ├── ClosureExpr
    ├── GStringExpr
    └── PropertyExpr
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

Implement `CpsAnalyzer` for Jenkins CPS compatibility analysis:

```kotlin
class JenkinsCpsAnalyzer : CpsAnalyzer {
    override fun isCpsCompatible(node: Node): Boolean {
        // Analyze node for CPS compatibility
        return getCpsViolations(node).isEmpty()
    }
    
    override fun getCpsViolations(node: Node): List<CpsViolation> {
        // Return list of CPS violations found
        return emptyList()
    }
    
    override fun isNonCps(node: Node): Boolean {
        // Check for @NonCPS annotation
        return false
    }
}
```

## License

Apache License 2.0

