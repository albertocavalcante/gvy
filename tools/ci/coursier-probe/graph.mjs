// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from 'node:assert/strict';
import { relative, isAbsolute } from 'node:path';

function keys(value, required, optional = []) {
  assert(value && typeof value === 'object' && !Array.isArray(value));
  for (const key of required) assert(Object.hasOwn(value, key), 'missing ' + key);
  for (const key of Object.keys(value)) assert([...required, ...optional].includes(key), 'unknown field ' + key);
}

function stringArray(values) {
  assert(Array.isArray(values) && values.every(x => typeof x === 'string' && x.length));
  return [...values].sort();
}

function compare(a, b) {
  return a < b ? -1 : a > b ? 1 : 0;
}

// v0.1.0 is the tagged CLI schema for M23, .26 and .27. Repeated coordinates
// represent distinct publications. Keep multiplicity, including metadata-only rows.
export async function normalize(raw, cache, digest) {
  keys(raw, ['version', 'conflict_resolution', 'dependencies']);
  assert.equal(raw.version, '0.1.0');
  assert(Array.isArray(raw.dependencies) && raw.dependencies.length);
  const conflicts = raw.conflict_resolution;
  assert(conflicts && typeof conflicts === 'object' && !Array.isArray(conflicts));
  assert(Object.values(conflicts).every(value => typeof value === 'string'));
  const dependencies = [];
  for (const entry of raw.dependencies) {
    keys(entry, ['coord', 'directDependencies', 'dependencies'], ['exclusions', 'file', 'url']);
    assert(typeof entry.coord === 'string' && entry.coord.split(':').length >= 3);
    let file = null;
    if (entry.file != null) {
      assert(typeof entry.file === 'string' && isAbsolute(entry.file));
      const path = relative(cache, entry.file);
      const match = /^https\/([^/]+)\/(.+)$/.exec(path);
      assert(match, 'file outside HTTPS resolver cache: ' + path);
      assert(!path.split('/').includes('..'));
      if (entry.url != null) {
        const url = new URL(entry.url);
        assert.equal(url.protocol, 'https:');
        assert.equal(url.host, match[1]);
        assert.equal(url.pathname.slice(1), match[2]);
      }
      const sha256 = await digest(entry.file);
      assert.match(sha256, /^[a-f0-9]{64}$/);
      // Strip the repository host while preserving the complete repository path.
      file = { path: match[2], sha256 };
    } else {
      assert(entry.url == null, 'URL on metadata-only node');
    }
    dependencies.push({
      coord: entry.coord, file, direct: stringArray(entry.directDependencies),
      transitive: stringArray(entry.dependencies), exclusions: stringArray(entry.exclusions ?? []),
    });
  }
  dependencies.sort((a, b) => compare(JSON.stringify(a), JSON.stringify(b)));
  return { dependencies, conflicts: Object.fromEntries(Object.entries(conflicts).sort(([a], [b]) => compare(a, b))) };
}

export function historical(paths) {
  const lines = paths.filter(p => /\.(jar|pom|module)$/.test(p)).map(p => p.slice(p.indexOf('/') + 1)).sort();
  return lines.length ? lines.join('\n') + '\n' : '';
}

export function compareGraph(left, right) {
  const counts = new Map();
  for (const node of left.dependencies) {
    const key = JSON.stringify(node);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  const added = [];
  for (const node of right.dependencies) {
    const key = JSON.stringify(node);
    const n = counts.get(key) ?? 0;
    if (n) counts.set(key, n - 1);
    else added.push(node);
  }
  const removed = [];
  for (const [key, count] of counts) for (let i = 0; i < count; i++) removed.push(JSON.parse(key));
  return { removed, added, conflictsChanged: JSON.stringify(left.conflicts) !== JSON.stringify(right.conflicts) };
}
