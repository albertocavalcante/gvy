# Native launcher dogfood: CLI agents and Windows

Date: 2026-11-11. Scope: the Kotlin/Native entry point from #1769, real JVM CLI/LSP workloads, and native Windows x64.
The [build guide](../launcher/README.md) is canonical for commands. Distribution design remains in
[the CLI/runtime study](CLI_DISTRIBUTION_RESEARCH.md) and [#1741](https://github.com/albertocavalcante/gvy/issues/1741).

## Findings and changes

| Observation                                                                    | Result and evidence                                                                                                                                                                                                                                                        | Remaining scope                                                                                                                                                                            |
| ------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `check --format sarif` emitted `CodeNarc completed` before its JSON            | Reproduced against the packaged JAR. A real analyzer test failed before the fix; a per-instance Groovy dispatch adapter sends the embedded runner's summary to stderr. It leaves process streams and the global runner-metaclass mapping unchanged.                        | [#1543](https://github.com/albertocavalcante/gvy/issues/1543) stays open for broader Logback-status and formatting-output acceptance.                                                      |
| A Kotlin subclass is not a safe CodeNarc output adapter                        | A reduced dependency-only probe failed when inherited Groovy closures dispatched a private runner method on the subclass. The final adapter keeps the original runner class. Pinned CodeNarc 3.7.0 and released 4.0.0 source both contain the unconditional summary print. | Analysis owners should recheck a supported output API before removing the adapter; retain the real-runner regression. No upstream submission was made.                                     |
| POSIX execution cannot be reused for Windows                                   | Windows now uses wide arguments/environment/paths, explicit `java.exe` selection, C-runtime-compatible quoting, an inherited-stdio allowlist, and full DWORD exit forwarding.                                                                                              | Windows has a parent/child lifecycle, not POSIX `exec` semantics.                                                                                                                          |
| Inherited `IGNORE_CTRL_C` prevented console cancellation                       | A private-console test reported ready; the host generated Ctrl+C, but the child did not observe it or exit. Clearing the inherited flag before Java creation changed that test from timeout 110 to success 73.                                                             | The supervisor still waits for the child, without forwarding the same console event twice.                                                                                                 |
| Killing an ordinary Windows wrapper can orphan its child                       | Java is assigned to a noninheritable, kill-on-close job atomically using `PROC_THREAD_ATTRIBUTE_JOB_LIST`. Native process tests verify Java/grandchild termination while an unrelated control process survives.                                                            | Newly started project-tool descendants belong to that job; existing processes remain outside it.                                                                                           |
| Short extended Java-home paths and hidden launch behavior were suspected risks | Dedicated native probes passed against the initial Windows binary. They are retained as regressions; these were not reproduced production bugs. Windows separators and long-path handling are explicit for portability.                                                    | Platform coverage is measured on actual hosts and CI, rather than inferred from source alone.                                                                                              |
| A Unicode JAR installation failed on Windows                                   | Reproduced with direct Zulu 21.0.12.1 as well as the native launcher, OS build 10.0.26320, system ACP 1252. ASCII paths with spaces work. A UTF-8 argument file did not fix startup.                                                                                       | [#1817](https://github.com/albertocavalcante/gvy/issues/1817) owns runtime investigation and a future bundled-runtime fix. Other JDK candidates and minimum Java 17 are separate evidence. |

On the native Windows x64 host, the final sequential process/workload run had 16 tests: 15 passed, zero failed/skipped,
and one executed TODO for the exact #1817 Java-runtime condition. Four native unit tests, release linking and module
lint also passed. This local run used Zulu Java 21; Java 17 and the other native platforms remain explicit CI checks.

The adapter's five focused analyzer tests and module lint pass. Packaged CLI dogfood parses good/error SARIF (error
status 1), verifies non-destructive formatting and reads a usable LSIF export containing a spaced filename. The native
process suite separately validates launcher transport, and the opened-document LSP probe requires an actual document
symbol response before shutdown. Tests bound process runtime and clean only their own fixtures.

The Windows native unit quoting test was first run with an identity quoting implementation: four tests ran and the
quoting test failed. Correct escaping then passed. Early process-tree assertions passed, but synchronous fixture cleanup
crashed Node; awaiting the owned control process's close fixed that harness failure. This was not attributed to the
launcher.

## What this means for the bundled runtime

The Windows result gives the bundled/BYO split a concrete compatibility purpose. Bazel's
[pinned minimized-JDK builder](https://bazel.googlesource.com/bazel/+/901cf95651c88ef796ebee17b36885c700c52633/src/minimize_jdk.sh#180)
patches its **owned** Windows `java.exe` manifest with `activeCodePage=UTF-8`; Microsoft's
[UTF-8 process documentation](https://learn.microsoft.com/en-us/windows/apps/design/globalizing/use-utf8-code-page)
describes that mechanism. This is a candidate to validate for a gvy-owned runtime, not proof that gvy already supports
it or authority to change an installed JDK. Signatures, licensing, checksums and runtime update ownership need review.

Keep the next milestones separate:

1. Stabilize the CLI contract and supported native process boundaries, including useful machine-readable output.
2. Measure the actual runtime module closure and CLI/LSP parity. Groovy/reflection/service-loading needs runtime tests
   beyond a static `jdeps` result. Exercise Windows Unicode with the candidate runtime explicitly.
3. Publish independently tested bundled and BYO-JDK distributions, with size/startup measurements and update ownership.
4. Evaluate a single downloadable executable against an ordinary archive. Extraction must handle concurrent startup,
   failed/interrupted extraction, tampering, offline runs and upgrades before becoming the default.

Kotlin/Native remains a useful real project for learning process APIs, native source sets and cross-platform tests. Its
first compiler/toolchain download and host-specific linking are real maintenance costs. The launcher remains a
replaceable boundary; no Groovy semantics, CLI parser or MCP implementation was moved into native code. No native
release artifact, bundled/minimal JVM, single-file cache, Intel Mac or Linux Arm64 support is claimed by this slice.

These ordinary syntax-error cases do not resolve the broader incomplete-analysis contract in
[#1710](https://github.com/albertocavalcante/gvy/issues/1710). CLI analysis maintainers own explicit incomplete/error
results and fail-closed status tests before a successful check can establish complete requested analysis.

Build diagnostics remain visible and owned under [#1603](https://github.com/albertocavalcante/gvy/issues/1603). Local
server staging also exposed warnings in unchanged JVM tests; functional success does not mean those are resolved.
