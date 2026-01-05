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
    val semantics = GroovySemantics(typeSolver, NativeCalculators.createRegistry())

    /**
     * Resolve the type of an AST node using GroovySemantics.
     * Uses the module-aware API for proper multi-document support.
     */
    fun resolveType(node: ASTNode, moduleNode: ModuleNode?): SemanticType = if (moduleNode != null) {
        // Use the new module-aware API for multi-document safety
        semantics.resolveType(node, moduleNode)
    } else {
        // Fallback for cases where module is not available
        @Suppress("DEPRECATION")
        semantics.resolveType(node)
    }

    /**
     * Convert a ClassNode directly to a SemanticType.
     * This is a direct conversion for static type references - it doesn't require
     * semantic resolution context and doesn't go through the GroovySemantics machinery.
     */
    fun toSemanticType(classNode: ClassNode): SemanticType = when {
        ClassHelper.isPrimitiveType(classNode) -> classNodeToPrimitive(classNode)
        classNode.isArray -> SemanticType.Array(toSemanticType(classNode.componentType))
        classNode == ClassHelper.DYNAMIC_TYPE -> SemanticType.Dynamic()
        classNode == ClassHelper.OBJECT_TYPE && classNode.name == "java.lang.Object" -> SemanticType.Dynamic()
        else -> SemanticType.Known(classNode.name)
    }

    private fun classNodeToPrimitive(classNode: ClassNode): SemanticType = when (classNode) {
        ClassHelper.boolean_TYPE -> SemanticType.Primitive(PrimitiveKind.BOOLEAN)
        ClassHelper.byte_TYPE -> SemanticType.Primitive(PrimitiveKind.BYTE)
        ClassHelper.char_TYPE -> SemanticType.Primitive(PrimitiveKind.CHAR)
        ClassHelper.short_TYPE -> SemanticType.Primitive(PrimitiveKind.SHORT)
        ClassHelper.int_TYPE -> SemanticType.Primitive(PrimitiveKind.INT)
        ClassHelper.long_TYPE -> SemanticType.Primitive(PrimitiveKind.LONG)
        ClassHelper.float_TYPE -> SemanticType.Primitive(PrimitiveKind.FLOAT)
        ClassHelper.double_TYPE -> SemanticType.Primitive(PrimitiveKind.DOUBLE)
        ClassHelper.VOID_TYPE -> SemanticType.Primitive(PrimitiveKind.VOID)
        else -> SemanticType.Unknown("Unknown primitive: ${classNode.name}")
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
            // Return first type for compatibility (sorted for determinism)
            type.types.sortedBy { formatSemanticType(it) }
                .firstOrNull()?.let { toClassNode(it, moduleNode) }
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
        is SemanticType.Union -> {
            val formatted = type.types.map { formatSemanticType(it) }.sorted()
            formatted.joinToString(" | ")
        }
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
