// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.gls.providers.imports

/** Which packages a layout group takes. */
internal sealed interface PackagePattern {
    /** `*`: everything no more specific group takes. */
    data object Any : PackagePattern

    /** `javax.*`: the package and everything below it. */
    data class Prefix(val prefix: String) : PackagePattern
}

internal sealed interface LayoutEntry {
    /** Imports matching [pattern], static or not, sorted together. */
    data class Group(val pattern: PackagePattern, val isStatic: Boolean) : LayoutEntry

    /** One blank line between the non-empty groups on either side. */
    data object BlankLine : LayoutEntry
}

/** Where imports go and where blank lines separate them. */
internal data class ImportLayout(val entries: List<LayoutEntry>) {

    /**
     * The index of the group that takes [target]: the longest matching package prefix, else the catch-all `*`
     * of the same static-ness. Null when no group takes it; callers place such imports last.
     */
    fun groupIndexOf(target: ImportTarget): Int? {
        val isStatic = target is ImportTarget.StaticMember || target is ImportTarget.StaticStar
        val owner = ownerName(target)
        val matching = entries.withIndex().mapNotNull { (index, entry) ->
            val group = entry as? LayoutEntry.Group
            val specificity = group?.takeIf { it.isStatic == isStatic }?.let { specificity(it.pattern, owner) }
            specificity?.let { index to it }
        }
        return matching.maxByOrNull { (_, specificity) -> specificity }?.first
    }

    private fun specificity(pattern: PackagePattern, owner: String): Int? = when (pattern) {
        PackagePattern.Any -> 0
        is PackagePattern.Prefix -> if (owner.startsWith(pattern.prefix + ".")) pattern.prefix.length + 1 else null
    }

    /** The dotted name whose package prefix decides the group. A star import matches by its package. */
    private fun ownerName(target: ImportTarget): String = when (target) {
        is ImportTarget.Type -> target.qualifiedName
        is ImportTarget.Star -> target.packageName + "."
        is ImportTarget.StaticMember -> target.className
        is ImportTarget.StaticStar -> target.className
    }

    companion object {
        /** IntelliJ's default: everything else, then `javax` and `java`, then static imports. */
        val DEFAULT = ImportLayout(
            listOf(
                LayoutEntry.Group(PackagePattern.Any, isStatic = false),
                LayoutEntry.BlankLine,
                LayoutEntry.Group(PackagePattern.Prefix("javax"), isStatic = false),
                LayoutEntry.Group(PackagePattern.Prefix("java"), isStatic = false),
                LayoutEntry.BlankLine,
                LayoutEntry.Group(PackagePattern.Any, isStatic = true),
            ),
        )
    }
}
