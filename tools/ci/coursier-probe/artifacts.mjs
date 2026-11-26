// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { join } from 'node:path';
import { hash, json } from './io.mjs';

export async function inventory(dir) {
  const rows = [];
  async function walk(prefix) {
    for (const item of await readdir(join(dir, prefix), { withFileTypes: true })) {
      const path = join(prefix, item.name);
      if (item.isDirectory()) await walk(path);
      else {
        assert(item.isFile(), 'unexpected link or non-file: ' + path);
        if (path === 'manifest.json') continue;
        assert(/\.(json|txt|patch)$/.test(path) || ['BUILD', 'java_argsfile'].includes(path), 'unexpected artifact: ' + path);
        const bytes = await readFile(join(dir, path));
        assert(!bytes.includes(0), 'binary evidence: ' + path);
        const sensitive = /https?:\/\/[^/\s"']+@|[?&](?:token|sig|signature|x-amz-signature|x-goog-signature)=|(?:gh[pousr]_|github_pat_)[a-z0-9_]{15,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|authorization:\s*(?:basic|bearer)\s+\S+/i;
        assert(!sensitive.test(bytes.toString('utf8')), 'credential-like material in ' + path);
        rows.push({ path, bytes: bytes.length, sha256: hash(bytes) });
      }
    }
  }
  await walk('');
  return rows.sort((a, b) => a.path < b.path ? -1 : a.path > b.path ? 1 : 0);
}

if (process.argv[1]?.endsWith('/artifacts.mjs')) {
  const dir = process.argv[2];
  await json(join(dir, 'manifest.json'), await inventory(dir));
}
