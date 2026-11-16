// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.dsl.completion

import org.eclipse.lsp4j.CompletionItem

/** Both forms originate at the producer; arbitrary snippet text is never parsed. */
data class InsertionTemplate(val snippet: String, val plainText: String)

/** Keeps server-only insertion data separate from CompletionItem.data, which carries method identity. */
class TemplateCompletionItem(@Transient val insertionTemplate: InsertionTemplate?) : CompletionItem()
