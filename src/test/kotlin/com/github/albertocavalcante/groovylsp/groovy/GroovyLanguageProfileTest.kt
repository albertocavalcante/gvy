package com.github.albertocavalcante.groovylsp.groovy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GroovyLanguageProfileTest {

    @Test
    fun `describe returns Groovy version string`() {
        val description = GroovyLanguageProfile.describe()

        assertThat(description)
            .describedAs("Groovy description should mention the runtime version")
            .startsWith("Groovy ")
    }

    @Test
    fun `isKeyword reports known Groovy keywords`() {
        assertThat(GroovyLanguageProfile.isKeyword("class")).isTrue()
        assertThat(GroovyLanguageProfile.isKeyword("notAKeyword")).isFalse()
    }
}
