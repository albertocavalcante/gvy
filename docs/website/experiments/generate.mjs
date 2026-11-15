// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';

const root = resolve('../..');
const pages = [
  ['shared/home.md', '', ''],
  ['../../user/quickstart.md', 'guides/quickstart', 'docs/user/quickstart.md'],
  ['../../user/reference-settings.md', 'reference/settings', 'docs/user/reference-settings.md'],
  ['shared/not-found.md', 'not-found', ''],
];
for (const [source, route, edit] of pages) {
  let body = await readFile(source, 'utf8');
  // Preserve selected routes locally; other canonical links point to their repository source.
  body = body.replace(/\]\(([^)#]+\.md)(#[^)]*)?\)/g, (_, target, fragment = '') => {
    const canonical = resolve(root, 'user', target).slice(resolve(root, '..').length + 1);
    const local = canonical === 'docs/user/quickstart.md' ? '/gvy/guides/quickstart/' : canonical === 'docs/user/reference-settings.md' ? '/gvy/reference/settings/' : `https://github.com/albertocavalcante/gvy/blob/main/${canonical}`;
    return `](${local}${fragment})`;
  });
  const astro = route === 'not-found' ? 'astro/src/pages/404.md' : `astro/src/pages/${route ? `${route}/index` : 'index'}.md`;
  const svelte = `svelte/src/routes/${route ? `${route}/` : ''}+page.svx`;
  for (const file of [astro, svelte]) await mkdir(dirname(file), { recursive: true });
  await writeFile(astro, `---\nlayout: ${route && route !== 'not-found' ? '../../../layouts/Docs.astro' : '../layouts/Docs.astro'}\nedit: ${edit || 'null'}\n---\n${body}`);
  await writeFile(svelte, `---\nedit: ${edit || 'null'}\n---\n${body}`);
}
