package com.github.albertocavalcante.gvy.viz.converters

import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.gvy.viz.model.NativeAstNodeDto
import com.github.albertocavalcante.gvy.viz.model.RangeDto
import com.github.albertocavalcante.gvy.viz.model.SymbolInfoDto
import com.github.albertocavalcante.gvy.viz.model.TypeInfoDto
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.Expression
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger

/**
 * Converts Native Groovy AST (org.codehaus.groovy.ast.*) to platform-agnostic DTOs.
 *
 * This converter traverses the Native AST produced by the Groovy compiler and creates
 * a serializable representation with symbol and type information.
 */
class NativeAstConverter {

    private val idGenerator = AtomicInteger(0)

    /**
     * Converts a ModuleNode to an AstNodeDto.
     *
     * @param moduleNode The root of the Native Groovy AST.
     * @return The DTO representation with type and symbol information.
     */
    fun convert(moduleNode: ModuleNode): AstNodeDto {
        idGenerator.set(0)
        return convertNode(moduleNode)
    }

    private fun convertNode(node: ASTNode): NativeAstNodeDto {
        val id = "node-${idGenerator.incrementAndGet()}"
        val type = node.javaClass.simpleName
        val range = extractRange(node)
        val children = extractChildren(node)
        val properties = extractProperties(node)
        val symbolInfo = extractSymbolInfo(node)
        val typeInfo = extractTypeInfo(node)

        return NativeAstNodeDto(
            id = id,
            type = type,
            range = range,
            children = children,
            properties = properties,
            symbolInfo = symbolInfo,
            typeInfo = typeInfo,
        )
    }

    private fun extractRange(node: ASTNode): RangeDto? {
        val lineNumber = node.lineNumber
        val columnNumber = node.columnNumber
        val lastLineNumber = node.lastLineNumber
        val lastColumnNumber = node.lastColumnNumber

        // Check if position information is available
        if (lineNumber <= 0 || columnNumber <= 0) {
            return null
        }

        return RangeDto(
            startLine = lineNumber,
            startColumn = columnNumber,
            endLine = if (lastLineNumber > 0) lastLineNumber else lineNumber,
            endColumn = if (lastColumnNumber > 0) lastColumnNumber else columnNumber,
        )
    }

    private fun extractChildren(node: ASTNode): List<AstNodeDto> = buildList {
        when (node) {
            is ModuleNode -> {
                // Add imports
                node.imports?.forEach { add(convertNode(it)) }
                node.starImports?.forEach { add(convertNode(it)) }
                node.staticImports?.values?.forEach { add(convertNode(it)) }
                node.staticStarImports?.values?.forEach { add(convertNode(it)) }

                // Add classes
                node.classes?.forEach { add(convertNode(it)) }
            }
            is ClassNode -> {
                // Add fields
                node.fields?.forEach { add(convertNode(it)) }

                // Add properties
                node.properties?.forEach { add(convertNode(it)) }

                // Add methods
                node.methods?.forEach { add(convertNode(it)) }

                // Add constructors
                node.declaredConstructors?.forEach { add(convertNode(it)) }
            }
            is MethodNode -> {
                // Add parameters
                node.parameters?.forEach { add(convertNode(it)) }

                // Note: We skip method code/body to keep DTO simple
                // Can be added later if needed
            }
            // For other node types, we use reflection as fallback
            // or simply don't extract children
        }
    }

