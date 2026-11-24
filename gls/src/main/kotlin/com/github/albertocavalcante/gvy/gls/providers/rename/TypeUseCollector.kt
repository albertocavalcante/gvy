// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

import com.github.albertocavalcante.groovyparser.ast.visitor.isSharedDefaultType
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.GenericsType
import org.codehaus.groovy.ast.InnerClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ArrayExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.control.SourceUnit

/**
 * Records every type a class header, member signature or expression spells. Only use-site nodes are recorded: a
 * node that redirects to a declared class, so its position is the use's. A declared class node carries the position
 * of its declaration and is never one (the resolved superclass is one; the use is the unresolved superclass). Types
 * without a position (`def`, generated members) spell nothing and are skipped by the windows built later.
 */
internal class TypeUseCollector : ClassCodeVisitorSupport() {
    private val types = TypeUseRecorder()
    val uses: List<TypeUseSite> get() = types.uses
    val otherNames = mutableListOf<OtherNameSite>()

    override fun getSourceUnit(): SourceUnit? = null

    override fun visitClass(node: ClassNode) {
        // `new Service() { }` keeps the use of `Service` as the anonymous class's supertype, not as the call's type.
        val anonymous = (node as? InnerClassNode)?.isAnonymous == true
        val superclass = if (anonymous) TypeUseKind.ANONYMOUS_SUBCLASS else TypeUseKind.SUPERCLASS
        val implemented = if (anonymous) TypeUseKind.ANONYMOUS_SUBCLASS else TypeUseKind.INTERFACE
        types.type(node.getUnresolvedSuperClass(false), superclass)
        node.interfaces.orEmpty().forEach { types.type(it, implemented) }
        node.permittedSubclasses.orEmpty().forEach { types.type(it, TypeUseKind.PERMITTED_SUBCLASS) }
        node.genericsTypes.orEmpty().forEach(types::generic)
        super.visitClass(node)
    }

    override fun visitConstructorOrMethod(node: MethodNode, isConstructor: Boolean) {
        if (!isConstructor) {
            types.type(node.returnType, TypeUseKind.RETURN_TYPE)
            otherNames += methodName(node)
        }
        node.exceptions.orEmpty().forEach { types.type(it, TypeUseKind.EXCEPTION) }
        node.genericsTypes.orEmpty().forEach(types::generic)
        node.parameters.orEmpty().forEach(types::parameter)
        super.visitConstructorOrMethod(node, isConstructor)
    }

    override fun visitField(node: FieldNode) {
        types.type(node.originType, TypeUseKind.FIELD)
        super.visitField(node)
    }

    override fun visitDeclarationExpression(expression: DeclarationExpression) {
        if (expression.isMultipleAssignmentDeclaration) {
            expression.tupleExpression.expressions.filterIsInstance<VariableExpression>()
                .forEach { types.type(it.originType, TypeUseKind.VARIABLE) }
        } else {
            types.type(expression.variableExpression.originType, TypeUseKind.VARIABLE)
        }
        super.visitDeclarationExpression(expression)
    }

    override fun visitCastExpression(expression: CastExpression) {
        types.type(expression.type, TypeUseKind.CAST)
        super.visitCastExpression(expression)
    }

    override fun visitClassExpression(expression: ClassExpression) {
        types.type(expression.type, TypeUseKind.CLASS_LITERAL)
        super.visitClassExpression(expression)
    }

    override fun visitArrayExpression(expression: ArrayExpression) {
        types.type(expression.elementType, TypeUseKind.ARRAY_ELEMENT)
        super.visitArrayExpression(expression)
    }

    override fun visitConstructorCallExpression(call: ConstructorCallExpression) {
        // An anonymous class is the type of its call; its own visit records the supertype it spells.
        if (!call.isUsingAnonymousInnerClass) types.type(call.type, TypeUseKind.CONSTRUCTOR_CALL)
        super.visitConstructorCallExpression(call)
    }

