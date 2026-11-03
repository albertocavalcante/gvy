package com.github.albertocavalcante.gvy.semantics.db

import com.github.albertocavalcante.gvy.common.ast.isStubSourceName
import com.github.albertocavalcante.gvy.semantics.db.CallOutcome.Reason
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
    private val hierarchies = TypeHierarchies()

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
     *    interfaces and traits, cycles guarded) is one the compiler resolved, see
     *    [TypeHierarchies.compilerResolved]: a source class of this compilation unit or a classpath
     *    class. So no unseen supertype can declare another candidate. A type the compiler left as a
     *    bare name (an AST that stopped before resolution), or one it replaced with a stub, makes the
     *    call unresolved. This indexer never looks a type name up itself.
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
    fun resolveCall(call: MethodCallExpression, name: String, insideClosure: Boolean = false): String? =
        (resolve(call, name, insideClosure) as? CallOutcome.Resolved)?.symbolId

    /** Like [resolveCall], but says why a call was not attributed. */
    fun resolve(call: MethodCallExpression, name: String, insideClosure: Boolean = false): CallOutcome {
        val receiver = call.objectExpression
        val arguments = argumentExpressions(call.arguments)
        val start = startClass(receiver)
        val hierarchy = start?.let(hierarchies::of)
        return when {
            // A closure resolves implicit-this calls through its delegate first in many cases (`with {}`,
            // DSL builders, @DelegatesTo), so the enclosing class is not known to be the target.
            insideClosure && call.isImplicitThis -> CallOutcome.Unresolved(Reason.CLOSURE_DELEGATE)
            // `Person.getName()` calls java.lang.Class.getName(), not a Person method.
            receiver is ClassExpression && name in GroovyDispatch.classMethodNames ->
                CallOutcome.Unresolved(Reason.CLASS_OBJECT_METHOD)
            start == null -> CallOutcome.Unresolved(Reason.UNTYPED_RECEIVER)
            arguments.any { it is SpreadExpression } -> CallOutcome.Unresolved(Reason.SPREAD_ARGUMENT)
            hierarchy == null -> CallOutcome.Unresolved(Reason.UNKNOWN_HIERARCHY)
            // Methods Groovy adds to every object (or to JDK types in the hierarchy) are candidates the
            // class's own declarations do not show: `repo.find { }` calls the GDK find(Closure).
            GroovyDispatch.isGdkName(name, hierarchy.hasClasspathType) -> CallOutcome.Unresolved(Reason.GDK_METHOD)
            else -> choose(hierarchy, name, arguments.size)
        }
    }

    private fun choose(hierarchy: TypeHierarchies.Hierarchy, name: String, arity: Int): CallOutcome {
        val candidates = hierarchy.methods.filter { it.name == name && Arity.accepts(it, arity) }
        return when (candidates.size) {
            0 -> CallOutcome.Unresolved(Reason.NO_CANDIDATE)
            1 -> candidates.single().let {
                CallOutcome.Resolved(SemanticDocumentBuilder.createMethodSymbolId(it.declaringClass, it))
            }
            else -> CallOutcome.Unresolved(Reason.AMBIGUOUS)
        }
    }

    /** The class whose hierarchy is searched: the superclass for `super`, else the receiver's class. */
    private fun startClass(receiver: Expression): ClassNode? =
        if (receiver is VariableExpression && receiver.isSuperExpression) {
            currentClass.unresolvedSuperClass
        } else {
            receiverType(receiver)
        }

    /** The class a member access is made on, or null when the receiver has no known static type. */
    fun receiverType(receiver: Expression): ClassNode? = when {
        receiver is VariableExpression && receiver.isThisExpression -> currentClass
        receiver is ClassExpression -> receiver.type
        else -> staticTypeOf(receiver)
    }

    /** Whether the compiler resolved [type] to a real class, see [TypeHierarchies.compilerResolved]. */
    fun isResolved(type: ClassNode): Boolean = hierarchies.compilerResolved(type) != null

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

