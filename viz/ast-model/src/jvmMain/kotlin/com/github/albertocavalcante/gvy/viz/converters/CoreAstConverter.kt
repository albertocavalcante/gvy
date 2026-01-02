package com.github.albertocavalcante.gvy.viz.converters

import com.github.albertocavalcante.groovyparser.Range
import com.github.albertocavalcante.groovyparser.ast.CompilationUnit
import com.github.albertocavalcante.groovyparser.ast.ImportDeclaration
import com.github.albertocavalcante.groovyparser.ast.Node
import com.github.albertocavalcante.groovyparser.ast.PackageDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ClassDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.ConstructorDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.FieldDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.MethodDeclaration
import com.github.albertocavalcante.groovyparser.ast.body.Parameter
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.gvy.viz.model.CoreAstNodeDto
import com.github.albertocavalcante.gvy.viz.model.RangeDto
import java.util.concurrent.atomic.AtomicInteger

/**
 * Converts Core parser AST (JavaParser-like) to platform-agnostic DTOs.
 *
 * This converter traverses the Core AST and creates a serializable representation
 * suitable for visualization and export.
 */
class CoreAstConverter {

    private val idGenerator = AtomicInteger(0)

    /**
     * Converts a CompilationUnit to an AstNodeDto.
     *
     * @param compilationUnit The root of the Core AST.
     * @return The DTO representation.
     */
    fun convert(compilationUnit: CompilationUnit): AstNodeDto {
        idGenerator.set(0)
        return convertNode(compilationUnit)
    }

    private fun convertNode(node: Node): CoreAstNodeDto {
        val id = "node-${idGenerator.incrementAndGet()}"
        val type = node.javaClass.simpleName
        val range = node.range?.let { convertRange(it) }
        val children = node.getChildNodes().map { convertNode(it) }
        val properties = extractProperties(node)

        return CoreAstNodeDto(
            id = id,
            type = type,
            range = range,
            children = children,
            properties = properties,
        )
    }

    private fun convertRange(range: Range): RangeDto = RangeDto(
        startLine = range.begin.line,
        startColumn = range.begin.column,
        endLine = range.end.line,
        endColumn = range.end.column,
    )

    private fun extractProperties(node: Node): Map<String, String> = buildMap {
        when (node) {
            is CompilationUnit -> {
                node.packageDeclaration.ifPresent { put("package", it.name) }
                if (node.types.isNotEmpty()) {
                    put("typeCount", node.types.size.toString())
                }
                if (node.imports.isNotEmpty()) {
                    put("importCount", node.imports.size.toString())
                }
            }
            is PackageDeclaration -> {
                put("name", node.name)
            }
            is ImportDeclaration -> {
                put("name", node.name)
                put("isStatic", node.isStatic.toString())
                put("isStarImport", node.isStarImport.toString())
            }
            is ClassDeclaration -> {
                put("name", node.name)
                val kind = when {
                    node.isInterface -> "INTERFACE"
                    node.isEnum -> "ENUM"
                    node.isScript -> "SCRIPT"
                    else -> "CLASS"
                }
                put("kind", kind)
                node.superClass?.let { put("extends", it) }
                if (node.implementedTypes.isNotEmpty()) {
                    put("implements", node.implementedTypes.joinToString(", "))
                }
            }
            is MethodDeclaration -> {
                put("name", node.name)
                put("returnType", node.returnType)
                if (node.isStatic) put("isStatic", "true")
                if (node.isAbstract) put("isAbstract", "true")
                if (node.isFinal) put("isFinal", "true")
                if (node.parameters.isNotEmpty()) {
                    put("parameterCount", node.parameters.size.toString())
                }
            }
            is ConstructorDeclaration -> {
                put("name", node.name)
                if (node.parameters.isNotEmpty()) {
                    put("parameterCount", node.parameters.size.toString())
                }
            }
            is FieldDeclaration -> {
                put("name", node.name)
                put("type", node.type)
                if (node.isStatic) put("isStatic", "true")
                if (node.isFinal) put("isFinal", "true")
                if (node.hasInitializer) put("hasInitializer", "true")
            }
            is Parameter -> {
                put("name", node.name)
                put("type", node.type)
            }
            // For other node types, attempt to extract common properties via reflection
            else -> {
                extractCommonProperties(node)
            }
        }
    }

    /**
     * Extracts common properties from a node using reflection.
     * This is a fallback for node types not explicitly handled above.
     */
    private fun MutableMap<String, String>.extractCommonProperties(node: Node) {
        try {
            // Try to get 'name' property if it exists
            val nameMethod = node.javaClass.methods.find {
                it.name == "getName" && it.parameterCount == 0
            }
            nameMethod?.let {
                val name = it.invoke(node)
                if (name != null) {
                    put("name", name.toString())
                }
            }

            // Try to get 'value' property if it exists (for literals)
            val valueMethod = node.javaClass.methods.find {
                it.name == "getValue" && it.parameterCount == 0
            }
            valueMethod?.let {
                val value = it.invoke(node)
                if (value != null) {
                    put("value", value.toString())
                }
            }
        } catch (e: Exception) {
            // Ignore reflection errors - just skip these properties
        }
    }
}
