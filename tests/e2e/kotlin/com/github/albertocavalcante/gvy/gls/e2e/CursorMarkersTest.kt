package com.github.albertocavalcante.gvy.gls.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CursorMarkersTest {

    @Test
    fun `text without markers is returned unchanged`() {
        val marked = CursorMarkers.strip("println 'hello'\n")

        assertThat(marked.text).isEqualTo("println 'hello'\n")
        assertThat(marked.markers).isEmpty()
    }

    @Test
    fun `anonymous marker is stripped and positioned`() {
        val marked = CursorMarkers.strip("class Foo {\n    def x = |^|bar\n}\n")

        assertThat(marked.text).isEqualTo("class Foo {\n    def x = bar\n}\n")
        assertThat(marked.markers).containsExactly(CursorMarker(name = null, line = 1, character = 12))
    }

    @Test
    fun `named marker is stripped and positioned`() {
        val marked = CursorMarkers.strip("new |^ctor|Greeter()")

        assertThat(marked.text).isEqualTo("new Greeter()")
        assertThat(marked.markers).containsExactly(CursorMarker(name = "ctor", line = 0, character = 4))
    }

    @Test
    fun `several markers on one line account for earlier stripped markers`() {
        val marked = CursorMarkers.strip("|^a|foo(|^b|x, |^c-1|y)")

        assertThat(marked.text).isEqualTo("foo(x, y)")
        assertThat(marked.markers).containsExactly(
            CursorMarker(name = "a", line = 0, character = 0),
            CursorMarker(name = "b", line = 0, character = 4),
            CursorMarker(name = "c-1", line = 0, character = 7),
        )
    }

    @Test
    fun `CRLF counts as one line break and is preserved`() {
        val marked = CursorMarkers.strip("a\r\nb|^x|c\r\n|^y|d")

        assertThat(marked.text).isEqualTo("a\r\nbc\r\nd")
        assertThat(marked.markers).containsExactly(
            CursorMarker(name = "x", line = 1, character = 1),
            CursorMarker(name = "y", line = 2, character = 0),
        )
    }

    @Test
    fun `lone CR is a line break`() {
        val marked = CursorMarkers.strip("a\rb|^|")

        assertThat(marked.markers).containsExactly(CursorMarker(name = null, line = 1, character = 1))
    }

    @Test
    fun `surrogate pair before a marker shifts the column by two UTF-16 units`() {
        // U+1F600 is one code point but two UTF-16 code units, which is what LSP counts by default.
        val marked = CursorMarkers.strip("def s = \"😀\"; |^|x")

        assertThat(marked.text).isEqualTo("def s = \"😀\"; x")
        assertThat(marked.markers).containsExactly(CursorMarker(name = null, line = 0, character = 14))
    }

    @Test
    fun `duplicate named marker in one text is rejected`() {
        assertThatThrownBy { CursorMarkers.strip("|^a|x\n|^a|y") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("|^a|")
            .hasMessageContaining("more than once")
    }

    @Test
    fun `duplicate anonymous marker in one text is rejected`() {
        assertThatThrownBy { CursorMarkers.strip("|^|x |^|y") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("|^|")
            .hasMessageContaining("more than once")
    }

    @Test
    fun `reserved marker names are rejected`() {
        listOf("line", "character", "uri", "path").forEach { reserved ->
            assertThatThrownBy { CursorMarkers.strip("x|^$reserved|") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("reserved")
                .hasMessageContaining(reserved)
        }
    }

    @Test
    fun `pipe caret sequences that are not markers stay in the text`() {
        val marked = CursorMarkers.strip("def r = ~/a|^b/ ; x |^ y|")

        assertThat(marked.text).isEqualTo("def r = ~/a|^b/ ; x |^ y|")
        assertThat(marked.markers).isEmpty()
    }
}
