# Documentation website validation

## Standalone language-server probe (2026-11-14)

A bounded Node client spoke LSP over stdio to the pre-existing source-install JAR. Its adjacent `version.json`
identifies a clean server build `0.5.4-dev+g7caa1a5`, source commit `7caa1a59c2e619a23ea163cebcfa2348749ec5e1`; the JAR
SHA-256 was `672890dfc4197b0d93f1338b03d20ba247f25cd5f0128291aba562329a7e98b5`. This is a development server, not the
older server bundled in the stable v0.5.4 VSIX. The documentation source baseline is
`f101e5bf5a5a2fae42d0cfcadba838bcfc6e3d14`.

The client launched `java -Xmx512m -jar <gls.jar>` with `JDK_JAVA_OPTIONS` cleared. Java was Homebrew OpenJDK
`21.0.13.0.3`; `/usr/libexec/java_home -v 17` did not find Java 17, so this run does not validate the minimum runtime.
It used a temporary workspace without a build file, initialized LSP, sent `initialized` and `didOpen`, then requested:

- Hover on `greet` in the quickstart's `Hello.groovy`: the response identified `public String greet(String name)` and
  owner `Greeter`.
- Completion at the start of that method call: 58 items were returned, including `Greeter`; this does not establish
  accurate member completion.
- Full-document formatting on the same file: one edit expanded the method body onto separate lines. The client inspected
  the edit; it did not apply it in an editor.
- Completion inside the documented Jenkinsfile's `steps` block: 59 items, including `echo`, `checkout`, and
  `archiveArtifacts`. The server found Jenkins core `2.479.3` in the machine's local Maven repository and injected it;
  this result is not proof of a clean-machine or offline experience.

The client then requested shutdown, sent exit, and observed exit code 0. Requests had 15-second timeouts. The temporary
workspace, probe script, and probe-specific Jenkins stub and CodeNarc temporary directories were removed. The
source-install artifact was not modified. No Gradle build ran.

The stderr tail showed CodeNarc ruleset loading, a missing optional `codenarc.properties`, local Jenkins core discovery,
and normal shutdown. Only the tail was retained in the probe output; this is not a complete warning audit. Notifications
and diagnostics were not evaluated systematically. This check does not validate VSIX installation, editor activation, UI
behavior, Java 17, project dependency resolution, or private repositories. Those remain separate acceptance checks.

## Spock LSP probe (2026-11-14)

The same `7caa1a59c2e619a23ea163cebcfa2348749ec5e1` server JAR and Java 21 setup were used in a new temporary workspace.
The workspace contained the exact `ExampleSpec.groovy` from the [Spock guide](../docs/user/spock.md), but no build file
or Spock dependency. After initialize, initialized and didOpen, the server reported a degraded compile with
`spock.lang.Specification` stubbed as unresolved.

- Hover at line 2, character 27 returned `public class ExampleSpec extends Specification` and listed `addition works()`.
- Completion at line 4, character 8 returned 65 items; its first eight labels were `given:`, `setup:`, `when:`, `then:`,
  `expect:`, `where:`, `cleanup:`, and `and:`.
- Code lens returned six entries: Run All, Debug All and Coverage for `ExampleSpec`, and Run, Debug and Coverage for
  `addition works`; the individual test commands identified framework `SPOCK`. No command was invoked and no test was
  executed.

Shutdown and exit succeeded with exit code 0. The workspace and probe-specific CodeNarc temporary directory were
removed. Results demonstrate block-aware suggestions and test discovery for this example in degraded analysis, not
dependency resolution, editor rendering, or execution. As above, stderr was sampled rather than audited in full, and
Java 17 and the stable VSIX were not tested.

## Stable VSIX isolated startup (2026-11-14)

Downloaded `gvy-0.5.4.vsix` from the v0.5.4 GitHub release with `gh`; SHA-256 was
`128a9f76b8595611196890b4305702b8005335f64b75f1f83e24f6172196a93b`. VS Code CLI 1.146.0 (arm64, commit
`69047191fc427f2f92828279aae1d90d8eeda7ab`) successfully installed it into a fresh, separate extensions directory and
launched a separate user-data profile with the quickstart `Hello.groovy` open. The installed server's `version` command
returned `gls version 0.4.8-SNAPSHOT`, confirming the stable VSIX limitation. This differs from the development JAR
tested above.

The isolated profile selected Homebrew OpenJDK 21.0.13.0.3 and configured `groovy.server.vmargs` to `[-Xmx512m]`; the
observed server process used that Java and heap argument. Its log reported that LSP initialized and that `Hello.groovy`
opened, then reported no build tool and zero dependency JARs in the minimal workspace. The client log labels server
stderr lines as errors even when the server's own line says INFO. The CLI also emitted Node DEP0169 `url.parse()`
deprecation warnings; the non-Git workspace produced agent-host Git discovery warnings. This check establishes
installation and startup, not a complete UI, diagnostics, or feature test. Workspace trust was disabled in this
disposable profile for this local example, and update checks were disabled. The existing editor profile and extension
directory were not modified.

A UI check in this isolated window opened hover at line 2, column 13: VS Code rendered
`public String greet(String name)`, return type `String`, and owner `Greeter`. CodeNarc displayed
`ClassStartsWithBlankLine`. The status bar continued to show **Groovy Starting** despite the working hover; do not use
that status alone as evidence of readiness. The isolated Code process and its remaining server and crash handler were
stopped by their verified PIDs, and the temporary profile, workspace, extension and download were removed after the
check.

## Website browser and accessibility limits

The homepage was inspected in a native browser at widths 360, 768, and 1440 pixels in dark appearance. DarkReader was
active, so those observations do not validate the site's native light theme. Full responsive coverage, search, keyboard
operation, and accessibility remain pre-merge review gates.

An automated accessibility audit was **not run**: the native browser automation session timed out and reset, and the
browser host was unavailable. A temporary loopback server for npm `axe-core` 4.14.0 was prepared but not used; its
process and private installation were removed. No automated accessibility pass is claimed. Deployment remains blocked
until the applicable pre-merge gates are complete.

## Automated website checks

The final local frozen install uses Bun 1.3.14, Node 26.13.1, Astro 7.3.6, Starlight 0.42.5, and pinned dprint 0.64.3.
Thirteen regression tests cover title/heading conversion, links, admonitions, literal code fences, source/edit links,
unsupported input, ownership-aware output cleanup and overwrite refusal, and built route/fragment/asset validation.
Astro check reports zero errors, warnings, and hints. The production build generates 20 HTML pages and validates all
internal links, assets, required legacy fragments, and the `/gvy/` base path. The three warning groups in #1888 no
longer appear. This is a local build result; it does not complete the pending browser or public-deployment gates.

The repository REUSE/header check, all 69 license regression tests, actionlint for the changed workflow, formatting, and
`git diff --check` also pass. The existing workflow action tags were checked against their pinned commit SHAs. The PR
records its exact publication head, independent review findings, and hosted CI results.
