package com.github.albertocavalcante.groovyparser.printer

import com.github.albertocavalcante.groovyparser.ast.AnnotationExpr
import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.ImportDeclaration
import com.github.albertocavalcante.groovyparser.ast.PackageDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ConstructorDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.expr.ArrayExpr
import com.github.albertocavalcante.groovyparser.ast.expr.AttributeExpr
import com.github.albertocavalcante.groovyparser.ast.expr.BinaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.BitwiseNegationExpr
import com.github.albertocavalcante.groovyparser.ast.expr.CastExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ClassExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ClosureExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstantExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstructorCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.DeclarationExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ElvisExpr
import com.github.albertocavalcante.groovyparser.ast.expr.Expression
import com.github.albertocavalcante.groovyparser.ast.expr.GStringExpr
import com.github.albertocavalcante.groovyparser.ast.expr.LambdaExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ListExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MapExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodPointerExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodReferenceExpr
import com.github.albertocavalcante.groovyparser.ast.expr.NotExpr
import com.github.albertocavalcante.groovyparser.ast.expr.PostfixExpr
import com.github.albertocavalcante.groovyparser.ast.expr.PrefixExpr
import com.github.albertocavalcante.groovyparser.ast.expr.PropertyExpr
import com.github.albertocavalcante.groovyparser.ast.expr.RangeExpr
import com.github.albertocavalcante.groovyparser.ast.expr.SpreadExpr
import com.github.albertocavalcante.groovyparser.ast.expr.SpreadMapExpr
import com.github.albertocavalcante.groovyparser.ast.expr.TernaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.UnaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr
import com.github.albertocavalcante.groovyparser.ast.stmt.AssertStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.BreakStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ContinueStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ExpressionStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ForStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.IfStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ReturnStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.Statement
import com.github.albertocavalcante.groovyparser.ast.stmt.SwitchStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ThrowStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.TryCatchStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.WhileStatement

/**
 * Pretty printer that converts AST back to Groovy source code.
 *
 * Example usage:
 * ```kotlin
 * val unit = StaticGroovyParser.parse(code)
 * val printer = GroovyPrettyPrinter()
 * val source = printer.print(unit)
 * ```
 */
class GroovyPrettyPrinter(private val config: PrinterConfiguration = PrinterConfiguration()) {

    private val sb = StringBuilder()
    private var indentLevel = 0

    /**
     * Print a compilation unit to Groovy source code.
     */
    fun print(unit: CompilationUnit): String {
        sb.clear()
        indentLevel = 0
        printCompilationUnit(unit)
        return sb.toString().trim()
    }

    private fun printCompilationUnit(unit: CompilationUnit) {
        // Package declaration
        unit.packageDeclaration.ifPresent { pkg ->
            printPackage(pkg)
            newLine()
        }

        // Imports
        if (unit.imports.isNotEmpty()) {
            unit.imports.forEach { imp ->
                printImport(imp)
            }
            newLine()
        }

        // Type declarations
        unit.types.forEachIndexed { index, type ->
            if (index > 0) newLine()
            if (type is ClassDeclaration) {
                printClass(type)
            }
        }
    }

    private fun printPackage(pkg: PackageDeclaration) {
        printAnnotations(pkg.annotations)
        append("package ${pkg.name}")
        newLine()
    }

    private fun printImport(imp: ImportDeclaration) {
        append("import ")
        if (imp.isStatic) append("static ")
        append(imp.name)
        if (imp.isStarImport) append(".*")
        newLine()
    }

    private fun printClass(cls: ClassDeclaration) {
        printAnnotations(cls.annotations)
        append("class ${cls.name}")

        if (cls.superClass != null) {
            append(" extends ${cls.superClass}")
        }

        if (cls.implementedTypes.isNotEmpty()) {
            append(" implements ${cls.implementedTypes.joinToString(", ")}")
        }

        append(" {")
        newLine()
        indentLevel++

        // Fields
        cls.fields.forEach { field ->
            printField(field)
        }

        if (cls.fields.isNotEmpty() && (cls.constructors.isNotEmpty() || cls.methods.isNotEmpty())) {
            newLine()
        }

        // Constructors
        cls.constructors.forEach { constructor ->
            printConstructor(constructor)
        }

        // Methods
        cls.methods.forEachIndexed { index, method ->
            if (index > 0) newLine()
            printMethod(method)
        }

        indentLevel--
        indent()
        append("}")
        newLine()
    }

