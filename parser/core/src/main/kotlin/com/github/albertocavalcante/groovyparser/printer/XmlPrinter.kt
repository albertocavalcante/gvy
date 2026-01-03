package com.github.albertocavalcante.groovyparser.printer

import com.github.albertocavalcante.groovyparser.ast.AnnotationExpr
import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.ImportDeclaration
import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.PackageDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ConstructorDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.groovyparser.ast.expr.BinaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ClosureExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstantExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.PropertyExpr
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ExpressionStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ForStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.IfStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ReturnStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.WhileStatement

/**
 * Prints AST nodes as XML.
 *
 * Useful for debugging, serialization, and analysis tools.
 *
 * Example usage:
 * ```kotlin
 * val cu = StaticGroovyParser.parse("class Foo { def bar() {} }")
 * val xml = XmlPrinter().print(cu)
 * println(xml)
 * ```
 */
class XmlPrinter {

    private val output = StringBuilder()
    private var indent = 0
    private val indentString = "  "

    /**
     * Whether to include range information in the XML output.
     */
    var includeRanges: Boolean = false

    /**
     * Prints a node and its children as XML.
     */
    fun print(node: Node): String {
        output.clear()
        indent = 0
        output.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        printNode(node)
        return output.toString()
    }

    private fun printNode(node: Node) {
        when (node) {
            is CompilationUnit -> printCompilationUnit(node)
            is ClassDeclaration -> printClassDeclaration(node)
            is MethodDeclaration -> printMethodDeclaration(node)
            is FieldDeclaration -> printFieldDeclaration(node)
            is ConstructorDeclaration -> printConstructorDeclaration(node)
            is Parameter -> printParameter(node)
            is PackageDeclaration -> printPackageDeclaration(node)
            is ImportDeclaration -> printImportDeclaration(node)
            is AnnotationExpr -> printAnnotation(node)
            is BlockStatement -> printBlockStatement(node)
            is ExpressionStatement -> printExpressionStatement(node)
            is IfStatement -> printIfStatement(node)
            is ForStatement -> printForStatement(node)
            is WhileStatement -> printWhileStatement(node)
            is ReturnStatement -> printReturnStatement(node)
            is MethodCallExpr -> printMethodCallExpr(node)
            is VariableExpr -> printVariableExpr(node)
            is ConstantExpr -> printConstantExpr(node)
            is BinaryExpr -> printBinaryExpr(node)
            is PropertyExpr -> printPropertyExpr(node)
            is ClosureExpr -> printClosureExpr(node)
            else -> printGenericNode(node)
        }
    }

    private fun printCompilationUnit(cu: CompilationUnit) {
        startElement("CompilationUnit")
        cu.packageDeclaration.ifPresent { printNode(it) }
        cu.imports.forEach { printNode(it) }
        cu.types.forEach { printNode(it) }
        endElement("CompilationUnit")
    }

    private fun printClassDeclaration(cls: ClassDeclaration) {
        startElement("ClassDeclaration", mapOf("name" to cls.name, "interface" to cls.isInterface.toString()))
        cls.superClass?.let { printAttribute("superClass", it) }
        if (cls.implementedTypes.isNotEmpty()) {
            printAttribute("implements", cls.implementedTypes.joinToString(","))
        }
        printRangeIfEnabled(cls)
        cls.annotations.forEach { printNode(it) }
        cls.fields.forEach { printNode(it) }
        cls.constructors.forEach { printNode(it) }
        cls.methods.forEach { printNode(it) }
        endElement("ClassDeclaration")
    }

    private fun printMethodDeclaration(method: MethodDeclaration) {
        startElement(
            "MethodDeclaration",
            mapOf(
                "name" to method.name,
                "returnType" to method.returnType,
                "static" to method.isStatic.toString(),
            ),
        )
        printRangeIfEnabled(method)
        method.annotations.forEach { printNode(it) }
        method.parameters.forEach { printNode(it) }
        method.body?.let {
            startElement("body")
            printNode(it)
            endElement("body")
        }
        endElement("MethodDeclaration")
    }

    private fun printFieldDeclaration(field: FieldDeclaration) {
        startElement(
            "FieldDeclaration",
            mapOf(
                "name" to field.name,
                "type" to field.type,
                "static" to field.isStatic.toString(),
                "final" to field.isFinal.toString(),
            ),
        )
        printRangeIfEnabled(field)
        field.annotations.forEach { printNode(it) }
        endElement("FieldDeclaration")
    }

    private fun printConstructorDeclaration(ctor: ConstructorDeclaration) {
        startElement("ConstructorDeclaration", mapOf("name" to ctor.name))
        printRangeIfEnabled(ctor)
        ctor.annotations.forEach { printNode(it) }
        ctor.parameters.forEach { printNode(it) }
        endElement("ConstructorDeclaration")
    }

    private fun printParameter(param: Parameter) {
        singleElement("Parameter", mapOf("name" to param.name, "type" to param.type))
    }

    private fun printPackageDeclaration(pkg: PackageDeclaration) {
        singleElement("PackageDeclaration", mapOf("name" to pkg.name))
    }

    private fun printImportDeclaration(imp: ImportDeclaration) {
        singleElement(
            "ImportDeclaration",
            mapOf(
                "name" to imp.name,
                "static" to imp.isStatic.toString(),
                "star" to imp.isStarImport.toString(),
            ),
        )
    }

