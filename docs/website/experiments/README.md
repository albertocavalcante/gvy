<!-- SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
SPDX-License-Identifier: Apache-2.0 -->

# Renderer comparison spike

This is a bounded experiment for #2022, not a replacement for the deployed site. It compares a custom Astro shell with
SvelteKit and mdsvex on the same four pages: a small neutral home (not the production homepage), canonical quickstart,
canonical settings reference, and a real static 404. Both index the selected corpus with Pagefind. Links to selected
routes use `/gvy/`; links outside the corpus lead to their source on GitHub. The quickstart and settings pages link to
their canonical edit destinations.

## Reproduce

Use Node 22.23.4 and Bun 1.4.4. From this directory:

```sh
bun install --frozen-lockfile
bunx playwright install --with-deps chromium
bun run build:astro
CANDIDATE=astro bun run test
bun run build:svelte
CANDIDATE=svelte bun run test
```

`generate.mjs` projects the canonical Markdown from `docs/user` into ignored renderer-specific route files. Do not edit
generated files. Edit the neutral home or 404 in `shared/`, and edit guides at their canonical sources. Both renderers
use GFM; SvelteKit also needs a heading slug plugin. The static server returns the generated 404 with HTTP 404 and
mounts the artifact beneath `/gvy/`. The workflow builds on GitHub-hosted Ubuntu with one browser worker and retains
reports and uncompressed byte counts. Its elapsed values measure build **and** indexing at one-second resolution, not
runtime performance; compare only runs with comparable inputs and environment. JavaScript bytes include all `.js` files
including Pagefind, not compressed transfer bytes.

## Component and ownership costs

Astro's generated home is MDX and imports a static `.astro` notice component with an accessible label and no client
JavaScript. The SvelteKit adapter emits the same notice in its home page. Both own navigation, system light/dark theme,
search UI, typography, route generation and error handling. The MDX home is a small shared specimen, not a conversion of
the production MDX homepage. The guide and settings text stay Markdown; each renderer owns its configuration and route
adapter. The SvelteKit build copies the rendered not-found page to `404.html`, avoiding an interactive SPA fallback.
This costs a renderer-specific finalization step. Search is optional progressive enhancement; the underlying pages
remain readable without JavaScript. There is no Svelte island because this corpus does not demonstrate a need for an
interactive settings filter.

This is not proof of all 19 production routes, all legacy fragments, the five production search journeys, browser
interoperability, accessibility compliance, or migration readiness. Automated keyboard and no-JavaScript checks cover
only the selected examples. Manual assistive-technology review, zoom/reflow, visual review, broader browser coverage,
accessible keyboard scrolling for overflowing tables and code, complete metadata, conversion of the production homepage,
its legacy fragments and production parity remain open before any renderer decision. No performance or successful-build
claim is made until the hosted workflow runs. The project does not deploy artifacts.
