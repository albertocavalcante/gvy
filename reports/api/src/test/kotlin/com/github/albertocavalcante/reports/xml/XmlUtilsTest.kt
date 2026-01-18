package com.github.albertocavalcante.reports.xml

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.xml.sax.SAXParseException
import java.io.File

class XmlUtilsTest {

    @Test
    fun `createSecureDocumentBuilder creates a valid DocumentBuilder`() {
        val builder = XmlUtils.createSecureDocumentBuilder()
        assertThat(builder).isNotNull
    }

    @Test
    fun `createSecureDocumentBuilder can parse valid XML`(@TempDir tempDir: File) {
        val xmlFile = File(tempDir, "test.xml")
        xmlFile.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <root>
                <element>value</element>
            </root>
            """.trimIndent(),
        )

        val builder = XmlUtils.createSecureDocumentBuilder()
        val doc = builder.parse(xmlFile)
        doc.documentElement.normalize()

        assertThat(doc.documentElement.tagName).isEqualTo("root")
        val elements = doc.getElementsByTagName("element")
        assertThat(elements.length).isEqualTo(1)
        assertThat(elements.item(0).textContent).isEqualTo("value")
    }

    @Test
    fun `createSecureDocumentBuilder rejects XML with DOCTYPE declaration`(@TempDir tempDir: File) {
        val xmlFile = File(tempDir, "test-with-doctype.xml")
        xmlFile.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE root [
                <!ELEMENT root ANY>
            ]>
            <root>
                <element>value</element>
            </root>
            """.trimIndent(),
        )

        val builder = XmlUtils.createSecureDocumentBuilder()
        assertThatThrownBy {
            builder.parse(xmlFile)
        }.isInstanceOf(SAXParseException::class.java)
            .hasMessageContaining("DOCTYPE")
    }

    @Test
    fun `createSecureDocumentBuilder handles malformed XML with exception`(@TempDir tempDir: File) {
        val xmlFile = File(tempDir, "malformed.xml")
        xmlFile.writeText("<root><unclosed>")

        val builder = XmlUtils.createSecureDocumentBuilder()
        assertThatThrownBy {
            builder.parse(xmlFile)
        }.isInstanceOf(SAXParseException::class.java)
    }

    @Test
    fun `createSecureDocumentBuilder parses XML with namespaces`(@TempDir tempDir: File) {
        val xmlFile = File(tempDir, "namespaced.xml")
        xmlFile.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <root xmlns:custom="http://example.com/ns">
                <custom:element>value</custom:element>
            </root>
            """.trimIndent(),
        )

        val builder = XmlUtils.createSecureDocumentBuilder()
        val doc = builder.parse(xmlFile)
        doc.documentElement.normalize()

        assertThat(doc.documentElement.tagName).isEqualTo("root")
    }
}
