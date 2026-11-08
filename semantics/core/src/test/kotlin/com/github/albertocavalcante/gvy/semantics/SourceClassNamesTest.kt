package com.github.albertocavalcante.gvy.semantics

import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.Phases
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SourceClassNamesTest {
    @Test
    fun `reflected nested classes retain source names when Groovy has no outer node`() {
        listOf(java.util.Map.Entry::class.java, Thread.State::class.java, java.util.AbstractMap.SimpleEntry::class.java)
            .forEach { type -> assertEquals(type.simpleName, ClassHelper.make(type).sourceSimpleName()) }
    }

    @Test
    fun `reflected top level dollar names retain the entire identifier`() {
        GroovyClassLoader().use { loader ->
            val type = loader.parseClass("class Reflected\$Dollar {}", "ReflectedNames.groovy")
            assertEquals("Reflected\$Dollar", ClassHelper.make(type).sourceSimpleName())
        }
    }

    @Test
    fun `source names preserve dollar signs after removing only the proved outer prefix`() {
        val unit = CompilationUnit()
        unit.addSource("Names.groovy", "package p\nclass Outer { static class In\$ner {} }\nclass Dollar\$Part {}\n")
        unit.compile(Phases.SEMANTIC_ANALYSIS)
        val classes = unit.ast.modules.single().classes
        val outer = classes.first { it.name == "p.Outer" }
        val nested = outer.innerClasses.next()

        assertEquals("Outer", outer.sourceSimpleName())
        assertEquals("In\$ner", nested.sourceSimpleName())
        assertEquals("In\$ner", nested.plainNodeReference.sourceSimpleName())
        assertEquals("Dollar\$Part", classes.first { it.name == "p.Dollar\$Part" }.sourceSimpleName())
    }
}
