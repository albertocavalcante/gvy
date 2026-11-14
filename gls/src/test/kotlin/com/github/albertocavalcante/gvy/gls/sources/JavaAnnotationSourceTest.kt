// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JavaAnnotationSourceTest {
    private val inspector = JavaSourceInspector()

    @Test
    fun `an annotation declaration is located at its name after meta annotations`() {
        val result = inspector.inspectClassFromContent(
            """
            package example;
            /** Enables compilation. */
            @Deprecated
            public @interface Static {}
            """.trimIndent(),
            "example.Static",
        )

        assertNotNull(result)
        assertEquals(4, result.lineNumber)
        assertEquals("Enables compilation.", result.documentation.summary)
    }

    @Test
    fun `nested annotation lookup follows lexical owners instead of the first simple name`() {
        val result = inspector.inspectClassFromContent(
            """
            package example;
            class Left { @interface Mark {} }
            class Right {
                @interface Mark {}
            }
            """.trimIndent(),
            "example.Right\$Mark",
        )

        assertNotNull(result)
        assertEquals(4, result.lineNumber)
    }

    @Test
    fun `a declaration in another package is not the requested type`() {
        assertNull(inspector.inspectClassFromContent("package other; public class Static {}", "example.Static"))
    }

    @Test
    fun `enum and record declarations have source definitions`() {
        assertNotNull(inspector.inspectClassFromContent("package example; public enum Mode { ON }", "example.Mode"))
        assertNotNull(
            inspector.inspectClassFromContent("package example; public record Point(int x) {}", "example.Point"),
        )
    }
}
