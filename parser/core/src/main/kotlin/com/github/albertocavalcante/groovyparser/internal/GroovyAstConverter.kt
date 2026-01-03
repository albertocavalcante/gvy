package com.github.albertocavalcante.groovyparser.internal

import com.github.albertocavalcante.groovyparser.Position
import com.github.albertocavalcante.groovyparser.Range
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
import com.github.albertocavalcante.groovyparser.ast.expr.MapEntryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MapExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodPointerExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodReferenceExpr
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
import com.github.albertocavalcante.groovyparser.ast.stmt.CaseStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.CatchClause
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
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
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
import org.codehaus.groovy.ast.stmt.EmptyStatement
import java.lang.reflect.Modifier
import org.codehaus.groovy.ast.expr.Expression as GroovyExpression
import org.codehaus.groovy.ast.stmt.AssertStatement as GroovyAssertStatement
import org.codehaus.groovy.ast.stmt.BlockStatement as GroovyBlockStatement
import org.codehaus.groovy.ast.stmt.BreakStatement as GroovyBreakStatement
import org.codehaus.groovy.ast.stmt.ContinueStatement as GroovyContinueStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement as GroovyExpressionStatement
import org.codehaus.groovy.ast.stmt.ForStatement as GroovyForStatement
import org.codehaus.groovy.ast.stmt.IfStatement as GroovyIfStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement as GroovyReturnStatement
import org.codehaus.groovy.ast.stmt.Statement as GroovyStatement
import org.codehaus.groovy.ast.stmt.SwitchStatement as GroovySwitchStatement
import org.codehaus.groovy.ast.stmt.ThrowStatement as GroovyThrowStatement
import org.codehaus.groovy.ast.stmt.TryCatchStatement as GroovyTryCatchStatement
import org.codehaus.groovy.ast.stmt.WhileStatement as GroovyWhileStatement

/**
 * Converts Groovy's native AST (ModuleNode) to our custom AST (CompilationUnit).
 *
 * Supports optional source-based comment extraction when source is provided.
 */
internal class GroovyAstConverter {

    /** Parser for extracting comments from source positions */
    private var commentParser: SourcePositionCommentParser? = null

