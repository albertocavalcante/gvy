package com.github.albertocavalcante.groovyparser.ast

import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ConstructorDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.groovyparser.ast.expr.Expression
import com.github.albertocavalcante.groovyparser.ast.stmt.CatchClause
import com.github.albertocavalcante.groovyparser.ast.stmt.Statement

/**
 * Provides deep cloning capability for AST nodes.
 *
 * Similar to JavaParser's CloneVisitor.
 */
object NodeCloner {

    /**
     * Deep clones a node and all its children.
     * The cloned node will have no parent set.
     */
    @Suppress("UNCHECKED_CAST", "CyclomaticComplexMethod")
    fun <T : Node> clone(node: T): T = when (node) {
        is CompilationUnit -> cloneCompilationUnit(node) as T
        is ClassDeclaration -> cloneClassDeclaration(node) as T
        is MethodDeclaration -> cloneMethodDeclaration(node) as T
        is FieldDeclaration -> cloneFieldDeclaration(node) as T
        is ConstructorDeclaration -> cloneConstructorDeclaration(node) as T
        is Parameter -> cloneParameter(node) as T
        is ImportDeclaration -> cloneImportDeclaration(node) as T
        is PackageDeclaration -> clonePackageDeclaration(node) as T
        is LineComment -> CommentCloner.cloneLineComment(node) as T
        is BlockComment -> CommentCloner.cloneBlockComment(node) as T
        is JavadocComment -> CommentCloner.cloneJavadocComment(node) as T

        is Statement -> StatementCloner.clone(node) as T
        is Expression -> ExpressionCloner.clone(node) as T
        is CatchClause -> cloneCatchClause(node) as T

        else -> throw UnsupportedOperationException("Cloning not supported for ${node::class.simpleName}")
    }

    private fun cloneCompilationUnit(node: CompilationUnit): CompilationUnit {
        val cloned = CompilationUnit()
        node.packageDeclaration.ifPresent { cloned.setPackageDeclaration(clone(it)) }
        node.imports.forEach { cloned.addImport(clone(it)) }
        node.types.forEach { cloned.addType(clone(it)) }
        cloned.range = CloningUtils.cloneRange(node.range)
        node.comment?.let { cloned.setComment(clone(it)) }
        node.orphanComments.forEach { cloned.addOrphanComment(clone(it)) }
        return cloned
    }

    private fun cloneClassDeclaration(node: ClassDeclaration): ClassDeclaration {
        val cloned = ClassDeclaration(
            name = node.name,
            isInterface = node.isInterface,
            isEnum = node.isEnum,
            isScript = node.isScript,
        )
        cloned.superClass = node.superClass
        node.implementedTypes.forEach { cloned.implementedTypes.add(it) }
        node.fields.forEach { cloned.addField(clone(it)) }
        node.methods.forEach { cloned.addMethod(clone(it)) }
        node.constructors.forEach { cloned.addConstructor(clone(it)) }
        cloned.range = CloningUtils.cloneRange(node.range)
        node.annotations.forEach { cloned.addAnnotation(clone(it)) }
        node.comment?.let { cloned.setComment(clone(it)) }
        return cloned
    }

    private fun cloneMethodDeclaration(node: MethodDeclaration): MethodDeclaration {
        val cloned = MethodDeclaration(name = node.name, returnType = node.returnType)
        node.parameters.forEach { cloned.addParameter(clone(it)) }
        node.body?.let { cloned.body = clone(it) }
        cloned.isStatic = node.isStatic
        cloned.isAbstract = node.isAbstract
        cloned.isFinal = node.isFinal
        cloned.range = CloningUtils.cloneRange(node.range)
        node.annotations.forEach { cloned.addAnnotation(clone(it)) }
        node.comment?.let { cloned.setComment(clone(it)) }
        return cloned
    }

    private fun cloneFieldDeclaration(node: FieldDeclaration): FieldDeclaration {
        val cloned = FieldDeclaration(name = node.name, type = node.type)
        cloned.isStatic = node.isStatic
        cloned.isFinal = node.isFinal
        cloned.hasInitializer = node.hasInitializer
        cloned.range = CloningUtils.cloneRange(node.range)
        node.annotations.forEach { cloned.addAnnotation(clone(it)) }
        node.comment?.let { cloned.setComment(clone(it)) }
        return cloned
    }

    private fun cloneConstructorDeclaration(node: ConstructorDeclaration): ConstructorDeclaration {
        val cloned = ConstructorDeclaration(name = node.name)
        node.parameters.forEach { cloned.addParameter(clone(it)) }
        cloned.range = CloningUtils.cloneRange(node.range)
        node.annotations.forEach { cloned.addAnnotation(clone(it)) }
        node.comment?.let { cloned.setComment(clone(it)) }
        return cloned
    }

    private fun cloneParameter(node: Parameter): Parameter {
        val cloned = Parameter(name = node.name, type = node.type)
        cloned.range = CloningUtils.cloneRange(node.range)
        node.annotations.forEach { cloned.addAnnotation(clone(it)) }
        return cloned
    }

    private fun cloneImportDeclaration(node: ImportDeclaration): ImportDeclaration {
        val cloned = ImportDeclaration(
            name = node.name,
            isStatic = node.isStatic,
            isStarImport = node.isStarImport,
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun clonePackageDeclaration(node: PackageDeclaration): PackageDeclaration {
        val cloned = PackageDeclaration(node.name)
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }

    private fun cloneCatchClause(node: CatchClause): CatchClause {
        val cloned = CatchClause(
            parameter = clone(node.parameter),
            body = clone(node.body),
        )
        cloned.range = CloningUtils.cloneRange(node.range)
        return cloned
    }
}

/**
 * Extension function for easy cloning.
 */
fun <T : Node> T.clone(): T = NodeCloner.clone(this)
