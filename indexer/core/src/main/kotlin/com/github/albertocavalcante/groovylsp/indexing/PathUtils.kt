package com.github.albertocavalcante.groovylsp.indexing

import java.io.File

object PathUtils {
    /**
     * Standardizes file URIs by ensuring they start with 'file:///' and removing
     * trailing slashes added by File.toURI() for directories.
     */
    fun toCanonicalUri(path: String): String {
        // java.io.File.toURI() produces file:/... on some platforms.
        // LSP and our golden files expect file:///...
        val uri = File(path).toURI().toString()
        val tripleSlashUri = if (uri.startsWith("file:/") && !uri.startsWith("file:///")) {
            uri.replaceFirst("file:/", "file:///")
        } else {
            uri
        }
        // Remove trailing slash which is added for directories, UNLESS it's the root
        // (e.g. file:/// should remain file:///, file:///C:/ should remain file:///C:/)
        return if (File(path).parent == null) {
            tripleSlashUri
        } else {
            tripleSlashUri.removeSuffix("/")
        }
    }
}
