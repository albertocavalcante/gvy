// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.db

import com.github.albertocavalcante.gvy.semantics.db.CallOutcome.Reason
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression
import org.codehaus.groovy.ast.expr.SpreadExpression
import org.codehaus.groovy.ast.expr.TupleExpression

/** Which constructors the index names: the ones a class's source declares, with the parameters it writes. */
internal object IndexedConstructors {
    /** Transforms that add constructors; matched by simple name, as the annotation may be imported or aliased. */
    private val annotations = setOf(
        "TupleConstructor",
        "MapConstructor",
        "Canonical",
        "Immutable",
        "InheritConstructors",
        "RecordType",
        "RecordBase",
        "Builder",
    )

    /**
     * Whether the compiler gives the constructors of [type] parameters its source does not write: the name and
     * ordinal of an enum, the outer instance of a member or local class that is not static. Their ids would say
     * `Outer$Inner#<init>(Outer,int).` for a source `Inner(int x)`, and a call's arguments would not match.
     */
    fun addsParameters(type: ClassNode): Boolean =
        type.isEnum || (type.outerClass != null && !java.lang.reflect.Modifier.isStatic(type.modifiers))

    /**
     * Whether [type] has or may get a constructor the source does not declare: a constructor with no source
     * position (a transform has run) or an annotation of a transform that adds them (it has not run yet).
     */
    fun mayAddTo(type: ClassNode): Boolean = type.declaredConstructors.any { it.lineNumber <= 0 } ||
        type.annotations.any { it.classNode.nameWithoutPackage in annotations }
}

/**
 * Resolves `new T(args)` to the id of the one constructor it invokes, or says why that is not certain.
 *
 * The same discipline as [CallResolver.resolve], with what differs for constructors: they are not inherited, so only
 * the constructors `T` declares count and no hierarchy needs to be known. The call is attributed only when:
 *
 * 1. `T` is a source class of this compilation unit that the compiler resolved (a classpath class has no indexed
 *    constructors), not one whose constructors take hidden compiler parameters (see
 *    [IndexedConstructors.addsParameters]) and not an anonymous class;
 * 2. every constructor `T` has is one the source declares: a transform that adds constructors (`@TupleConstructor`,
 *    `@MapConstructor`, `@Canonical`...) may add one that takes the call, and such a constructor has no id here;
 * 3. the call is not `this(...)` or `super(...)`, has no spread argument, and no named arguments, which may use the
 *    map constructor instead of a declared one;
 * 4. exactly one declared constructor can take the argument count (see [Arity]).
 *
 * A class with no declared constructor has the implicit default one, which has no id: its calls are
 * [Reason.NO_CANDIDATE]. Argument types are never consulted, as for methods.
 */
internal class ConstructorResolver(private val hierarchies: TypeHierarchies = TypeHierarchies()) {
    fun resolve(call: ConstructorCallExpression): CallOutcome {
        val target = hierarchies.compilerResolved(call.type)
        val arguments = argumentExpressions(call.arguments)
        return when {
            call.isUsingAnonymousInnerClass -> CallOutcome.Unresolved(Reason.ANONYMOUS_CLASS)
            call.isSpecialCall -> CallOutcome.Unresolved(Reason.SPECIAL_CONSTRUCTOR_CALL)
            target == null || !target.isPrimaryClassNode || IndexedConstructors.addsParameters(target) ->
                CallOutcome.Unresolved(Reason.NOT_INDEXED_CLASS)
            IndexedConstructors.mayAddTo(target) -> CallOutcome.Unresolved(Reason.GENERATED_CONSTRUCTOR)
            arguments.any { it is SpreadExpression } -> CallOutcome.Unresolved(Reason.SPREAD_ARGUMENT)
            arguments.any { it is NamedArgumentListExpression } -> CallOutcome.Unresolved(Reason.NAMED_ARGUMENTS)
            else -> choose(target.declaredConstructors, arguments.size)
        }
    }

    private fun choose(constructors: List<ConstructorNode>, arity: Int): CallOutcome {
        val candidates = constructors.filter { Arity.accepts(it, arity) }
        return when (candidates.size) {
            0 -> CallOutcome.Unresolved(Reason.NO_CANDIDATE)
            1 -> candidates.single().let {
                CallOutcome.Resolved(SemanticDocumentBuilder.createMethodSymbolId(it.declaringClass, it).id)
            }
            else -> CallOutcome.Unresolved(Reason.AMBIGUOUS)
        }
    }

    private fun argumentExpressions(arguments: Expression): List<Expression> =
        if (arguments is TupleExpression) arguments.expressions else listOf(arguments)
}
