// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import path from 'node:path';
import { existsSync } from 'node:fs';
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkStringify from 'remark-stringify';
import remarkGfm from 'remark-gfm';
import { visit } from 'unist-util-visit';
import { toString } from 'mdast-util-to-string';
import GithubSlugger from 'github-slugger';

const processor = unified().use(remarkParse).use(remarkGfm).use(remarkStringify, { fences: true });

export async function project(markdown, { source, editSource = source, title, description, routes }) {
  const tree = processor.parse(markdown);
  tree.children = tree.children.filter(node => !(node.type === 'html' && /^<!-- SPDX-/.test(node.value)));
  const titles = tree.children.filter(node => node.type === 'heading' && node.depth === 1);
  if (titles.length !== 1 || !toString(titles[0]).trim()) throw new Error(`${source}: expected exactly one nonempty title`);
  const first = tree.children.indexOf(titles[0]);
  visit(tree, 'html', node => {
    if (!/^<a id="[a-z0-9-]+">$|^<\/a>$/.test(node.value)) throw new Error(`${source}: unsupported HTML`);
  });
  const heading = tree.children[first];
  const sourceSlugs = new GithubSlugger();
  const projectedSlugs = new GithubSlugger();
  let slug;
  for (const item of tree.children.filter(node => node.type === 'heading')) {
    const text = toString(item);
    const original = sourceSlugs.slug(text);
    if (item === heading) { slug = original; continue; }
    if (projectedSlugs.slug(text) !== original) throw new Error(`${source}: duplicate title anchor cannot be projected faithfully`);
  }
  tree.children.splice(first, 1, { type: 'html', value: `<span id="${slug}"></span>` });
  visit(tree, node => {
    if (node.type !== 'link' && node.type !== 'image' && node.type !== 'definition') return;
    const url = node.url;
    if (!url || /^(?:[a-z][a-z\d+.-]*:|\/|#)/i.test(url)) return;
    const [file, fragment] = url.split('#', 2);
    const resolved = path.posix.normalize(path.posix.join(path.posix.dirname(source), decodeURI(file)));
    if (routes[resolved]) node.url = `${routes[resolved]}${fragment ? `#${fragment}` : ''}`;
    else if (/\.md$/i.test(file) && !existsSync(path.resolve('../..', resolved))) throw new Error(`${source}: missing link ${url}`);
    else node.url = `https://github.com/albertocavalcante/gvy/${node.type === 'image' ? 'raw' : 'blob'}/main/${resolved}${fragment ? `#${fragment}` : ''}`;
  });
  visit(tree, 'blockquote', node => {
    const text = node.children[0]?.children?.[0];
    if (text?.type !== 'text') return;
    text.value = text.value.replace(/^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]/, (_, type) => `${type[0]}${type.slice(1).toLowerCase()}:`);
  });
  return `---\ntitle: ${JSON.stringify(title)}\ndescription: ${JSON.stringify(description)}\neditUrl: https://github.com/albertocavalcante/gvy/edit/main/${editSource}\n---\n\n${processor.stringify(tree)}\n\n[View source on GitHub](https://github.com/albertocavalcante/gvy/blob/main/${source})\n`;
}