    /** Track the last position processed for comment extraction */
    private var lastLine: Int = 1
    private var lastColumn: Int = 1

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
        // Comments are attached to each class based on the gap before its position
        moduleNode.classes?.forEach { classNode ->
            val classDecl = convertClass(classNode)
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

    private fun convertClass(classNode: ClassNode): ClassDeclaration {
        val classDecl = ClassDeclaration(
            name = classNode.nameWithoutPackage,
            isInterface = classNode.isInterface,
            isEnum = classNode.isEnum,
            isScript = classNode.isScript,
        )

        // Convert annotations
        convertAnnotations(classNode.annotations, classDecl)

        // Set superclass
        classNode.superClass?.let { superClass ->
            if (superClass.name != "java.lang.Object" && superClass.name != "groovy.lang.Script") {
                classDecl.superClass = superClass.name
            }
        }

        // Set implemented interfaces
        classNode.interfaces?.forEach { iface ->
            classDecl.implementedTypes.add(iface.name)
        }

        // Convert fields (properties in Groovy are converted to fields)
        classNode.fields?.forEach { fieldNode ->
            if (!fieldNode.isSynthetic) {
                val field = convertField(fieldNode)
                attachLeadingComment(field, fieldNode)
                classDecl.addField(field)
            }
        }

        // Convert properties (Groovy properties generate synthetic fields, so we convert them directly)
        classNode.properties?.forEach { propertyNode ->
            val field = FieldDeclaration(
                name = propertyNode.name,
                type = propertyNode.type?.name ?: "Object",
            )
            field.isStatic = Modifier.isStatic(propertyNode.modifiers)
            field.isFinal = Modifier.isFinal(propertyNode.modifiers)
            field.hasInitializer = propertyNode.field?.hasInitialExpression() ?: false
            convertAnnotations(propertyNode.annotations, field)
            setRange(field, propertyNode)
            attachLeadingComment(field, propertyNode)
            classDecl.addField(field)
        }

        // Convert constructors
        classNode.declaredConstructors?.forEach { constructorNode ->
            val constructor = convertConstructor(constructorNode, classNode.nameWithoutPackage)
            attachLeadingComment(constructor, constructorNode)
            classDecl.addConstructor(constructor)
        }

        // Convert methods
        classNode.methods?.forEach { methodNode ->
            if (!methodNode.isSynthetic) {
                val method = convertMethod(methodNode)
                attachLeadingComment(method, methodNode)
                classDecl.addMethod(method)
            }
        }

        setRange(classDecl, classNode)
        return classDecl
    }

    private fun convertField(fieldNode: FieldNode): FieldDeclaration {
        val field = FieldDeclaration(
            name = fieldNode.name,
            type = fieldNode.type?.name ?: "Object",
        )
        field.isStatic = Modifier.isStatic(fieldNode.modifiers)
        field.isFinal = Modifier.isFinal(fieldNode.modifiers)
        field.hasInitializer = fieldNode.hasInitialExpression()
        convertAnnotations(fieldNode.annotations, field)
        setRange(field, fieldNode)
        return field
    }

    private fun convertMethod(methodNode: MethodNode): MethodDeclaration {
        val returnType = methodNode.returnType?.name ?: "Object"
        val method = MethodDeclaration(
            name = methodNode.name,
            returnType = returnType,
        )
        method.isStatic = Modifier.isStatic(methodNode.modifiers)
        method.isAbstract = Modifier.isAbstract(methodNode.modifiers)
        method.isFinal = Modifier.isFinal(methodNode.modifiers)

        // Convert annotations
        convertAnnotations(methodNode.annotations, method)

        // Convert parameters
        methodNode.parameters?.forEach { param ->
            val parameter = Parameter(
                name = param.name,
                type = param.type?.name ?: "Object",
            )
            convertAnnotations(param.annotations, parameter)
            setRange(parameter, param)
            method.addParameter(parameter)
        }

        // Convert method body
        methodNode.code?.let { code ->
            method.body = convertStatement(code)
        }

        setRange(method, methodNode)
        return method
    }

    // ========== Statement Conversion ==========

    private fun convertStatement(stmt: GroovyStatement): Statement? = when (stmt) {
        is GroovyBlockStatement -> convertBlockStatement(stmt)
        is GroovyExpressionStatement -> convertExpressionStatement(stmt)
        is GroovyReturnStatement -> convertReturnStatement(stmt)
        is GroovyIfStatement -> convertIfStatement(stmt)
        is GroovyForStatement -> convertForStatement(stmt)
        is GroovyWhileStatement -> convertWhileStatement(stmt)
        is GroovyTryCatchStatement -> convertTryCatchStatement(stmt)
        is GroovySwitchStatement -> convertSwitchStatement(stmt)
        is GroovyThrowStatement -> convertThrowStatement(stmt)
        is GroovyAssertStatement -> convertAssertStatement(stmt)
        is GroovyBreakStatement -> convertBreakStatement(stmt)
        is GroovyContinueStatement -> convertContinueStatement(stmt)
        is EmptyStatement -> null
        else -> {
            // For unknown statement types, wrap in a block if possible
            val block = BlockStatement()
            setRange(block, stmt)
            block
        }
    }

    private fun convertBlockStatement(stmt: GroovyBlockStatement): BlockStatement {
        val block = BlockStatement()
        stmt.statements?.forEach { s ->
            convertStatement(s)?.let { block.addStatement(it) }
        }
        setRange(block, stmt)
        return block
    }

    private fun convertExpressionStatement(stmt: GroovyExpressionStatement): ExpressionStatement {
        val expr = convertExpression(stmt.expression)
        val exprStmt = ExpressionStatement(expr)
        setRange(exprStmt, stmt)
        return exprStmt
    }

    private fun convertReturnStatement(stmt: GroovyReturnStatement): ReturnStatement {
        val expr = stmt.expression?.let { convertExpression(it) }
        val returnStmt = ReturnStatement(expr)
        setRange(returnStmt, stmt)
        return returnStmt
    }

    private fun convertIfStatement(stmt: GroovyIfStatement): IfStatement {
        val condition = convertExpression(stmt.booleanExpression.expression)
        val thenStmt = convertStatement(stmt.ifBlock) ?: BlockStatement()
        val elseStmt = stmt.elseBlock?.let {
            if (it !is EmptyStatement) convertStatement(it) else null
        }
        val ifStmt = IfStatement(condition, thenStmt, elseStmt)
        setRange(ifStmt, stmt)
        return ifStmt
    }

    private fun convertForStatement(stmt: GroovyForStatement): ForStatement {
        val variableName = stmt.variable?.name ?: "it"
        val collection = convertExpression(stmt.collectionExpression)
        val body = convertStatement(stmt.loopBlock) ?: BlockStatement()
        val forStmt = ForStatement(variableName, collection, body)
        setRange(forStmt, stmt)
        return forStmt
    }

    private fun convertWhileStatement(stmt: GroovyWhileStatement): WhileStatement {
        val condition = convertExpression(stmt.booleanExpression.expression)
        val body = convertStatement(stmt.loopBlock) ?: BlockStatement()
        val whileStmt = WhileStatement(condition, body)
        setRange(whileStmt, stmt)
        return whileStmt
    }

    private fun convertTryCatchStatement(stmt: GroovyTryCatchStatement): TryCatchStatement {
        val tryBlock = convertStatement(stmt.tryStatement) ?: BlockStatement()
        val tryCatch = TryCatchStatement(tryBlock)

        stmt.catchStatements?.forEach { catchStmt ->
            val param = Parameter(
                name = catchStmt.variable?.name ?: "e",
                type = catchStmt.exceptionType?.name ?: "Exception",
            )
            val body = convertStatement(catchStmt.code) ?: BlockStatement()
            val catchClause = CatchClause(param, body)
            setRange(catchClause, catchStmt)
            tryCatch.addCatchClause(catchClause)
        }

        stmt.finallyStatement?.let { finallyStmt ->
            if (finallyStmt !is EmptyStatement) {
                tryCatch.finallyBlock = convertStatement(finallyStmt)
            }
        }

        setRange(tryCatch, stmt)
        return tryCatch
    }

    private fun convertSwitchStatement(stmt: GroovySwitchStatement): SwitchStatement {
        val expression = convertExpression(stmt.expression)
        val switch = SwitchStatement(expression)

        stmt.caseStatements?.forEach { caseStmt ->
            val caseExpr = convertExpression(caseStmt.expression)
            val caseBody = convertStatement(caseStmt.code) ?: BlockStatement()
            val case = CaseStatement(caseExpr, caseBody)
            setRange(case, caseStmt)
            switch.addCase(case)
        }

        stmt.defaultStatement?.let { defaultStmt ->
            if (defaultStmt !is EmptyStatement) {
                switch.defaultCase = convertStatement(defaultStmt)
            }
        }

        setRange(switch, stmt)
        return switch
    }

    private fun convertThrowStatement(stmt: GroovyThrowStatement): ThrowStatement {
        val expr = convertExpression(stmt.expression)
        val throwStmt = ThrowStatement(expr)
        setRange(throwStmt, stmt)
        return throwStmt
    }

    private fun convertAssertStatement(stmt: GroovyAssertStatement): AssertStatement {
        val condition = convertExpression(stmt.booleanExpression.expression)
        val message = stmt.messageExpression?.let {
            if (it !is ConstantExpression || it.value != null) {
                convertExpression(it)
            } else {
                null
            }
        }
        val assertStmt = AssertStatement(condition, message)
        setRange(assertStmt, stmt)
        return assertStmt
    }

    private fun convertBreakStatement(stmt: GroovyBreakStatement): BreakStatement {
        val breakStmt = BreakStatement(stmt.label)
        setRange(breakStmt, stmt)
        return breakStmt
    }

    private fun convertContinueStatement(stmt: GroovyContinueStatement): ContinueStatement {
        val continueStmt = ContinueStatement(stmt.label)
        setRange(continueStmt, stmt)
        return continueStmt
    }

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
        is MethodCallExpression -> convertMethodCallExpression(expr)
        is ConstantExpression -> convertConstantExpression(expr)
        is VariableExpression -> convertVariableExpression(expr)
        // Declaration extends BinaryExpression, so check it first
        is DeclarationExpression -> convertDeclarationExpression(expr)
        is BinaryExpression -> convertBinaryExpression(expr)
        // Attribute extends Property, so check it first
        is AttributeExpression -> convertAttributeExpression(expr)
        is PropertyExpression -> convertPropertyExpression(expr)
        is ClosureExpression -> convertClosureExpression(expr)
        is GStringExpression -> convertGStringExpression(expr)
        is ListExpression -> convertListExpression(expr)
        is MapExpression -> convertMapExpression(expr)
        is RangeExpression -> convertRangeExpression(expr)
        // Ternary and Elvis (must check Elvis first as it extends Ternary)
        is ElvisOperatorExpression -> convertElvisExpression(expr)
        is TernaryExpression -> convertTernaryExpression(expr)
        // Unary expressions
        is NotExpression -> convertNotExpression(expr)
        is UnaryMinusExpression -> convertUnaryMinusExpression(expr)
        is UnaryPlusExpression -> convertUnaryPlusExpression(expr)
        is BitwiseNegationExpression -> convertBitwiseNegationExpression(expr)
        is PrefixExpression -> convertPrefixExpression(expr)
        is PostfixExpression -> convertPostfixExpression(expr)
        // Type expressions
        is CastExpression -> convertCastExpression(expr)
        is ClassExpression -> convertClassExpression(expr)
        is ConstructorCallExpression -> convertConstructorCallExpression(expr)
        is ArrayExpression -> convertArrayExpression(expr)
        // Spread expressions
        is SpreadExpression -> convertSpreadExpression(expr)
        is SpreadMapExpression -> convertSpreadMapExpression(expr)
        // Method references
        is MethodPointerExpression -> convertMethodPointerExpression(expr)
        is MethodReferenceExpression -> convertMethodReferenceExpression(expr)
        // Lambda and Declaration
        is LambdaExpression -> convertLambdaExpression(expr)
        // DeclarationExpression moved up
        // is DeclarationExpression -> convertDeclarationExpression(expr)
        // Attribute access moved up
        // is AttributeExpression -> convertAttributeExpression(expr)
        else -> {
            // Fallback: create a constant with the text representation
            val constant = ConstantExpr(expr.text)
            setRange(constant, expr)
            constant
        }
    }

