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
            byArity.size <= 1 -> byArity.singleOrNull()
            argTypes.any { it == null } -> null
            else -> chooseByTypes(byArity, argTypes.filterNotNull())
        }
    }

    private fun chooseByTypes(candidates: List<MethodNode>, args: List<ClassNode>): MethodNode? {
        val wanted = TypeSignatures.parameterList(args)
        return candidates.singleOrNull { TypeSignatures.parameterList(it.parameters) == wanted }
            ?: candidates.singleOrNull { method -> args.indices.all { assignable(method, it, args[it]) } }
    }

    private fun acceptsArity(method: MethodNode, argCount: Int): Boolean {
        val params = method.parameters.orEmpty()
        val required = params.count { !it.hasInitialExpression() }
        val varargs = params.lastOrNull()?.type?.isArray == true
        return argCount in required..params.size || (varargs && argCount >= params.size - 1)
    }

    private fun assignable(method: MethodNode, index: Int, arg: ClassNode): Boolean {
        val params = method.parameters
        val param = (params.getOrNull(index) ?: params.last()).type
        val paramName = ClassHelper.getUnwrapper(param).name
        val argName = ClassHelper.getUnwrapper(arg).name
        return when {
            param.name == "java.lang.Object" || paramName == argName -> true
            paramName in numericWidening && argName in numericWidening ->
                numericWidening.indexOf(argName) <= numericWidening.indexOf(paramName)
            else -> runCatching { arg.isDerivedFrom(param) || arg.implementsInterface(param) }.getOrDefault(false)
        }
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
        val candidates = target.getMethods(name).distinctBy { TypeSignatures.parameterList(it.parameters) }
        return when {
            candidates.isNotEmpty() ->
                OverloadResolver.choose(candidates, argTypes)?.let {
                    SemanticDocumentBuilder.createMethodSymbolId(it.declaringClass, it)
                }
            target.name == currentClass.name -> null // our own class has no such method
            argTypes.any { it == null } -> null
            else -> "${resolveTypeName(receiver)}#$name(${TypeSignatures.parameterList(argTypes)})."
        }
    }

    /** The class a member access is made on, or null when the receiver has no known static type. */
    fun receiverType(receiver: Expression): ClassNode? = when {
        receiver is VariableExpression && receiver.isThisExpression -> currentClass
        receiver is ClassExpression -> receiver.type
        else -> staticTypeOf(receiver)
    }

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
