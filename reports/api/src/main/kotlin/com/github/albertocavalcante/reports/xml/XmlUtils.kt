package com.github.albertocavalcante.reports.xml

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Utilities for secure XML parsing.
 *
 * Extracted from duplicated code in JaCoCo and Surefire parsers.
 */
object XmlUtils {

    /**
     * Create a secure DocumentBuilder with XXE (XML External Entity) protection.
     *
     * Disables DTD processing to prevent XXE attacks.
     *
     * @return A configured DocumentBuilder instance
     */
    fun createSecureDocumentBuilder(): DocumentBuilder {
        val dbFactory = DocumentBuilderFactory.newInstance()
        // Disable external entities for security (XXE protection)
        dbFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        return dbFactory.newDocumentBuilder()
    }
}
