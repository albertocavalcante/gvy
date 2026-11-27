// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.jvm.facts.builder

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import kotlin.test.fail

class AsmVersionDiagnosticTest {
    @Test
    fun `reports the ASM jar in use`() {
        val where = ClassReader::class.java.protectionDomain.codeSource.location
        fail("ASM jar in use: $where; newest readable major ${NewestReadableMajor.value}")
    }
}
