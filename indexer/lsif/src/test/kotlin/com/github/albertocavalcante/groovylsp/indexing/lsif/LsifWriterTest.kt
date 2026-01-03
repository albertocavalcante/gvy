package com.github.albertocavalcante.groovylsp.indexing.lsif

import com.github.albertocavalcante.groovylsp.indexing.NdjsonParser
import com.github.albertocavalcante.groovylsp.indexing.Range
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LsifWriterTest {

    @Test
    fun `emits metaData vertex first`() {
        val baos = ByteArrayOutputStream()
        val writer = LsifWriter(baos, "/project")
        writer.close()

        val elements = NdjsonParser.parseToMaps(baos.toString())

        assertTrue(elements.isNotEmpty())
        val first = elements[0]
        assertEquals("vertex", first["type"])
        assertEquals("metaData", first["label"])
        assertEquals(1, first["id"])
    }

    @Test
    fun `emits document vertex with correct URI`() {
        val baos = ByteArrayOutputStream()
        val writer = LsifWriter(baos, "/project")

        writer.visitDocumentStart("src/Foo.groovy", "class Foo {}")
        writer.visitDocumentEnd()
        writer.close()

        val elements = NdjsonParser.parseToMaps(baos.toString())
        val docVertex = elements.find { it["label"] == "document" }

        assertNotNull(docVertex)
        assertEquals("vertex", docVertex["type"])
        assertTrue((docVertex["uri"] as String).contains("src/Foo.groovy"))
    }

    @Test
    fun `emits range vertex with 0-based positions`() {
        val baos = ByteArrayOutputStream()
        val writer = LsifWriter(baos, "/project")

        writer.visitDocumentStart("src/Foo.groovy", "class Foo {}")
        // 1-based input: line 1, col 7-10
        writer.visitDefinition(
            Range(1, 7, 1, 10),
            "scip-groovy maven com.example 0.0.0 Foo#",
            isLocal = false,
            documentation = null,
        )
        writer.visitDocumentEnd()
        writer.close()

        val elements = NdjsonParser.parseToMaps(baos.toString())
        val rangeVertex = elements.find { it["label"] == "range" }

        assertNotNull(rangeVertex)
        // LSIF uses 0-based positions
        @Suppress("UNCHECKED_CAST")
        val start = rangeVertex["start"] as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val end = rangeVertex["end"] as Map<String, Any>

        assertEquals(0, start["line"])
        assertEquals(6, start["character"])
        assertEquals(0, end["line"])
        assertEquals(9, end["character"])
    }

    @Test
    fun `emits moniker with scip-groovy scheme`() {
        val baos = ByteArrayOutputStream()
        val writer = LsifWriter(baos, "/project")

        writer.visitDocumentStart("src/Foo.groovy", "class Foo {}")
        writer.visitDefinition(
            Range(1, 7, 1, 10),
            "scip-groovy maven com.example 0.0.0 Foo#",
            isLocal = false,
            documentation = null,
        )
        writer.visitDocumentEnd()
        writer.close()

        val elements = NdjsonParser.parseToMaps(baos.toString())
        val moniker = elements.find { it["label"] == "moniker" }

        assertNotNull(moniker)
        assertEquals("scip-groovy", moniker["scheme"])
        assertEquals("scip-groovy maven com.example 0.0.0 Foo#", moniker["identifier"])
        assertEquals("export", moniker["kind"])
    }

    @Test
    fun `local definitions have local kind`() {
        val baos = ByteArrayOutputStream()
        val writer = LsifWriter(baos, "/project")

        writer.visitDocumentStart("src/Foo.groovy", "def x = 1")
        writer.visitDefinition(
            Range(1, 5, 1, 6),
            "local 0",
            isLocal = true,
            documentation = null,
        )
        writer.visitDocumentEnd()
        writer.close()

        val elements = NdjsonParser.parseToMaps(baos.toString())
        val moniker = elements.find { it["label"] == "moniker" }

        assertNotNull(moniker)
        assertEquals("local", moniker["kind"])
    }

    @Test
    fun `references have import kind`() {
        val baos = ByteArrayOutputStream()
        val writer = LsifWriter(baos, "/project")

        writer.visitDocumentStart("src/Bar.groovy", "Foo foo")
        writer.visitReference(
            Range(1, 1, 1, 4),
            "scip-groovy maven com.example 0.0.0 Foo#",
            isDefinition = false,
        )
        writer.visitDocumentEnd()
        writer.close()

        val elements = NdjsonParser.parseToMaps(baos.toString())
        val moniker = elements.find { it["label"] == "moniker" }

        assertNotNull(moniker)
        assertEquals("import", moniker["kind"])
    }

    @Test
    fun `contains edge links document to range`() {
        val baos = ByteArrayOutputStream()
        val writer = LsifWriter(baos, "/project")

        writer.visitDocumentStart("src/Foo.groovy", "class Foo {}")
        writer.visitDefinition(
            Range(1, 7, 1, 10),
            "scip-groovy maven com.example 0.0.0 Foo#",
            isLocal = false,
            documentation = null,
        )
        writer.visitDocumentEnd()
        writer.close()

        val elements = NdjsonParser.parseToMaps(baos.toString())
        val containsEdge = elements.find { it["label"] == "contains" }

        assertNotNull(containsEdge)
        assertEquals("edge", containsEdge["type"])

        // outV should be document, inV should be range
        val docId = elements.find { it["label"] == "document" }?.get("id")
        val rangeId = elements.find { it["label"] == "range" }?.get("id")

        assertEquals(docId, containsEdge["outV"])
        assertEquals(rangeId, containsEdge["inV"])
    }

    @Test
    fun `all elements have required fields`() {
        val baos = ByteArrayOutputStream()
        val writer = LsifWriter(baos, "/project")

        writer.visitDocumentStart("src/Foo.groovy", "class Foo {}")
        writer.visitDefinition(
            Range(1, 7, 1, 10),
            "scip-groovy maven com.example 0.0.0 Foo#",
            isLocal = false,
            documentation = "A class",
        )
        writer.visitDocumentEnd()
        writer.close()

        val elements = NdjsonParser.parseToMaps(baos.toString())

        elements.forEach { element ->
            assertTrue(element.containsKey("id"), "Missing 'id' in $element")
            assertTrue(element.containsKey("type"), "Missing 'type' in $element")
            assertTrue(element.containsKey("label"), "Missing 'label' in $element")
        }
    }
}
