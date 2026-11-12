// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.fmt

import org.openrewrite.ExecutionContext
import org.openrewrite.SourceFile
import org.openrewrite.groovy.GroovyParser
import org.openrewrite.groovy.format.AutoFormat
import org.openrewrite.internal.InMemoryLargeSourceSet
import org.openrewrite.internal.RecipeRunException
import java.util.Collections
import java.util.IdentityHashMap

/** The formatter's three external-engine phases; instances retain no operation-specific state. */
internal open class RewriteOperations {
    open fun parse(text: String, context: ExecutionContext): List<SourceFile> {
        // Enforce this check in the formatter before recipes instead of generating the SDK's Git-based diff.
        context.putMessage(ExecutionContext.REQUIRE_PRINT_EQUALS_INPUT, false)
        return GroovyParser.builder().build().parse(context, text).use { it.toList() }
    }

    open fun rewrite(sources: List<SourceFile>, context: ExecutionContext): SourceFile? =
        AutoFormat().run(InMemoryLargeSourceSet(sources), context).changeset.allResults
            .mapNotNull { it.after }.firstOrNull()

    open fun print(source: SourceFile): String = source.printAll()
}

/** OpenRewrite wraps visitor faults; keep its original failure observable at every formatter boundary. */
internal fun Throwable.originalRecipeFailure(): Throwable {
    if (this !is RecipeRunException) return this

    var failure = this
    val wrappers = mutableListOf<RecipeRunException>()
    val excluded = throwableIdentitySet()
    while (failure is RecipeRunException && excluded.add(failure)) {
        wrappers.add(failure)
        failure = failure.cause ?: break
    }
    val original = failure
    excluded.add(original)
    val independent = wrappers.asReversed().flatMap { it.suppressed.asList() }
        .filterNot { it.causeReferences(excluded) }
    // Foreign cause accessors run above, outside this short duplicate-check/append transaction.
    synchronized(original) {
        val retained = throwableIdentitySet().apply { addAll(original.suppressed) }
        independent.forEach { evidence ->
            if (retained.add(evidence)) original.addSuppressed(evidence)
        }
    }
    return original
}

/** Do not attach a wrapper or dependent cause chain back to its original failure. */
private fun Throwable.causeReferences(excluded: Set<Throwable>): Boolean {
    val visited = throwableIdentitySet()
    var current: Throwable? = this
    while (current != null && visited.add(current)) {
        if (current in excluded) return true
        current = current.cause
    }
    return false
}

private fun throwableIdentitySet(): MutableSet<Throwable> =
    Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