/**
 * The class hierarchies of one document's receiver types: superclass chain, interfaces and traits,
 * and the methods a call can reach through them. A hierarchy is only returned when every class in
 * it is known, so no unseen supertype can declare another candidate.
 */
internal class TypeHierarchies {
    /**
     * A class's methods in [of] order, with overridden and implemented ones merged away;
     * [hasClasspathType] when a compiled (JDK or library) class is part of it.
     */
    class Hierarchy(val methods: List<MethodNode>, val hasClasspathType: Boolean)

    // Built once per class per document: a JDK receiver type has a deep hierarchy and is called often.
    private val cache = HashMap<String, Hierarchy?>()

    /** The methods reachable on [start], or null unless every class in its hierarchy is known. */
    fun of(start: ClassNode): Hierarchy? = cache.getOrPut(start.name) { buildHierarchy(start) }

    private fun buildHierarchy(start: ClassNode): Hierarchy? {
        val seen = HashSet<String>()
        val chain = superclassChain(start, seen) ?: return null
        val interfaces = interfacesOf(chain, seen) ?: return null
        return Hierarchy(methodsOf(chain, interfaces), (chain + interfaces).any { !it.isPrimaryClassNode })
    }

    /** [start] and its superclasses up to Object, or null if one is not known (see [knownClass]). */
    private fun superclassChain(start: ClassNode, seen: MutableSet<String>): List<ClassNode>? {
        val chain = ArrayList<ClassNode>()
        var node: ClassNode? = start
        while (node != null && node.name != OBJECT && seen.add(node.name)) {
            val known = knownClass(node) ?: return null
            chain += known
            node = known.unresolvedSuperClass
        }
        return chain.takeIf { it.isNotEmpty() }
    }

    /** Every interface and trait of [chain], transitively, or null if one is not known. */
    private fun interfacesOf(chain: List<ClassNode>, seen: MutableSet<String>): List<ClassNode>? {
        val interfaces = ArrayList<ClassNode>()
        val pending = ArrayDeque(chain.flatMap { it.unresolvedInterfaces.toList() })
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (seen.add(next.name)) {
                val known = knownClass(next) ?: return null
                interfaces += known
                pending.addAll(known.unresolvedInterfaces)
            }
        }
        return interfaces
    }

    /** A class whose methods are all known and reached normally, or null (see [compilerResolved], [GroovyDispatch]). */
    private fun knownClass(type: ClassNode): ClassNode? = compilerResolved(type)?.takeUnless(GroovyDispatch::isDynamic)

    /** Candidate methods: the chain's (nearest override wins) plus Object's, then interface and trait methods. */
    private fun methodsOf(chain: List<ClassNode>, interfaces: List<ClassNode>): List<MethodNode> {
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
        return classMethods + inherited
    }

    /**
     * The methods [type] declares. For a source class only those written in the source (ones a transform
     * added, trait forwarders say, have no position); for a compiled classpath class all of them, so an
     * inherited `ArrayList.add(E)` still makes a local `add(String)` ambiguous.
     */
    private fun declared(type: ClassNode): List<MethodNode> =
        if (type.isPrimaryClassNode) type.methods.filter { it.lineNumber > 0 } else type.methods

    /**
     * The class the compiler resolved [type] to, or null. This indexer never resolves a type name
     * itself: a name is known only when Groovy's own resolution bound it to a source class of the
     * compilation unit (a primary ClassNode) or to a compiled classpath class, and that class is not
     * a synthetic stub standing in for a class that could not be found (its methods are unknown).
     * `java.lang.Object` needs no resolution, as its methods are known.
     */
    fun compilerResolved(type: ClassNode): ClassNode? {
        val target = type.redirect()
        val resolved = target.isPrimaryClassNode || target.isResolved
        return target.takeIf { resolved && !isStub(it) }
    }

    /** A stub's source unit is named for it; a classpath class has no module. */
    private fun isStub(type: ClassNode): Boolean = isStubSourceName(type.module?.context?.name)
}

