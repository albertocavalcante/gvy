// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pages } from '../content-map.mjs';
import { project } from './projection.mjs';
import { marker, preflight, removeStaleOwned } from './owned.mjs';

const root = path.resolve('../..');
await preflight('src/content/docs', pages);
const snapshot = await readFile('stable-v0.5.4-package.json');
const hash = createHash('sha256').update(snapshot).digest('hex');
if (hash !== 'db861176b359244c84713f0f77711e0ac4fadbf5d042a60eeb826640b3cb9f4e') throw new Error('Stable manifest checksum mismatch');
const stable = JSON.parse(snapshot);
const currentBytes = await readFile(path.join(root, 'editors/code/package.json'));
const current = JSON.parse(currentBytes);
const currentHash = createHash('sha256').update(currentBytes).digest('hex');
const check = process.argv.includes('--check');
const intro = '<!-- SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors -->\n<!-- SPDX-License-Identifier: Apache-2.0 -->\n\n';
const escape = value => String(value ?? '').replaceAll('|', '\\|').replaceAll('\n', ' ');
function table(kind, manifest) {
  if (kind === 'settings') {
    const properties = manifest.contributes.configuration.properties;
    return '| Setting | Default | Description |\n| --- | --- | --- |\n' + Object.entries(properties).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([name, value]) => `| \`${name}\` | \`${escape(JSON.stringify(value.default) ?? 'not specified')}\` | ${escape(value.markdownDescription || value.description || value.deprecationMessage)} |`).join('\n');
  }
  return '| Command | Title |\n| --- | --- |\n' + manifest.contributes.commands.toSorted((a, b) => a.command < b.command ? -1 : a.command > b.command ? 1 : 0).map(value => `| \`${value.command}\` | ${escape(value.title)} |`).join('\n');
}
await mkdir(path.join(root, 'docs/user'), { recursive: true });
for (const kind of ['settings', 'commands']) {
  let content = `${intro}# VS Code ${kind}\n\nThese entries come from extension manifests; their presence does not guarantee runtime support. The [stable manifest](../website/stable-v0.5.4-package.json) is a release snapshot; the [current manifest](../../editors/code/package.json) is maintained in the checkout.\n\n## Stable v0.5.4\n\nPackaged manifest from the official v0.5.4 VSIX at tag commit \`c441b8451ceed18a899a5d12369e1139b01ef9d7\`. VSIX SHA-256 \`128a9f76b8595611196890b4305702b8005335f64b75f1f83e24f6172196a93b\`; manifest SHA-256 \`${hash}\`.\n\n${table(kind, stable)}\n\n## Current checkout\n\nManifest SHA-256 \`${currentHash}\`.\n\n${table(kind, current)}\n`;
  const formatted = spawnSync('node_modules/.bin/dprint', ['fmt', '--config', 'dprint.json', '--stdin', 'reference.md'], { input: content, encoding: 'utf8' });
  if (formatted.status !== 0) throw new Error(`dprint failed: ${formatted.stderr}`);
  content = formatted.stdout;
  const destination = path.join(root, `docs/user/reference-${kind}.md`);
  if (check) {
    if (await readFile(destination, 'utf8') !== content) throw new Error(`Reference drift: ${destination}; run bun run generate`);
  } else await writeFile(destination, content);
}
const route = slug => `/gvy/${slug.replace(/\/index$/, '')}/`;
const routes = Object.fromEntries(pages.map(([source, slug]) => [source, route(slug)]));
const generated = pages.map(([, slug]) => `${slug}.md`);
const manifestPath = '.astro/gvy-generated.json';
let previous = [];
try { previous = JSON.parse(await readFile(manifestPath, 'utf8')); } catch (error) { if (error.code !== 'ENOENT') throw error; }
await removeStaleOwned('src/content/docs', previous, generated);
for (const [source, slug, title] of pages) {
  const markdown = await readFile(path.join(root, source), 'utf8');
  const output = await project(markdown, { source, editSource: source.startsWith('docs/user/reference-') ? 'editors/code/package.json' : source, title, description: `gvy ${title} documentation`, routes });
  const target = path.join('src/content/docs', `${slug}.md`);
  await mkdir(path.dirname(target), { recursive: true });
  await writeFile(target, output + `\n${marker}\n`);
}

await mkdir('.astro', { recursive: true });
await writeFile(manifestPath, JSON.stringify(generated, null, 2) + '\n');
