package com.github.albertocavalcante.groovylsp.indexing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnifiedIndexerTest {

    class MockWriter : IndexWriter {
        val events: MutableList<String> = mutableListOf()

        override fun visitDocumentStart(path: String, content: String) {
            events.add("START $path")
        }

        override fun visitDocumentEnd() {
            events.add("END")
        }

        override fun visitDefinition(range: Range, symbol: String, isLocal: Boolean, documentation: String?) {
            events.add("DEF $symbol")
        }

        override fun visitReference(range: Range, symbol: String, isDefinition: Boolean) {
            events.add("REF $symbol")
        }

        override fun close() {}
    }

    @Test
    fun `should index simple class`() {
        val code = """
            package com.example
            class Foo {
                void bar() {}
            }
        """.trimIndent()

        val writer = MockWriter()
        val indexer = UnifiedIndexer(listOf(writer))

        indexer.indexDocument("src/main/groovy/com/example/Foo.groovy", code)

        val joined = writer.events.joinToString("\n")

        // Assertions
        assertTrue(writer.events.contains("START src/main/groovy/com/example/Foo.groovy"))
        // Check for Class Definition
        // Symbol format: scip-groovy maven com.example 0.0.0 Foo#
        assertTrue(writer.events.any { it.startsWith("DEF scip-groovy maven com.example 0.0.0 com.example.Foo#") })
        // Check for Method Definition
        // Symbol format: scip-groovy maven com.example 0.0.0 Foo#bar().
        assertTrue(
            writer.events.any {
                it.startsWith("DEF scip-groovy maven com.example 0.0.0 com.example.Foo#bar().")
            },
        )
        assertTrue(writer.events.contains("END"))
    }
}
