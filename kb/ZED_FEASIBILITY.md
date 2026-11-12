# Zed feasibility experiment

Status: the local-JAR prototype is implemented; the real-editor experiment is in progress. Owner and acceptance
criteria: [#1175](https://github.com/albertocavalcante/gvy/issues/1175).

## Candidate and boundary

The first adapter landed in [#1816](https://github.com/albertocavalcante/gvy/pull/1816) at
`b0d5a9aeaad9b4748d0b1469641f088be21bd79c`; its reviewed publication head was
`d0a0dd3ee26d8b2d31fcabfaa47a464aea0b0371`. The Rust source is unchanged between those commits.

The experiment on 2026-11-11 and 2026-11-12 uses Zed stable 1.28.1 on macOS Apple silicon, released `zed_extension_api`
0.7.0, Rust 1.96.1 and `wasm32-wasip2`. The selected external runtime is Homebrew OpenJDK 17.0.21.0.3. The server is the
clean JVM revision `95e53ab642e3def9055494b6ee2d16011a4727cb`, obtained from the candidate-server artifact of
[CI run 43170504837](https://github.com/albertocavalcante/gvy/actions/runs/43170504837). Its SHA-256 is
`1b3ae1179e86d5e0e6261f28e8ee5569776c31aa2f8ca5600793b09a973fac7e`. A newer main or downloaded release is a different
candidate.

The test uses plain Groovy files in a synthetic project with explicit Java and JAR settings. It installs neither a
Tree-sitter grammar nor the legacy Groovy extension. It does not validate Gradle, Jenkins, remote development, other
operating systems or registry publishing. See the [adapter procedure](../editors/zed/README.md) and
[copyable fixture](../editors/zed/fixtures/README.md) to repeat it. Keep real host paths and raw editor logs outside
Git; startup failures can include environment details.

## Observations

These are bounded observations from one environment, not a support matrix or performance benchmark.

| Check                     | Observed result                                                                                                                                                                                                                                                                                          |
| ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Grammar-free registration | The dev index registered Groovy with `grammar: null` and zero query files; the buffer selected Groovy.                                                                                                                                                                                                   |
| Native dev installation   | Zed's Install Dev Extension action compiled, wrote the WASM component and reloaded the extension in 11.56 seconds. This measures that rebuild only.                                                                                                                                                      |
| Trust                     | Restricted Mode did not start a JVM. Trusting only the synthetic project enabled startup and two-file indexing.                                                                                                                                                                                          |
| Definition                | Go to Definition on `Greeter` in Main opened the declaration in Greeter.                                                                                                                                                                                                                                 |
| Completion                | An unsaved `greeter.gre` offered the project member `greet(String)`. Accepting it inserted a call with a selectable argument placeholder.                                                                                                                                                                |
| Hover                     | The editor displayed the server's method card. This does not establish hover correctness at every token.                                                                                                                                                                                                 |
| Diagnostics               | An incomplete unsaved expression produced a diagnostic; repairing the expression changed/cleared it. CodeNarc diagnostics also appeared.                                                                                                                                                                 |
| Semantic highlighting     | Keywords and member names were colored with semantic-only highlighting enabled. Full lexical coverage is not established.                                                                                                                                                                                |
| Rebuild recovery          | Rebuilding stopped the JVM. Restarting on existing buffers alone did not reconnect in this run; closing only the project window and reopening the folder and Main started a fresh JVM and restored highlighting.                                                                                         |
| Shutdown                  | The prior JVM exited after the fixture window closed; reopening created a different process.                                                                                                                                                                                                             |
| Argument boundaries       | Java and JAR paths containing spaces, plus a Unicode JAR filename, started successfully. A JVM property was read back as the exact value `a b`, confirming that it remained one argument.                                                                                                                |
| Project isolation         | Two simultaneously open projects had distinct JVMs and literal JVM property values. The second project offered its own `secondOnly()` member instead of the first project's `greet(String)`.                                                                                                             |
| Live configuration        | Toggling `groovy.codenarc.enabled` in the second project and editing an open document produced CodeNarc diagnostics while its JVM PID remained unchanged. This verifies that setting, not universal live reconfiguration.                                                                                |
| LSP folding               | The protocol trace contained `textDocument/foldingRange` with a nonempty range, and the class body collapsed in the editor.                                                                                                                                                                              |
| LSP outline               | In the standalone fixture outside the Git checkout, the panel displayed the class, method and parameter with `document_symbols: "on"` and no grammar. An earlier ignored scratch directory did not issue a document-symbol request in the captured interval; it is not evidence of a grammar limitation. |
| Invalid explicit Java     | The initial adapter surfaced a verbose host spawn failure. The preflight candidate instead reported the exact `launch.java_path` setting and Java 17 guidance before spawning, without fallback.                                                                                                         |
| Missing JAR               | Zed displayed Java's `Unable to access jarfile` message. Restoring the valid JAR and reopening recovered startup.                                                                                                                                                                                        |

The next language-configuration experiment adds static braces, brackets and parentheses, following
[Zed 1.28.1's language configuration](https://github.com/zed-industries/zed/blob/v1.28.1/extensions/glsl/languages/glsl/config.toml).
Before that change, a physically typed opening brace inserted no closer and Return added no indentation. After the
change, the editor inserted the closer, skipped it when typed, and removed both delimiters with Backspace in an empty
pair. Lists and method-call parentheses retained one closing delimiter. Return between braces added a separate closing
line, but no body indentation, including with explicit `auto_indent: true`, `autoclose_brackets: true` and
`tab_size: 4`. The same brace/newline behavior worked after reopening with `language_servers: []`, with no fixture JVM
running; the outline and semantic colors were then absent. Braces also closed inside a line comment and a double-quoted
string. These are text-based delimiter operations, not syntax-aware editing. No quote pairs, scope exclusions or
indentation regex were added.

## Remaining gates and ownership

| Gap                              | Impact and reason                                                                                                                                                                        | Responsible scope, next action and acceptance                                                                                                                                                |
| -------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Runtime version                  | Executable lookup checks availability, not the Java runtime version.                                                                                                                     | Adapter maintainers: retain manual Java 17+ verification until a bounded host probe is available; do not introduce an unbounded subprocess to remove that check.                             |
| Host error redaction             | A host spawn failure can expose the inherited environment in Zed error details. Lookup reduces predictable failures but cannot remove the lookup/spawn race or cover every host error.   | Zed maintainers: prepare a minimal, sanitized report for human review; acceptance is a concise host error without environment values. No upstream report was submitted by this experiment.   |
| JAR failures                     | WASM cannot inspect arbitrary host files directly.                                                                                                                                       | Adapter experiment: observe missing/corrupt JAR startup messages and document recovery; do not claim preflight validation.                                                                   |
| Remaining structural editing     | Static delimiter operations work offline, but body indentation and syntax-aware exclusions remain absent; outline/folding do not establish syntax selection or incomplete-code recovery. | Adapter experiment in #1175: retain the explicit editing checks above, evaluate a syntax backend from these gaps, and verify Groovy string forms and incomplete edits before broader claims. |
| Lexical highlighting and Unicode | Semantic categories in the legend do not prove emitted token coverage or correct coordinates.                                                                                            | Shared server work in #121/#1190: retain explicit fixtures and observed gaps; choose any later syntax-backend scope from evidence.                                                           |
| Publishing                       | Registry requirements differ from local dev loading.                                                                                                                                     | Extension maintainers: resolve grammar policy, public identity, license and overlap before a human-owned submission. This experiment submits nothing upstream.                               |

A successful connection does not close these gates or authorize a parser replacement. Continue the experiment before
choosing a syntax dependency or claiming a distributable extension.