    private fun printField(field: FieldDeclaration) {
        indent()
        printAnnotations(field.annotations, inline = true)
        if (field.isStatic) append("static ")
        if (field.isFinal) append("final ")
        append("${field.type} ${field.name}")
        newLine()
    }

    private fun printConstructor(constructor: ConstructorDeclaration) {
        indent()
        printAnnotations(constructor.annotations, inline = true)
        append(constructor.name)
        append("(")
        append(
            constructor.parameters.joinToString(", ") { param ->
                "${param.type} ${param.name}"
            },
        )
        append(") {")
        newLine()
        // Constructor body would go here
        indent()
        append("}")
        newLine()
    }

    private fun printMethod(method: MethodDeclaration) {
        indent()
        printAnnotations(method.annotations, inline = true)
        if (method.isStatic) append("static ")
        if (method.isAbstract) append("abstract ")
        if (method.isFinal) append("final ")
        append("${method.returnType} ${method.name}")
        append("(")
        append(
            method.parameters.joinToString(", ") { param ->
                buildString {
                    if (param.annotations.isNotEmpty()) {
                        append(param.annotations.joinToString(" ") { "@${it.name}" })
                        append(" ")
                    }
                    append("${param.type} ${param.name}")
                }
            },
        )
        append(")")

        val body = method.body
        if (body != null) {
            append(" {")
            newLine()
            indentLevel++
            printStatement(body)
            indentLevel--
            indent()
            append("}")
        }
        newLine()
    }

    private fun printStatement(stmt: Statement) {
        when (stmt) {
            is BlockStatement -> printBlockStatement(stmt)
            is ExpressionStatement -> printExpressionStatement(stmt)
            is IfStatement -> printIfStatement(stmt)
            is ForStatement -> printForStatement(stmt)
            is WhileStatement -> printWhileStatement(stmt)
            is ReturnStatement -> printReturnStatement(stmt)
            is TryCatchStatement -> printTryCatchStatement(stmt)
            is SwitchStatement -> printSwitchStatement(stmt)
            is ThrowStatement -> printThrowStatement(stmt)
            is AssertStatement -> printAssertStatement(stmt)
            is BreakStatement -> printBreakStatement(stmt)
            is ContinueStatement -> printContinueStatement(stmt)
            else -> {
                indent()
                append("// Unknown statement type")
                newLine()
            }
        }
    }

    private fun printBlockStatement(stmt: BlockStatement) {
        stmt.statements.forEach { printStatement(it) }
    }

    private fun printExpressionStatement(stmt: ExpressionStatement) {
        indent()
        printExpression(stmt.expression)
        newLine()
    }

    private fun printIfStatement(stmt: IfStatement) {
        indent()
        append("if (")
        printExpression(stmt.condition)
        append(") {")
        newLine()
        indentLevel++
        printStatement(stmt.thenStatement)
        indentLevel--
        indent()
        append("}")

        stmt.elseStatement?.let { elseStmt ->
            append(" else {")
            newLine()
            indentLevel++
            printStatement(elseStmt)
            indentLevel--
            indent()
            append("}")
        }
        newLine()
    }

    private fun printForStatement(stmt: ForStatement) {
        indent()
        append("for (")
        append(stmt.variableName)
        append(" in ")
        printExpression(stmt.collectionExpression)
        append(") {")
        newLine()
        indentLevel++
        printStatement(stmt.body)
        indentLevel--
        indent()
        append("}")
        newLine()
    }

