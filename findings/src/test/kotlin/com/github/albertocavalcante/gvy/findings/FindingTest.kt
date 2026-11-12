package com.github.albertocavalcante.gvy.findings

import com.github.albertocavalcante.gvy.source.SourceLocation
import com.github.albertocavalcante.gvy.source.Utf16Position
import com.github.albertocavalcante.gvy.source.Utf16Range
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FindingTest {
    @Test
    fun `finding keeps unknown values distinct from present codes`() {
        val unknown = Finding(location = null, message = "message", severity = null, source = null, code = null)
        assertNull(unknown.location)
        assertNull(unknown.severity)
        assertNull(unknown.source)
        assertNull(unknown.code)
        val range = Utf16Range(Utf16Position(0, 2), Utf16Position(1, 0))
        val finding = Finding(
            location = SourceLocation("file:///A%20B.groovy", range),
            message = "message",
            severity = FindingSeverity.INFORMATION,
            source = "actual source",
            code = FindingCode.Number(42),
        )
        assertEquals(FindingCode.Number(42), finding.code)
        assertEquals(FindingCode.Text("42"), finding.copy(code = FindingCode.Text("42")).code)
        assertEquals(range, finding.location?.range)
        assertEquals("file:///A%20B.groovy", finding.location?.uri)
    }
}