/**
 * Ways Groovy reaches a method that a class's declared methods do not show. A hierarchy that uses
 * them is treated as unknown, so its calls stay unresolved rather than risk the wrong id.
 */
internal object GroovyDispatch {
    /** Transforms that add methods to a class or route calls elsewhere. */
    private val methodAddingAnnotations = setOf("Delegate", "Mixin", "AutoImplement", "Category")

    /** Methods through which Groovy dispatches calls that match no declared method, or all calls. */
    private val dispatchMethods = setOf("methodMissing", "invokeMethod")

    fun isDynamic(type: ClassNode): Boolean {
        if (!type.isPrimaryClassNode) return false
        val annotated = type.annotations.any { it.classNode.nameWithoutPackage in methodAddingAnnotations } ||
            type.fields.any { field -> field.annotations.any { it.classNode.nameWithoutPackage == "Delegate" } } ||
            type.properties.any { prop ->
                prop.field?.annotations?.any { it.classNode.nameWithoutPackage == "Delegate" } ==
                    true
            }
        // Only methods written in the source: Groovy adds a methodMissing to every member class.
        val dispatching = type.methods.any { it.name in dispatchMethods && it.lineNumber > 0 } ||
            type.interfaces.any {
                it.name == "groovy.lang.GroovyInterceptable" ||
                    it.nameWithoutPackage == "GroovyInterceptable"
            }
        return annotated || dispatching
    }

    /** Names of `java.lang.Class` methods. */
    val classMethodNames: Set<String> by lazy { Class::class.java.methods.mapTo(HashSet()) { it.name } }

    private class GdkNames(val anyObject: Set<String>, val all: Set<String>)

    private val gdk: GdkNames by lazy {
        val holders = listOf(
            "org.codehaus.groovy.runtime.DefaultGroovyMethods",
            "org.codehaus.groovy.runtime.StringGroovyMethods",
            "org.codehaus.groovy.runtime.IOGroovyMethods",
            "org.codehaus.groovy.runtime.ResourceGroovyMethods",
        ).mapNotNull { runCatching { Class.forName(it) }.getOrNull() }
        val methods = holders.flatMap { holder ->
            holder.methods.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount > 0 }
        }
        val forAnyObject = methods.filter { it.parameterTypes[0] == Any::class.java }.mapTo(HashSet()) { it.name }
        GdkNames(forAnyObject, methods.mapTo(HashSet()) { it.name })
    }

    /**
     * True when [name] may be a GDK method on the receiver: one Groovy adds to every object, or, when
     * the hierarchy includes a compiled JDK/library type, any GDK method (they target such types).
     */
    fun isGdkName(name: String, hasClasspathType: Boolean): Boolean =
        name in gdk.anyObject || (hasClasspathType && name in gdk.all)
}

/** The result of attributing a call: the method's symbol id, or why the call stays unresolved. */
sealed interface CallOutcome {
    data class Resolved(val symbolId: String) : CallOutcome

    data class Unresolved(val reason: Reason) : CallOutcome

    enum class Reason {
        /** An implicit-this call inside a closure, which its delegate may receive. */
        CLOSURE_DELEGATE,

        /** A java.lang.Class method called on a class reference. */
        CLASS_OBJECT_METHOD,

        /** The receiver has no known static type. */
        UNTYPED_RECEIVER,

        /** A spread argument makes the arity unknown. */
        SPREAD_ARGUMENT,

        /** A class in the hierarchy is not known, or dispatches dynamically. */
        UNKNOWN_HIERARCHY,

        /** A GDK method of that name may be the target. */
        GDK_METHOD,

        /** No method of that name and arity. */
        NO_CANDIDATE,

        /** More than one method of that name and arity. */
        AMBIGUOUS,
    }
}
