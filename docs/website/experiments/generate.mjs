// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { readFile, mkdir, writeFile, access } from 'node:fs/promises';
import { dirname, resolve, posix } from 'node:path';
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import remarkStringify from 'remark-stringify';
import { visit } from 'unist-util-visit';

// Match the production projection's link-node policy without its Starlight frontmatter.
const processor = unified().use(remarkParse).use(remarkGfm).use(remarkStringify, { fences: true });
const repo = resolve('../../..');
const routes = {
  'docs/user/quickstart.md': '/gvy/guides/quickstart/',
  'docs/user/reference-settings.md': '/gvy/reference/settings/',
};
const pages = [
  ['shared/home.md', '', null, null],
  ['../../user/quickstart.md', 'guides/quickstart', 'docs/user/quickstart.md', 'docs/user/quickstart.md'],
  ['../../user/reference-settings.md', 'reference/settings', 'docs/user/reference-settings.md', 'editors/code/package.json'],
  ['shared/not-found.md', 'not-found', null, null],
];
for (const [input, route, source, edit] of pages) {
  const tree = processor.parse(await readFile(input, 'utf8'));
  tree.children = tree.children.filter(node => !(node.type === 'html' && /^<!-- SPDX-/.test(node.value)));
  const localSources = [];
  if (source) visit(tree, node => {
    if (!['link', 'image', 'definition'].includes(node.type)) return;
    const url = node.url;
    if (!url || /^(?:[a-z][a-z\d+.-]*:|\/|#)/i.test(url)) return;
    const [file, fragment] = url.split('#', 2);
    const resolved = posix.normalize(posix.join(posix.dirname(source), decodeURI(file)));
    localSources.push(resolve(repo, resolved));
    node.url = `${routes[resolved] || `https://github.com/albertocavalcante/gvy/${node.type === 'image' ? 'raw' : 'blob'}/main/${resolved}`}${fragment ? `#${fragment}` : ''}`;
  });
  await Promise.all(localSources.map(file => access(file)));
  const body = processor.stringify(tree);
  const astro = route === 'not-found' ? 'astro/src/pages/404.md' : route ? `astro/src/pages/${route}/index.md` : 'astro/src/pages/index.mdx';
  const svelte = `svelte/src/routes/${route ? `${route}/` : ''}+page.svx`;
  for (const file of [astro, svelte]) await mkdir(dirname(file), { recursive: true });
  const astroImport = route === '' ? "\nimport Notice from '../components/Notice.astro';\n" : '';
  const astroNotice = route === '' ? "\n<Notice />\n" : '';
  const svelteNotice = route === '' ? '\n<aside aria-label="Experiment notice"><h2 id="about-this-experiment">About this experiment</h2><p>This is a four-page renderer experiment, not the published documentation.</p></aside>\n\n' : '';
  await writeFile(astro, `---\nlayout: ${route && route !== 'not-found' ? '../../../layouts/Docs.astro' : '../layouts/Docs.astro'}\nedit: ${edit || 'null'}\n---\n${astroImport}\n${body}${astroNotice}`);
  await writeFile(svelte, `---\nedit: ${edit || 'null'}\n---\n${body}${svelteNotice}`);
}
