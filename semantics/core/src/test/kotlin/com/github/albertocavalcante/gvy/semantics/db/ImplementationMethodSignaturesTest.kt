package com.github.albertocavalcante.gvy.semantics.db

import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImplementationMethodSignaturesTest {
    private val uri = URI.create("file:///Parent.groovy")
    private val other = URI.create("file:///Other.groovy")
    private val range = Range(0, 0, 2, 1)
    private val owner = "Parent#"
    private val symbol = "Parent#choose(Date)."

    @Test
    fun `erased symbol collisions retain both qualified signatures after source removal`() {
        val db = GroovySemanticDB()
        val facts = ImplementationFacts.Snapshot(
            listOf(ImplementationClass(owner, range)),
            mapOf(
                owner to ImplementationType(
                    false,
                    false,
                    null,
                    emptyList(),
                    listOf(method("java.util.Date"), method("java.sql.Date")),
                ),
            ),
        )
        db.updateDocument(uri, SemanticDocument(uri, emptyList(), emptyList()), "source", facts)
        val captured = db.implementationSnapshot.getValue(uri)
        db.removeDocument(uri)

        val signatures = ImplementationMethodSignatures.parameterTypesBySymbol(captured, owner)

        assertEquals(mapOf(symbol to listOf(listOf("java.util.Date"), listOf("java.sql.Date"))), signatures)
        assertTrue(db.implementationSnapshot.isEmpty())
        assertFailsWith<UnsupportedOperationException> { (signatures as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (signatures.getValue(symbol) as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> {
            (signatures.getValue(symbol).first() as MutableList).clear()
        }
    }

    @Test
    fun `classpath ancestry facts cannot masquerade as a source owned class`() {
        val document = ImplementationDocument(
            SemanticDocument(uri, emptyList(), emptyList()),
            "source",
            emptyList(),
            mapOf(owner to ImplementationType(false, false, null, emptyList(), listOf(method("java.util.Date")))),
        )

        assertTrue(ImplementationMethodSignatures.parameterTypesBySymbol(document, owner).isEmpty())
    }

    @Test
    fun `single document lookup returns only the unambiguous defining document`() {
        val db = GroovySemanticDB()
        val parent = SymbolInfo(owner, SymbolKind.CLASS, range, "Parent", null)
        val facts = ImplementationFacts.Snapshot(
            listOf(ImplementationClass(owner, range)),
            mapOf(owner to ImplementationType(false, false, null, emptyList(), listOf(method("java.util.Date")))),
        )
        db.updateDocument(uri, SemanticDocument(uri, listOf(parent), emptyList()), "source", facts)
        db.updateDocument(other, SemanticDocument(other, emptyList(), emptyList()), "other", facts)

        val found = assertNotNull(db.findImplementationDocument(owner))

        assertEquals(uri, found.document.uri)
        assertEquals(mapOf(symbol to listOf(listOf("java.util.Date"))), parameterTypes(found))
        assertNull(db.findImplementationDocument("Missing#"))

        db.updateDocument(other, SemanticDocument(other, listOf(parent), emptyList()), "other", facts)
        assertNull(db.findImplementationDocument(owner), "A class defined in two files is ambiguous")
    }

    private fun parameterTypes(document: ImplementationDocument) =
        ImplementationMethodSignatures.parameterTypesBySymbol(document, owner)

    @Test
    fun `supertypes and overrides are empty for classes the document does not own`() {
        val document = genericDocument(kParameter = "K")

        assertNull(ImplementationMethodSignatures.supertypes(document, "java/lang/Comparable#"))
        assertTrue(ImplementationMethodSignatures.genericOverrides(document, "java/lang/Comparable#").isEmpty())
        assertEquals(
            SourceSupertypes(null, listOf("java/lang/Comparable#")),
            ImplementationMethodSignatures.supertypes(document, "K#"),
        )
    }

    @Test
    fun `a source method overriding through a substituted type variable is reported by its erasure`() {
        val overrides = ImplementationMethodSignatures.genericOverrides(genericDocument(kParameter = "K"), "K#")

        assertEquals(setOf("compareTo" to listOf("java.lang.Object")), overrides)
    }

    @Test
    fun `an overload whose types differ after substitution is not an override`() {
        val document = genericDocument(kParameter = "java.lang.Integer")

        assertTrue(ImplementationMethodSignatures.genericOverrides(document, "K#").isEmpty())
    }

    // class K implements Comparable<K> { int compareTo(<kParameter> o) }
    private fun genericDocument(kParameter: String): ImplementationDocument {
        val comparable = "java/lang/Comparable#"
        val variable = ImplementationTypeReference("T", isVariable = true)
        return ImplementationDocument(
            SemanticDocument(uri, emptyList(), emptyList()),
            "source",
            listOf(ImplementationClass("K#", range)),
            mapOf(
                "K#" to ImplementationType(
                    isInterface = false,
                    isAbstract = false,
                    superclass = null,
                    interfaces = listOf(comparable),
                    methods = listOf(
                        ImplementationMethod(
                            "K#compareTo(K).",
                            "compareTo",
                            listOf(kParameter),
                            range,
                            false,
                            false,
                            false,
                        ),
                    ),
                    parentArguments = mapOf(comparable to listOf(ImplementationTypeReference("K"))),
                ),
                comparable to ImplementationType(
                    isInterface = true,
                    isAbstract = true,
                    superclass = null,
                    interfaces = emptyList(),
                    methods = listOf(
                        ImplementationMethod(
                            "java/lang/Comparable#compareTo(Object).",
                            "compareTo",
                            listOf("java.lang.Object"),
                            null,
                            isAbstract = true,
                            isStatic = false,
                            isPrivate = false,
                            parameterReferences = listOf(variable),
                        ),
                    ),
                    parameterDefaults = mapOf("T" to "java.lang.Object"),
                ),
            ),
        )
    }

    private fun method(parameter: String): ImplementationMethod =
        ImplementationMethod(symbol, "choose", listOf(parameter), range, false, false, false)
}
