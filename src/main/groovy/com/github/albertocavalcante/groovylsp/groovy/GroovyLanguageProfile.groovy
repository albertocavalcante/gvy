package com.github.albertocavalcante.groovylsp.groovy

import groovy.transform.CompileStatic
import java.util.Collections

/**
 * Simple Groovy-side helper so we can validate mixed Kotlin/Groovy builds.
 */
@CompileStatic
class GroovyLanguageProfile {
    static final List<String> GROOVY_KEYWORDS = Collections.unmodifiableList([
        "def",
        "class",
        "trait",
        "enum",
        "interface",
    ])

    static String describe() {
        return "Groovy " + GroovySystem.version
    }

    static boolean isKeyword(String token) {
        return GROOVY_KEYWORDS.contains(token)
    }
}
