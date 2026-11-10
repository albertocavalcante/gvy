package com.github.albertocavalcante.gvy.gls.providers.semantictokens

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ModuleNode
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals

/**
 * With source text the visitor takes token spans from the source (see SemanticTokensSourcePositionTest).
 * Without it, a type token's length comes from the type name, and an array's name is a JVM descriptor.
 */
class ArrayTypeTokenTest {
    @Test
    fun `without source text array type tokens span the element type name`(): Unit = runBlocking {
        val compilation = GroovyCompilationService()
        val uri = URI.create("file:///ArrayTokens.groovy")
        // `String[]` at 15 (return), `String[]` at 30 and `int[][]` at 46 (parameters).
        val source = "class Sample { String[] split(String[] items, int[][] grid) { items } }\n"
        compilation.compile(uri, source)
        val classNode = (compilation.getAst(uri) as ModuleNode).classes.first { it.name == "Sample" }
        val tokens = mutableListOf<SemanticTokenVisitor.SemanticToken>()

        SemanticTokenVisitor(emptyList()).visitClassDeclaration(classNode, tokens)

        val typeTokens = tokens.filter { it.tokenType == GroovySemanticTokenProvider.TokenTypes.CLASS }
            .associate { it.startChar to it.length }
        assertEquals(
            mapOf(15 to 6, 30 to 6, 46 to 3),
            typeTokens.filterKeys {
                it in setOf(15, 30, 46)
            },
            "found $tokens",
        )
    }
}
