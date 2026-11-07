package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.transform.trait.Traits
import java.net.URI
import java.util.Collections

/** A source declaration's location; its qualified hierarchy is stored once in the document graph. */
internal data class ImplementationClass(val symbol: String, val range: Range)

/** Direct edges only: a long source inheritance chain does not retain an ancestor set per class. */
internal data class ImplementationType(
    val isInterface: Boolean,
    val isAbstract: Boolean,
    val superclass: String?,
    val interfaces: List<String>,
    val methods: List<ImplementationMethod>,
)

/** Qualified, erased parameter types distinguish packages; classpath declarations have no source range. */
internal data class ImplementationMethod(
    val symbol: String,
    val name: String,
    val parameterTypes: List<String>,
    val range: Range?,
    val isAbstract: Boolean,
    val isStatic: Boolean,
    val isPrivate: Boolean,
    val bridgeOwner: String? = null,
)

/** Semantic metadata, source and hierarchy captured in one database read. */
class ImplementationDocument internal constructor(
    val document: SemanticDocument,
    val sourceText: String?,
    internal val classes: List<ImplementationClass>,
    internal val hierarchy: Map<String, ImplementationType>,
)

data class ImplementationLocation(val uri: URI, val range: Range)

/** Extracts detached qualified facts while a compiled module is available; performs no IO. */
object ImplementationFacts {
    class Snapshot internal constructor(
        internal val classes: List<ImplementationClass> = emptyList(),
        internal val hierarchy: Map<String, ImplementationType> = emptyMap(),
    )

    val empty: Snapshot = Snapshot()

    fun build(module: ModuleNode): Snapshot {
        val local = module.classes.filterNot { it.isSynthetic }
        val classes = local.mapNotNull { node -> node.range()?.let { ImplementationClass(node.symbol(), it) } }
        val localNames = local.map { it.name }.toSet()
        val pending = ArrayDeque(local)
        val hierarchy = linkedMapOf<String, ImplementationType>()
        val types = TypeHierarchies()
        while (pending.isNotEmpty()) {
            val node = types.compilerResolved(pending.removeFirst())
            if (node != null && node.symbol() !in hierarchy) {
                hierarchy[node.symbol()] = node.fact()
                // Other source modules publish their own direct edges. Only classpath ancestry is expanded here.
                pending.addAll(parentNodes(node, localNames, types))
            }
        }
        return Snapshot(classes, hierarchy)
    }

    private fun parentNodes(node: ClassNode, localNames: Set<String>, types: TypeHierarchies): List<ClassNode> =
        (listOfNotNull(node.unresolvedSuperClass) + node.unresolvedInterfaces).mapNotNull(types::compilerResolved)
            .filter { it.module == null || it.name in localNames }

    /** Freeze every nested collection before facts become shared between writers and queries. */
    internal fun freeze(snapshot: Snapshot): Snapshot = Snapshot(
        Collections.unmodifiableList(snapshot.classes.toList()),
        Collections.unmodifiableMap(
            snapshot.hierarchy.mapValues { (_, type) ->
                type.copy(
                    interfaces = Collections.unmodifiableList(type.interfaces.toList()),
                    methods = Collections.unmodifiableList(
                        type.methods.map { method ->
                            method.copy(parameterTypes = Collections.unmodifiableList(method.parameterTypes.toList()))
                        },
                    ),
                )
            },
        ),
    )

    private fun ClassNode.fact(): ImplementationType = ImplementationType(
        isInterface = isInterface,
        isAbstract = isAbstract,
        superclass = unresolvedSuperClass?.symbol(),
        interfaces = unresolvedInterfaces.map { it.symbol() },
        methods = methods.filter { it.declaringClass == this && !it.isSynthetic }.map { method ->
            val range = if (isPrimaryClassNode) method.range() else null
            val bridgeOwner = method.getAnnotations(Traits.TRAITBRIDGE_CLASSNODE).firstOrNull()
                ?.getMember("traitClass")?.let { it as? ClassExpression }?.type?.symbol()
            // Other generated declarations have no navigable range, but still mask inherited methods.
            ImplementationMethod(
                symbol = SemanticDocumentBuilder.createMethodSymbolId(this, method),
                name = method.name,
                parameterTypes = method.parameters.map { qualifiedType(it.type) },
                range = range,
                isAbstract = method.isAbstract && !Traits.hasDefaultImplementation(method),
                isStatic = method.isStatic,
                isPrivate = method.isPrivate,
                bridgeOwner = bridgeOwner,
            )
        },
    )

    private fun qualifiedType(node: ClassNode): String = when {
        node.isArray -> qualifiedType(node.componentType) + "[]"
        node.isGenericsPlaceHolder -> node.redirect().takeUnless { it === node }?.let(::qualifiedType)
            ?: "java.lang.Object"
        else -> node.name
    }

    private fun ClassNode.symbol(): String = SemanticDocumentBuilder.createClassSymbolId(this)

    private fun ASTNode.range(): Range? =
        SemanticDocumentBuilder.groovyToLspRange(lineNumber, columnNumber, lastLineNumber, lastColumnNumber)
}
