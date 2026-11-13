// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.sources

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GroovySourceTypeIndexTest {
    @Test
    fun `Groovy annotation source links imports annotation types and class-valued argument aliases`() {
        val source = """
            package groovy.transform
            import java.lang.annotation.ElementType
            import java.lang.annotation.Target
            import groovy.transform.options.ImmutablePropertyHandler as Handler
            @PropertyOptions(propertyHandler = Handler)
            @Target([ElementType.TYPE])
            @interface Immutable {}
        """.trimIndent()
        val index = GroovySourceTypeIndex.build(source)

        assertEquals(
            listOf(listOf("groovy.transform.options.ImmutablePropertyHandler")),
            assertNotNull(index.at(position(source, "ImmutablePropertyHandler"))).candidates,
        )
        assertEquals(
            listOf(listOf("groovy.transform.options.ImmutablePropertyHandler")),
            assertNotNull(index.at(position(source, "Handler", occurrence = 1))).candidates,
        )
        assertEquals(
            listOf(listOf("groovy.transform.options.ImmutablePropertyHandler")),
            assertNotNull(index.at(position(source, "Handler", occurrence = 2))).candidates,
        )
        assertEquals(
            listOf("groovy.transform.PropertyOptions"),
            assertNotNull(index.at(position(source, "PropertyOptions"))).candidates.first(),
        )
        assertEquals(
            listOf(listOf("java.lang.annotation.ElementType")),
            assertNotNull(index.at(position(source, "ElementType", occurrence = 1))).candidates,
        )
    }

    @Test
    fun `declared types generic arguments constructors casts and parameters use the written import alias`() {
        val source = """
            package example
            import dependency.Item as Thing
            import java.util.List
            class Model {
                List<Thing> items
                Thing copy(Thing input) {
                    Thing local = new Thing()
                    (Thing) local
                }
            }
        """.trimIndent()
        val index = GroovySourceTypeIndex.build(source)

        for (occurrence in 1..6) {
            val reference = assertNotNull(
                index.at(position(source, "Thing", occurrence)),
                "Thing occurrence $occurrence",
            )
            assertEquals(listOf(listOf("dependency.Item")), reference.candidates)
        }
        assertEquals(
            listOf(listOf("java.util.List")),
            assertNotNull(index.at(position(source, "List", 1))).candidates,
        )
    }

    @Test
    fun `a local annotation links to its exact declaration name after meta annotations`() {
        val source = """
            package example
            import java.lang.annotation.Retention
            import java.lang.annotation.RetentionPolicy
            @Retention(RetentionPolicy.RUNTIME)
            @interface Local {}
            @Local class Model {}
        """.trimIndent()
        val reference = assertNotNull(GroovySourceTypeIndex.build(source).at(position(source, "Local", 1)))
        val definition = assertNotNull(reference.localDefinition)

        assertEquals(SourceSpan(4, 11, 4, 16), definition.nameRange)
        assertTrue(definition.declarationRange.contains(definition.nameRange))
        assertEquals(emptyList(), reference.candidates)
    }

    @Test
    fun `a local nested annotation shadows an imported annotation alias`() {
        val source = """
            import java.lang.Deprecated as Mark
            class Outer {
                @interface Mark {}
                @Mark void method() {}
            }
        """.trimIndent()
        val reference = assertNotNull(GroovySourceTypeIndex.build(source).at(position(source, "Mark", 2)))

        assertNotNull(reference.localDefinition)
        assertEquals(emptyList(), reference.candidates)
    }

    @Test
    fun `an imported annotation is not chosen when an inherited nested type can shadow it`() {
        val source = """
            import java.lang.Deprecated as Mark
            class Model extends Parent {
                @Mark void method() {}
            }
        """.trimIndent()

        assertNull(GroovySourceTypeIndex.build(source).at(position(source, "Mark", 1)))
    }

    @Test
    fun `an outer local annotation is not chosen ahead of an unknown inherited nested type`() {
        val source = """
            class Outer {
                @interface Mark {}
                class Model extends Parent {
                    @Mark void method() {}
                }
            }
        """.trimIndent()

        assertNull(GroovySourceTypeIndex.build(source).at(position(source, "Mark", 1)))
    }

    @Test
    fun `a dotted outer nested annotation is not chosen ahead of an unknown inherited type`() {
        val source = """
            class Outer {
                static class Qualifier { @interface Mark {} }
                class Model extends Parent {
                    @Qualifier.Mark void method() {}
                }
            }
        """.trimIndent()

        assertNull(GroovySourceTypeIndex.build(source).at(position(source, "Mark", 1)))
    }

    @Test
    fun `a current class annotation remains linkable ahead of its inherited nested types`() {
        val source = """
            class Model extends Parent {
                @interface Mark {}
                @Mark void method() {}
            }
        """.trimIndent()
        val reference = assertNotNull(GroovySourceTypeIndex.build(source).at(position(source, "Mark", 1)))

        assertNotNull(reference.localDefinition)
        assertEquals(emptyList(), reference.candidates)
    }

    @Test
    fun `a qualified annotation remains linkable despite unknown inherited nested types`() {
        val source = """
            class Model extends Parent {
                @java.lang.Deprecated void method() {}
            }
        """.trimIndent()
        val reference = assertNotNull(GroovySourceTypeIndex.build(source).at(position(source, "Deprecated")))

        assertEquals(listOf("java.lang.Deprecated"), reference.candidates.first())
    }

    @Test
    fun `source comments strings dynamic receivers and value-shadowed annotation arguments do not become type links`() {
        val source = """
            import dependency.Handler
            @interface Configuration { Class value() }
            @Configuration(Handler)
            class Model {
                static final Class Handler = String
                String text = 'Handler'
                // Handler
                def run() { Handler.invoke() }
            }
        """.trimIndent()
        val index = GroovySourceTypeIndex.build(source)

        assertNotNull(index.at(position(source, "Handler")), "The import itself is an explicit class reference")
        assertNull(index.at(position(source, "Handler", 1)), "Annotation expression can be a value")
        assertNull(index.at(position(source, "Handler", 3)), "String literal")
        assertNull(index.at(position(source, "Handler", 4)), "Comment")
        assertNull(index.at(position(source, "Handler", 5)), "Dynamic receiver")
    }

    @Test
    fun `type variables and unknown inherited nested-type shadows are not guessed`() {
        val generic = "import dependency.T\nclass Model<T> { T value }"
        assertNull(GroovySourceTypeIndex.build(generic).at(position(generic, "T value")))

        val inherited = "import dependency.Item\nclass Model extends Parent { Item value }"
        assertNull(GroovySourceTypeIndex.build(inherited).at(position(inherited, "Item value")))
    }

    @Test
    fun `on-demand import candidates are grouped for origin validation rather than choosing the first`() {
        val source = "package example\nimport first.*\nimport second.*\nclass Model { Item value }"
        val reference = assertNotNull(GroovySourceTypeIndex.build(source).at(position(source, "Item value")))

        assertEquals(listOf("example.Item"), reference.candidates[0])
        assertEquals(listOf("first.Item", "second.Item"), reference.candidates[1])
    }

    @Test
    fun `source type ranges use UTF16 and comments between annotation segments are not clickable`() {
        val source = "/*😀*/ @groovy /*hidden*/ . transform . CompileStatic class Model {}"
        val index = GroovySourceTypeIndex.build(source)
        val reference = assertNotNull(index.at(position(source, "CompileStatic")))

        assertEquals(source.indexOf("CompileStatic"), reference.range.startCharacter)
        assertEquals(listOf("groovy.transform.CompileStatic"), reference.candidates.first())
        assertNull(index.at(position(source, "hidden")))
    }

    @Test
    fun `malformed or oversized source does not publish speculative type links`() {
        assertNull(GroovySourceTypeIndex.build("@Broken( class Model {").at(Position(0, 1)))
        assertNull(GroovySourceTypeIndex.build(" ".repeat(1024 * 1024 + 1)).at(Position(0, 0)))
    }

    @Test
    fun `source index cancellation is preserved rather than becoming an empty successful index`() {
        val cancelled = CancellationException("cancelled")
        assertSame(
            cancelled,
            assertFailsWith<CancellationException> {
                GroovySourceTypeIndex.build("class Model {}") { throw cancelled }
            },
        )
    }

    @Test
    fun `oversized names discard the whole Groovy index rather than truncating candidate tiers`() {
        val name = "A".repeat(SourceTypeIndexBudget.MAX_NAME_CHARACTERS + 1)
        val source = "import java.util.List\nclass $name { List value }"
        val index = GroovySourceTypeIndex.build(source)

        assertNull(
            index.at(position(source, "List")),
            "Earlier import links must not survive an exceeded metadata budget",
        )
        assertNull(index.at(position(source, "List", 1)))
    }

    @Test
    fun `retained repeated candidate rows count toward the aggregate metadata budget`() {
        val packageName = "p".repeat(2000)
        val source = "package $packageName\nclass Model {\n" +
            (0..1100).joinToString("\n") { "Item value$it" } + "\n}"

        assertNull(GroovySourceTypeIndex.build(source).at(position(source, "Item")))
    }

    @Test
    fun `repeated ordinary type spellings retain identical complete ambiguity tiers`() {
        val source = "package example\nimport first.*\nimport second.*\nclass Model { Item first; Item second }"
        val index = GroovySourceTypeIndex.build(source)

        assertEquals(
            assertNotNull(index.at(position(source, "Item"))).candidates,
            assertNotNull(index.at(position(source, "Item", 1))).candidates,
        )
        assertEquals(
            listOf("first.Item", "second.Item"),
            assertNotNull(index.at(position(source, "Item"))).candidates[1],
        )
    }

    private fun position(source: String, text: String, occurrence: Int = 0): Position {
        var offset = -1
        repeat(occurrence + 1) { offset = source.indexOf(text, offset + 1) }
        require(offset >= 0) { "Missing occurrence $occurrence of $text" }
        val prefix = source.substring(0, offset)
        val line = prefix.count { it == '\n' }
        return Position(line, offset - (source.lastIndexOf('\n', offset) + 1))
    }
}
