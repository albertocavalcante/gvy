package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression

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

    fun parameterList(types: List<ClassNode?>): String = types.joinToString(",") { erasedName(it) }

    /** The type itself, or null when it says nothing (`def`, `Object`), i.e. the value is dynamic. */
    fun knownTypeOrNull(type: ClassNode?): ClassNode? = type?.takeUnless {
        it.name == "java.lang.Object" || it.name == "Object" || it.isGenericsPlaceHolder
    }
}

/**
 * Picks the overload a call invokes, using only what is known statically.
 *
 * 1. Keep the overloads whose arity can take the call (default values and varargs counted).
 * 2. One left: that is the one, whatever the argument types (Groovy dispatches dynamically anyway).
 * 3. Several left and every argument type is known: an exact erased match wins, otherwise the single
 *    overload all arguments are assignable to.
 * 4. Anything else is ambiguous and yields null; a wrong guess would corrupt rename and references.
 */
internal object OverloadResolver {
    private val numericWidening = listOf("byte", "short", "int", "long", "float", "double")

    fun choose(candidates: List<MethodNode>, argTypes: List<ClassNode?>): MethodNode? {
        val byArity = candidates.filter { acceptsArity(it, argTypes.size) }
        return when {
            byArity.isEmpty() -> null
            // Arity alone never decides when the arguments are known and cannot be passed.
            byArity.size == 1 -> byArity.single().takeUnless { ruledOut(it, argTypes) }
            argTypes.any { it == null } -> null
            else -> chooseByTypes(byArity, argTypes.filterNotNull())
        }
    }

    private fun ruledOut(method: MethodNode, argTypes: List<ClassNode?>): Boolean =
        argTypes.withIndex().any { (i, arg) -> arg != null && fits(method, i, arg) == false }

    private fun chooseByTypes(candidates: List<MethodNode>, args: List<ClassNode>): MethodNode? {
        val wanted = TypeSignatures.parameterList(args)
        return candidates.singleOrNull { TypeSignatures.parameterList(it.parameters) == wanted }
            ?: candidates.singleOrNull { method -> args.indices.all { fits(method, it, args[it]) == true } }
    }

    private fun acceptsArity(method: MethodNode, argCount: Int): Boolean {
        val params = method.parameters.orEmpty()
        val required = params.count { !it.hasInitialExpression() }
        val varargs = params.lastOrNull()?.type?.isArray == true
        return argCount in required..params.size || (varargs && argCount >= params.size - 1)
    }

    /** True or false when decidable from what is loaded, null when the types cannot be compared. */
    private fun fits(method: MethodNode, index: Int, arg: ClassNode): Boolean? {
        val params = method.parameters
        val last = params.lastOrNull()?.type
        val param = params.getOrNull(index)?.type ?: last ?: return null
        val atVarargs = last?.isArray == true && index >= params.size - 1
        val direct = fitsType(param, arg)
        return if (atVarargs && direct != true) fitsType(last.componentType, arg) ?: direct else direct
    }

    @Suppress("ReturnCount")
    private fun fitsType(param: ClassNode, arg: ClassNode): Boolean? {
        if (param.isGenericsPlaceHolder || param.name == "java.lang.Object" || param.name == "Object") return true
        val p = canonical(param) ?: return null
        val a = canonical(arg) ?: return null
        val paramName = ClassHelper.getUnwrapper(p).name
        val argName = ClassHelper.getUnwrapper(a).name
        return when {
            paramName == argName -> true
            paramName == "java.lang.String" && a.name == "groovy.lang.GString" -> true
            paramName in numericWidening && argName in numericWidening ->
                numericWidening.indexOf(argName) <= numericWidening.indexOf(paramName)
            else -> derives(a, p)
        }
    }

    private fun derives(arg: ClassNode, param: ClassNode): Boolean? {
        if (!decidable(param) || !decidable(arg)) return null
        return runCatching {
            val pw = ClassHelper.getWrapper(param)
            val aw = ClassHelper.getWrapper(arg)
            aw.isDerivedFrom(pw) || aw.implementsInterface(pw)
        }.getOrDefault(false)
    }

    private fun decidable(type: ClassNode): Boolean =
        ClassHelper.isPrimitiveType(type) || type.name.startsWith("java.") || type.name == "groovy.lang.GString"

