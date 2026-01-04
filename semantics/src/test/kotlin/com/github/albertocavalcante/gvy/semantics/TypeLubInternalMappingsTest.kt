package com.github.albertocavalcante.gvy.semantics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class TypeLubInternalMappingsTest {

    @Test
    fun `KNOWN_FOR_RANK includes BigInteger and BigDecimal wrappers`() {
        val typeLubClass = TypeLub::class.java

        val rankBigInteger = readPrivateIntConstant(typeLubClass, "RANK_BIG_INTEGER")
        val rankBigDecimal = readPrivateIntConstant(typeLubClass, "RANK_BIG_DECIMAL")

        val knownForRankField = typeLubClass.getDeclaredField("KNOWN_FOR_RANK").apply { isAccessible = true }
        val receiver = TypeLub

        @Suppress("UNCHECKED_CAST")
        val map = knownForRankField.get(receiver) as Map<Int, SemanticType>

        val bigInteger = map[rankBigInteger] as? SemanticType.Known
        val bigDecimal = map[rankBigDecimal] as? SemanticType.Known

        assertNotNull(bigInteger)
        assertNotNull(bigDecimal)
        assertEquals("java.math.BigInteger", bigInteger!!.fqn)
        assertEquals("java.math.BigDecimal", bigDecimal!!.fqn)
    }

    private fun readPrivateIntConstant(clazz: Class<*>, name: String): Int {
        val field = clazz.getDeclaredField(name).apply { isAccessible = true }
        val receiver = TypeLub
        return field.get(receiver) as Int
    }
}
