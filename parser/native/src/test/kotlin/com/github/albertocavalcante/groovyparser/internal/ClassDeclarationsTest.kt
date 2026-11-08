package com.github.albertocavalcante.groovyparser.internal

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CancellationException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClassDeclarationsTest {
    @Test
    fun `declarations retain recovery evidence across comments and unicode names`() {
        for (source in listOf("class /* comment */ Real {}", "class\nΔοκιμή {}", "class lowerCase {}")) {
            assertTrue(hasClassDeclaration(source) {}, source)
        }
    }

    @Test
    fun `a class literal cannot consume a subsequent statement as its declaration name`() {
        assertFalse(hasClassDeclaration("Map.Entry.class\nprintln 1") {})
    }

    @Test
    fun `unfinished strings do not manufacture recovery evidence`() {
        assertFalse(hasClassDeclaration("println 'class Pretend") {})
    }

    @Test
    fun `cancellation during declaration scanning propagates`() {
        var checks = 0
        assertThrows<CancellationException> {
            hasClassDeclaration("println 1\nclass Real {}") {
                checks++
                if (checks == 3) throw CancellationException("cancelled")
            }
        }
    }
}
