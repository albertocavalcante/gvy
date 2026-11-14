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

## Initial native browser limits

The homepage was initially inspected in a native browser at widths 360, 768, and 1440 pixels in dark appearance.
DarkReader was active, so those observations do not validate the site's native light theme. Full responsive coverage,
search, keyboard operation, and accessibility remain pre-merge review gates.

An automated accessibility audit was **not run in that attempt**: the native browser automation session timed out and
reset, and the browser host was unavailable. A temporary loopback server for npm `axe-core` 4.14.0 was prepared but not
used; its process and private installation were removed. No automated accessibility pass is claimed. Deployment remains
blocked until the applicable pre-merge gates are complete.

## Headless Playwright feasibility (2026-11-14)

After the owner explicitly requested Playwright testing, a disposable probe tested the production build from
`5962d30aabff50e188df0eff5e5a699c31eb01cd`. It used Playwright 1.65.1, `@axe-core/playwright` 4.14.0 and isolated,
extension-free system Chrome 157.0.8103.79, serving static files beneath `/gvy/`. All 18 visits across home,
installation and settings at 360/768/1440 px in light/dark returned 200 and matched the selected theme.

This was a finding-producing audit, not a pass. At 360 px the homepage's document width was 402 px in both themes. Axe
reported light-theme contrast failures in the homepage code/notice and installation guide, keyboard-focus failures for
scrolling tables, and a desktop search button label/name mismatch. The
[testing and platform plan](DOCUMENTATION_WEBSITE_TESTING_AND_PLATFORM_PLAN.md) records B01–B04 with owners and
completion gates. No browser defect was fixed by the planning follow-up.

A separate corrected search run found the expected guide for installation, Java, Jenkins, Spock and troubleshooting.
Escape closed the dialog and returned focus to the search button. With JavaScript disabled, the homepage retained its
headline, installation link, Homebrew command and stable bundled-server warning. Six homepage screenshots were retained;
360/light and 1440/dark were inspected. Full visual and keyboard review remains open.

An initial temporary-server routing error caused a false homepage 404; the corrected matrix supersedes it. The first
search attempt used the wrong `searchbox` role; the successful run used Pagefind's text input. The private evidence
retains both the completed matrix report and the corrected search report. This does not constitute a committed test
suite, pinned-browser CI coverage, Firefox/WebKit testing, assistive-technology review or accessibility certification.
Owned probe packages, cache, server and browser are removed after preserving evidence.

## Disposable MDX/Svelte composition (2026-11-14)

An isolated project built Astro 7.4.12, MDX 8.0.2, `@astrojs/svelte` 9.1.6, Svelte 5.57.3 and TypeScript 5.9.3 on Node
26.13.1 with base `/gvy/`. MDX imported a static Astro notice and a Svelte `client:visible` settings filter. The build
included the notice and both example rows. In headless Chrome, JavaScript-disabled output retained both rows and omitted
the inert filter; with JavaScript enabled, hydration revealed the input and filtering for beta removed alpha.

This verifies a minimal composition, not a production dependency change, performance benchmark or renderer migration.
The temporary project, cache, server and browser were removed; concise results and illustrative sources were retained
privately with the task evidence.

## Automated website checks

The final local frozen install uses Bun 1.3.14, Node 26.13.1, Astro 7.4.12, Starlight 0.42.5, and pinned dprint 0.64.3.
Sixteen regression tests cover title/heading conversion, links, admonitions, literal code fences, source/edit links,
unsupported input, ownership-aware output cleanup and overwrite refusal, and built route/fragment/asset validation.
Astro check reports zero errors, warnings, and hints. The production build generates 20 HTML pages and validates all
internal links, assets, required legacy fragments, and the `/gvy/` base path. The three warning groups in #1888 no
longer appear. This is a local build result; it does not complete the pending browser or public-deployment gates.

The repository REUSE/header check, all 69 license regression tests, actionlint for the changed workflow, formatting, and
`git diff --check` also pass. The existing workflow action tags were checked against their pinned commit SHAs. The PR
records its exact publication head, independent review findings, and hosted CI results.

## Hosted security feedback and correction

The initial publication head `8805e9197d0f30fbbadc877a7632d38a75da08bb` passed
[the hosted documentation build](https://github.com/albertocavalcante/gvy/actions/runs/43597225883). CodeQL then
identified a leaf-file check/read race in generator preflight (alert #373). Ownership checks now use no-follow file
handles, and generation rechecks ownership and writes through the same handle; new files are seeded by an atomic hard
link from a private staging file, without replacing an existing destination. A real link-failure regression verifies
staging cleanup and preservation of existing content. The generator assumes a trusted checkout and a single writer with
no concurrent directory replacement.

Dependency review also rejected Astro 7.3.6 for [GHSA-r3h5-4585-7x3w](https://github.com/advisories/GHSA-r3h5-4585-7x3w)
and [GHSA-mh32-jxcr-mh9f](https://github.com/advisories/GHSA-mh32-jxcr-mh9f). Their first patched Astro versions are
7.4.7 and 7.4.5 respectively; the dependency is now pinned to 7.4.12. The correction requires passing CodeQL and
dependency review on its actual publication head; the PR retains those results. No alert dismissal or diagnostic
suppression is used.