    private fun printWhileStatement(stmt: WhileStatement) {
        indent()
        append("while (")
        printExpression(stmt.condition)
        append(") {")
        newLine()
        indentLevel++
        printStatement(stmt.body)
        indentLevel--
        indent()
        append("}")
        newLine()
    }

    private fun printReturnStatement(stmt: ReturnStatement) {
        indent()
        append("return")
        stmt.expression?.let { expr ->
            append(" ")
            printExpression(expr)
        }
        newLine()
    }

    private fun printTryCatchStatement(stmt: TryCatchStatement) {
        indent()
        append("try {")
        newLine()
        indentLevel++
        printStatement(stmt.tryBlock)
        indentLevel--

        stmt.catchClauses.forEach { clause ->
            indent()
            append("} catch (${clause.parameter.type} ${clause.parameter.name}) {")
            newLine()
            indentLevel++
            printStatement(clause.body)
            indentLevel--
        }

        stmt.finallyBlock?.let { finallyBlock ->
            indent()
            append("} finally {")
            newLine()
            indentLevel++
            printStatement(finallyBlock)
            indentLevel--
        }

        indent()
        append("}")
        newLine()
    }

    private fun printSwitchStatement(stmt: SwitchStatement) {
        indent()
        append("switch (")
        printExpression(stmt.expression)
        append(") {")
        newLine()
        indentLevel++

        stmt.cases.forEach { case ->
            indent()
            append("case ")
            printExpression(case.expression)
            append(":")
            newLine()
            indentLevel++
            printStatement(case.body)
            indentLevel--
        }

        stmt.defaultCase?.let { defaultCase ->
            indent()
            append("default:")
            newLine()
            indentLevel++
            printStatement(defaultCase)
            indentLevel--
        }

        indentLevel--
        indent()
        append("}")
        newLine()
    }

    private fun printThrowStatement(stmt: ThrowStatement) {
        indent()
        append("throw ")
        printExpression(stmt.expression)
        newLine()
    }

    private fun printAssertStatement(stmt: AssertStatement) {
        indent()
        append("assert ")
        printExpression(stmt.condition)
        stmt.message?.let { msg ->
            append(" : ")
            printExpression(msg)
        }
        newLine()
    }

    private fun printBreakStatement(stmt: BreakStatement) {
        indent()
        append("break")
        stmt.label?.let { append(" $it") }
        newLine()
    }

    private fun printContinueStatement(stmt: ContinueStatement) {
        indent()
        append("continue")
        stmt.label?.let { append(" $it") }
        newLine()
    }

    private fun printExpression(expr: Expression) {
        when (expr) {
            is ConstantExpr -> printConstantExpr(expr)
            is VariableExpr -> append(expr.name)
            is BinaryExpr -> printBinaryExpr(expr)
            is MethodCallExpr -> printMethodCallExpr(expr)
            is PropertyExpr -> printPropertyExpr(expr)
            is ClosureExpr -> printClosureExpr(expr)
            is GStringExpr -> printGStringExpr(expr)
            is ListExpr -> printListExpr(expr)
            is MapExpr -> printMapExpr(expr)
            is RangeExpr -> printRangeExpr(expr)
            is TernaryExpr -> printTernaryExpr(expr)
            is ElvisExpr -> printElvisExpr(expr)
            is UnaryExpr -> printUnaryExpr(expr)
            is NotExpr -> printNotExpr(expr)
            is BitwiseNegationExpr -> printBitwiseNegationExpr(expr)
            is CastExpr -> printCastExpr(expr)
            is ConstructorCallExpr -> printConstructorCallExpr(expr)
            is SpreadExpr -> printSpreadExpr(expr)
            is SpreadMapExpr -> printSpreadMapExpr(expr)
            is AttributeExpr -> printAttributeExpr(expr)
            is MethodPointerExpr -> printMethodPointerExpr(expr)
            is MethodReferenceExpr -> printMethodReferenceExpr(expr)
            is LambdaExpr -> printLambdaExpr(expr)
            is DeclarationExpr -> printDeclarationExpr(expr)
            is ClassExpr -> append(expr.className)
            is ArrayExpr -> printArrayExpr(expr)
            is PostfixExpr -> printPostfixExpr(expr)
            is PrefixExpr -> printPrefixExpr(expr)
            else -> append(expr.toString())
        }
    }