    private fun convertMethodCallExpression(expr: MethodCallExpression): MethodCallExpr {
        val objectExpr = if (expr.isImplicitThis) {
            null
        } else {
            convertExpression(expr.objectExpression)
        }
        val methodName = expr.methodAsString ?: expr.method?.text ?: "unknown"
        val call = MethodCallExpr(objectExpr, methodName)

        // Convert arguments
        val args = expr.arguments
        if (args is ArgumentListExpression) {
            args.expressions?.forEach { arg ->
                call.addArgument(convertExpression(arg))
            }
        }

        setRange(call, expr)
        return call
    }

    private fun convertConstantExpression(expr: ConstantExpression): ConstantExpr {
        val constant = ConstantExpr(expr.value)
        setRange(constant, expr)
        return constant
    }

    private fun convertVariableExpression(expr: VariableExpression): VariableExpr {
        val variable = VariableExpr(expr.name)
        setRange(variable, expr)
        return variable
    }

    private fun convertBinaryExpression(expr: BinaryExpression): BinaryExpr {
        val left = convertExpression(expr.leftExpression)
        val right = convertExpression(expr.rightExpression)
        val operator = expr.operation?.text ?: "?"
        val binary = BinaryExpr(left, operator, right)
        setRange(binary, expr)
        return binary
    }

