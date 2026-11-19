// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { readdir, stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
const [root, output, milliseconds] = process.argv.slice(2);
let bytes = 0, jsBytes = 0, files = 0;
async function walk(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) await walk(path);
    else { const size = (await stat(path)).size; bytes += size; files++; if (path.endsWith('.js')) jsBytes += size; }
  }
}
await walk(root);
await writeFile(output, JSON.stringify({ buildAndIndexMilliseconds: Number(milliseconds), files, bytes, jsBytes }, null, 2) + '\n');