    private fun printAnnotation(ann: AnnotationExpr) {
        if (ann.members.isEmpty()) {
            singleElement("Annotation", mapOf("name" to ann.name))
        } else {
            startElement("Annotation", mapOf("name" to ann.name))
            ann.members.forEach { (key, value) ->
                startElement("member", mapOf("name" to key))
                printNode(value)
                endElement("member")
            }
            endElement("Annotation")
        }
    }

    private fun printBlockStatement(block: BlockStatement) {
        startElement("BlockStatement")
        block.statements.forEach { printNode(it) }
        endElement("BlockStatement")
    }

    private fun printExpressionStatement(stmt: ExpressionStatement) {
        startElement("ExpressionStatement")
        printNode(stmt.expression)
        endElement("ExpressionStatement")
    }

    private fun printIfStatement(stmt: IfStatement) {
        startElement("IfStatement")
        startElement("condition")
        printNode(stmt.condition)
        endElement("condition")
        startElement("then")
        printNode(stmt.thenStatement)
        endElement("then")
        stmt.elseStatement?.let {
            startElement("else")
            printNode(it)
            endElement("else")
        }
        endElement("IfStatement")
    }

    private fun printForStatement(stmt: ForStatement) {
        startElement("ForStatement", mapOf("variable" to stmt.variableName))
        startElement("collection")
        printNode(stmt.collectionExpression)
        endElement("collection")
        startElement("body")
        printNode(stmt.body)
        endElement("body")
        endElement("ForStatement")
    }

    private fun printWhileStatement(stmt: WhileStatement) {
        startElement("WhileStatement")
        startElement("condition")
        printNode(stmt.condition)
        endElement("condition")
        startElement("body")
        printNode(stmt.body)
        endElement("body")
        endElement("WhileStatement")
    }

    private fun printReturnStatement(stmt: ReturnStatement) {
        if (stmt.expression != null) {
            startElement("ReturnStatement")
            printNode(stmt.expression)
            endElement("ReturnStatement")
        } else {
            singleElement("ReturnStatement")
        }
    }

    private fun printMethodCallExpr(expr: MethodCallExpr) {
        startElement("MethodCallExpr", mapOf("name" to expr.methodName))
        expr.objectExpression?.let {
            startElement("object")
            printNode(it)
            endElement("object")
        }
        if (expr.arguments.isNotEmpty()) {
            startElement("arguments")
            expr.arguments.forEach { printNode(it) }
            endElement("arguments")
        }
        endElement("MethodCallExpr")
    }

    private fun printVariableExpr(expr: VariableExpr) {
        singleElement("VariableExpr", mapOf("name" to expr.name))
    }

    private fun printConstantExpr(expr: ConstantExpr) {
        val type = expr.value?.javaClass?.simpleName ?: "null"
        singleElement("ConstantExpr", mapOf("type" to type, "value" to escapeXml(expr.value?.toString() ?: "null")))
    }

    private fun printBinaryExpr(expr: BinaryExpr) {
        startElement("BinaryExpr", mapOf("operator" to expr.operator))
        startElement("left")
        printNode(expr.left)
        endElement("left")
        startElement("right")
        printNode(expr.right)
        endElement("right")
        endElement("BinaryExpr")
    }

    private fun printPropertyExpr(expr: PropertyExpr) {
        startElement("PropertyExpr", mapOf("property" to expr.propertyName))
        startElement("object")
        printNode(expr.objectExpression)
        endElement("object")
        endElement("PropertyExpr")
    }

    private fun printClosureExpr(expr: ClosureExpr) {
        startElement("ClosureExpr")
        if (expr.parameters.isNotEmpty()) {
            startElement("parameters")
            expr.parameters.forEach { printNode(it) }
            endElement("parameters")
        }
        expr.body?.let {
            startElement("body")
            printNode(it)
            endElement("body")
        }
        endElement("ClosureExpr")
    }

    private fun printGenericNode(node: Node) {
        val className = node::class.simpleName ?: "Node"
        startElement(className)
        node.getChildNodes().forEach { printNode(it) }
        endElement(className)
    }

    private fun printRangeIfEnabled(node: Node) {
        if (includeRanges && node.range != null) {
            val range = node.range!!
            output.append("\n")
            printIndent()
            output.append("<range begin=\"${range.begin.line}:${range.begin.column}\"")
            output.append(" end=\"${range.end.line}:${range.end.column}\"/>")
        }
    }

    private fun printAttribute(name: String, value: String) {
        output.append("\n")
        printIndent()
        output.append("<$name>")
        output.append(escapeXml(value))
        output.append("</$name>")
    }

    private fun startElement(name: String, attributes: Map<String, String> = emptyMap()) {
        printIndent()
        output.append("<$name")
        attributes.forEach { (key, value) ->
            output.append(" $key=\"${escapeXml(value)}\"")
        }
        output.append(">\n")
        indent++
    }

    private fun endElement(name: String) {
        indent--
        printIndent()
        output.append("</$name>\n")
    }

    private fun singleElement(name: String, attributes: Map<String, String> = emptyMap()) {
        printIndent()
        output.append("<$name")
        attributes.forEach { (key, value) ->
            output.append(" $key=\"${escapeXml(value)}\"")
        }
        output.append("/>\n")
    }

    private fun printIndent() {
        repeat(indent) { output.append(indentString) }
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