    private fun convertPropertyExpression(expr: PropertyExpression): PropertyExpr {
        val objectExpr = convertExpression(expr.objectExpression)
        val propertyName = expr.propertyAsString ?: expr.property?.text ?: "unknown"
        val prop = PropertyExpr(objectExpr, propertyName)
        setRange(prop, expr)
        return prop
    }

    private fun convertClosureExpression(expr: ClosureExpression): ClosureExpr {
        val closure = ClosureExpr()

        // Convert parameters
        expr.parameters?.forEach { param ->
            val parameter = Parameter(
                name = param.name,
                type = param.type?.name ?: "Object",
            )
            setRange(parameter, param)
            closure.addParameter(parameter)
        }

        // Convert body
        expr.code?.let { code ->
            closure.body = convertStatement(code)
        }

        setRange(closure, expr)
        return closure
    }

    private fun convertGStringExpression(expr: GStringExpression): GStringExpr {
        val gstring = GStringExpr()

        // Add string parts
        expr.strings?.forEach { str ->
            if (str is ConstantExpression) {
                gstring.addString(str.value?.toString() ?: "")
            }
        }

        // Add expressions
        expr.values?.forEach { value ->
            gstring.addExpression(convertExpression(value))
        }

        setRange(gstring, expr)
        return gstring
    }

