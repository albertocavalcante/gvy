package com.github.albertocavalcante.groovylsp.types

import com.github.albertocavalcante.groovyparser.resolution.TypeSolver
import com.github.albertocavalcante.gvy.semantics.PrimitiveKind
import com.github.albertocavalcante.gvy.semantics.SemanticType
import com.github.albertocavalcante.gvy.semantics.native.GroovySemantics
import com.github.albertocavalcante.gvy.semantics.native.NativeCalculators
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode

/**
 * Bridge between GroovySemantics and LSP providers.
 * Provides type resolution using the new semantic layer while maintaining
 * backward compatibility with existing code that expects ClassNode.
 */
class SemanticTypeResolver(private val typeSolver: TypeSolver) {
    private val semantics = GroovySemantics(typeSolver, NativeCalculators.createRegistry())

    /**
     * Resolve the type of an AST node using GroovySemantics.
     * Automatically injects the module if not already injected.
     */
    fun resolveType(node: ASTNode, moduleNode: ModuleNode?): SemanticType {
        // Inject module if available
        moduleNode?.let { semantics.inject(it) }
        return semantics.resolveType(node)
    }

    /**
     * Convert SemanticType to ClassNode for backward compatibility.
     * Used during migration to keep existing code working.
     *
     * @return ClassNode if type can be resolved, null otherwise
     */
    fun toClassNode(type: SemanticType, moduleNode: ModuleNode?): ClassNode? = when (type) {
        is SemanticType.Known -> findClassNode(type.fqn, moduleNode)
        is SemanticType.Primitive -> getPrimitiveClassNode(type.kind)
        is SemanticType.Dynamic -> ClassHelper.OBJECT_TYPE
        is SemanticType.Null -> null
        is SemanticType.Unknown -> null
        is SemanticType.Union -> {
            // Return first type for compatibility
            type.types.firstOrNull()?.let { toClassNode(it, moduleNode) }
        }

        is SemanticType.Array -> {
            toClassNode(type.componentType, moduleNode)?.makeArray()
        }
    }

    /**
     * Format SemanticType for display in hover, inlay hints, etc.
     */
    fun formatSemanticType(type: SemanticType): String = when (type) {
        is SemanticType.Known -> type.fqn.substringAfterLast('.')
        is SemanticType.Primitive -> type.kind.name.lowercase()
        is SemanticType.Dynamic -> type.hint ?: "def"
        is SemanticType.Unknown -> "unresolved"
        is SemanticType.Union -> type.types.joinToString(" | ") { formatSemanticType(it) }
        is SemanticType.Null -> "null"
        is SemanticType.Array -> "${formatSemanticType(type.componentType)}[]"
    }

    private fun getPrimitiveClassNode(kind: PrimitiveKind): ClassNode = when (kind) {
        PrimitiveKind.VOID -> ClassHelper.VOID_TYPE
        PrimitiveKind.BOOLEAN -> ClassHelper.boolean_TYPE
        PrimitiveKind.BYTE -> ClassHelper.byte_TYPE
        PrimitiveKind.CHAR -> ClassHelper.char_TYPE
        PrimitiveKind.SHORT -> ClassHelper.short_TYPE
        PrimitiveKind.INT -> ClassHelper.int_TYPE
        PrimitiveKind.LONG -> ClassHelper.long_TYPE
        PrimitiveKind.FLOAT -> ClassHelper.float_TYPE
        PrimitiveKind.DOUBLE -> ClassHelper.double_TYPE
    }

    private fun findClassNode(fqn: String, moduleNode: ModuleNode?): ClassNode? {
        // Try to find in module classes first
        moduleNode?.classes?.find { it.name == fqn }?.let { return it }

        // Fallback: create a ClassNode placeholder (ClassHelper will cache and resolve)
        return ClassHelper.make(fqn)
    }
}
