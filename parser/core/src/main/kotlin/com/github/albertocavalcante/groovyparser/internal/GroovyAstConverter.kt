package com.github.albertocavalcante.groovyparser.internal

import com.github.albertocavalcante.groovyparser.Position
import com.github.albertocavalcante.groovyparser.Range
import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.ImportDeclaration
import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.PackageDeclaration
import com.github.albertocavalcante.groovyparser.ast.expr.ConstantExpr
import com.github.albertocavalcante.groovyparser.ast.expr.Expression
import com.github.albertocavalcante.groovyparser.ast.stmt.Statement
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ArrayExpression
import org.codehaus.groovy.ast.expr.AttributeExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.BitwiseNegationExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.LambdaExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.MethodPointerExpression
import org.codehaus.groovy.ast.expr.MethodReferenceExpression
import org.codehaus.groovy.ast.expr.NotExpression
import org.codehaus.groovy.ast.expr.PostfixExpression
import org.codehaus.groovy.ast.expr.PrefixExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.RangeExpression
import org.codehaus.groovy.ast.expr.SpreadExpression
import org.codehaus.groovy.ast.expr.SpreadMapExpression
import org.codehaus.groovy.ast.expr.TernaryExpression
import org.codehaus.groovy.ast.expr.UnaryMinusExpression
import org.codehaus.groovy.ast.expr.UnaryPlusExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.expr.Expression as GroovyExpression

/**
 * Converts Groovy's native AST (ModuleNode) to our custom AST (CompilationUnit).
 *
 * This class now delegates to specialized converters for different AST categories:
 * - [LiteralConverter]: Handles literals (strings, numbers, lists, maps, ranges)
 * - [ExpressionConverter]: Handles expressions (binary, unary, ternary, method calls)
 * - [StatementConverter]: Handles statements (if, for, while, try, switch)
 * - [DeclarationConverter]: Handles declarations (class, method, field, constructor)
 *
 * Supports optional source-based comment extraction when source is provided.
 */
internal class GroovyAstConverter {

    /** Parser for extracting comments from source positions */
    private var commentParser: SourcePositionCommentParser? = null

    /** Track the last position processed for comment extraction */
    private var lastLine: Int = 1
    private var lastColumn: Int = 1

    // Specialized converters using composition
    private val literalConverter = LiteralConverter(setRange = ::setRange)
    private val expressionConverter = ExpressionConverter(
        setRange = ::setRange,
    )
    private val statementConverter = StatementConverter(setRange = ::setRange)
    private val declarationConverter = DeclarationConverter(
        setRange = ::setRange,
    )

    /**
     * Converts a native Groovy ModuleNode to a CompilationUnit.
     *
     * @param moduleNode the native Groovy AST
     * @param source optional source code for comment extraction
     */
    fun convert(moduleNode: ModuleNode, source: String? = null): CompilationUnit {
        // Initialize comment parser if source is provided
        commentParser = source?.let { SourcePositionCommentParser(it) }
        lastLine = 1
        lastColumn = 1

        val unit = CompilationUnit()

        // Convert package declaration
        moduleNode.packageName?.let { packageName ->
            val pkg = PackageDeclaration(packageName.removeSuffix("."))
            unit.setPackageDeclaration(pkg)
        }

        // Convert imports
        moduleNode.imports?.forEach { importNode ->
            unit.addImport(convertImport(importNode))
        }
        moduleNode.starImports?.forEach { importNode ->
            unit.addImport(convertStarImport(importNode))
        }
        moduleNode.staticImports?.values?.forEach { importNode ->
            unit.addImport(convertStaticImport(importNode))
        }
        moduleNode.staticStarImports?.values?.forEach { importNode ->
            unit.addImport(convertStaticStarImport(importNode))
        }

        // Convert classes (with comment attachment)
        // NOTE: attachLeadingComment updates position tracking, so we must call it
        // in the correct order: class comment AFTER all member comments
        moduleNode.classes?.forEach { classNode ->
            val classDecl = declarationConverter.convertClass(classNode, ::convertAnnotations)

            // Convert fields (properties in Groovy are converted to fields)
            classNode.fields?.forEach { fieldNode ->
                if (!fieldNode.isSynthetic) {
                    val field = declarationConverter.convertField(fieldNode, ::convertAnnotations)
                    attachLeadingComment(field, fieldNode)
                    classDecl.addField(field)
                }
            }

            // Convert properties (Groovy properties generate synthetic fields)
            classNode.properties?.forEach { propertyNode ->
                val field = declarationConverter.convertProperty(propertyNode, ::convertAnnotations)
                attachLeadingComment(field, propertyNode)
                classDecl.addField(field)
            }

            // Convert constructors
            classNode.declaredConstructors?.forEach { constructorNode ->
                val constructor = declarationConverter.convertConstructor(
                    constructorNode,
                    classNode.nameWithoutPackage,
                    ::convertAnnotations,
                )
                attachLeadingComment(constructor, constructorNode)
                classDecl.addConstructor(constructor)
            }

            // Convert methods
            classNode.methods?.forEach { methodNode ->
                if (!methodNode.isSynthetic) {
                    val method = declarationConverter.convertMethod(
                        methodNode,
                        ::convertAnnotations,
                        ::convertStatement,
                    )
                    attachLeadingComment(method, methodNode)
                    classDecl.addMethod(method)
                }
            }

            // Attach class comment AFTER processing all members
            // (attachLeadingComment updates lastLine/lastColumn, which affects subsequent member comment extraction)
            attachLeadingComment(classDecl, classNode)

            unit.addType(classDecl)
        }

        return unit
    }

