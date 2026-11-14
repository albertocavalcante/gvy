<!-- SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Ordinary Groovy debugger: implementation gates

A source debugger needs evidence from a real Groovy JVM before gvy chooses an engine or advertises editor support. The
initial control is `:tests:test --tests '*GroovyAttachProbeTest'`: a Groovy 4.0.33 script runs in a separate, bounded
JVM and listens for a loopback JDI attachment. The test requests a breakpoint before a file write, checks its source
name, line, stack frame and local value, resumes, checks that the retained JDI frame is invalid, and detaches while the
target continues. It releases and cleans up the target even if the assertion fails. The fixture uses the version catalog
pin; this is one compiler version and one script shape, not a support matrix.

The test matches a source **filename** and line; that is not content, build or class-loader identity. Its stale-frame
check establishes JDI behavior, not an adapter's session-local reference policy. It uses JDI directly as a control and
is not a DAP server or evidence of editor interoperability.

| Gate                          | Owner and next action                                                                                | Acceptance evidence                                                                                                                                                               |
| ----------------------------- | ---------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Control experiment            | gvy debugger: run the focused test on the supported JDK and CI, recording head and results           | A real target stops before the effect, exposes the expected value, resumes and survives attach disconnect; failure cleanup leaves no child.                                       |
| Provider comparison           | gvy debugger: run a standalone java-debug provider composition against the same fixture              | Record pinned dependencies, providers, isolation, source and variable behavior, lifecycle, license and failure evidence. Compare with the JDI control before selecting an engine. |
| Source and state identity     | Selected engine: model source content/build and class-loader identity, and session-local stop epochs | Wrong-source and duplicate-loader cases are rejected; references from a resumed stop fail explicitly.                                                                             |
| Ordinary attach milestone     | Selected engine: add breakpoint lifecycle, step, stack and bounded read-only variables               | Pending and unresolvable breakpoints are honest; independent DAP client and actual editor exercise the real fixture and cleanup.                                                  |
| Language coverage             | gvy debugger: qualify closures, captured values, static compilation and generated frames             | Pin compiler versions and publish individual passing cases and limitations.                                                                                                       |
| Build and Jenkins integration | Build integration and Jenkins backend owners: resolve real test workers; design CPS separately       | Correct worker identity and teardown are proven; CPS claims require their own runtime evidence.                                                                                   |

Do not infer Groovy evaluation semantics from a Java evaluator. No conditional breakpoint, arbitrary evaluation, hot
replacement or Jenkins capability is promised by this control. A future expression evaluator needs a separate execution
and safety contract.
