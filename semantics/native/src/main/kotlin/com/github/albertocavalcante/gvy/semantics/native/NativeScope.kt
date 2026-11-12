package com.github.albertocavalcante.gvy.semantics.native

import com.github.albertocavalcante.gvy.semantics.SemanticType
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import java.lang.ref.WeakReference

/**
 * Scope tracking for variable resolution in native Groovy AST.
 * Supports module-level resolution for field and method lookups.
 *
 * Module member signatures are copied into the scope so cached contexts do not retain the AST.
 * The optional module reference is observational and does not own the module.
 */
class NativeScope private constructor(
    private val parent: NativeScope?,
    private val variables: MutableMap<String, SemanticType> = mutableMapOf(),
    private val module: WeakReference<ModuleNode>? = null,
    private val members: Map<String, ModuleMembers> = emptyMap(),
) {

    /** The source AST if it is still owned by the caller; member lookups use the captured signatures. */
    val currentModule: ModuleNode? get() = module?.get()

    internal fun findFieldType(classFqn: String, fieldName: String): SemanticType? =
        members[classFqn]?.fields?.get(fieldName)

    internal fun findMethodType(classFqn: String, methodName: String, arity: Int): SemanticType? {
        val types = members[classFqn]?.methods?.get(methodName to arity).orEmpty()
        return types.firstOrNull()?.takeIf { first -> types.all { it == first } }
    }

    /**
     * Look up a variable by name in this scope or parent scopes.
     */
    fun lookupVariable(name: String): SemanticType? = variables[name] ?: parent?.lookupVariable(name)

    /**
     * Define a variable in this scope.
     */
    fun defineVariable(name: String, type: SemanticType) {
        variables[name] = type
    }

    /**
     * Create a child scope.
     * Shares the parent’s module reference and member snapshot.
     */
    fun child(): NativeScope = NativeScope(parent = this, module = module, members = members)

    companion object {
        /**
         * Create a root scope from a ClassNode.
         * Includes class fields as variables.
         */
        fun fromClass(classNode: ClassNode): NativeScope {
            val scope = NativeScope(parent = null)

            // Add fields
            for (field in classNode.fields) {
                val type = NativeTypeContext.fromClassNode(field.type)
                scope.defineVariable(field.name, type)
            }

            return scope
        }

        /**
         * Create a scope for a method.
         * Includes parameters.
         */
        fun fromMethod(methodNode: MethodNode, parent: NativeScope): NativeScope {
            val scope = parent.child()

            // Add parameters
            for (param in methodNode.parameters) {
                val type = NativeTypeContext.fromClassNode(param.type)
                scope.defineVariable(param.name, type)
            }

            return scope
        }

        /**
         * Create an empty root scope.
         */
        fun empty(): NativeScope = NativeScope(parent = null)

        /**
         * Create a root scope from a module.
         * Enables module-level resolution for fields and methods.
         */
        fun fromModule(moduleNode: ModuleNode): NativeScope = NativeScope(
            parent = null,
            module = WeakReference(moduleNode),
            members = moduleNode.classes.distinctBy { it.name }.associate { classNode ->
                classNode.name to ModuleMembers(
                    fields = classNode.fields.distinctBy { it.name }
                        .associate { it.name to NativeTypeContext.fromClassNode(it.type) },
                    methods = classNode.methods.groupBy { it.name to it.parameters.size }
                        .mapValues { (_, methods) -> methods.map { NativeTypeContext.fromClassNode(it.returnType) } },
                )
            },
        )
    }
}

private data class ModuleMembers(
    val fields: Map<String, SemanticType>,
    val methods: Map<Pair<String, Int>, List<SemanticType>>,
)
