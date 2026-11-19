// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.groovyparser.internal

import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.ExceptionMessage
import org.codehaus.groovy.control.messages.Message
import org.codehaus.groovy.control.messages.SimpleMessage
import org.codehaus.groovy.control.messages.SyntaxErrorMessage

/** Groovy exposes syntax locators but keeps the owner of other messages protected. */
internal object CompilerMessageSource {
    private val simpleOwner = runCatching {
        SimpleMessage::class.java.getDeclaredField("owner").apply { isAccessible = true }
    }.getOrNull()
    private val exceptionOwner = runCatching {
        ExceptionMessage::class.java.getDeclaredField("owner").apply { isAccessible = true }
    }.getOrNull()

    fun locator(message: Message): String? = when (message) {
        is SyntaxErrorMessage -> message.cause.sourceLocator
        is SimpleMessage -> runCatching { (simpleOwner?.get(message) as? SourceUnit)?.name }.getOrNull()
        is ExceptionMessage -> runCatching { (exceptionOwner?.get(message) as? SourceUnit)?.name }.getOrNull()
        else -> null
    }
}
