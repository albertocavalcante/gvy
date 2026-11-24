// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.semantics.db

import com.github.albertocavalcante.gvy.semantics.db.CallOutcome.Reason
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression
import org.codehaus.groovy.ast.expr.SpreadExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import java.lang.reflect.Modifier

/** Which constructors the index names: the ones a class's source declares, with the parameters it writes. */
internal object IndexedConstructors {
    /**
     * Transforms that add constructors, matched by simple name as the annotation may be imported or aliased. The
     * workspace indexer runs at SEMANTIC_ANALYSIS, before they run, so this list is all that protects it:
     * - `TupleConstructor`, `MapConstructor`, `Canonical`, `Immutable`, `RecordType`/`RecordBase` (records), and
     *   `Builder` (its initializer strategy) generate constructors;
     * - `InheritConstructors` copies the superclass's;
     * - `AutoClone(style = COPY_CONSTRUCTOR)` adds a copy constructor `T(T)`;
     * - `NamedVariant` on a constructor adds a `Map`-taking one.
     * See the Groovy documentation of `groovy.transform` (TupleConstructor, MapConstructor, Canonical, Immutable,
     * InheritConstructors, AutoClone, NamedVariant, builder.Builder). `Singleton` makes the constructor private and
     * adds none, and `Newify` only rewrites calls, so neither is listed.
     */
    private val annotations = setOf(
        "TupleConstructor",
        "MapConstructor",
        "Canonical",
        "Immutable",
        "InheritConstructors",
        "RecordType",
        "RecordBase",
        "Builder",
        "AutoClone",
        "NamedVariant",
    )

    /**
     * Whether the compiler gives the constructors of [type] parameters its source does not write: the name and
     * ordinal of an enum, the outer instance of a member or local class that is not static. Their ids would say
     * `Outer$Inner#<init>(Outer,int).` for a source `Inner(int x)`, and a call's arguments would not match.
     */
    fun addsParameters(type: ClassNode): Boolean =
        type.isEnum || (type.outerClass != null && !Modifier.isStatic(type.modifiers))

    /**
     * Whether [type] has or may get a constructor the source does not declare: a constructor with no source
     * position (a transform has run) or an annotation of a transform that adds them (it has not run yet).
     */
    fun mayAddTo(type: ClassNode): Boolean = type.declaredConstructors.any { it.lineNumber <= 0 } ||
        (type.annotations + type.declaredConstructors.flatMap { it.annotations })
            .any { it.classNode.nameWithoutPackage in annotations }
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
 *    map constructor instead of a declared one; a single argument that may be a Map is not attributed either when
 *    `T` has a constructor that takes no arguments (it may be that one, with the Map setting properties);
 * 4. exactly one declared constructor can take the argument count (see [Arity]); argument types are not consulted,
 *    so `new T(1)` is credited to the one-argument `T(String)`, as Groovy's own dispatch could still reject it.
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
            target == null || !target.isPrimaryClassNode -> CallOutcome.Unresolved(Reason.NOT_SOURCE_CLASS)
            IndexedConstructors.addsParameters(target) -> CallOutcome.Unresolved(Reason.HIDDEN_PARAMETERS)
            IndexedConstructors.mayAddTo(target) -> CallOutcome.Unresolved(Reason.GENERATED_CONSTRUCTOR)
            arguments.any { it is SpreadExpression } -> CallOutcome.Unresolved(Reason.SPREAD_ARGUMENT)
            arguments.any { it is NamedArgumentListExpression } -> CallOutcome.Unresolved(Reason.NAMED_ARGUMENTS)
            mayBeMapForProperties(target.declaredConstructors, arguments) ->
                CallOutcome.Unresolved(Reason.MAP_ARGUMENT)
            else -> choose(target.declaredConstructors, arguments.size)
        }
    }

    /**
     * Groovy builds `new T(map)` with a no-argument constructor and sets the properties when no constructor takes
     * the Map, so a single argument that may be a Map must not be credited to a one-argument constructor of `T`
     * when `T` can also be constructed without arguments. A literal string, number or list is not a Map.
     */
    private fun mayBeMapForProperties(constructors: List<ConstructorNode>, arguments: List<Expression>): Boolean {
        val single = arguments.singleOrNull() ?: return false
        val notAMap = single is ListExpression || (single is ConstantExpression && single.value != null)
        return !notAMap && constructors.any { Arity.accepts(it, 0) }
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
