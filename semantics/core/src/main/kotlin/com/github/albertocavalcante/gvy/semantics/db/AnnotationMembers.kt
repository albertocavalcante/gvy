package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.AnnotationConstantExpression
import org.codehaus.groovy.ast.expr.ListExpression

/** The member names a source file writes in annotations (`@A(v = 1)` names `v`), which nothing attributes. */
internal object AnnotationMembers {
    /** The member names written in [annotations], nested annotations included: `@A(v = 1)` names `v`. */
    fun of(annotations: Collection<AnnotationNode>): Set<String> {
        val found = HashSet<String>()
        fun collect(annotation: AnnotationNode) {
            found += annotation.members.keys
            annotation.members.values.forEach { value ->
                val nested = if (value is ListExpression) value.expressions else listOf(value)
                nested.filterIsInstance<AnnotationConstantExpression>().forEach { collect(it.value as AnnotationNode) }
            }
        }
        // The compiler's own annotations (trait metadata: `traitClass`, `desc`) are not the source's.
        annotations.filterNot { it.classNode.name.startsWith("org.codehaus.groovy.") }.forEach(::collect)
        return found
    }

    /** The annotations on the declarations of [module] (the visitor reaches those inside method bodies). */
    fun declared(module: ModuleNode): List<AnnotationNode> {
        val methods = module.methods + module.classes.flatMap { it.methods }
        val parameters = methods.flatMap { it.parameters.toList() }
        val members = module.classes.flatMap { type ->
            type.annotations + type.fields.flatMap { it.annotations } +
                type.properties.flatMap { it.annotations + it.field.annotations }
        }
        return members + methods.flatMap { it.annotations } + parameters.flatMap { it.annotations } +
            module.`package`?.annotations.orEmpty()
    }
}
