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
[#1999](https://github.com/albertocavalcante/gvy/pull/1999) remains a separate workstream.

Implementation base: `f101e5bf5a5a2fae42d0cfcadba838bcfc6e3d14`. The shared main checkout and unrelated changes are
preserved.

| Work package                                | Owner                           | Completion gate                                                                       | State                            |
| ------------------------------------------- | ------------------------------- | ------------------------------------------------------------------------------------- | -------------------------------- |
| Content projection and reference generation | Documentation maintainers       | Deterministic output, unsupported-input tests, frozen install and link checks         | In progress                      |
| User journey and workflows                  | Documentation maintainers       | Source-verified examples and explicit release limits                                  | In progress                      |
| Homepage and navigation                     | Website maintainers             | Responsive, accessible rendered review in both themes                                 | In progress                      |
| Release upkeep and validation               | Release and website maintainers | Drift checks, independent complete-diff review, CI and public deployment verification | Implemented; publication pending |

Validation evidence and unresolved acceptance gates are recorded below before publication. A source inspection does not
establish a successful editor workflow.

## Current handoff

The implementation and automated checks are complete locally. Runtime and clean stable VSIX evidence is in
[the validation record](DOCUMENTATION_WEBSITE_VALIDATION.md). The PR remains a draft until the approved acceptance gates
below pass. No runtime-feature implementation or distribution publishing is included.

| Remaining item                                          | Impact and reason                                                                                                                                                            | Owner and next action                                                                                            | Completion gate                                                                                                                                             |
| ------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Responsive, keyboard, search and accessibility review   | Browser host unavailable; native CUA became unreliable, then timed out; Guest tabs crashed. Dark Reader affected partial native screenshots.                                 | Website maintainers: repeat the complete review when a supported browser surface is available.                   | Home and representative guides at 360/768/1440 in both themes; no page overflow or serious accessibility violations; keyboard and five search queries pass. |
| Gradle and restricted-environment workflow verification | Source-inspected guidance is not a successful authenticated import or clean network/offline test.                                                                            | Build and documentation maintainers: exercise the guide in a controlled fixture with approved repository access. | Imports resolve documented dependency; credentials remain private; network/offline limits match observations.                                               |
| Minimum Java and full editor workflow coverage          | Probes used Java 21 and a development JAR; stable clean installation tested Groovy hover only. Jenkins used cached core; Spock used degraded analysis without running tests. | Documentation maintainers: repeat advertised release-specific workflows and minimum Java 17 checks.              | Record exact editor, extension, server, Java, dependencies and outputs for each supported claim.                                                            |
| Hosted checks and publication                           | No merge/deployment before required validation is complete.                                                                                                                  | Coordinator: inspect final-head CI and independent review, then squash merge and verify Pages.                   | Required CI passes, exact head reviewed, public home/guides/search/legacy URLs/404 verified; owned branch and worktree cleaned.                             |

The stable packaging limitation remains owned by release maintainers in #1751; the provisional quality scorecard remains
owned by #1550. Documentation records these limits and does not resolve them.
