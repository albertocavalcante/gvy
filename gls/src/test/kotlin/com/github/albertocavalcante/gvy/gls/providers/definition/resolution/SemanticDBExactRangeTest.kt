package com.github.albertocavalcante.gvy.gls.providers.definition.resolution

import com.github.albertocavalcante.groovyparser.ast.types.Position
import com.github.albertocavalcante.gvy.gls.indexing.WorkspaceSymbolIndex
import com.github.albertocavalcante.gvy.gls.providers.definition.DefinitionResolver
import com.github.albertocavalcante.gvy.semantics.db.GroovySemanticDB
import com.github.albertocavalcante.gvy.semantics.db.OccurrenceRole
import com.github.albertocavalcante.gvy.semantics.db.SemanticDocumentBuilder
import kotlinx.coroutines.runBlocking
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Documents built from real compiled sources, not hand-written ranges: what the index reports and what
 * the cursor matches is exactly the member identifier, with no extra column.
 */
class SemanticDBExactRangeTest {

    private val targetUri = URI.create("file:///test/Target.groovy")
    private val sourceUri = URI.create("file:///test/Source.groovy")
    private val target = "class Target {\n    def doSomething() { 1 }\n}"
    private val source = "def t = new Target()\nt.doSomething()"

    private lateinit var index: WorkspaceSymbolIndex
    private lateinit var strategy: SemanticDBResolutionStrategy

    @BeforeEach
    fun setUp() {
        val unit = CompilationUnit(CompilerConfiguration())
        unit.addSource("Target.groovy", target)
        unit.addSource("Source.groovy", source)
        unit.compile(Phases.CANONICALIZATION)
        val db = GroovySemanticDB()
        unit.ast.modules.forEach { module ->
            val uri = if (module.description.endsWith("Target.groovy")) targetUri else sourceUri
            db.updateDocument(uri, SemanticDocumentBuilder(module, uri).build())
        }
        index = WorkspaceSymbolIndex(db)
        strategy = SemanticDBResolutionStrategy(index)
    }

    private fun resolveAt(line: Int, character: Int) = runBlocking {
        strategy.resolve(
            ResolutionContext(ClassNode("Dummy", 0, null), sourceUri, Position(line, character)),
        )
    }

    @Test
    fun `references report the exact method identifier range`() {
        val symbol = index.getDocument(sourceUri)!!.occurrences.single {
            it.range.startLine == 1 && it.role == OccurrenceRole.CALL
        }.symbol
        val call = index.findReferences(symbol).single { it.uri == sourceUri.toString() }
        assertEquals(Range(org.eclipse.lsp4j.Position(1, 2), org.eclipse.lsp4j.Position(1, 13)), call.range)
    }

    @Test
    fun `definition resolves on the last character of the method identifier`() {
        val result = resolveAt(1, 12)
        val binary = assertInstanceOf(DefinitionResolver.DefinitionResult.Binary::class.java, result.getOrNull())
        assertEquals(targetUri.toString(), binary.uri.toString())
        assertEquals(
            Range(org.eclipse.lsp4j.Position(1, 4), org.eclipse.lsp4j.Position(1, 27)),
            binary.range,
        )
    }

    @Test
    fun `definition does not resolve the method on its parentheses`() {
        assertTrue(resolveAt(1, 13).isLeft(), "opening parenthesis is past the identifier's exclusive end")
        assertTrue(resolveAt(1, 14).isLeft(), "closing parenthesis is not the method identifier")
    }

    @Test
    fun `definition does not resolve one column past the end of the call`() {
        val result = resolveAt(1, 15)
        assertTrue(result.isLeft(), "cursor after the call is outside its half-open range: $result")
    }
}
