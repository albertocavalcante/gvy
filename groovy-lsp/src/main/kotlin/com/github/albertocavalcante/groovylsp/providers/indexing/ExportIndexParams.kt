package com.github.albertocavalcante.groovylsp.providers.indexing

import com.github.albertocavalcante.groovylsp.indexing.IndexFormat

data class ExportIndexParams(val format: IndexFormat, val outputPath: String)
