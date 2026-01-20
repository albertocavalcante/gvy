package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.groovylsp.markdown.dsl.MarkdownBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.PackageNode

/**
 * Renders metadata nodes (imports, packages, annotations) to markdown hover content.
 */
internal object MetadataNodeRenderer {
    private val logger = KotlinLogging.logger {}

    fun MarkdownBuilder.renderImportNode(node: ImportNode) {
        val formatted = formatImport(node)
        logger.debug { "formatImport result: '$formatted'" }
        section("Import") {
            code("groovy") { formatted }
            keyValue(
                "Class" to node.className,
                "Alias" to (node.alias ?: "none"),
                "Package" to (node.packageName ?: "default"),
                "Star Import" to node.isStar.toString(),
            )
        }
    }

    fun MarkdownBuilder.renderPackageNode(node: PackageNode) {
        section("Package") {
            code("groovy") { "package ${node.name}" }
        }
    }

    fun MarkdownBuilder.renderAnnotationNode(node: AnnotationNode) {
        section("Annotation") {
            code("groovy") { "@${node.classNode.nameWithoutPackage}" }
            if (node.members.isNotEmpty()) {
                val members = node.members.entries.joinToString(", ") { "${it.key}: ${it.value.text}" }
                keyValue("Members" to members)
            }
        }
    }

    private fun formatImport(node: ImportNode): String = buildString {
        append("import ")
        if (node.isStatic) append("static ")
        append(node.className)
        if (node.isStatic && node.fieldName != null && !node.isStar) {
            append(".${node.fieldName}")
        }
        if (node.isStar) append(".*")

        val simpleClassName = node.className.substringAfterLast('.')
        node.alias?.let {
            if (it != simpleClassName) {
                append(" as $it")
            }
        }
    }
}
