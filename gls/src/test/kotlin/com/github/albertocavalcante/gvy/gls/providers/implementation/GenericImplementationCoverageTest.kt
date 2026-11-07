package com.github.albertocavalcante.gvy.gls.providers.implementation

import com.github.albertocavalcante.gvy.gls.compilation.GroovyCompilationService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenericImplementationCoverageTest {
    @Test
    fun `generic interface parameters specialize independently for each implementation`(): Unit = runBlocking {
        assertImplementations(
            """
            interface Converter<T> {
                T convert(T value)
            }
            class Strings implements Converter<String> {
                String convert(String value) { value } // implementation
            }
            class Integers implements Converter<Integer> {
                Integer convert(Integer value) { value } // implementation
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `renamed generic variables propagate through interface inheritance`(): Unit = runBlocking {
        assertImplementations(
            """
            interface Converter<T> {
                T convert(T value)
            }
            interface Child<U> extends Converter<U> {}
            class Strings implements Child<String> {
                String convert(String value) { value } // implementation
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `generic array dimensions survive specialization`(): Unit = runBlocking {
        assertImplementations(
            """
            interface Converter<T> {
                T[][] convert(T[][] value)
            }
            class Strings implements Converter<String> {
                String[][] convert(String[][] value) { value } // implementation
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `bounded generic variables specialize to concrete argument types`(): Unit = runBlocking {
        assertImplementations(
            """
            interface Converter<T extends CharSequence> {
                T convert(T value)
            }
            class Strings implements Converter<String> {
                String convert(String value) { value } // implementation
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `inherited generic class methods resolve in the concrete subclass context`(): Unit = runBlocking {
        assertImplementations(
            """
            interface Converter<T> {
                T convert(T value)
            }
            abstract class Base<U> {
                U convert(U value) { value } // implementation
                Integer convert(Integer value) { value }
            }
            class Strings extends Base<String> implements Converter<String> {}
            """.trimIndent(),
        )
    }

    @Test
    fun `method generic variables shadow class variables without being substituted`(): Unit = runBlocking {
        assertImplementations(
            """
            interface Converter<T> {
                public <T extends Number> T convert(T value)
            }
            class Strings implements Converter<String> {
                public <U extends Number> U convert(U value) { value } // implementation
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `generic substitutions preserve qualified overload parameter names`(): Unit = runBlocking {
        assertImplementations(
            """
            interface Converter<T> {
                T convert(T value)
            }
            class Dates implements Converter<java.util.Date> {
                java.sql.Date convert(java.sql.Date value) { value }
                java.util.Date convert(java.util.Date value) { value } // implementation
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `raw bounded implementations use the declared erasure`(): Unit = runBlocking {
        assertImplementations(
            """
            interface Converter<T extends Number> {
                T convert(T value)
            }
            class Numbers implements Converter {
                Number convert(Number value) { value } // implementation
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `reordered type parameters propagate through several class and interface edges`(): Unit = runBlocking {
        assertImplementations(
            """
            interface Converter<A, B> {
                A convert(B first, A second)
            }
            interface Child<X, Y> extends Converter<Y, X> {}
            abstract class Base<P, Q> implements Child<Q, P> {}
            class Strings extends Base<String, Integer> {
                String convert(Integer first, String second) { second } // implementation
                String convert(String first, Integer second) { first }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `array arguments combine with array parameters through inheritance`(): Unit = runBlocking {
        assertImplementations(
            """
            interface Converter<T> {
                T[] convert(T[] value)
            }
            interface Child<U> extends Converter<U[]> {}
            class Strings implements Child<String> {
                String[][] convert(String[][] value) { value } // implementation
                String[] convert(String[] value) { value }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `generic default methods retain the compiler selected trait implementation`(): Unit = runBlocking {
        assertImplementations(
            """
            interface Converter<T> {
                default T convert(T value) { value } // implementation
            }
            class Strings implements Converter<String> {}
            """.trimIndent(),
        )
    }

    @Test
    fun `a concrete override masks a specialized generic default`(): Unit = runBlocking {
        assertImplementations(
            """
            interface Converter<T> {
                default T convert(T value) { value }
            }
            class Strings implements Converter<String> {
                String convert(String value) { value } // implementation
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `method generic bounds refer to the specialized class variable`(): Unit = runBlocking {
        assertImplementations(
            """
            interface Converter<T> {
                public <U extends T> U convert(U value)
            }
            class Strings implements Converter<String> {
                public <U extends String> U convert(U value) { value } // implementation
            }
            """.trimIndent(),
        )
    }

    private suspend fun assertImplementations(source: String) {
        val service = GroovyCompilationService()
        val uri = URI.create("file:///GenericImplementations.groovy")
        val compiled = service.compile(uri, source)
        assertTrue(compiled.isSuccess, compiled.diagnostics.toString())
        val before = source.substringBefore("convert(")
        val position = Position(before.count { it == '\n' }, before.substringAfterLast('\n').length)
        val expected = source.lines().mapIndexedNotNull { index, line ->
            index.takeIf { "// implementation" in line }
        }
        assertTrue(expected.isNotEmpty())

        val locations = ImplementationProvider(service).provideImplementations(uri.toString(), position).toList()

        assertEquals(expected, locations.map { it.range.start.line }.sorted())
        assertTrue(locations.all { it.uri == uri.toString() })
    }
}
