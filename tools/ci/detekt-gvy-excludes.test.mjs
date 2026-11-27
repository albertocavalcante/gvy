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
  if (lines.slice(0, start).reverse().find(line => /^[^\s#]/.test(line)) !== 'gvy:') return null;
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

/**
 * The `gvy:` rule set's own keys (the lines indented two spaces, comments skipped), or null when it is missing. detekt
 * applies a set-level `active`, `excludes` or `includes` to every rule in the set.
 */
function gvyRuleSetKeys(config) {
  const lines = config.replaceAll('\r\n', '\n').split('\n');
  const start = lines.indexOf('gvy:');
  if (start < 0) return null;
  const keys = [];
  for (const line of lines.slice(start + 1)) {
    if (/^\s*(?:#.*)?$/.test(line)) continue;
    if (/^\S/.test(line)) break;
    if (/^ {2}\S/.test(line)) keys.push(line.slice(2));
  }
  return keys;
}

/** The directory of every Gradle project in settings.gradle.kts, relative to the root (which is `.`). */
function projectDirs() {
  const settings = readFileSync(join(root, 'settings.gradle.kts'), 'utf8');
  const dirs = new Map([[':', '.']]);
  for (const [, args] of settings.matchAll(/^\s*include\(([^)]*)\)/gm)) {
    for (const [, name] of args.matchAll(/"([^"]+)"/g)) {
      const path = name.replace(/^:/, '');
      dirs.set(`:${path}`, path.replaceAll(':', '/'));
    }
  }
  for (const [, name, dir] of settings.matchAll(/^\s*project\("([^"]+)"\)\.projectDir\s*=\s*file\("([^"]+)"\)/gm)) {
    dirs.set(name, dir);
  }
  return [...dirs.values()];
}

const detektConfig = () => readFileSync(join(root, 'tools/lint/detekt.yml'), 'utf8');

/** The active `gvy > <rule>` block of tools/lint/detekt.yml. */
function configured(rule) {
  const config = gvyRuleConfig(detektConfig(), rule);
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
  const message = 'JdkAtomics excludes exactly tests, :common:concurrent and the separately published modules. ' +
    'Suppress a sanctioned use at its site, with a reason; when a module starts or stops being published on its ' +
    'own, change jdkAtomicsPermanent here and tools/lint/detekt.yml together (#2474)';
  assert.deepEqual([...excludes].sort(), [...jdkAtomicsPermanent].sort(), message);
  for (const permanent of jdkAtomicsPermanent) {
    const module = permanent.match(/^\*\*\/(.+)\/src\/\*\*$/)?.[1];
    if (module) assert.ok(existsSync(join(root, module, 'src')), `${permanent} names no module`);
  }
});

test('the gvy rule set reader takes only two-space keys, so set-wide excludes and includes show', () => {
  const config = [
    'style:',
    '  active: false',
    '# A comment before the rule set.',
    'gvy:',
    '  active: true',
    '  # A comment.',
    '  excludes:',
    "  - '**/gls/src/**'",
    '# A first-column comment does not end the rule set, nor hide it from a rule block.',
    '  VolatileProperty:',
    '    active: true',
    'naming:',
    '  active: false',
  ].join('\n');
  assert.deepEqual(gvyRuleSetKeys(config), ['active: true', 'excludes:', "- '**/gls/src/**'", 'VolatileProperty:']);
  assert.equal(gvyRuleSetKeys('style:\n  active: true\n'), null);
  assert.deepEqual(gvyRuleConfig(config, 'VolatileProperty'), { active: true, excludes: [] });
});

test('the gvy rule set is active and takes no set-wide excludes or includes', () => {
  const keys = gvyRuleSetKeys(detektConfig());
  assert.ok(keys, 'tools/lint/detekt.yml must configure the gvy rule set');
  assert.ok(keys.includes('active: true'), 'the gvy rule set must stay active');
  const unexpected = keys.filter(key => key !== 'active: true' && !/^[A-Z]\w*:$/.test(key));
  assert.deepEqual(unexpected, [], 'a gvy rule-set key applies to every gvy rule; configure each rule instead (#2474)');
});

test('no detekt baseline holds a VolatileProperty or JdkAtomics finding', () => {
  const dirs = projectDirs();
  assert.ok(dirs.includes('gls') && dirs.includes('bsp/bsp-core'), 'settings.gradle.kts projects must be found');
  for (const dir of dirs) {
    const baseline = join(root, dir, 'detekt-baseline.xml');
    if (!existsSync(baseline)) continue;
    const ids = readFileSync(baseline, 'utf8').match(/<ID>(?:VolatileProperty|JdkAtomics):[^<]*<\/ID>/g) ?? [];
    assert.deepEqual(ids, [], `${dir}/detekt-baseline.xml must not hold the gvy migration rules' findings (#2474)`);
  }
});