    private fun printConstantExpr(expr: ConstantExpr) {
        when (val value = expr.value) {
            is String -> append("\"${escapeString(value)}\"")
            is Char -> append("'$value'")
            null -> append("null")
            else -> append(value.toString())
        }
    }

    private fun printBinaryExpr(expr: BinaryExpr) {
        printExpression(expr.left)
        append(" ${expr.operator} ")
        printExpression(expr.right)
    }

    private fun printMethodCallExpr(expr: MethodCallExpr) {
        expr.objectExpression?.let { obj ->
            printExpression(obj)
            append(".")
        }
        append(expr.methodName)
        append("(")
        expr.arguments.forEachIndexed { index, arg ->
            if (index > 0) append(", ")
            printExpression(arg)
        }
        append(")")
    }

    private fun printPropertyExpr(expr: PropertyExpr) {
        printExpression(expr.objectExpression)
        append(".${expr.propertyName}")
    }

    private fun printClosureExpr(expr: ClosureExpr) {
        append("{ ")
        if (expr.parameters.isNotEmpty()) {
            append(expr.parameters.joinToString(", ") { "${it.type} ${it.name}" })
            append(" -> ")
        }
        expr.body?.let { body ->
            // For simple closures, inline the body
            when (body) {
                is BlockStatement -> {
                    if (body.statements.size == 1) {
                        printStatementInline(body.statements[0])
                    } else {
                        newLine()
                        indentLevel++
                        printStatement(body)
                        indentLevel--
                        indent()
                    }
                }
                else -> printStatementInline(body)
            }
        }
        append(" }")
    }

    private fun printStatementInline(stmt: Statement) {
        when (stmt) {
            is ExpressionStatement -> printExpression(stmt.expression)
            is ReturnStatement -> {
                stmt.expression?.let { printExpression(it) }
            }
            else -> append("/* ... */")
        }
    }

    private fun printGStringExpr(expr: GStringExpr) {
        append("\"")
        // Simplified GString printing
        expr.strings.forEachIndexed { index, str ->
            append(escapeString(str))
            if (index < expr.expressions.size) {
                append("\${")
                printExpression(expr.expressions[index])
                append("}")
            }
        }
        append("\"")
    }

    private fun printListExpr(expr: ListExpr) {
        append("[")
        expr.elements.forEachIndexed { index, element ->
            if (index > 0) append(", ")
            printExpression(element)
        }
        append("]")
    }

    private fun printMapExpr(expr: MapExpr) {
        append("[")
        if (expr.entries.isEmpty()) {
            append(":")
        } else {
            expr.entries.forEachIndexed { index, entry ->
                if (index > 0) append(", ")
                printExpression(entry.key)
                append(": ")
                printExpression(entry.value)
            }
        }
        append("]")
    }

    private fun printRangeExpr(expr: RangeExpr) {
        printExpression(expr.from)
        append(if (expr.inclusive) ".." else "..<")
        printExpression(expr.to)
    }

    private fun printTernaryExpr(expr: TernaryExpr) {
        printExpression(expr.condition)
        append(" ? ")
        printExpression(expr.trueExpression)
        append(" : ")
        printExpression(expr.falseExpression)
    }

    private fun printElvisExpr(expr: ElvisExpr) {
        printExpression(expr.expression)
        append(" ?: ")
        printExpression(expr.defaultValue)
    }

    private fun printUnaryExpr(expr: UnaryExpr) {
        if (expr.isPrefix) {
            append(expr.operator)
            printExpression(expr.expression)
        } else {
            printExpression(expr.expression)
            append(expr.operator)
        }
    }

    private fun printNotExpr(expr: NotExpr) {
        append("!")
        printExpression(expr.expression)
    }

    private fun printBitwiseNegationExpr(expr: BitwiseNegationExpr) {
        append("~")
        printExpression(expr.expression)
    }

