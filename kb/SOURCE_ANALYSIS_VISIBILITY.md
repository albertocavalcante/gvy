<!-- SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Source membership and analysis visibility

Gradle exclusions must affect the files compiled and the sources used during recovery. An excluded open buffer must not
silently enter another file's compilation. At the same time, a test source can legitimately refer to a main source, and
a project dependency can provide sources before its output JAR exists.

Compilation membership and dependency source visibility therefore carry separate evidence. A completed diagnostic run
means the analysis finished; it does not establish that compilation, every provider, or source attribution succeeded.

## Captured source evidence

| State                      | Eager compilation inputs                                              | Source lookup                                                                                        |
| -------------------------- | --------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| Selected source set        | Observed compiler-task members, including task-level filters          | Own sources are visible; positive exclusions are denied; unknown dependency sources retain recovery. |
| Independent document       | The explicitly requested document                                     | Other workspace buffers and sources are excluded until membership is refreshed.                      |
| Unknown target attribution | Existing recovery inputs, filtered by any positive exclusion evidence | Unknown candidates retain recovery and the limitation remains visible.                               |

An independent document is a safe self-only compilation policy, not proof that the file existed when Gradle was
observed. Its membership remains unconfirmed until a refreshed compiler observation assigns it to a target. This
conservative notice also applies to explicitly opened excluded files: the producer does not enumerate the universe of
excluded files, so a later filesystem scan must not turn an unmodeled file into a claim of known exclusion.

Resource directories do not establish compiler-source membership. The producer reads the Java and Groovy compiler tasks'
source trees. Source-set enumeration completeness is an explicit producer fact, scoped to the observed build; included
builds, unmodeled compiler tasks, and failed enumeration prevent a completeness claim. An empty observed list without
that fact does not prove exclusion.

An immutable workspace model supplies membership, candidate classification and cache keys. Request-specific index views
use that captured model and decline results when it retires. The ordinary workspace symbol search remains global. Rename
and references retain their existing cross-workspace behavior; they must not be restricted to one source set merely
because compilation uses that source set's inputs.

## Status and Problems

The terminal diagnostic result retains the compiler outcome and provider failures. The optional document-analysis
notification is capability gated and includes the document session, version and run identity. New document sessions
reject delayed old-session results, including when a restarted server resets its run counter.

Compiler phase names belong in technical details. Actionable messages and informational Problems notices distinguish
limited compilation, provider failures and inferred source modeling. Rejected CodeNarc configurations carry their
configuration URI and, where available, the literal range. Per-source ownership deduplicates shared configuration
notices and removes obsolete contributions on correction, configuration replacement, close and reanalysis.

Standalone native engine sessions without a captured workspace keep their legacy, unknown source visibility. The
server's held-snapshot hover path carries its original workspace model instead of pairing an old parse with a newly read
model.

## Remaining model limits

These are explicit limits, not completed work. They must remain visible in the analysis details and delivery record.

| Limit and impact                                                                                   | Reason                                                                                                            | Responsible scope and next action                                                                      | Acceptance gate                                                                                                                                                                                |
| -------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Dependency source reachability can remain inferred, including unbuilt project JARs.                | Artifact paths and source membership do not establish which source target produced a selected dependency variant. | Build model and source navigation: add producer-owned component/variant-to-source-target evidence.     | Resolve unbuilt test-to-main and project dependencies using exact producer identities, preserve exclusions, and refuse ambiguous mappings without choosing the first target or guessing names. |
| The first file in an initially empty external task-source tree may need manual dependency refresh. | Current compiler-file enumeration does not reveal every empty external tree's configured origin.                  | Build model and watchers: report supported source origins or add explicit bounded watch configuration. | Creating the first external source updates membership automatically without recursively scanning an unrelated ancestor.                                                                        |
| External source filenames containing wildcard characters may need manual refresh.                  | A literal file path must not accidentally become a broad client glob.                                             | Source watchers: qualify literal matching for supported client capabilities.                           | Exact create/change/delete events for such names refresh only the intended source observation, with no broad parent scan.                                                                      |

Publishing already-retained provider failures does not change a provider's internal fault capture. CodeNarc SDK failure
propagation is tracked separately by [#1782](https://github.com/albertocavalcante/gvy/pull/1782); provider failure
publication follows [#456](https://github.com/albertocavalcante/gvy/issues/456).
