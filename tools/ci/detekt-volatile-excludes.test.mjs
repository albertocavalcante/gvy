// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

// Keeps the VolatileProperty excludes a ratchet that only shrinks (#2474). Each line must name one module's src tree,
// which the lint task's detekt run reads, and that tree must still hold a @Volatile annotation. A pull request that
// migrates a module therefore has to drop its line, and a line for an unlinted path such as tests/e2e (#2480) or a
// clean module cannot be added.

import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../..', import.meta.url));
const volatileAnnotation = /@(?:field:)?\[?\s*(?:kotlin\.(?:jvm|concurrent)\.)?Volatile\b/;

/** The `excludes` entries of `gvy > VolatileProperty`, or null when that rule block is missing. */
function volatileExcludes(config) {
  const lines = config.split('\n');
  const start = lines.indexOf('  VolatileProperty:');
  if (start < 0) return null;
  const owner = lines.slice(0, start).reverse().find(line => /^\S/.test(line));
  if (owner !== 'gvy:') return null;
  const block = [];
  for (const line of lines.slice(start + 1)) {
    if (line.trim() === '' || line.trimStart().startsWith('#')) continue;
    if (!line.startsWith('    ')) break;
    block.push(line);
  }
  return block.flatMap(line => {
    const entry = line.match(/^ {4}- '([^']+)'$/);
    return entry ? [entry[1]] : [];
  });
}

function kotlinFiles(dir) {
  return readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter(entry => entry.isFile() && entry.name.endsWith('.kt'))
    .map(entry => join(entry.parentPath, entry.name));
}

test('the parser reads only the VolatileProperty excludes of the gvy rule set', () => {
  const config = [
    'gvy:',
    '  RegexInFunctionBody:',
    '    excludes:',
    "    - '**/other/src/**'",
    '  VolatileProperty:',
    '    active: true',
    '    excludes:',
    '    # A comment between entries.',
    "    - '**/first/src/**'",
    "    - '**/second/module/src/**'",
    'naming:',
    "    - '**/not-an-exclude/src/**'",
  ].join('\n');
  assert.deepEqual(volatileExcludes(config), ['**/first/src/**', '**/second/module/src/**']);
  assert.equal(volatileExcludes('style:\n  VolatileProperty:\n    active: true\n'), null);
  assert.equal(volatileExcludes('gvy:\n  RegexInFunctionBody:\n    active: true\n'), null);
});

test('every VolatileProperty exclude names a linted module tree that still has a @Volatile site', () => {
  const excludes = volatileExcludes(readFileSync(join(root, 'tools/lint/detekt.yml'), 'utf8'));
  assert.ok(excludes, 'tools/lint/detekt.yml must configure gvy > VolatileProperty');
  for (const exclude of excludes) {
    const module = exclude.match(/^\*\*\/(.+)\/src\/\*\*$/)?.[1];
    assert.ok(module, `${exclude} must have the form '**/<module>/src/**', a tree the lint task reads (#2480)`);
    const sites = kotlinFiles(join(root, module, 'src'))
      .filter(file => volatileAnnotation.test(readFileSync(file, 'utf8')));
    assert.ok(sites.length > 0, `${module} has no @Volatile left: remove its VolatileProperty exclude (#2474)`);
  }
});
