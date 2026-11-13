<!-- SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Hosted test case map provenance

`hosted-child-test-cases.json` is an authored, literal expected inventory for the frozen source tree
7f56c7f346114b905ed907d9eeb46ded4dc6bdf5. For each of the 12 classes selected by the worker, the map uses that file's
package and every `@Test` function name followed by `()`, the JUnit XML spelling. Each selected source was checked for
`@ParameterizedTest`, `@RepeatedTest`, `@TestFactory`, `@Nested`, and `@TestTemplate`; none occur. The extraction
asserted each `@Test` has one matched function and wrote sorted unique class/case identities, rather than reading the
candidate run's output to decide what should pass. The literal file is hashed and pinned by the worker. Counts by class:
visibility 1, RequestWait 10, XfailOutcome 31, collector 10, observer 1, outcome JSON 6, named execution 25, named
recorder 12, agent scenario 9, PackagedLaunch 32, PackagedSessionFactory 26, SessionResources 3. Review the full literal
file and these source declarations; if any selected source changes, regenerate and independently review the map.

For a consistency check only, the ten prior v11 GREEN XML classes from the retained original artifact were compared:
none had an XML-only case absent from the source-derived map. AgentOutcomeJson gained one and NamedObservationExecution
gained seven cases; the other eight prior classes were unchanged. The recorder and agent-scenario classes are newly
selected. This comparison does not turn historical v11 execution into proof of the current candidate.

The formatter follow-up preserves all 166 names. Hosted run 43350524827 retained the original formatter patch; Root
reviewed the two manual line wraps and an independent reviewer inspected all 23 formatter paths.

The second hosted formatter run 43352200128 also failed before tests; its one-file formatter output and subsequent full
long-line sweep preserve the 166 source-owned test names.

The third hosted formatter run 43354801593 failed before tests. Its test-only follow-up flattens two engine setup
blocks; Root and an independent reviewer confirmed the existing 166 test names, values, order, and assertions remain
intact.

Hosted run 43359743691 passed the formatter but stopped before tests when it changed two files. Root and an independent
reviewer inspected the exact formatter output; the 166 source-owned test names remain unchanged.
