package com.github.albertocavalcante.groovylsp.test

import com.github.albertocavalcante.groovylsp.version.GroovyVersion

fun parseGroovyVersion(raw: String): GroovyVersion =
    requireNotNull(GroovyVersion.parse(raw)) { "Failed to parse version: $raw" }
