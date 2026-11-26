// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.concurrent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnceFlagTest {

    @Test
    fun `a new flag is not set`() {
        assertFalse(OnceFlag().isSet)
    }

    @Test
    fun `only the first trySet succeeds`() {
        val flag = OnceFlag()

        assertTrue(flag.trySet(), "The first call sets the flag")
        assertFalse(flag.trySet(), "A later call finds it already set")
        assertTrue(flag.isSet)
    }
}