    private fun convertListExpression(expr: ListExpression): ListExpr {
        val list = ListExpr()
        expr.expressions?.forEach { element ->
            list.addElement(convertExpression(element))
        }
        setRange(list, expr)
        return list
    }

    private fun convertMapExpression(expr: MapExpression): MapExpr {
        val map = MapExpr()
        expr.mapEntryExpressions?.forEach { entry ->
            val key = convertExpression(entry.keyExpression)
            val value = convertExpression(entry.valueExpression)
            val mapEntry = MapEntryExpr(key, value)
            setRange(mapEntry, entry)
            map.addEntry(mapEntry)
        }
        setRange(map, expr)
        return map
    }

    private fun convertRangeExpression(expr: RangeExpression): RangeExpr {
        val from = convertExpression(expr.from)
        val to = convertExpression(expr.to)
        val range = RangeExpr(from, to, expr.isInclusive)
        setRange(range, expr)
        return range
    }

    private fun convertTernaryExpression(expr: TernaryExpression): TernaryExpr {
        val condition = convertExpression(expr.booleanExpression.expression)
        val trueExpr = convertExpression(expr.trueExpression)
        val falseExpr = convertExpression(expr.falseExpression)
        val ternary = TernaryExpr(condition, trueExpr, falseExpr)
        setRange(ternary, expr)
        return ternary
    }

    private fun convertNotExpression(expr: NotExpression): UnaryExpr {
        val inner = convertExpression(expr.expression)
        val unary = UnaryExpr(inner, "!", true)
        setRange(unary, expr)
        return unary
    }

    private fun convertUnaryMinusExpression(expr: UnaryMinusExpression): UnaryExpr {
        val inner = convertExpression(expr.expression)
        val unary = UnaryExpr(inner, "-", true)
        setRange(unary, expr)
        return unary
    }

    private fun convertUnaryPlusExpression(expr: UnaryPlusExpression): UnaryExpr {
        val inner = convertExpression(expr.expression)
        val unary = UnaryExpr(inner, "+", true)
        setRange(unary, expr)
        return unary
    }

    private fun convertPrefixExpression(expr: PrefixExpression): UnaryExpr {
        val inner = convertExpression(expr.expression)
        val unary = UnaryExpr(inner, expr.operation?.text ?: "++", true)
        setRange(unary, expr)
        return unary
    }

    private fun convertPostfixExpression(expr: PostfixExpression): UnaryExpr {
        val inner = convertExpression(expr.expression)
        val unary = UnaryExpr(inner, expr.operation?.text ?: "++", false)
        setRange(unary, expr)
        return unary
    }

    private fun convertCastExpression(expr: CastExpression): CastExpr {
        val inner = convertExpression(expr.expression)
        val targetType = expr.type?.name ?: "Object"
        val cast = CastExpr(inner, targetType, expr.isCoerce)
        setRange(cast, expr)
        return cast
    }

    private fun convertConstructorCallExpression(expr: ConstructorCallExpression): ConstructorCallExpr {
        val typeName = expr.type?.name ?: "Object"
        val constructorCall = ConstructorCallExpr(typeName)

        val args = expr.arguments
        if (args is ArgumentListExpression) {
            args.expressions?.forEach { arg ->
                constructorCall.addArgument(convertExpression(arg))
            }
        }

        setRange(constructorCall, expr)
        return constructorCall
    }

    private fun convertElvisExpression(expr: ElvisOperatorExpression): ElvisExpr {
        val value = convertExpression(expr.trueExpression)
        val defaultValue = convertExpression(expr.falseExpression)
        val elvis = ElvisExpr(value, defaultValue)
        setRange(elvis, expr)
        return elvis
    }

    private fun convertBitwiseNegationExpression(expr: BitwiseNegationExpression): BitwiseNegationExpr {
        val inner = convertExpression(expr.expression)
        val bitwise = BitwiseNegationExpr(inner)
        setRange(bitwise, expr)
        return bitwise
    }

