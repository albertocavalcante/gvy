package com.github.albertocavalcante.gvy.gls.utils

import com.github.albertocavalcante.groovyparser.ast.GroovyAstModel
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import java.net.URI
import java.util.IdentityHashMap

/** Source ownership proof for compiler-cloned parameters within one held compilation. */
internal class ParameterOrigins(private val model: GroovyAstModel, private val uri: URI) {
    private val methods by lazy { model.getAllClassNodes().flatMap { it.methods }.distinct() }
    private val origins = IdentityHashMap<Parameter, Parameter>()

    fun source(parameter: Parameter): Parameter = if (parameter.hasSource()) {
        parameter
    } else {
        origins.getOrPut(parameter) { recover(parameter) }
    }

    fun matches(candidate: Parameter, target: Parameter): Boolean = candidate === target ||
        (candidate.name == target.name && source(candidate) === target)

    private fun recover(parameter: Parameter): Parameter {
        val helper = checkNotNull(methods.singleOrNull { method -> method.parameters.any { it === parameter } }) {
            "Cannot establish the owner of transformed parameter '${parameter.name}'"
        }
        val body = checkNotNull(helper.code) { "Transformed method has no source body" }
        check(helper.generated() && !helper.hasSource() && body.hasSource() && model.getUri(body) == uri) {
            "Transformed parameter has no provable source method"
        }
        // NOTE: Scope comes from the complete retained source body, not the parameter spelling.
        // Generated overloads and helpers are excluded before requiring a unique source owner.
        val owner = checkNotNull(
            methods.singleOrNull { method ->
                method.declaringClass === helper.declaringClass && !method.generated() &&
                    method.hasSource() && model.getUri(method) == uri && method.encloses(body)
            },
        ) { "Transformed parameter source ownership is ambiguous" }
        check(
            owner.parameters.size == helper.parameters.size &&
                owner.parameters.zip(helper.parameters).all { (original, clone) ->
                    original.hasSource() && owner.encloses(original) &&
                        original.name == clone.name && original.type.name == clone.type.name
                },
        ) { "Transformed parameter signature does not match its source owner" }
        val ordinal = helper.parameters.indexOfFirst { it === parameter }
        return owner.parameters[ordinal]
    }

    private fun MethodNode.generated(): Boolean = annotations.any { it.classNode.name == "groovy.transform.Generated" }

    private fun ASTNode.hasSource(): Boolean = lineNumber > 0 && columnNumber > 0 &&
        lastLineNumber >= lineNumber && lastColumnNumber > 0

    private fun ASTNode.encloses(other: ASTNode): Boolean =
        (lineNumber < other.lineNumber || lineNumber == other.lineNumber && columnNumber <= other.columnNumber) &&
            (
                lastLineNumber > other.lastLineNumber ||
                    lastLineNumber == other.lastLineNumber && lastColumnNumber >= other.lastColumnNumber
                )
}
