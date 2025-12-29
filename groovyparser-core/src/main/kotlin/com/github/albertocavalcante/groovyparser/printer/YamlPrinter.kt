package com.github.albertocavalcante.groovyparser.printer

import com.github.albertocavalcante.groovyparser.ast.AnnotationExpr
import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.ImportDeclaration
import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.PackageDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.groovyparser.ast.expr.BinaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstantExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ExpressionStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ReturnStatement

/**
 * Prints AST nodes as YAML.
 *
 * Useful for readable output and configuration files.
 *
 * Example usage:
 * ```kotlin
 * val cu = StaticGroovyParser.parse("class Foo { def bar() {} }")
 * val yaml = YamlPrinter().print(cu)
 * println(yaml)
 * ```
 */
class YamlPrinter {

    private val output = StringBuilder()
    private var indent = 0
    private val indentString = "  "

    /**
     * Whether to include range information.
     */
    var includeRanges: Boolean = false

    /**
     * Prints a node and its children as YAML.
     */
    fun print(node: Node): String {
        output.clear()
        indent = 0
        printNode(node)
        return output.toString()
    }

    private fun printNode(node: Node) {
        when (node) {
            is CompilationUnit -> printCompilationUnit(node)
            is ClassDeclaration -> printClassDeclaration(node)
            is MethodDeclaration -> printMethodDeclaration(node)
            is FieldDeclaration -> printFieldDeclaration(node)
            is Parameter -> printParameter(node)
            is PackageDeclaration -> printPackageDeclaration(node)
            is ImportDeclaration -> printImportDeclaration(node)
            is AnnotationExpr -> printAnnotation(node)
            is BlockStatement -> printBlockStatement(node)
            is ExpressionStatement -> printExpressionStatement(node)
            is ReturnStatement -> printReturnStatement(node)
            is MethodCallExpr -> printMethodCallExpr(node)
            is VariableExpr -> printVariableExpr(node)
            is ConstantExpr -> printConstantExpr(node)
            is BinaryExpr -> printBinaryExpr(node)
            else -> printGenericNode(node)
        }
    }

    private fun printCompilationUnit(cu: CompilationUnit) {
        printLine("type: CompilationUnit")
        cu.packageDeclaration.ifPresent {
            printLine("package: ${it.name}")
        }
        if (cu.imports.isNotEmpty()) {
            printLine("imports:")
            indent++
            cu.imports.forEach {
                printLine("- name: ${it.name}")
                indent++
                if (it.isStatic) printLine("static: true")
                if (it.isStarImport) printLine("star: true")
                indent--
            }
            indent--
        }
        if (cu.types.isNotEmpty()) {
            printLine("types:")
            indent++
            cu.types.forEach {
                printLine("-")
                indent++
                printNode(it)
                indent--
            }
            indent--
        }
    }

    private fun printClassDeclaration(cls: ClassDeclaration) {
        printLine("type: ClassDeclaration")
        printLine("name: ${cls.name}")
        if (cls.isInterface) printLine("interface: true")
        if (cls.isEnum) printLine("enum: true")
        cls.superClass?.let { printLine("extends: $it") }
        if (cls.implementedTypes.isNotEmpty()) {
            printLine("implements: [${cls.implementedTypes.joinToString(", ")}]")
        }
        printRangeIfEnabled(cls)

        if (cls.annotations.isNotEmpty()) {
            printLine("annotations:")
            indent++
            cls.annotations.forEach {
                printLine("- ${it.name}")
            }
            indent--
        }

        if (cls.fields.isNotEmpty()) {
            printLine("fields:")
            indent++
            cls.fields.forEach {
                printLine("-")
                indent++
                printNode(it)
                indent--
            }
            indent--
        }

        if (cls.methods.isNotEmpty()) {
            printLine("methods:")
            indent++
            cls.methods.forEach {
                printLine("-")
                indent++
                printNode(it)
                indent--
            }
            indent--
        }
    }

    private fun printMethodDeclaration(method: MethodDeclaration) {
        printLine("type: MethodDeclaration")
        printLine("name: ${method.name}")
        printLine("returnType: ${method.returnType}")
        if (method.isStatic) printLine("static: true")
        if (method.isAbstract) printLine("abstract: true")
        printRangeIfEnabled(method)

        if (method.parameters.isNotEmpty()) {
            printLine("parameters:")
            indent++
            method.parameters.forEach {
                printLine("- name: ${it.name}")
                indent++
                printLine("type: ${it.type}")
                indent--
            }
            indent--
        }

        method.body?.let {
            printLine("body:")
            indent++
            printNode(it)
            indent--
        }
    }

