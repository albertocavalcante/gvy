# Revamp gvy’s documentation and website

## Summary

Build a distinctive gvy product homepage backed by practical, accurate documentation. Prioritize people installing and
using gvy, with stable releases as the default and development-only behavior clearly labeled.

The current six-page site has stale setup commands, an outdated feature matrix, and little guidance for everyday
workflows. Keep Astro, Starlight, Bun, GitHub Pages, and the `/gvy/` URL.

## 1. Redesign the experience

- Brand the site **gvy**, with the headline “Groovy language tools for your editor.”
- Build a custom homepage with a clear introduction, **Install gvy** and **Read the quickstart** actions, a working
  installation example, and links into Groovy, Jenkins, and Spock workflows.
- Use a blue and teal palette, strong typography, generous spacing, and consistent light/dark themes. Keep assets local
  and motion minimal.
- Show real editor captures with their tested version identified. Use annotated code examples where a feature has no
  verified capture.
- Retain Starlight’s reading layout, table of contents, search, copy buttons, and keyboard navigation. Its
  [customization support](https://starlight.astro.build/guides/customization/) accommodates a distinct homepage without
  replacing the documentation framework.

Organize the documentation around these tasks:

| Section          | Pages and outcomes                                                                                                                 |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| **Get started**  | Understand gvy, install it, open a first project, verify the running server, update or roll back                                   |
| **Workflows**    | Edit Groovy projects; import Gradle dependencies; configure Jenkins pipelines and shared libraries; work with Spock tests          |
| **Configure**    | Select server/project Java runtimes, configure diagnostics and formatting, understand private repositories and offline limitations |
| **Reference**    | Settings, commands, standalone server, and feature support                                                                         |
| **Troubleshoot** | Recover from startup, import, completion, and compatibility problems; collect useful bug reports                                   |
| **Contribute**   | Find existing development guides, architecture documentation, and the Quality Bar roadmap                                          |

Each guide needs prerequisites, a complete example, the expected result, relevant limitations, and a next step.

## 2. Establish accurate, maintainable content

- Keep canonical user prose in repository Markdown, preserving GitHub and offline readability. Publish it through
  deterministic Starlight projections; keep the custom homepage in MDX.
- Add an explicit source-to-page map. The projection must handle headings, internal links, admonitions, and edit links;
  generated pages remain untracked.
- Reuse and reconcile existing installation, editor, standalone-server, and support documentation. Preserve the
  extension README’s usefulness when packaged.
- Generate settings and command tables from verified extension manifests. Distinguish the stable packaged manifest from
  current `main`; manifest presence alone does not prove runtime support.
- Identify the version and source revision behind feature claims. Keep the feature matrix explicitly provisional until
  the existing quality harness supplies its scorecard, then consume that output under
  [#1550](https://github.com/albertocavalcante/gvy/issues/1550).
- Preserve all six existing published routes and existing repository heading links.

Stable instructions must retain the
[v0.5.4 bundled-server limitation](https://github.com/albertocavalcante/gvy/issues/1751) until a corrected release is
verified. Marketplace availability, newer capabilities, and full offline operation require evidence before being
advertised.

For private environments, explain repository credentials, `mavenLocal()`, internal downloads, and current network
behavior. Document GDSL execution where readers enable it.

## 3. Deliver in coherent increments

| Milestone                          | Deliverables and completion gate                                                                                                                     |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Foundation and correctness**     | Record the plan in `kb/`, connect it to #1550, inventory routes and anchors, establish content projection, and correct onboarding and support claims |
| **Homepage and navigation**        | Ship the visual identity, custom homepage, ordered navigation, and install-to-first-project journey                                                  |
| **Guides and reference**           | Land scoped PRs for configuration, Groovy/Gradle, Jenkins, Spock, troubleshooting, and reference material                                            |
| **Maintenance and release upkeep** | Complete automated drift checks and document how releases refresh installation links, manifests, screenshots, and version notes                      |

Add applicable checks with each increment. Every PR receives independent review of its complete final diff, appropriate
validation, and squash merge after required checks pass.

Resolve the existing [Astro content and head warnings in #1888](https://github.com/albertocavalcante/gvy/issues/1888)
within this work. Coordinate overlapping installation changes with active work. Distribution publishing and runtime
feature implementation stay in their existing workstreams.

## 4. Acceptance and validation

- Frozen-install builds, Astro checks, Markdown/MDX formatting, and license checks pass.
- Internal links, fragments, assets, navigation entries, existing URLs, and `/gvy/` base paths resolve.
- Projection tests cover heading conversion, links, admonitions, code fences, source attribution, and unsupported input.
- A clean installation reaches a working Groovy file using documented steps. Record tested editor, Java, extension, and
  server versions.
- Each workflow’s claimed behavior is exercised against its stated release or development build.
- Desktop and mobile review covers 360, 768, and 1440 px widths, both themes, keyboard navigation, and search.
- Search finds installation, Java configuration, Jenkins, Spock, and troubleshooting guidance. No page-level horizontal
  overflow or serious accessibility findings remain.
- After deployment, verify the public homepage, representative guides, search, compatibility URLs, and 404 page.

**Defaults:** English, one documentation edition, stable-first guidance with labeled development sections. Browser
preview was unavailable during this audit; rendered visual validation remains an implementation gate.

## Delivery record

Approved scope recorded on 2026-11-14. Canonical task record: this page. Related work:
[documentation truth #1550](https://github.com/albertocavalcante/gvy/issues/1550),
[Astro warnings #1888](https://github.com/albertocavalcante/gvy/issues/1888), and
[stable packaging #1751](https://github.com/albertocavalcante/gvy/issues/1751). Installation work in
[#1999](https://github.com/albertocavalcante/gvy/pull/1999) merged separately; its exact-main CI installation guidance
is preserved here.

Implementation base: `f101e5bf5a5a2fae42d0cfcadba838bcfc6e3d14`. The shared main checkout and unrelated changes are
preserved. The publication branch was subsequently rebased onto `71f7318e9397cc133b4172c097636be80db36c67` to retain
incoming installer/GDSL guidance and refresh manifest provenance against the current integration trunk.

| Work package                                | Owner                           | Completion gate                                                                       | State                           |
| ------------------------------------------- | ------------------------------- | ------------------------------------------------------------------------------------- | ------------------------------- |
| Content projection and reference generation | Documentation maintainers       | Deterministic output, unsupported-input tests, frozen install and link checks         | Implemented and checked         |
| User journey and workflows                  | Documentation maintainers       | Source-verified examples and explicit release limits                                  | Implemented; runtime gates open |
| Homepage and navigation                     | Website maintainers             | Responsive, accessible rendered review in both themes                                 | Implemented; browser fixes open |
| Release upkeep and validation               | Release and website maintainers | Drift checks, independent complete-diff review, CI and public deployment verification | Merged; follow-ups tracked      |

Validation evidence and unresolved acceptance gates are recorded below before publication. A source inspection does not
establish a successful editor workflow.

## Current handoff

[PR #2004](https://github.com/albertocavalcante/gvy/pull/2004) was squash-merged as
`573d0affca10415569ca3e56634b42190a6e1a03` on 2026-11-14. The reviewed publication head
`4f6987f3824e2af86053414bf198eae0398dca46` passed 35 checks with four expected skips. The owner explicitly requested
merging this increment and completing the remaining acceptance work in later PRs. This changes delivery order; the
original browser and workflow limitations were not declared resolved.

[Pages run 43606666193](https://github.com/albertocavalcante/gvy/actions/runs/43606666193) built and deployed that
merged commit successfully. A subsequent public smoke passed routes, five search journeys, essential no-JavaScript
content and the 404 page. Browser findings B01–B04 remain separately reproducible against this deployed snapshot.

| Follow-up                                                                                           | Owner and next action                                                                                                                      | Completion gate                                                                                                                                                                        |
| --------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [#2020: browser tests and B01–B04](https://github.com/albertocavalcante/gvy/issues/2020)            | Website maintainers and coordinating agent: land a permanent Playwright harness and fixes in the first follow-up PR; reconcile this record | No document overflow at 360/768/1440 in both themes; contrast, code/table keyboard focus and search naming fixed; CI and independent review; remaining visual/manual coverage explicit |
| [#2021: release/workflow verification](https://github.com/albertocavalcante/gvy/issues/2021)        | Build/release and documentation maintainers: exercise exact release and controlled dependency fixtures                                     | Record Java 17, editor/server versions, Gradle/private/offline observations, Jenkins without accidental cached core and actual Spock execution; retain qualifications when unverified  |
| [#2022: components and renderer alternatives](https://github.com/albertocavalcante/gvy/issues/2022) | Website maintainers: establish useful component contracts, then compare custom Astro and SvelteKit/mdsvex on the same corpus               | Preserve routes/anchors, source/edit links, search, no-JavaScript content, accessibility and static /gvy/ hosting; record conversion costs and measured decision evidence              |

The [testing and platform plan](DOCUMENTATION_WEBSITE_TESTING_AND_PLATFORM_PLAN.md) describes the sequence and the
[validation record](DOCUMENTATION_WEBSITE_VALIDATION.md) preserves exact probe versions and limits. Private source
evidence is retained at `~/dev/local/gvy-docs-evidence/pr-2004` until maintained reproductions/contracts replace it. The
merged task branch and worktree were removed; warm tools were reused in the active follow-up checkout.

The stable packaging limitation remains owned by release maintainers in #1751; the provisional quality scorecard remains
owned by #1550. Documentation records these limits and does not resolve them. No runtime-feature implementation or
distribution publishing is included.