    /**
     * Attaches a leading comment to a node based on source position.
     */
    private fun attachLeadingComment(node: Node, nativeNode: ASTNode) {
        if (commentParser == null || nativeNode.lineNumber <= 0) return

        val comments = commentParser?.extractCommentsBetween(
            lastLine,
            lastColumn,
            nativeNode.lineNumber,
            nativeNode.columnNumber,
        ) ?: return

        // Attach the last comment as the node's leading comment (typically Javadoc)
        val leadingComment = comments.lastOrNull()
        if (leadingComment != null) {
            node.setComment(leadingComment)
            // Other comments become orphans
            comments.dropLast(1).forEach { node.addOrphanComment(it) }
        }

        // Update position tracker
        if (nativeNode.lastLineNumber > 0) {
            lastLine = nativeNode.lastLineNumber
            lastColumn = nativeNode.lastColumnNumber
        }
    }

    // ========== Import Conversion ==========

    private fun convertImport(importNode: ImportNode): ImportDeclaration {
        val import = ImportDeclaration(
            name = importNode.type?.name ?: importNode.className ?: "",
            isStatic = false,
            isStarImport = false,
        )
        setRange(import, importNode)
        return import
    }

    private fun convertStarImport(importNode: ImportNode): ImportDeclaration {
        val import = ImportDeclaration(
            name = importNode.packageName?.removeSuffix(".") ?: "",
            isStatic = false,
            isStarImport = true,
        )
        setRange(import, importNode)
        return import
    }

    private fun convertStaticImport(importNode: ImportNode): ImportDeclaration {
        val import = ImportDeclaration(
            name = "${importNode.type?.name}.${importNode.fieldName}",
            isStatic = true,
            isStarImport = false,
        )
        setRange(import, importNode)
        return import
    }

    private fun convertStaticStarImport(importNode: ImportNode): ImportDeclaration {
        val import = ImportDeclaration(
            name = importNode.type?.name ?: "",
            isStatic = true,
            isStarImport = true,
        )
        setRange(import, importNode)
        return import
    }

    // ========== Statement Conversion ==========

    private fun convertStatement(stmt: org.codehaus.groovy.ast.stmt.Statement): Statement? =
        statementConverter.convert(stmt, ::convertExpression)

    // ========== Expression Conversion ==========