    private fun printFieldDeclaration(field: FieldDeclaration) {
        printLine("type: FieldDeclaration")
        printLine("name: ${field.name}")
        printLine("fieldType: ${field.type}")
        if (field.isStatic) printLine("static: true")
        if (field.isFinal) printLine("final: true")
        printRangeIfEnabled(field)
    }

    private fun printParameter(param: Parameter) {
        printLine("type: Parameter")
        printLine("name: ${param.name}")
        printLine("paramType: ${param.type}")
    }

    private fun printPackageDeclaration(pkg: PackageDeclaration) {
        printLine("type: PackageDeclaration")
        printLine("name: ${pkg.name}")
    }

    private fun printImportDeclaration(imp: ImportDeclaration) {
        printLine("type: ImportDeclaration")
        printLine("name: ${imp.name}")
        if (imp.isStatic) printLine("static: true")
        if (imp.isStarImport) printLine("star: true")
    }

    private fun printAnnotation(ann: AnnotationExpr) {
        printLine("type: Annotation")
        printLine("name: ${ann.name}")
        if (ann.members.isNotEmpty()) {
            printLine("members:")
            indent++
            ann.members.forEach { (key, _) ->
                printLine("$key: ...")
            }
            indent--
        }
    }

    private fun printBlockStatement(block: BlockStatement) {
        printLine("type: BlockStatement")
        if (block.statements.isNotEmpty()) {
            printLine("statements:")
            indent++
            block.statements.forEach {
                printLine("-")
                indent++
                printNode(it)
                indent--
            }
            indent--
        }
    }

    private fun printExpressionStatement(stmt: ExpressionStatement) {
        printLine("type: ExpressionStatement")
        printLine("expression:")
        indent++
        printNode(stmt.expression)
        indent--
    }

    private fun printReturnStatement(stmt: ReturnStatement) {
        printLine("type: ReturnStatement")
        stmt.expression?.let {
            printLine("expression:")
            indent++
            printNode(it)
            indent--
        }
    }

    private fun printMethodCallExpr(expr: MethodCallExpr) {
        printLine("type: MethodCallExpr")
        printLine("name: ${expr.methodName}")
        expr.objectExpression?.let {
            printLine("object:")
            indent++
            printNode(it)
            indent--
        }
        if (expr.arguments.isNotEmpty()) {
            printLine("arguments:")
            indent++
            expr.arguments.forEach {
                printLine("-")
                indent++
                printNode(it)
                indent--
            }
            indent--
        }
    }

    private fun printVariableExpr(expr: VariableExpr) {
        printLine("type: VariableExpr")
        printLine("name: ${expr.name}")
    }

    private fun printConstantExpr(expr: ConstantExpr) {
        printLine("type: ConstantExpr")
        printLine("value: ${escapeYaml(expr.value?.toString() ?: "null")}")
    }

    private fun printBinaryExpr(expr: BinaryExpr) {
        printLine("type: BinaryExpr")
        printLine("operator: ${expr.operator}")
        printLine("left:")
        indent++
        printNode(expr.left)
        indent--
        printLine("right:")
        indent++
        printNode(expr.right)
        indent--
    }

    private fun printGenericNode(node: Node) {
        val className = node::class.simpleName ?: "Node"
        printLine("type: $className")
        val children = node.getChildNodes()
        if (children.isNotEmpty()) {
            printLine("children:")
            indent++
            children.forEach {
                printLine("-")
                indent++
                printNode(it)
                indent--
            }
            indent--
        }
    }

    private fun printRangeIfEnabled(node: Node) {
        if (includeRanges && node.range != null) {
            val range = node.range!!
            printLine("range:")
            indent++
            printLine("begin: ${range.begin.line}:${range.begin.column}")
            printLine("end: ${range.end.line}:${range.end.column}")
            indent--
        }
    }

    private fun printLine(text: String) {
        repeat(indent) { output.append(indentString) }
        output.append(text)
        output.append("\n")
    }

    private fun escapeYaml(text: String): String = when {
        text.contains(":") || text.contains("#") || text.startsWith(" ") -> "\"${text.replace("\"", "\\\"")}\""
        text.contains("\n") -> "|\n$text"
        else -> text
    }
}
