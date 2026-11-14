<!-- SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# Test the documentation and preserve a path beyond Starlight

Status: proposed implementation sequence, researched and probed on 2026-11-14. Owner: website maintainers. This extends
the [approved revamp](DOCUMENTATION_WEBSITE_REVAMP.md) and its [validation record](DOCUMENTATION_WEBSITE_VALIDATION.md).
The revamp merged in [PR #2004](https://github.com/albertocavalcante/gvy/pull/2004); the owner authorized the remaining
acceptance work in follow-up PRs. Track browser work in [#2020](https://github.com/albertocavalcante/gvy/issues/2020),
workflow evidence in [#2021](https://github.com/albertocavalcante/gvy/issues/2021), and component/renderer work in
[#2022](https://github.com/albertocavalcante/gvy/issues/2022).

Make the documentation's behavior measurable before changing its renderer. Keep Starlight for the current increment;
evaluate a custom Astro shell first and SvelteKit with mdsvex second. This is a recommendation for experiments, not a
decision to remain on Starlight or a framework migration. The first follow-up now implements a Chromium regression suite
and B01–B04 fixes. Production Svelte integration and renderer migration remain separate experiments.

## First follow-up implementation

[#2020](https://github.com/albertocavalcante/gvy/issues/2020) now has a production-artifact Playwright harness with
pinned Playwright 1.65.1 and axe integration 4.14.0. It checks mapped routes/legacy anchors, 404, search and focus,
no-JavaScript content, actual keyboard scrolling, runtime/assets, overflow and axe findings across six Chromium
width/theme projects. Tests reproduced the deployed defects; an initial fixed local run passed 22 tests with the
explicit CI-only clipboard assertion skipped. Two focused Mac/Linux shortcut cases were added after hosted platform
feedback; the publication-head CI result is recorded in the follow-up PR. CI is configured to use bundled Chromium and
upload reports and six homepage screenshots.

The harness and supported theme adapters address B01–B04. This does not complete reviewed Linux visual baselines,
Firefox/WebKit, full keyboard/zoom/screen-reader review or the separate workflow matrix. Keep those remaining gates in
#2020 and #2021. Component and migration work remains in #2022.

## Evidence already collected

The exact production-build input was `5962d30aabff50e188df0eff5e5a699c31eb01cd`: Astro 7.4.12, Starlight 0.42.5, Bun
1.3.14 and Node 26.13.1. A disposable Playwright 1.65.1 / axe-core Playwright 4.14.0 probe served built files beneath
`/gvy/` and used an isolated, extension-free, headless system Chrome 157.0.8103.79. This establishes feasibility, not a
reproducible CI baseline.

| Probe                                                              | Observed result                                                                                    |
| ------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------- |
| Home, installation and settings, at 360/768/1440 px in both themes | All 18 visits returned 200; theme selection matched; four finding families remained at probe time  |
| Production search                                                  | Installation, Java, Jenkins, Spock and troubleshooting each found the expected guide               |
| Search keyboard dismissal                                          | Escape closed the dialog and returned focus to the search button                                   |
| Homepage without JavaScript at 360 px                              | Headline, installation link, Homebrew command and stable bundled-server warning remained available |
| Screenshot inspection                                              | Home at 360/light and 1440/dark inspected; six screenshots retained, full visual approval pending  |

The first temporary server returned a false homepage 404; the corrected 18-visit run supersedes that result. A search
probe initially assumed a `searchbox`; Pagefind uses a text input. The corrected role/placeholder locator passed all
five queries. Neither exploratory error is a site defect.

### Findings to reproduce before fixing

These were acceptance blockers in the initial probe, owned by website maintainers and subsequently deferred to #2020.
The first follow-up now addresses them with reproductions and fixes; CI and independent review still verify each
publication head. Do not approve known-bad screenshots as baselines or suppress axe rules to obtain a pass.

| ID  | Impact and observation                                                                                                 | Next action and completion gate                                                                                                                                  |
| --- | ---------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| B01 | Homepage is 402 px wide at a 360 px viewport in both themes                                                            | Find the overflowing element; document width must fit the viewport at all three widths, with long code/tables scrolling inside their own containers              |
| B02 | Light-theme contrast fails in the homepage example and release notice, and the installation guide's linked inline code | Correct foreground/background tokens; repeat axe and inspect syntax, links, code and focus in both themes; normal text meets 4.5:1                               |
| B03 | The installation guide’s scrolling code block and settings tables are not keyboard-focusable                           | Make overflowing regions keyboard-operable with meaningful names and visible focus; verify both code and table scrolling and reading order manually and with axe |
| B04 | Desktop search button has a visible-label/accessibility-name mismatch                                                  | Inspect its shortcut/name markup; ensure the accessible name includes the visible label and test activation, dialog focus and focus restoration                  |

The audit used WCAG 2 A/AA and 2.1 A/AA axe tags. It does not establish compliance, full keyboard coverage,
screen-reader behavior, Firefox or WebKit support. Browser tests also do not verify Groovy feature claims or
installation commands.

## Playwright implementation plan

Add exact development dependency pins for `@playwright/test` and `@axe-core/playwright` with the Bun lockfile. Use the
matching Playwright browser binaries in CI; the system Chrome used for this spike is not the permanent baseline.
[Playwright browser management](https://playwright.dev/docs/browsers) describes that version relationship.

Suggested files in `docs/website/`:

```text
playwright.config.ts
tests/e2e/routes.spec.ts
tests/e2e/journeys.spec.ts
tests/e2e/accessibility.spec.ts
tests/e2e/visual.spec.ts
tests/e2e/contracts.ts
```

Reuse `content-map.mjs` and `route-inventory.json` to enumerate current routes and legacy fragments. Keep semantic
expectations in `contracts.ts`: key headings, five search query/target pairs, canonical edit destinations and essential
release notices. Avoid copying generated page HTML into fixtures or tying tests to Starlight's internal CSS classes.

| Coverage               | Assertions and scope                                                                                                                                                                                     |
| ---------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Routes and assets      | Visit every generated route once; verify title, one main heading, canonical/edit destinations, old fragments, no unexpected page errors or failed local assets, and `/gvy/` links                        |
| First project          | Follow home → installation → quickstart → Java/troubleshooting; assert useful headings and the stable limitation; test mobile navigation and skip link by keyboard                                       |
| Search                 | Run the five recorded queries against the built Pagefind index; open a result, dismiss with Escape, verify initial dialog focus and return focus                                                         |
| Layout and theme       | Home, installation and settings at 360/768/1440 in light/dark; no document overflow; verify theme switching and persistence, readable code, keyboard scrolling of code/tables and visible focus          |
| Accessibility          | Run axe on representative pages and open navigation/search states; record every violation, block serious/critical findings, and resolve the known contrast/name findings even if assigned a lower impact |
| Copy and no JavaScript | In ephemeral CI, copy the exact install command; with JavaScript disabled verify essential prose, links, code and notices remain readable                                                                |
| 404                    | Static test host returns the built 404 page with HTTP 404 for an unknown `/gvy/` URL; assert useful navigation, not a success response from a catch-all                                                  |
| Visual regression      | Review homepage and one reading/reference page baseline in both themes at the three widths; include expanded mobile navigation and search screenshots                                                    |

Use six Chromium projects for the layout/theme matrix and start with one worker on the shared 16 GB machine. Run broad
route coverage once instead of multiplying it by six. Add Firefox and WebKit critical-journey checks before release and
before choosing a replacement renderer. Native Safari and manual assistive-technology review remain explicit limits.

Set `baseURL` to `http://127.0.0.1:4321/gvy/`. Navigate with relative paths such as `guides/quickstart/`; a leading `/`
would discard the project base path. Test static production output because development mode does not supply the final
search index. Configure an owned foreground preview/static server with an explicit port, readiness URL and shutdown;
keep `reuseExistingServer: false` to avoid testing stale or another agent's output. See
[web server configuration](https://playwright.dev/docs/test-webserver).

Browser contexts do not guarantee a private operating-system clipboard. Run the real clipboard assertion only in an
ephemeral CI environment. A local test may stub `navigator.clipboard.writeText` and verify its argument without reading
or replacing the owner’s clipboard; label that as a simulated copy check.

Use role/label locators, web-first assertions and state-based waits. Do not add fixed sleeps. Capture traces on the
first retry and screenshots on failure, and retain the HTML report even when tests fail. Record input SHA, OS, Node,
Bun, Playwright and browser versions. Keep visual baselines on one pinned Linux/browser/font environment; review image
changes and never automatically accept them. Mac spike screenshots are evidence, not Linux baselines. See
[test configuration](https://playwright.dev/docs/test-configuration) and
[snapshot guidance](https://playwright.dev/docs/test-snapshots).

In CI, build once, run browser tests against that artifact, then allow deployment only after applicable checks pass. If
jobs are separated, transfer the same artifact rather than rebuilding different bytes. Keep actions SHA-pinned with
verified version comments. Extend path filters to include canonical content, generated-reference inputs, component code,
browser tests and their configuration. A post-deployment, read-only smoke verifies the explicit public `/gvy/` URL, five
search queries, legacy routes and 404 without executing download/install commands. Update the maintenance README with
actual commands only when the suite exists.

Automated accessibility catches a subset of problems; pair it with keyboard traversal, zoom/reflow and a screen-reader
check. [Playwright's accessibility guidance](https://playwright.dev/docs/accessibility-testing) explains that boundary.

## MDX, Astro and Svelte authoring

[MDX](https://mdxjs.com/docs/what-is-mdx/) combines Markdown, JSX and JavaScript imports/expressions. It is not Svelte
template syntax: `{#if}` and `{#each}` belong inside a `.svelte` file, not in MDX. Keep general user guides in canonical
plain Markdown. Reserve MDX for pages that need imported components, currently the custom homepage.

| Format    | Use here                                     | Portability cost                                                                    |
| --------- | -------------------------------------------- | ----------------------------------------------------------------------------------- |
| `.md`     | Guides, commands and release-qualified prose | Lowest; preserve links, heading IDs and supported Markdown conventions              |
| `.mdx`    | Compose a product page with explicit imports | JSX/MDX conventions travel; imported Astro/Starlight components require replacement |
| `.astro`  | Static notices, layout and install examples  | Astro-specific template; no browser JavaScript needed for static rendering          |
| `.svelte` | A useful interactive filter or comparison    | Svelte-specific implementation; keep data and meaning independent of its renderer   |
| mdsvex    | Potential Markdown authoring in SvelteKit    | A separate Svelte Markdown preprocessor, not an interchangeable MDX compiler        |

[Astro's MDX integration](https://docs.astro.build/en/guides/integrations-guide/mdx/) supports imported Astro and
framework components. Static rendering is the default. Use `client:load` only for immediately needed interaction;
`client:visible` delays hydration until an island is visible. Avoid `client:only` for essential documentation because it
skips server rendering. Pass serializable data across hydration boundaries, not callback functions. See
[framework components](https://docs.astro.build/en/guides/framework-components/) and
[client directives](https://docs.astro.build/en/reference/directives-reference/).

A disposable, isolated build verified Astro 7.4.12 + MDX 8.0.2 + `@astrojs/svelte` 9.1.6 + Svelte 5.57.3 + TypeScript
5.9.3 on Node 26.13.1 with `/gvy/`. MDX imported a static `.astro` notice and `<Settings client:visible />`. Both sample
rows appeared in server-rendered HTML and with JavaScript disabled. After hydration the filter appeared and selecting
`beta` removed `alpha`. This verifies a small composition, not CI support, performance or a migration.

For a real component, define its contract first: accessible name, semantic HTML, no-JavaScript result, keyboard
behavior, theme tokens and data shape. A settings filter must render all rows on the server and reveal its input after
mounting, so readers never receive a nonfunctional filter. Test initial content, hydration, filtering, empty results and
both themes. Test headings in imported components separately: Astro MDX's heading extraction does not include every
heading created inside an imported component.

Start with a static release notice or install snippet only when it removes actual duplication. Add a Svelte filter only
when reference navigation needs it. Keep Starlight imports in thin page/layout adapters; do not spread them through
canonical guides. [Starlight's component guide](https://starlight.astro.build/components/using-components/) and
[Astro's Svelte integration](https://docs.astro.build/en/guides/integrations-guide/svelte/) are the authoring
references.

## Alternatives and exit criteria

This comparison is based on official documentation, not measured candidate builds. All candidates must produce a static
GitHub Pages artifact at `/gvy/`; server-only APIs cannot support essential documentation behavior there.

| Candidate                                 | Why evaluate it                                                                             | Work and constraints to prove                                                                                                                |
| ----------------------------------------- | ------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| Custom Astro shell + MDX + Svelte islands | Smallest initial renderer change; reuse the tested Astro/MDX composition and own the design | Own navigation, TOC, theme, search integration, accessibility and upgrade tests; removing Starlight does not supply these automatically      |
| SvelteKit + mdsvex                        | Svelte-native alternative if interactive documentation becomes central                      | Prerender every route, preserve base paths, build the docs shell/search, and convert Astro/JSX/Starlight components                          |
| VitePress                                 | A documentation shell with built-in local search and Vue components in Markdown             | Adopt Vue for custom UI and adapt Markdown/components; select a stable release explicitly because the current main docs describe a 2.0 alpha |
| Docusaurus                                | A documentation shell with MDX/React and versioning support                                 | Adopt React, replace Astro/Svelte components, and evaluate hosted search or a maintained local-search integration                            |

Primary references: [Astro on GitHub Pages](https://docs.astro.build/en/guides/deploy/github/),
[SvelteKit static generation](https://svelte.dev/docs/kit/adapter-static),
[Svelte packages including mdsvex](https://svelte.dev/packages),
[VitePress deployment](https://vitepress.dev/guide/deploy),
[VitePress local search](https://vitepress.dev/reference/default-theme-search),
[Vue in Markdown](https://vitepress.dev/guide/using-vue),
[Docusaurus deployment](https://docusaurus.io/docs/deployment),
[React in MDX](https://docusaurus.io/docs/markdown-features/react), and
[Docusaurus search ownership](https://docusaurus.io/docs/search). Pin candidate versions when each experiment starts; do
not copy current documentation's configuration into an older release without checking it.

Keep the migration seam small: canonical Markdown, manifest provenance, route/anchor contracts and neutral page metadata
are reusable inputs. The projection's Starlight frontmatter and link conversion form the current adapter. Component
templates and theme CSS remain renderer-specific. Inventory their conversion cost; do not build a generic plugin engine
or a separate component repository ahead of demonstrated need.

## Delivery sequence and decision gates

| Increment                                  | Owner and dependency                                       | Observable completion gate                                                                                                                                                                         |
| ------------------------------------------ | ---------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1. Browser harness and defect reproduction | Website maintainers; current built site                    | Commit pinned tests and CI lifecycle; B01–B04 reproduce as real failures, five search journeys pass, failures retain useful artifacts                                                              |
| 2. Correct current behavior                | Website maintainers; increment 1                           | Fix B01–B04, repeat matrix/keyboard/axe and independent review, approve clean visual baselines; finish the revamp's separate runtime gates before merge                                            |
| 3. Component contract                      | Website maintainers; demonstrated content need             | Land one small component with SSR/no-JavaScript, keyboard and hydration coverage; add Svelte only if interaction warrants it                                                                       |
| 4. Custom Astro shell experiment           | Website maintainers; browser contracts stable              | Render the same home, guide, code/table reference and 404; preserve every route/legacy fragment, edit destination, search query and essential no-JavaScript content                                |
| 5. SvelteKit/mdsvex comparison             | Website maintainers; same corpus and contracts             | Static `/gvy/` artifact passes the same browser gates; record component conversions and docs-shell ownership alongside Astro results                                                               |
| 6. Renderer decision and migration PRs     | Maintainers and independent reviewer; measured experiments | Record versioned build size/time, client JavaScript, accessibility, maintenance and conversion evidence; choose a renderer, stage parity, then switch deployment with a previous-artifact rollback |

Revisit alternatives when a feature requires substantial Starlight overrides or an upgrade exposes maintenance cost, and
after these experiments produce evidence. Keep findings in this record; no recurring automation is configured. If a
candidate fails a gate, record the failure and next action rather than migrating around it. Preserve the existing Pages
destination and release qualifications throughout. Remove disposable candidate projects after retaining concise
evidence; preserve the prior deployed artifact and exact deployment SHA for rollback.
