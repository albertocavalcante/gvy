// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.rename

/** One place the document spells the renamed name as a token the compiler reads as a name; UTF-16 `[start, end)`. */
internal data class NameSpan(val start: Int, val end: Int)

/**
 * A part of the source the AST attributes to one node, as UTF-16 offsets `[start, end)`, where the renamed name is
 * spelled at most once outside the smaller windows nested in it. [kind] and the 1-based [line] name it in a refusal.
 */
internal data class NameWindow(val start: Int, val end: Int, val kind: TypeUseKind, val line: Int) {
    val size: Int get() = end - start

    operator fun contains(span: NameSpan): Boolean = start <= span.start && span.end <= end
}

/** What a class rename may do with the document: edit exactly these names, or nothing, for a reason. */
internal sealed interface ClassUses {
    /** Every occurrence of the name is either one of [edits] or the name of something else. */
    data class Complete(val edits: List<NameSpan>) : ClassUses

    /** Some occurrence of the name is not proven to be either; the whole rename is refused. */
    data class Refused(val reason: String) : ClassUses
}

/**
 * The facts a class rename's completeness check accounts for, all in one document.
 *
 * @property occurrences every token that spells the name (from the lexer, never from a text search)
 * @property located the edits the reference scan and the constructor reader established, each on its own name
 * @property uses windows of the AST's use sites of the renamed class (parameter types, supertypes, ...)
 * @property others names proven to belong to something else: a method, a variable, a same-named class
 * @property otherUses windows of use sites of other classes spelled with the same simple name
 */
internal class NameAccount(
    val name: String,
    val occurrences: List<NameSpan>,
    val located: List<NameSpan>,
    val uses: List<NameWindow>,
    val others: List<NameSpan> = emptyList(),
    val otherUses: List<NameWindow> = emptyList(),
)

/**
 * Decides whether a class rename covers the document: an edit for every use of the class, each exactly once, and
 * nothing else. Each window of a use site is narrowed to the single occurrence it contains once the occurrences of
 * the smaller windows inside it are set aside (a type argument inside its type, a bound inside a type parameter);
 * a window holding two is refused, as which one is the use cannot be told. Any occurrence that ends up neither an
 * edit nor proven to be something else's name refuses the rename: that is what makes a partial edit impossible for
 * kinds of use no scan knows yet.
 */
internal object ClassUseCoverage {
    fun check(account: NameAccount, lineOf: (Int) -> Int): ClassUses {
        val occurrences = account.occurrences.toSet()
        val stray = account.located.firstOrNull { it !in occurrences }
        if (stray != null) return refused(account, "the edit at line ${lineOf(stray.start)} is not on the name in code")
        return when (val narrowed = narrow(account.uses, occurrences)) {
            is Narrowed.Ambiguous -> with(narrowed.window) {
                refused(account, "the ${kind.label} at line $line cannot be located exactly in the source")
            }
            is Narrowed.Uses -> classify(account, occurrences, account.located.toSet() + narrowed.spans, lineOf)
        }
    }

    /** The occurrence each use window holds, smallest window first, outside the ones smaller windows took. */
    private fun narrow(windows: List<NameWindow>, occurrences: Set<NameSpan>): Narrowed {
        val claimedBy = HashMap<NameSpan, Int>()
        for (window in windows.sortedWith(compareBy(NameWindow::size, NameWindow::start))) {
            val candidates = occurrences.filter { it in window && (claimedBy[it] ?: Int.MAX_VALUE) >= window.size }
            if (candidates.size > 1) return Narrowed.Ambiguous(window)
            candidates.singleOrNull()?.let { claimedBy.putIfAbsent(it, window.size) }
        }
        return Narrowed.Uses(claimedBy.keys)
    }

    private fun classify(
        account: NameAccount,
        occurrences: Set<NameSpan>,
        edits: Set<NameSpan>,
        lineOf: (Int) -> Int,
    ): ClassUses {
        val others = account.others.filterTo(mutableSetOf()) { it in occurrences }
        account.otherUses.forEach { window ->
            occurrences.filter { it in window && it !in edits }.singleOrNull()?.let(others::add)
        }
        val conflict = edits.firstOrNull { it in others }
        val unexplained = occurrences.sortedBy(NameSpan::start).firstOrNull { it !in edits && it !in others }
        return when {
            conflict != null -> refused(
                account,
                "the name at line ${lineOf(conflict.start)} is both a use of the class and another name",
            )
            unexplained != null -> refused(
                account,
                "a use at line ${lineOf(unexplained.start)} could not be classified",
            )
            else -> ClassUses.Complete(edits.sortedBy(NameSpan::start))
        }
    }

    private fun refused(account: NameAccount, why: String) = ClassUses.Refused("Cannot rename '${account.name}': $why")

    private sealed interface Narrowed {
        data class Uses(val spans: Set<NameSpan>) : Narrowed

        data class Ambiguous(val window: NameWindow) : Narrowed
    }
}
