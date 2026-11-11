package com.github.albertocavalcante.gvy.viz.converters

import com.github.albertocavalcante.groovyparser.api.model.Position
import com.github.albertocavalcante.groovyparser.api.model.Range
import com.github.albertocavalcante.groovyparser.provider.RewriteParserProvider
import com.github.albertocavalcante.gvy.viz.model.AstNodeDto
import com.github.albertocavalcante.gvy.viz.model.RangeDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RewriteAstConverterTest {
    private val converter = RewriteAstConverter(RewriteParserProvider())

    @Test
    fun `root ranges use one based exclusive end coordinates`() {
        val cases = mapOf(
            "" to RangeDto(1, 1, 1, 1),
            "x" to RangeDto(1, 1, 1, 2),
            "x\n" to RangeDto(1, 1, 2, 1),
            "x\nyz" to RangeDto(1, 1, 2, 3),
        )
        cases.forEach { (source, range) ->
            assertEquals(range, assertNotNull(converter.parse(source)).range, "source: $source")
        }
    }

    @Test
    fun `unavailable and invalid parser ranges convert to null`() {
        assertEquals(null, Range.EMPTY.toDtoRange())
        assertEquals(null, Range(Position(1, 1), Position(0, 0)).toDtoRange())
        assertEquals(null, Range(Position(2, 1), Position(1, 1)).toDtoRange())
    }

    @Test
    fun `symbol ranges survive JSON export`() {
        val source = "// comment\nclass Foo {\n    String name\n    def work() {}\n}"
        val dto = assertNotNull(converter.parse(source))
        fun range(name: String) = dto.children.single { it.properties["name"] == name }.range
        assertEquals(RangeDto(2, 1, 5, 2), range("Foo"))
        assertEquals(RangeDto(3, 12, 3, 16), range("name"))
        assertEquals(RangeDto(4, 5, 4, 18), range("work"))
        val json = Json.encodeToString<AstNodeDto>(dto)
        assertEquals(dto, Json.decodeFromString<AstNodeDto>(json))
    }
}
