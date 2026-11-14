// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test

class DocumentAnalysisSessionInputTest {
    @Test
    fun `malformed session input is ignored`() {
        val server = GroovyLanguageServer(exitProcess = {})
        try {
            server.documentAnalysisSession(null)
            server.documentAnalysisSession(JsonParser.parseString("[]"))
            server.documentAnalysisSession(JsonParser.parseString("""{"uri":"not a valid uri","sessionId":"s"}"""))
            server.documentAnalysisSession(JsonParser.parseString("""{"uri":"file:///A.groovy","sessionId":1}"""))
        } finally {
            server.shutdown().join()
        }
    }
}
