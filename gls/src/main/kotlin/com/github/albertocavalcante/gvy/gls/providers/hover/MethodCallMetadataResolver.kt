package com.github.albertocavalcante.gvy.gls.providers.hover

import com.github.albertocavalcante.gvy.gls.documentation.DocExtractor
import com.github.albertocavalcante.gvy.gls.documentation.DocFormatter
import com.github.albertocavalcante.gvy.gls.documentation.SignatureFormatter
import com.github.albertocavalcante.gvy.gls.services.ClasspathService
import com.github.albertocavalcante.gvy.gls.services.GdkExtensionMethod
import com.github.albertocavalcante.gvy.gls.services.GroovyGdkProvider
import com.github.albertocavalcante.gvy.gls.services.ReflectedMethod
import com.github.albertocavalcante.gvy.gls.types.SemanticTypeResolver
import com.github.albertocavalcante.gvy.gls.utils.rethrowIfCancellationOrError
import com.github.albertocavalcante.gvy.semantics.SemanticType
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.slf4j.LoggerFactory
import java.lang.reflect.Modifier

/** Resolves proven method targets; ambiguous or dynamic calls keep their honest call summary. */
class MethodCallMetadataResolver(
    private val classpathService: ClasspathService,
    private val gdkProvider: GroovyGdkProvider,
    private val semanticResolver: SemanticTypeResolver,
) {
    private val logger = LoggerFactory.getLogger(MethodCallMetadataResolver::class.java)

    data class MethodMetadata(
        val signature: String,
        val declaringClass: String,
        val returnType: String,
        val documentation: String?,
    )

    fun resolveMethodCall(call: MethodCallExpression, moduleNode: ModuleNode?): MethodMetadata? {
        val name = call.methodAsString?.takeIf { it.isNotBlank() } ?: return null
        val target = call.methodTarget ?: call.getNodeMetaData<Any>("targetMethod") as? MethodNode
        if (target != null) return target.toHoverMetadata()
        if (call.isSpreadSafe) return null
        val receiver = resolveReceiver(call, moduleNode) ?: return null
        val arguments = (call.arguments as? TupleExpression)?.expressions ?: return null
        return resolveOnReceiver(call, receiver, arguments, name)
    }

    private fun resolveOnReceiver(
        call: MethodCallExpression,
        receiver: ClassNode,
        arguments: List<Expression>,
        name: String,
    ): MethodMetadata? {
        // Source ASTs retain defaults and Groovydoc. Binary members use the reflected snapshot below,
        // which preserves source parameter names/documentation rather than compiler-generated arg0 names.
        val methods = receiver.takeIf { it.redirect().isPrimaryClassNode }?.getMethods(name).orEmpty().filter {
            (it.isPublic || call.isImplicitThis) && (call.objectExpression !is ClassExpression || it.isStatic)
        }
        val nativeMatches = HoverMethodSelection.select(receiver, methods, arguments)
        return if (nativeMatches.isNotEmpty()) {
            nativeMatches.singleOrNull()?.toHoverMetadata()
        } else {
            resolveExternal(call, receiver, arguments, name)
        }
    }

    private fun resolveExternal(
        call: MethodCallExpression,
        receiver: ClassNode,
        arguments: List<Expression>,
        name: String,
    ): MethodMetadata? {
        val reflected = classpathService.getMethods(receiver.name).filter {
            it.name == name && it.isPublic && (call.objectExpression !is ClassExpression || it.isStatic)
        }
        val reflectedNodes = reflected.map { it.toMethodNode() }
        val reflectedMatches = HoverMethodSelection.select(receiver, reflectedNodes, arguments)
        return when {
            reflectedMatches.isNotEmpty() -> reflectedMatches.singleOrNull()?.let { selected ->
                reflected[reflectedNodes.indexOf(selected)].toMethodMetadata()
            }

            call.objectExpression is ClassExpression -> null
            else -> resolveExtension(receiver, arguments, name)
        }
    }

    private fun resolveExtension(receiver: ClassNode, arguments: List<Expression>, name: String): MethodMetadata? {
        val extensions = gdkProvider.getQualifiedMethodsForType(receiver.name).filter { it.display.name == name }
        val extensionNodes = extensions.map {
            methodNode(it.display.name, it.qualifiedParameters, receiver, Modifier.PUBLIC)
        }
        val extension = HoverMethodSelection.select(receiver, extensionNodes, arguments).singleOrNull() ?: return null
        return extensions[extensionNodes.indexOf(extension)].display.toMethodMetadata()
    }

    private fun resolveReceiver(call: MethodCallExpression, module: ModuleNode?): ClassNode? {
        if (call.isImplicitThis) {
            // A lexical class does not establish a closure's owner/delegate dispatch target.
            return null
        }
        val expression = call.objectExpression
        if (expression is ClassExpression) return expression.type
        val declared = expression.type
        if (!ClassHelper.isDynamicTyped(declared) && declared.name != "java.lang.Object") return declared
        return runCatching {
            when (val type = semanticResolver.resolveType(expression, module)) {
                is SemanticType.Known -> module?.classes?.find { it.name == type.fqn } ?: ClassHelper.make(type.fqn)
                is SemanticType.Dynamic, is SemanticType.Unknown -> null
                else -> ClassHelper.make(semanticResolver.formatSemanticType(type))
            }
        }.getOrElse { error ->
            rethrowIfCancellationOrError(error)
            logger.debug("Failed to resolve receiver type for method call", error)
            null
        }
    }

    private fun ReflectedMethod.toMethodNode(): MethodNode = methodNode(
        name,
        qualifiedParameters,
        ClassHelper.make(declaringClass),
        Modifier.PUBLIC or if (isStatic) Modifier.STATIC else 0,
    )

    private fun methodNode(name: String, types: List<String>, owner: ClassNode, modifiers: Int): MethodNode =
        MethodNode(
            name,
            modifiers,
            ClassHelper.OBJECT_TYPE,
            types.mapIndexed { index, type -> Parameter(parameterType(type), "arg$index") }.toTypedArray(),
            null,
            null,
        ).apply { declaringClass = owner }

    private fun parameterType(type: String): ClassNode = when {
        type.endsWith("[]") -> parameterType(type.removeSuffix("[]")).makeArray()
        type.endsWith("...") -> parameterType(type.removeSuffix("...")).makeArray()
        else -> ClassHelper.make(type)
    }

    private fun GdkExtensionMethod.toMethodMetadata(): MethodMetadata = MethodMetadata(
        signature = "$returnType $name(${displayParameterTypes.mapIndexed { index, type ->
            "$type ${parameterNames.getOrNull(index) ?: "arg$index"}"
        }.joinToString(", ")})",
        declaringClass = originClass,
        returnType = returnType,
        documentation = doc,
    )

    private fun ReflectedMethod.toMethodMetadata(): MethodMetadata = MethodMetadata(
        signature = "$returnType $name(${displayParameters.mapIndexed { index, type ->
            "$type ${parameterNames.getOrNull(index) ?: "arg$index"}"
        }.joinToString(", ")})",
        declaringClass = declaringClass,
        returnType = returnType,
        documentation = doc,
    )
}

internal fun MethodNode.toHoverMetadata(): MethodCallMetadataResolver.MethodMetadata =
    MethodCallMetadataResolver.MethodMetadata(
        SignatureFormatter.formatMethod(this),
        declaringClass?.name ?: "unknown",
        returnType.name,
        DocFormatter.formatAsMarkdown(DocExtractor.extractAttachedDocumentation(this)).takeIf { it.isNotBlank() },
    )
