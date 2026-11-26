// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

// gvy's migration rules have finished (#2474): VolatileProperty excludes nothing, and JdkAtomics only its permanent
// set (tests, :common:concurrent and the modules published as their own artifacts). A use either rule reports is
// suppressed at its site with a reason, never excluded by module. Kotlin outside src/main and src/test, such as
// tests/e2e, is not linted unless its module sets detekt.source (#2480).

import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../..', import.meta.url));
// Tests, the facade and modules published on their own keep the JDK atomics for good (see tools/lint/detekt.yml).
const jdkAtomicsPermanent = [
  '**/src/test/**', '**/src/jvmTest/**', '**/src/commonTest/**', '**/src/jmh/**',
  '**/common/concurrent/src/**', '**/bsp/bsp-core/src/**', '**/bsp/maven-bsp/src/**', '**/parser/core/src/**',
];

/**
 * The `gvy > <rule>` block as `{ active, excludes }`, or null when it is missing. Any line in the block other than
 * `active: true|false`, `excludes:`, a comment or a single-quoted list entry is an error, so a change of YAML style
 * cannot silently empty the list.
 */
function gvyRuleConfig(config, rule) {
  const lines = config.replaceAll('\r\n', '\n').split('\n');
  const start = lines.indexOf(`  ${rule}:`);
  if (start < 0) return null;
  if (lines.slice(0, start).reverse().find(line => /^\S/.test(line)) !== 'gvy:') return null;
  let active;
  const excludes = [];
  for (const line of lines.slice(start + 1)) {
    // A comment at any indentation is still inside the block for YAML, so it must not end it.
    if (/^\s*(?:#.*)?$/.test(line)) continue;
    if (!line.startsWith('    ')) break;
    const setting = line.match(/^ {4}active: (true|false)$/);
    const entry = line.match(/^ {4}- '([^']+)'$/);
    if (setting) active = setting[1] === 'true';
    else if (entry) excludes.push(entry[1]);
    else if (line !== '    excludes:') throw new Error(`Unexpected ${rule} configuration line: ${line}`);
  }
  return { active, excludes };
}

/** The active `gvy > <rule>` block of tools/lint/detekt.yml. */
function configured(rule) {
  const config = gvyRuleConfig(readFileSync(join(root, 'tools/lint/detekt.yml'), 'utf8'), rule);
  assert.ok(config, `tools/lint/detekt.yml must configure gvy > ${rule}`);
  assert.equal(config.active, true, `${rule} must stay active`);
  return config;
}

test('the configuration reader takes only the gvy VolatileProperty block and rejects other YAML styles', () => {
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
    '  # A comment at another indentation does not end the block.',
    '# Nor does one at the first column.',
    "    - '**/second/module/src/**'",
    'naming:',
    "    - '**/not-an-exclude/src/**'",
  ].join('\n');
  const expected = { active: true, excludes: ['**/first/src/**', '**/second/module/src/**'] };
  assert.deepEqual(gvyRuleConfig(config, 'VolatileProperty'), expected);
  assert.deepEqual(gvyRuleConfig(config.replaceAll('\n', '\r\n'), 'VolatileProperty'), expected);
  assert.equal(gvyRuleConfig('style:\n  VolatileProperty:\n    active: true\n', 'VolatileProperty'), null);
  assert.equal(gvyRuleConfig('gvy:\n  RegexInFunctionBody:\n    active: true\n', 'VolatileProperty'), null);
  const otherStyles = [
    '    - "**/fmt/src/**"',
    "    - '**/fmt/src/**' # note",
    "      - '**/fmt/src/**'",
    "    excludes: ['**/fmt/src/**']",
    '    includes:',
  ];
  for (const line of otherStyles) {
    const block = `gvy:\n  VolatileProperty:\n    active: true\n${line}\n`;
    assert.throws(() => gvyRuleConfig(block, 'VolatileProperty'), /Unexpected/, line);
  }
});

test('VolatileProperty is active and excludes nothing', () => {
  const { excludes } = configured('VolatileProperty');
  assert.deepEqual(excludes, [], 'suppress a sanctioned @Volatile at its site, with a reason (#2474)');
});

test('JdkAtomics is active and excludes only its permanent patterns, each naming a module', () => {
  const { excludes } = configured('JdkAtomics');
  const message = 'JdkAtomics excludes only tests, :common:concurrent and the separately published modules; ' +
    'suppress a sanctioned use at its site, with a reason (#2474)';
  assert.deepEqual([...excludes].sort(), [...jdkAtomicsPermanent].sort(), message);
  for (const permanent of jdkAtomicsPermanent) {
    const module = permanent.match(/^\*\*\/(.+)\/src\/\*\*$/)?.[1];
    if (module) assert.ok(existsSync(join(root, module, 'src')), `${permanent} names no module`);
  }
});
