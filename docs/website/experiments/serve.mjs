// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { createServer } from 'node:http';
import { constants } from 'node:fs';
import { open } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
import { pathToFileURL } from 'node:url';

const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.wasm': 'application/wasm', '.pf_index': 'application/octet-stream' };

// The built artifact and its parent directories are trusted; never follow a leaf symlink.
// The optional callback lets the regression test replace a path after it has been opened.
async function readLeaf(file, afterOpen) {
  const handle = await open(file, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
  try {
    const info = await handle.stat();
    if (info.isDirectory()) return null;
    if (!info.isFile()) throw new Error('not a regular file');
    if (afterOpen) await afterOpen(file);
    return await handle.readFile();
  } finally {
    await handle.close();
  }
}

export async function readAsset(file, afterOpen) {
  let bytes = await readLeaf(file, afterOpen);
  if (bytes === null) {
    file = resolve(file, 'index.html');
    bytes = await readLeaf(file, afterOpen);
    if (bytes === null) throw new Error('index is a directory');
  }
  return { file, bytes };
}

export function staticServer(directory) {
  const root = resolve(directory);
  return createServer(async (req, res) => {
    try {
      const pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
      if (!pathname.startsWith('/gvy/')) throw new Error('outside base');
      const file = resolve(root, `.${pathname.slice(4)}`);
      if (!file.startsWith(root + sep) && file !== root) throw new Error('outside root');
      const asset = await readAsset(file);
      res.writeHead(200, { 'content-type': types[extname(asset.file)] || 'application/octet-stream' });
      res.end(asset.bytes);
    } catch {
      try {
        const asset = await readAsset(resolve(root, '404.html'));
        res.writeHead(404, { 'content-type': 'text/html' });
        res.end(asset.bytes);
      } catch {
        res.writeHead(500, { 'content-type': 'text/plain' });
        res.end('Static 404 page unavailable');
      }
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  staticServer(process.argv[2]).listen(4321, '127.0.0.1');
}