    private fun convertClassExpression(expr: ClassExpression): ClassExpr {
        val classExpr = ClassExpr(expr.type?.name ?: "Object")
        setRange(classExpr, expr)
        return classExpr
    }

    private fun convertArrayExpression(expr: ArrayExpression): ArrayExpr {
        val elementType = expr.elementType?.name ?: "Object"
        val sizes = expr.sizeExpression?.map { convertExpression(it) } ?: emptyList()
        val inits = expr.expressions?.map { convertExpression(it) } ?: emptyList()
        val array = ArrayExpr(elementType, sizes, inits)
        setRange(array, expr)
        return array
    }

    private fun convertSpreadExpression(expr: SpreadExpression): SpreadExpr {
        val inner = convertExpression(expr.expression)
        val spread = SpreadExpr(inner)
        setRange(spread, expr)
        return spread
    }

    private fun convertSpreadMapExpression(expr: SpreadMapExpression): SpreadMapExpr {
        val inner = convertExpression(expr.expression)
        val spreadMap = SpreadMapExpr(inner)
        setRange(spreadMap, expr)
        return spreadMap
    }

    private fun convertMethodPointerExpression(expr: MethodPointerExpression): MethodPointerExpr {
        val obj = convertExpression(expr.expression)
        val method = convertExpression(expr.methodName)
        val methodPointer = MethodPointerExpr(obj, method)
        setRange(methodPointer, expr)
        return methodPointer
    }

    private fun convertMethodReferenceExpression(expr: MethodReferenceExpression): MethodReferenceExpr {
        val obj = convertExpression(expr.expression)
        val method = convertExpression(expr.methodName)
        val methodRef = MethodReferenceExpr(obj, method)
        setRange(methodRef, expr)
        return methodRef
    }

    private fun convertLambdaExpression(expr: LambdaExpression): LambdaExpr {
        val lambda = LambdaExpr()

        expr.parameters?.forEach { param ->
            val parameter = Parameter(
                name = param.name,
                type = param.type?.name ?: "Object",
            )
            setRange(parameter, param)
            lambda.addParameter(parameter)
        }

        expr.code?.let { code ->
            lambda.body = convertStatement(code)
        }

        setRange(lambda, expr)
        return lambda
    }

    private fun convertDeclarationExpression(expr: DeclarationExpression): DeclarationExpr {
        val variable = convertExpression(expr.leftExpression)
        val right = convertExpression(expr.rightExpression)
        val typeName = expr.leftExpression.type?.name ?: "def"
        val declaration = DeclarationExpr(variable, right, typeName)
        setRange(declaration, expr)
        return declaration
    }

    private fun convertAttributeExpression(expr: AttributeExpression): AttributeExpr {
        val obj = convertExpression(expr.objectExpression)
        val attrName = expr.propertyAsString ?: expr.property?.text ?: "unknown"
        val attribute = AttributeExpr(obj, attrName)
        setRange(attribute, expr)
        return attribute
    }

    // ========== Annotation Conversion ==========

    private fun convertAnnotations(
        annotations: List<AnnotationNode>?,
        target: com.github.albertocavalcante.groovyparser.ast.Node,
    ) {
        annotations?.forEach { ann ->
            val annotation = AnnotationExpr(ann.classNode?.name ?: "Unknown")

            // Convert annotation members
            ann.members?.forEach { (name, value) ->
                annotation.addMember(name, convertExpression(value))
            }

            setRange(annotation, ann)
            target.addAnnotation(annotation)
        }
    }

    private fun convertConstructor(constructorNode: MethodNode, className: String): ConstructorDeclaration {
        val constructor = ConstructorDeclaration(name = className)

        // Convert annotations
        convertAnnotations(constructorNode.annotations, constructor)

        // Convert parameters
        constructorNode.parameters?.forEach { param ->
            val parameter = Parameter(
                name = param.name,
                type = param.type?.name ?: "Object",
            )
            convertAnnotations(param.annotations, parameter)
            setRange(parameter, param)
            constructor.addParameter(parameter)
        }

        setRange(constructor, constructorNode)
        return constructor
    }

    private fun setRange(
        node: com.github.albertocavalcante.groovyparser.ast.Node,
        nativeNode: org.codehaus.groovy.ast.ASTNode,
    ) {
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
