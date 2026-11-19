// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { load } from 'cheerio';
import path from 'node:path';
import { readdir, readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

export function validate(files, base, inventory) {
  const errors = [];
  const html = new Map([...files].filter(([name]) => name.endsWith('.html')).map(([name, text]) => [name, load(text)]));
  const targetFile = pathname => {
    const relative = pathname.slice(base.length);
    return relative.endsWith('/') || !path.posix.extname(relative) ? `${relative.replace(/\/$/, '')}${relative ? '/' : ''}index.html` : relative;
  };
  for (const [name, $] of html) {
    $('[href], [src]').each((_, element) => {
      const raw = $(element).attr('href') ?? $(element).attr('src');
      if (!raw || /^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(raw)) return;
      let url;
      try { url = new URL(raw, `https://example.invalid${base}${name.replace(/index\.html$/, '')}`); } catch { errors.push(`${name}: invalid URL ${raw}`); return; }
      if (url.pathname === base.slice(0, -1)) url.pathname = base;
      if (!url.pathname.startsWith(base)) { errors.push(`${name}: outside base ${raw}`); return; }
      const target = targetFile(decodeURIComponent(url.pathname));
      if (!files.has(target)) { errors.push(`${name}: missing ${raw}`); return; }
      if (url.hash && html.has(target)) {
        const id = decodeURIComponent(url.hash.slice(1));
        if (!html.get(target)('[id]').toArray().some(node => html.get(target)(node).attr('id') === id)) errors.push(`${name}: missing fragment ${raw}`);
      }
    });
  }
  for (const [route, ids] of Object.entries(inventory)) {
    const target = targetFile(`${base}${route.replace(/^\//, '')}`);
    const $ = html.get(target);
    if (!$) { errors.push(`missing route ${route}`); continue; }
    for (const id of ids) if (!$('[id]').toArray().some(node => $(node).attr('id') === id)) errors.push(`${route}: missing required fragment #${id}`);
  }
  return errors;
}
async function collect(dir, prefix = '') {
  const entries = await readdir(dir, { withFileTypes: true });
  const files = new Map();
  for (const entry of entries) {
    const name = path.posix.join(prefix, entry.name);
    if (entry.isDirectory()) for (const pair of await collect(path.join(dir, entry.name), name)) files.set(...pair);
    else files.set(name, await readFile(path.join(dir, entry.name), 'utf8'));
  }
  return files;
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const inventory = JSON.parse(await readFile('route-inventory.json', 'utf8'));
  const errors = validate(await collect('dist'), '/gvy/', inventory);
  if (errors.length) { console.error(errors.join('\n')); process.exitCode = 1; }
  else console.log('Built links, assets, routes and fragments validated.');
}