    private fun extractProperties(node: ASTNode): Map<String, String> = buildMap {
        when (node) {
            is ModuleNode -> {
                node.packageName?.let { put("packageName", it) }
                node.classes?.size?.let { put("classCount", it.toString()) }
                node.imports?.size?.let { put("importCount", it.toString()) }
            }
            is ImportNode -> {
                node.className?.let { put("className", it) }
                node.fieldName?.let { put("fieldName", it) }
                put("isStatic", node.isStatic.toString())
            }
            is ClassNode -> {
                put("name", node.name ?: "")
                put("isInterface", node.isInterface.toString())
                put("isEnum", node.isEnum.toString())
                put("isScript", node.isScript.toString())
                put("isAbstract", node.isAbstract.toString())

                node.superClass?.let {
                    if (it.name != "java.lang.Object") {
                        put("superClass", it.name)
                    }
                }

                val interfaces = node.interfaces
                if (interfaces?.isNotEmpty() == true) {
                    put("interfaces", interfaces.joinToString(", ") { it.name })
                }

                // Modifiers
                extractModifiers(node.modifiers, this)
            }
            is MethodNode -> {
                put("name", node.name ?: "")
                node.returnType?.let { put("returnType", it.name) }
                put("isAbstract", node.isAbstract.toString())
                put("isStatic", node.isStatic.toString())
                put("isFinal", node.isFinal.toString())
                put("isVoidMethod", node.isVoidMethod.toString())

                val paramCount = node.parameters?.size ?: 0
                put("parameterCount", paramCount.toString())

                // Modifiers
                extractModifiers(node.modifiers, this)
            }
            is FieldNode -> {
                put("name", node.name ?: "")
                node.type?.let { put("type", it.name) }
                put("isStatic", node.isStatic.toString())
                put("isFinal", node.isFinal.toString())
                put("isVolatile", Modifier.isVolatile(node.modifiers).toString())

                // Modifiers
                extractModifiers(node.modifiers, this)
            }
            is PropertyNode -> {
                put("name", node.name ?: "")
                node.type?.let { put("type", it.name) }
                put("isStatic", node.isStatic.toString())
            }
            is Parameter -> {
                put("name", node.name ?: "")
                node.type?.let { put("type", it.name) }
                put("hasInitialExpression", (node.initialExpression != null).toString())
            }
        }
    }

    private fun extractModifiers(modifiers: Int, map: MutableMap<String, String>) {
        if (Modifier.isPublic(modifiers)) {
            map["visibility"] = "public"
        } else if (Modifier.isProtected(modifiers)) {
            map["visibility"] = "protected"
        } else if (Modifier.isPrivate(modifiers)) {
            map["visibility"] = "private"
        } else {
            map["visibility"] = "package-private"
        }
    }

    private fun extractSymbolInfo(node: ASTNode): SymbolInfoDto? {
        val kind = when (node) {
            is ClassNode -> "CLASS"
            is MethodNode -> "METHOD"
            is FieldNode -> "FIELD"
            is PropertyNode -> "PROPERTY"
            is Parameter -> "PARAMETER"
            else -> return null
        }

        val scope = when (node) {
            is ClassNode -> "FILE"
            is MethodNode -> "CLASS"
            is FieldNode -> "CLASS"
            is PropertyNode -> "CLASS"
            is Parameter -> "METHOD"
            else -> "UNKNOWN"
        }

        val modifiers = when (node) {
            is ClassNode -> node.modifiers
            is MethodNode -> node.modifiers
            is FieldNode -> node.modifiers
            else -> 0
        }

        val visibility = when {
            Modifier.isPublic(modifiers) -> "PUBLIC"
            Modifier.isProtected(modifiers) -> "PROTECTED"
            Modifier.isPrivate(modifiers) -> "PRIVATE"
            else -> "PACKAGE_PRIVATE"
        }

        return SymbolInfoDto(
            kind = kind,
            scope = scope,
            visibility = visibility,
        )
    }

    private fun extractTypeInfo(node: ASTNode): TypeInfoDto? {
        val resolvedType = when (node) {
            is ClassNode -> node.name
            is MethodNode -> node.returnType?.name
            is FieldNode -> node.type?.name
            is PropertyNode -> node.type?.name
            is Parameter -> node.type?.name
            is Expression -> node.type?.name
            else -> null
        } ?: return null

        val typeParameters = when (node) {
            is ClassNode -> node.genericsTypes?.map { it.name } ?: emptyList()
            is MethodNode -> node.returnType?.genericsTypes?.map { it.name } ?: emptyList()
            is FieldNode -> node.type?.genericsTypes?.map { it.name } ?: emptyList()
            else -> emptyList()
        }

        // For now, we assume types are not inferred (Groovy has complex inference logic)
        val isInferred = false

        return TypeInfoDto(
            resolvedType = resolvedType,
            isInferred = isInferred,
            typeParameters = typeParameters,
        )
    }
}
