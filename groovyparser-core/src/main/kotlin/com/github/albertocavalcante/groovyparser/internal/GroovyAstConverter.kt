package com.github.albertocavalcante.groovyparser.internal

import com.github.albertocavalcante.groovyparser.Position
import com.github.albertocavalcante.groovyparser.Range
import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.ImportDeclaration
import com.github.albertocavalcante.groovyparser.ast.PackageDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ConstructorDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.groovyparser.ast.expr.BinaryExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ClosureExpr
import com.github.albertocavalcante.groovyparser.ast.expr.ConstantExpr
import com.github.albertocavalcante.groovyparser.ast.expr.Expression
import com.github.albertocavalcante.groovyparser.ast.expr.GStringExpr
import com.github.albertocavalcante.groovyparser.ast.expr.MethodCallExpr
import com.github.albertocavalcante.groovyparser.ast.expr.PropertyExpr
import com.github.albertocavalcante.groovyparser.ast.expr.VariableExpr
import com.github.albertocavalcante.groovyparser.ast.stmt.BlockStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ExpressionStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ForStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.IfStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.ReturnStatement
import com.github.albertocavalcante.groovyparser.ast.stmt.Statement
import com.github.albertocavalcante.groovyparser.ast.stmt.WhileStatement
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.EmptyStatement
import java.lang.reflect.Modifier
import org.codehaus.groovy.ast.expr.Expression as GroovyExpression
import org.codehaus.groovy.ast.stmt.BlockStatement as GroovyBlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement as GroovyExpressionStatement
import org.codehaus.groovy.ast.stmt.ForStatement as GroovyForStatement
import org.codehaus.groovy.ast.stmt.IfStatement as GroovyIfStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement as GroovyReturnStatement
import org.codehaus.groovy.ast.stmt.Statement as GroovyStatement
import org.codehaus.groovy.ast.stmt.WhileStatement as GroovyWhileStatement

/**
 * Converts Groovy's native AST (ModuleNode) to our custom AST (CompilationUnit).
 */
internal class GroovyAstConverter {

    /**
     * Converts a native Groovy ModuleNode to a CompilationUnit.
     */
    fun convert(moduleNode: ModuleNode): CompilationUnit {
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

        // Convert classes
        moduleNode.classes?.forEach { classNode ->
            unit.addType(convertClass(classNode))
        }

        return unit
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
                classDecl.addField(convertField(fieldNode))
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
            setRange(field, propertyNode)
            classDecl.addField(field)
        }

        // Convert constructors
        classNode.declaredConstructors?.forEach { constructorNode ->
            classDecl.addConstructor(convertConstructor(constructorNode, classNode.nameWithoutPackage))
        }

        // Convert methods
        classNode.methods?.forEach { methodNode ->
            if (!methodNode.isSynthetic) {
                classDecl.addMethod(convertMethod(methodNode))
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

        // Convert parameters
        methodNode.parameters?.forEach { param ->
            val parameter = Parameter(
                name = param.name,
                type = param.type?.name ?: "Object",
            )
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

    // ========== Expression Conversion ==========

    private fun convertExpression(expr: GroovyExpression): Expression = when (expr) {
        is MethodCallExpression -> convertMethodCallExpression(expr)
        is ConstantExpression -> convertConstantExpression(expr)
        is VariableExpression -> convertVariableExpression(expr)
        is BinaryExpression -> convertBinaryExpression(expr)
        is PropertyExpression -> convertPropertyExpression(expr)
        is ClosureExpression -> convertClosureExpression(expr)
        is GStringExpression -> convertGStringExpression(expr)
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
        if (args is org.codehaus.groovy.ast.expr.ArgumentListExpression) {
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

    private fun convertConstructor(constructorNode: MethodNode, className: String): ConstructorDeclaration {
        val constructor = ConstructorDeclaration(name = className)

        // Convert parameters
        constructorNode.parameters?.forEach { param ->
            val parameter = Parameter(
                name = param.name,
                type = param.type?.name ?: "Object",
            )
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
