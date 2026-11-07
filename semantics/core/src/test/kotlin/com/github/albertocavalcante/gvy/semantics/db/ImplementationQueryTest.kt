package com.github.albertocavalcante.gvy.semantics.db

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.SourceUnit
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImplementationQueryTest {
    private val uri = URI.create("file:///Hierarchy.groovy")
    private val targetRange = Range(0, 0, 0, 20)
    private val implementationRange = Range(2, 0, 2, 20)
    private val target = ImplementationMethod("I#greet().", "greet", emptyList(), targetRange, true, false, false)
    private val occurrence = SymbolOccurrence(target.symbol, targetRange, OccurrenceRole.DEFINITION)

    @Test
    fun `deep inheritance queries use bounded stack space`() {
        val hierarchy = linkedMapOf("I#" to ImplementationType(true, true, null, emptyList(), listOf(target)))
        repeat(10000) { index ->
            hierarchy["C$index#"] = ImplementationType(
                isInterface = false,
                isAbstract = index != 9999,
                superclass = if (index == 0) null else "C${index - 1}#",
                interfaces = if (index == 0) listOf("I#") else emptyList(),
                methods = if (index ==
                    0
                ) {
                    listOf(target.copy(symbol = "C0#greet().", range = implementationRange, isAbstract = false))
                } else {
                    emptyList()
                },
            )
        }

        assertEquals(listOf(ImplementationLocation(uri, implementationRange)), query(hierarchy))
    }

    @Test
    fun `concrete inheritance chains resolve a shared method with linear superclass work`() {
        val count = 10000
        val hierarchy = linkedMapOf("I#" to ImplementationType(true, true, null, emptyList(), listOf(target)))
        repeat(count) { index ->
            hierarchy["C$index#"] = ImplementationType(
                isInterface = false,
                isAbstract = false,
                superclass = if (index == 0) null else "C${index - 1}#",
                interfaces = if (index == 0) listOf("I#") else emptyList(),
                methods = if (index ==
                    0
                ) {
                    listOf(target.copy(symbol = "C0#greet().", range = implementationRange, isAbstract = false))
                } else {
                    emptyList()
                },
            )
        }
        val declarations = hierarchy.keys.reversed().map { ImplementationClass(it, targetRange) }
        val document =
            ImplementationDocument(SemanticDocument(uri, emptyList(), emptyList()), "source", declarations, hierarchy)

        val (locations, work) = ImplementationQuery.measuredFind(mapOf(uri to document), uri, occurrence)

        println(
            "implementation/concrete-chain classes=$count " +
                "superclassSteps=${work.superclassSteps} locations=${locations.size}",
        )
        assertEquals(listOf(ImplementationLocation(uri, implementationRange)), locations)
        assertTrue(work.superclassSteps <= count * 2L, "Superclass steps: ${work.superclassSteps} for $count classes")
    }

    @Test
    fun `cyclic incomplete hierarchies terminate without inventing implementations`() {
        val hierarchy = mapOf(
            "I#" to ImplementationType(true, true, null, emptyList(), listOf(target)),
            "C#" to ImplementationType(false, false, "C#", listOf("I#"), emptyList()),
        )

        assertEquals(emptyList(), query(hierarchy))
    }

    @Test
    fun `abstract superclass declaration masks an interface default`() {
        val hierarchy = mapOf(
            "I#" to ImplementationType(true, false, null, emptyList(), listOf(target.copy(isAbstract = false))),
            "Base#" to ImplementationType(
                false,
                true,
                null,
                emptyList(),
                listOf(target.copy(symbol = "Base#greet().")),
            ),
            "C#" to ImplementationType(false, false, "Base#", listOf("I#"), emptyList()),
        )

        assertEquals(emptyList(), query(hierarchy))
    }

    @Test
    fun `most specific abstract interface declaration masks its parent default`() {
        val hierarchy = mapOf(
            "I#" to ImplementationType(true, false, null, emptyList(), listOf(target.copy(isAbstract = false))),
            "Child#" to
                ImplementationType(true, true, null, listOf("I#"), listOf(target.copy(symbol = "Child#greet()."))),
            "C#" to ImplementationType(false, false, null, listOf("I#", "Child#"), emptyList()),
        )

        assertEquals(emptyList(), query(hierarchy))
    }

    @Test
    fun `deep source chains store one direct edge per class`() {
        val module = ModuleNode(SourceUnit.create("Hierarchy.groovy", ""))
        var parent = ClassHelper.OBJECT_TYPE
        repeat(10000) { index ->
            val node = ClassNode("C$index", Modifier.PUBLIC or Modifier.ABSTRACT, parent).apply {
                lineNumber = index + 1
                lastLineNumber = index + 1
                columnNumber = 1
                lastColumnNumber = 20
            }
            module.addClass(node)
            parent = node
        }

        val snapshot = ImplementationFacts.build(module)

        assertEquals(10000, snapshot.classes.size)
        assertEquals(10001, snapshot.hierarchy.size)
        assertEquals(
            10000,
            snapshot.hierarchy.values.sumOf {
                (
                    if (it.superclass ==
                        null
                    ) {
                        0
                    } else {
                        1
                    }
                    ) + it.interfaces.size
            },
        )
    }

    private fun query(hierarchy: Map<String, ImplementationType>): List<ImplementationLocation> {
        val declarations = hierarchy.keys.map { ImplementationClass(it, targetRange) }
        val document =
            ImplementationDocument(SemanticDocument(uri, emptyList(), emptyList()), "source", declarations, hierarchy)
        return ImplementationQuery.find(mapOf(uri to document), uri, occurrence)
    }
}
