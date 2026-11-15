<!-- SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
SPDX-License-Identifier: Apache-2.0 -->

# Astro authoring spike

This bounded #2022 experiment builds a custom Astro shell with MDX at `/gvy/`. It uses a small neutral home, the
canonical quickstart, the canonical settings reference and a static 404. The home imports a static Astro notice from
MDX. Pagefind indexes this selected corpus. It is not a conversion of the production homepage or a renderer selection.

## Reproduce

Use Node 22.23.4 and Bun 1.4.4. From this directory:

```sh
bun install --frozen-lockfile
bunx playwright install --with-deps chromium
bun run test:server
bun run build:astro
bun run test
```

`generate.mjs` projects canonical Markdown from `docs/user` into ignored Astro route files. Edit guides at their
canonical sources, and the neutral home or 404 under `shared/`. Its AST link adapter follows the production projection
policy: selected routes remain local and other relative links point to source files on GitHub. The reference edit link
points to the extension manifest. Satteri supplies Markdown rendering and a static focusable-region adapter; Shiki's
transformer marks highlighted code blocks as focusable. The loopback static server returns the built 404 with HTTP 404.
It assumes trusted artifact directories and refuses leaf symlinks.

The hosted workflow retains browser reports, axe violations and incomplete results, and uncompressed artifact and
JavaScript byte counts. Its elapsed value measures build and indexing together at one-second resolution; it is not
runtime performance or comparative evidence. Automated checks cover selected search journeys, routes, links, fragments,
theme colors, keyboard scrolling, and no-JavaScript content. Serious or critical axe violations fail the suite;
automated checks do not establish accessibility compliance.

## Remaining decision gates

This small homepage does not establish production homepage conversion or its legacy fragments. Full route parity,
production search journeys, broader browser coverage, zoom/reflow, visual inspection and manual assistive-technology
review remain open before a renderer decision. The experiment does not deploy.

A SvelteKit comparison is deferred: its Vite plugin depends on `deepmerge` 4.3.1, affected by
[GHSA-gcqx-w48m-w2h4](https://github.com/advisories/GHSA-gcqx-w48m-w2h4), with no fixed release identified.
[Upstream PR 1415](https://github.com/sveltejs/vite-plugin-svelte/pull/1415) proposes removing that dependency. A prior
SvelteKit build and five browser tests passed, but that does not clear the security gate or establish comparative
performance. Issue #2022 owns the blocked comparison.