    /**
     * Converts Groovy AST expressions to our internal AST.
     *
     * [HEURISTIC NOTE] - CRITICAL ORDERING
     * Groovy's AST hierarchy has some quirks where specialized nodes extend generic ones:
     * 1. `DeclarationExpression` extends `BinaryExpression`.
     * 2. `AttributeExpression` extends `PropertyExpression`.
     * 3. `ElvisOperatorExpression` extends `TernaryExpression`.
     *
     * We MUST check for the specialized subtype FIRST.
     * If we check `is BinaryExpression` before `is DeclarationExpression`, we will incorrectly
     * parse declarations (def x = 1) as binary assignments (x = 1), losing type information.
     *
     * This heuristic reliance on strict `when` clause ordering is brittle but necessary
     * due to the upstream Groovy AST design.
     */
    private fun convertExpression(expr: GroovyExpression): Expression = when (expr) {
        // Common expressions
        is MethodCallExpression -> expressionConverter.convertMethodCall(expr, ::convertExpression)
        is ConstantExpression -> literalConverter.convertConstant(expr)
        is VariableExpression -> expressionConverter.convertVariable(expr)

        // Declaration extends BinaryExpression, so check it first
        is DeclarationExpression -> expressionConverter.convertDeclaration(expr, ::convertExpression)
        is BinaryExpression -> expressionConverter.convertBinary(expr, ::convertExpression)

        // Attribute extends Property, so check it first
        is AttributeExpression -> expressionConverter.convertAttribute(expr, ::convertExpression)
        is PropertyExpression -> expressionConverter.convertProperty(expr, ::convertExpression)

        is ClosureExpression -> expressionConverter.convertClosure(expr, ::convertStatement)

        // Literal value expressions
        is GStringExpression, is ListExpression, is MapExpression, is RangeExpression, is ArrayExpression ->
            convertValueExpression(expr)

        // Unary and ternary expressions
        is ElvisOperatorExpression, is TernaryExpression, is NotExpression, is UnaryMinusExpression,
        is UnaryPlusExpression, is BitwiseNegationExpression, is PrefixExpression, is PostfixExpression,
        ->
            convertLogicExpression(expr)

        // Structural expressions
        else -> convertStructuralExpression(expr)
    }

    private fun convertValueExpression(expr: GroovyExpression): Expression = when (expr) {
        is GStringExpression -> literalConverter.convertGString(expr, ::convertExpression)
        is ListExpression -> literalConverter.convertList(expr, ::convertExpression)
        is MapExpression -> literalConverter.convertMap(expr, ::convertExpression)
        is RangeExpression -> literalConverter.convertRange(expr, ::convertExpression)
        is ArrayExpression -> literalConverter.convertArray(expr, ::convertExpression)
        else -> throw IllegalArgumentException("Unknown value expression: ${expr.javaClass.name}")
    }

    private fun convertLogicExpression(expr: GroovyExpression): Expression = when (expr) {
        is ElvisOperatorExpression -> expressionConverter.convertElvis(expr, ::convertExpression)
        is TernaryExpression -> expressionConverter.convertTernary(expr, ::convertExpression)
        is NotExpression -> expressionConverter.convertNot(expr, ::convertExpression)
        is UnaryMinusExpression -> expressionConverter.convertUnaryMinus(expr, ::convertExpression)
        is UnaryPlusExpression -> expressionConverter.convertUnaryPlus(expr, ::convertExpression)
        is BitwiseNegationExpression -> expressionConverter.convertBitwiseNegation(expr, ::convertExpression)
        is PrefixExpression -> expressionConverter.convertPrefix(expr, ::convertExpression)
        is PostfixExpression -> expressionConverter.convertPostfix(expr, ::convertExpression)
        else -> throw IllegalArgumentException("Unknown logic expression: ${expr.javaClass.name}")
    }

    private fun convertStructuralExpression(expr: GroovyExpression): Expression = when (expr) {
        is CastExpression -> expressionConverter.convertCast(expr, ::convertExpression)
        is ClassExpression -> expressionConverter.convertClass(expr)
        is ConstructorCallExpression -> expressionConverter.convertConstructorCall(expr, ::convertExpression)
        is SpreadExpression -> expressionConverter.convertSpread(expr, ::convertExpression)
        is SpreadMapExpression -> expressionConverter.convertSpreadMap(expr, ::convertExpression)
        is MethodPointerExpression -> expressionConverter.convertMethodPointer(expr, ::convertExpression)
        is MethodReferenceExpression -> expressionConverter.convertMethodReference(expr, ::convertExpression)
        is LambdaExpression -> expressionConverter.convertLambda(expr, ::convertStatement)
        else -> {
            // Fallback: create a constant with the text representation
            val constant = ConstantExpr(expr.text)
            setRange(constant, expr)
            constant
        }
    }

    // ========== Annotation Conversion ==========

    private fun convertAnnotations(annotations: List<AnnotationNode>?, target: Node) {
        declarationConverter.convertAnnotations(annotations, target, ::convertExpression)
    }

    // ========== Range Helper ==========

    private fun setRange(node: Node, nativeNode: ASTNode) {
        if (nativeNode.lineNumber > 0 && nativeNode.columnNumber > 0) {
            val begin = Position(nativeNode.lineNumber, nativeNode.columnNumber)
            val end = if (nativeNode.lastLineNumber > 0 && nativeNode.lastColumnNumber > 0) {
                Position(nativeNode.lastLineNumber, nativeNode.lastColumnNumber)
            } else {
                begin
            }
            node.range = Range(begin, end)
        }
    }
}
