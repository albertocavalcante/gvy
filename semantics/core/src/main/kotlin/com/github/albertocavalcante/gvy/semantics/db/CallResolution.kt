package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.SpreadExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.transform.trait.Traits

/**
 * Erased type names used in method symbol ids.
 *
 * Names are simple (package-less) so an id does not change depending on whether the compiler has
 * already qualified `String` to `java.lang.String`. Generics are erased and arrays keep their `[]`.
 */
internal object TypeSignatures {
    fun erasedName(type: ClassNode?): String = when {
        type == null -> "Object"
        type.isArray -> erasedName(type.componentType) + "[]"
        type.isGenericsPlaceHolder -> type.redirect().takeUnless { it === type }?.let(::erasedName) ?: "Object"
        else -> type.name.substringAfterLast('.')
    }

    fun parameterList(parameters: Array<Parameter>?): String =
        parameters.orEmpty().joinToString(",") { erasedName(it.type) }

    /** The type itself, or null when it says nothing (`def`, `Object`), i.e. the value is dynamic. */
    fun knownTypeOrNull(type: ClassNode?): ClassNode? = type?.takeUnless {
        it.name == "java.lang.Object" || it.name == "Object" || it.isGenericsPlaceHolder
    }
}

private const val OBJECT = "java.lang.Object"

/** Whether a method can be invoked with a given number of arguments, by declaration alone. */
internal object Arity {
    /** Default-valued parameters widen the range to `required..size`; a varargs last parameter takes any extra. */
    fun accepts(method: MethodNode, argCount: Int): Boolean {
        val params = method.parameters.orEmpty()
        val required = params.count { !it.hasInitialExpression() }
        val varargs = params.lastOrNull()?.type?.isArray == true
        return argCount in required..params.size || (varargs && argCount >= params.size - 1)
    }
}

/**
 * A name visible in a lexical scope: a parameter or a local variable.
 *
 * @property id the unique symbol id of the declaration the name is bound to
 * @property type the statically known type, or null when the variable is dynamically typed
 */
internal data class LocalBinding(val id: String, val type: ClassNode?)

/**
 * Works out which class and which method a member access refers to, from static information only.
 *
 * @param lookup the scope lookup of the enclosing visitor: the binding of a parameter or local, or null
 */
