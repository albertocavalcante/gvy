package com.github.albertocavalcante.groovylsp.markdown.dsl

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownBuilderTest {

    @Test
    fun `should render basic text`() {
        val result = markdown {
            text("Hello World")
        }
        assertEquals("Hello World", result)
    }

    @Test
    fun `should render code blocks`() {
        val result = markdown {
            code("kotlin", "val x = 1")
        }
        assertEquals("```kotlin\nval x = 1\n```", result)
    }

    @Test
    fun `should render headers`() {
        val result = markdown {
            h1("Title")
            h2("Subtitle")
        }
        assertEquals("# Title\n\n## Subtitle", result)
    }

    @Test
    fun `should render sections`() {
        val result = markdown {
            section("Details") {
                text("Some info")
            }
        }
        assertEquals("### Details\n\nSome info", result)
    }

    @Test
    fun `should render tables`() {
        val result = markdown {
            table(
                headers = listOf("Col 1", "Col 2"),
                rows = listOf(
                    listOf("V1", "V2"),
                    listOf("V3", "V4")
                )
            )
        }
        val expected = """
            | Col 1 | Col 2 |
            | --- | --- |
            | V1 | V2 |
            | V3 | V4 |
        """.trimIndent()
        assertEquals(expected, result)
    }

    @Test
    fun `should render links`() {
        val result = markdown {
            link("Google", "https://google.com")
        }
        assertEquals("[Google](https://google.com)", result)
    }

    @Test
    fun `should support bold and italic helpers`() {
        val result = markdown {
            text("This is ${bold("bold")} and ${italic("italic")}")
        }
        assertEquals("This is **bold** and _italic_", result)
    }
}
