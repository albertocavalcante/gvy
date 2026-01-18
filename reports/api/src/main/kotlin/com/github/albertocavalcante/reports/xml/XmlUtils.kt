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
        val dbFactory = DocumentBuilderFactory.newInstance().apply {
            // Apply recommended security settings for XXE protection, following OWASP guidelines.
            setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        return dbFactory.newDocumentBuilder()
    }
}