    override fun visitAnnotations(node: AnnotatedNode) {
        node.annotations.forEach { types.type(it.classNode, TypeUseKind.ANNOTATION) }
        super.visitAnnotations(node)
    }

    override fun visitImports(module: ModuleNode?) {
        module?.let { imports ->
            (imports.imports + imports.staticImports.values + imports.staticStarImports.values)
                .forEach { types.type(it.type, TypeUseKind.IMPORT) }
        }
        super.visitImports(module)
    }

    override fun visitClosureExpression(expression: ClosureExpression) {
        expression.parameters.orEmpty().forEach(types::parameter)
        super.visitClosureExpression(expression)
    }

    override fun visitCatchStatement(statement: CatchStatement) {
        statement.variable?.let(types::parameter)
        super.visitCatchStatement(statement)
    }

    override fun visitForLoop(loop: ForStatement) {
        loop.variable?.takeUnless { it === ForStatement.FOR_LOOP_DUMMY }?.let(types::parameter)
        super.visitForLoop(loop)
    }

    override fun visitVariableExpression(expression: VariableExpression) {
        if (!expression.isThisExpression && !expression.isSuperExpression) {
            otherNames += OtherNameSite(expression.name, expression)
        }
        super.visitVariableExpression(expression)
    }

    override fun visitMethodCallExpression(call: MethodCallExpression) {
        (call.method as? ConstantExpression)?.let { method ->
            (method.value as? String)?.let { otherNames += OtherNameSite(it, method) }
        }
        super.visitMethodCallExpression(call)
    }
}

/** The use sites of one walk; see [TypeUseCollector]. */
private class TypeUseRecorder {
    val uses = mutableListOf<TypeUseSite>()

    fun parameter(parameter: Parameter) = type(parameter.originType, TypeUseKind.PARAMETER)

    fun generic(generic: GenericsType) {
        if (!generic.isPlaceholder && !generic.isWildcard) type(generic.type, TypeUseKind.TYPE_ARGUMENT)
        generic.upperBounds.orEmpty().forEach { type(it, TypeUseKind.TYPE_PARAMETER_BOUND) }
        generic.lowerBound?.let { type(it, TypeUseKind.TYPE_PARAMETER_BOUND) }
    }

    /** Records [node] (or, for an array, its element type) as a use of the class it resolves to, and its generics. */
    fun type(node: ClassNode?, kind: TypeUseKind) {
        if (node == null || isSharedDefaultType(node)) return
        if (node.isArray) {
            arrayElement(node, kind)
            return
        }
        val declared = node.redirect()
        if (node !== declared && !node.isGenericsPlaceHolder) {
            uses += TypeUseSite(declared, kind, node)
            generateSequence(declared.outerClass) { it.outerClass }.forEach {
                uses += TypeUseSite(it, TypeUseKind.OUTER_QUALIFIER, node)
            }
        }
        node.genericsTypes.orEmpty().forEach(::generic)
    }

    /** `Service[]` positions the array type; its element type may have no position of its own. */
    private fun arrayElement(array: ClassNode, kind: TypeUseKind) {
        val element = generateSequence(array) { it.componentType }.last()
        if (element.lineNumber > 0) {
            type(element, kind)
            return
        }
        val declared = element.redirect()
        if (element !== declared && !isSharedDefaultType(element)) {
            uses += TypeUseSite(declared, kind, array)
        }
        element.genericsTypes.orEmpty().forEach(::generic)
    }
}

/**
 * A method's name lies after its annotations, type parameters and return type, which these nodes span, and before
 * its parameters (and their annotations) and its body.
 */
private fun methodName(method: MethodNode): OtherNameSite {
    val after = buildList<ASTNode> {
        addAll(method.annotations)
        addAll(method.genericsTypes.orEmpty())
        add(method.returnType)
    }
    val until = buildList<ASTNode> {
        method.parameters.orEmpty().forEach { parameter ->
            add(parameter)
            addAll(parameter.annotations)
        }
        method.code?.let(::add)
    }
    return OtherNameSite(method.name, method, after, until)
}
