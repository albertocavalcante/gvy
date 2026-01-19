package com.github.albertocavalcante.gvy.gls.test

import com.github.albertocavalcante.gvy.gls.version.GroovyVersion

fun parseGroovyVersion(raw: String): GroovyVersion =
    requireNotNull(GroovyVersion.parse(raw)) { "Failed to parse version: $raw" }