internal class CallResolver(
    private val moduleNode: ModuleNode,
    private val currentClass: ClassNode,
    private val lookup: (String) -> LocalBinding?,
) {
    fun resolveTypeName(type: ClassNode): String {
        // If already fully qualified (contains dots), just use it
        if (type.name.contains(".")) {
            return type.name.replace('.', '/')
        }

        // Try redirect (might point to FQN)
        if (type.redirect() != type && type.redirect().name.contains(".")) {
            return type.redirect().name.replace('.', '/')
        }

        // Fallback: prepend current package if available
        val packageName = moduleNode.packageName
        if (!packageName.isNullOrEmpty()) {
            val pkgPath = packageName.replace('.', '/')
            return if (pkgPath.endsWith("/")) "${pkgPath}${type.name}" else "$pkgPath/${type.name}"
        }

        return type.name
    }

    /** `def x = new Foo()` and `def x = (Foo) y` have a known type even though `x` is declared `def`. */
    fun inferType(declaration: DeclarationExpression): ClassNode? = when (val init = declaration.rightExpression) {
        is ConstructorCallExpression -> init.type.takeUnless { init.isUsingAnonymousInnerClass }
        is CastExpression -> init.type
        else -> null
    }

    /**
     * Resolves a call to the id of the method it invokes, or null when that is not certain.
     *
     * Renames are driven by these ids, so crediting a call to the wrong method is worse than missing
     * one. A call is therefore attributed only when its target is uniquely determined:
     *
     * 1. The receiver has a declared or inferred type (`this`, `super`, a class name, a typed
     *    variable, field, or `new X()`); an untyped receiver is never attributed.
     * 2. Every class and interface of the receiver's hierarchy (the superclass chain first, then
     *    interfaces and traits, cycles guarded) is visible in this compilation: declared in this
     *    module, or in another source file the compiler resolved to its source class (a workspace
     *    unit compiled with all workspace sources). So no unseen supertype can declare another
     *    candidate. A supertype that is only a name (another file that is not part of the
     *    compilation) or a classpath class makes the call unresolved.
     * 3. Exactly one method of that name has an arity that can take the call (default parameters
     *    count as a `min..max` range, a varargs last parameter takes any count of at least
     *    `params - 1`). The call is unresolved if any argument is a spread, as its arity is unknown.
     *    An override in the superclass chain is one method, the nearest declaration; an interface or
     *    trait method counts separately unless it is abstract and a class in the chain implements it.
     *
     * The id is that of the declaring class's method. Argument types are never consulted: Groovy
     * dispatches on runtime types and coerces freely (`1.5` to `double`, a list literal to
     * `ArrayList`), so static types cannot choose between overloads without reimplementing Groovy's
     * method selection. With a single candidate they need not: that method is the only target.
     *
     * A callee class that is not visible (declared in another file) yields no occurrence either:
     * an id built from static argument types can equal the real id of a different overload, since
     * the runtime type of an argument may differ from its declared type.
     */
    fun resolveCall(call: MethodCallExpression, name: String): String? {
        val start = startClass(call.objectExpression) ?: return null
        val args = argumentExpressions(call.arguments)
        if (args.any { it is SpreadExpression }) return null
        val hierarchy = hierarchyOf(start) ?: return null
        val method = hierarchy.methods.filter { it.name == name && Arity.accepts(it, args.size) }.singleOrNull()
        return method?.let { SemanticDocumentBuilder.createMethodSymbolId(it.declaringClass, it) }
    }

    /** The class whose hierarchy is searched: the superclass for `super`, else the receiver's class. */
    private fun startClass(receiver: Expression): ClassNode? =
        if (receiver is VariableExpression && receiver.isSuperExpression) {
            currentClass.unresolvedSuperClass
        } else {
            receiverType(receiver)
        }

    /** A class's methods in [hierarchyOf] order, with overridden and implemented ones merged away. */
    private class Hierarchy(val methods: List<MethodNode>)

    /**
     * Collects the methods of [start], its superclass chain and then its interfaces. Returns null
     * unless every class involved is visible, see [localClass]: before resolution a supertype's
     * ClassNode is an empty stub, so it is looked up in the module by name. `java.lang.Object` is the one
     * exception, as its methods are known.
     */
    // Built once per class per document: a JDK receiver type has a deep hierarchy and is called often.
    private val hierarchies = HashMap<String, Hierarchy?>()

    private fun hierarchyOf(start: ClassNode): Hierarchy? = hierarchies.getOrPut(start.name) { buildHierarchy(start) }

    private fun buildHierarchy(start: ClassNode): Hierarchy? {
        val seen = HashSet<String>()
        val chain = ArrayList<ClassNode>()
        var node: ClassNode? = start
        while (node != null && node.name != OBJECT) {
            if (!seen.add(node.name)) break
            val local = localClass(node) ?: return null
            chain += local
            node = local.unresolvedSuperClass
        }
        if (chain.isEmpty()) return null
        val interfaces = ArrayList<ClassNode>()
        val pending = ArrayDeque<ClassNode>()
        chain.forEach { pending.addAll(it.unresolvedInterfaces) }
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (!seen.add(next.name)) continue
            val local = localClass(next) ?: return null
            interfaces += local
            pending.addAll(local.unresolvedInterfaces)
        }
        val classMethods = (chain.flatMap(::declared) + ClassHelper.OBJECT_TYPE.methods)
            .distinctBy { it.name + "(" + TypeSignatures.parameterList(it.parameters) + ")" }
        val implemented = classMethods.map { it.name to TypeSignatures.parameterList(it.parameters) }.toSet()
        // A trait method is woven into the implementing class, so it overrides the superclass's: never merged.
        val inherited = interfaces.flatMap { type ->
            declared(type).filterNot {
                !Traits.isTrait(type) && it.isAbstract &&
                    (it.name to TypeSignatures.parameterList(it.parameters)) in implemented
            }
        }
        return Hierarchy(classMethods + inherited)
    }

    /**
     * The methods [type] declares. For a source class only those written in the source (ones a transform
     * added, trait forwarders say, have no position); for a compiled classpath class all of them, so an
     * inherited `ArrayList.add(E)` still makes a local `add(String)` ambiguous.
     */
    private fun declared(type: ClassNode): List<MethodNode> =
        if (type.isPrimaryClassNode) type.methods.filter { it.lineNumber > 0 } else type.methods

    /**
     * The class [type] names, or null when it is not fully known (a name-only stub). Known means: in
     * this module; a class of another file the compiler resolved to its source ClassNode (a workspace
     * unit compiled to SEMANTIC_ANALYSIS does so for every source in the unit); or a compiled classpath
     * class, such as a script's `groovy.lang.Script`, whose methods all come from its bytecode.
     */
    private fun localClass(type: ClassNode): ClassNode? = moduleNode.classes.firstOrNull {
        it.name == type.name || (!type.name.contains('.') && it.nameWithoutPackage == type.name)
    } ?: type.redirect().takeIf { it.isPrimaryClassNode || it.isResolved }

    /** The class a member access is made on, or null when the receiver has no known static type. */
    fun receiverType(receiver: Expression): ClassNode? = when {
        receiver is VariableExpression && receiver.isThisExpression -> currentClass
        receiver is ClassExpression -> receiver.type
        // Before name resolution `A.foo()` has a bare name for `A`; a class of this file wins over no binding.
        receiver is VariableExpression && lookup(receiver.name) == null && !hasMember(receiver.name) ->
            moduleNode.classes.firstOrNull { it.name == receiver.name || it.nameWithoutPackage == receiver.name }
                ?: staticTypeOf(receiver)
        else -> staticTypeOf(receiver)
    }

    private fun hasMember(name: String) = currentClass.getField(name) != null || currentClass.getProperty(name) != null

    private fun argumentExpressions(arguments: Expression): List<Expression> =
        if (arguments is TupleExpression) arguments.expressions else listOf(arguments)

    /** The statically known type of [expression] as a value, or null when it is dynamic. */
    private fun staticTypeOf(expression: Expression): ClassNode? = when (expression) {
        is VariableExpression -> variableType(expression)
        is ConstructorCallExpression -> expression.type.takeUnless { expression.isUsingAnonymousInnerClass }
        is CastExpression -> TypeSignatures.knownTypeOrNull(expression.type)
        else -> null
    }

    private fun variableType(variable: VariableExpression): ClassNode? {
        if (variable.isThisExpression) return currentClass
        val binding = lookup(variable.name)
        if (binding != null) return binding.type
        val member = currentClass.getField(variable.name)?.type ?: currentClass.getProperty(variable.name)?.type
        return TypeSignatures.knownTypeOrNull(member)
    }
}