    private fun printCastExpr(expr: CastExpr) {
        if (expr.isCoercion) {
            printExpression(expr.expression)
            append(" as ${expr.targetType}")
        } else {
            append("(${expr.targetType})")
            printExpression(expr.expression)
        }
    }

    private fun printConstructorCallExpr(expr: ConstructorCallExpr) {
        append("new ${expr.typeName}(")
        expr.arguments.forEachIndexed { index, arg ->
            if (index > 0) append(", ")
            printExpression(arg)
        }
        append(")")
    }

    private fun printSpreadExpr(expr: SpreadExpr) {
        append("*")
        printExpression(expr.expression)
    }

    private fun printSpreadMapExpr(expr: SpreadMapExpr) {
        append("*:")
        printExpression(expr.expression)
    }

    private fun printAttributeExpr(expr: AttributeExpr) {
        printExpression(expr.objectExpression)
        append(".@${expr.attribute}")
    }

    private fun printMethodPointerExpr(expr: MethodPointerExpr) {
        printExpression(expr.objectExpression)
        append(".&")
        printExpression(expr.methodName)
    }

    private fun printMethodReferenceExpr(expr: MethodReferenceExpr) {
        printExpression(expr.objectExpression)
        append("::")
        printExpression(expr.methodName)
    }

    private fun printLambdaExpr(expr: LambdaExpr) {
        append("(")
        append(
            expr.parameters.joinToString(", ") { param ->
                if (param.type == "Object") param.name else "${param.type} ${param.name}"
            },
        )
        append(") -> ")
        expr.body?.let { body ->
            when (body) {
                is BlockStatement -> {
                    if (body.statements.size == 1) {
                        printStatementInline(body.statements[0])
                    } else {
                        append("{")
                        newLine()
                        indentLevel++
                        printStatement(body)
                        indentLevel--
                        indent()
                        append("}")
                    }
                }
                is ExpressionStatement -> printExpression(body.expression)
                else -> printStatementInline(body)
            }
        }
    }

    private fun printDeclarationExpr(expr: DeclarationExpr) {
        if (expr.type == "def" || expr.type == "Object") {
            append("def ")
        } else {
            append("${expr.type} ")
        }
        printExpression(expr.variableExpression)
        append(" = ")
        printExpression(expr.rightExpression)
    }

    private fun printArrayExpr(expr: ArrayExpr) {
        append("new ${expr.elementType}")
        if (expr.sizeExpressions.isNotEmpty()) {
            expr.sizeExpressions.forEach { size ->
                append("[")
                printExpression(size)
                append("]")
            }
        } else if (expr.initExpressions.isNotEmpty()) {
            append("[] {")
            expr.initExpressions.forEachIndexed { index, init ->
                if (index > 0) append(", ")
                printExpression(init)
            }
            append("}")
        }
    }

    private fun printPostfixExpr(expr: PostfixExpr) {
        printExpression(expr.expression)
        append(expr.operator)
    }

    private fun printPrefixExpr(expr: PrefixExpr) {
        append(expr.operator)
        printExpression(expr.expression)
    }

    private fun printAnnotations(annotations: List<AnnotationExpr>, inline: Boolean = false) {
        annotations.forEach { ann ->
            if (!inline) indent()
            append("@${ann.name}")
            if (ann.members.isNotEmpty()) {
                append("(")
                ann.members.entries.forEachIndexed { index, (name, value) ->
                    if (index > 0) append(", ")
                    append("$name = ")
                    printExpression(value)
                }
                append(")")
            }
            if (inline) append(" ") else newLine()
        }
    }

    private fun indent() {
        repeat(indentLevel) {
            append(config.indentString)
        }
    }

    private fun newLine() {
        sb.append(config.lineEnding)
    }

    private fun append(str: String) {
        sb.append(str)
    }

    private fun escapeString(str: String): String = str
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}

/**
 * Configuration for the pretty printer.
 */
data class PrinterConfiguration(val indentString: String = "    ", val lineEnding: String = "\n")