    /** The type with a qualified name, or null for a name the compiler has not resolved yet. */
    private fun canonical(type: ClassNode): ClassNode? = when {
        type.isArray || type.name.contains('.') || ClassHelper.isPrimitiveType(type) -> type
        else -> runCatching { ClassHelper.make(Class.forName("java.lang.${type.name}")) }.getOrNull()
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
     * Resolve a call to the id of the method it invokes, or null when that cannot be known statically.
     *
     * Policy: a call on a receiver with no declared or inferred type is never attributed to a class.
     * When the receiver's methods are visible, pick the overload by arity, then by static argument
     * types; when several overloads still fit, leave the call unresolved rather than guess.
     * When the receiver's methods are not visible (the class lives in another file), the id is built
     * from the static argument types and only exists if every argument has one.
     */
    fun resolveCall(call: MethodCallExpression, name: String): String? {
        val receiver = receiverType(call.objectExpression) ?: return null
        val argTypes = argumentExpressions(call.arguments).map { staticTypeOf(it) }
        val target = declaredClass(receiver)
        val hierarchy = hierarchyOf(target)
        val candidates = hierarchy.methods
            .filter { it.name == name }
            .distinctBy { TypeSignatures.parameterList(it.parameters) }
        return when {
            candidates.isNotEmpty() ->
                OverloadResolver.choose(candidates, argTypes)?.let {
                    SemanticDocumentBuilder.createMethodSymbolId(it.declaringClass, it)
                }
            hierarchy.complete -> null // every class in the chain is known and none has such a method
            argTypes.any { it == null } -> null
            else -> "${resolveTypeName(receiver)}#$name(${TypeSignatures.parameterList(argTypes)})."
        }
    }

    /** Methods of a class, then of its superclasses and interfaces, nearest first. */
    private class Hierarchy(val methods: List<MethodNode>, val complete: Boolean)

    /**
     * Walks [start] and everything it extends or implements. Supertypes declared in this file are
     * looked up in the module (before resolution their ClassNodes are empty stubs); [Hierarchy.complete]
     * is false when a supertype lives elsewhere, so its methods are not visible.
     */
    private fun hierarchyOf(start: ClassNode): Hierarchy {
        val seen = HashSet<String>()
        val methods = ArrayList<MethodNode>()
        var complete = true
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.name == "java.lang.Object" || !seen.add(node.name)) continue
            val local = moduleNode.classes.firstOrNull {
                it.name == node.name || (!node.name.contains('.') && it.nameWithoutPackage == node.name)
            }
            if (local == null) complete = false
            val resolved = local ?: node.redirect()
            methods += resolved.methods
            resolved.unresolvedSuperClass?.let(queue::addLast)
            resolved.unresolvedInterfaces.forEach(queue::addLast)
        }
        return Hierarchy(methods, complete)
    }

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

    /** The class whose methods describe [type]: a class from this file, else the (possibly loaded) type. */
    private fun declaredClass(type: ClassNode): ClassNode = moduleNode.classes.firstOrNull {
        it.name == type.name || (!type.name.contains('.') && it.nameWithoutPackage == type.name)
    } ?: type.redirect()

    /** The statically known type of [expression] as a value, or null when it is dynamic. */
    private fun staticTypeOf(expression: Expression): ClassNode? = when (expression) {
        is VariableExpression -> variableType(expression)
        is ConstructorCallExpression -> expression.type.takeUnless { expression.isUsingAnonymousInnerClass }
        is CastExpression -> TypeSignatures.knownTypeOrNull(expression.type)
        else -> literalType(expression)
    }

    private fun variableType(variable: VariableExpression): ClassNode? {
        if (variable.isThisExpression) return currentClass
        val binding = lookup(variable.name)
        if (binding != null) return binding.type
        val member = currentClass.getField(variable.name)?.type ?: currentClass.getProperty(variable.name)?.type
        return TypeSignatures.knownTypeOrNull(member)
    }

    private fun literalType(expression: Expression): ClassNode? = when (expression) {
        // Literals are primitives to Groovy's static view (`add(5)` fits `add(int)`).
        is ConstantExpression ->
            if (expression.isNullExpression) null else ClassHelper.getUnwrapper(expression.type)
        is ClassExpression -> ClassHelper.CLASS_Type
        is GStringExpression -> ClassHelper.GSTRING_TYPE
        is ListExpression -> ClassHelper.LIST_TYPE
        is MapExpression -> ClassHelper.MAP_TYPE
        else -> null
    }
}
