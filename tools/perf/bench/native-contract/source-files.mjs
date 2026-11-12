import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const fail = code => { throw new Error(code); };

export function readRegularBytes(file, limit) {
  let handle;
  try {
    handle = fs.openSync(file, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
    const stat = fs.fstatSync(handle);
    if (!stat.isFile() || !Number.isSafeInteger(stat.size) || stat.size > limit) fail('SOURCE_FILE_INVALID');
    const buffer = Buffer.alloc(stat.size + 1);
    let length = 0;
    while (length < buffer.length) {
      const count = fs.readSync(handle, buffer, length, buffer.length - length, null);
      if (count === 0) break;
      length += count;
    }
    if (length !== stat.size) fail('SOURCE_FILE_INVALID');
    return buffer.subarray(0, length);
  } catch {
    fail('SOURCE_FILE_INVALID');
  } finally {
    if (handle !== undefined) fs.closeSync(handle);
  }
}

/** Verify only bounded regular files; identities never expose the local cache path. */
export function verifyFiles(root, expected) {
  if (!expected || typeof expected !== 'object' || Array.isArray(expected)) fail('SOURCE_PIN_INVALID');
  const entries = Object.entries(expected);
  if (entries.length === 0 || entries.length > 128) fail('SOURCE_PIN_INVALID');
  let total = 0;
  return entries.sort(([a], [b]) => a.localeCompare(b)).map(([name, digest]) => {
    if (!/^[a-zA-Z0-9_./-]+$/.test(name) || name.startsWith('/') || name.split('/').some(part => part === '..' || part === '.' || !part) || !/^[a-f0-9]{64}$/.test(digest)) fail('SOURCE_PIN_INVALID');
    const bytes = readRegularBytes(path.join(root, name), Math.min(32 * 1024 * 1024, 64 * 1024 * 1024 - total));
    total += bytes.length;
    if (sha256(bytes) !== digest) fail('SOURCE_DIGEST_MISMATCH');
    return { path: name, sha256: digest, bytes: bytes.length };
  });
}

/** Inventory compiled/generated source, query and binding inputs without following links. */
export function sourcePaths(root) {
  const result = ['Cargo.toml'];
  let directories = 0;
  function visit(directory, depth = 0) {
    if (++directories > 128 || depth > 8) fail('SOURCE_FILE_INVALID');
    for (const entry of fs.readdirSync(path.join(root, directory), { withFileTypes: true })) {
      const name = `${directory}/${entry.name}`;
      if (entry.isDirectory()) visit(name, depth + 1);
      else if (entry.isFile()) result.push(name);
      else fail('SOURCE_FILE_INVALID');
      if (result.length > 128) fail('SOURCE_FILE_INVALID');
    }
  }
  for (const directory of ['src', 'queries', 'bindings/rust']) visit(directory);
  for (const name of fs.readdirSync(root)) if (/^LICENSE(?:[-.]|$)/.test(name)) result.push(name);
  return result.sort();
}
