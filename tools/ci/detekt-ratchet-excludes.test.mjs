// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

// Keeps the excludes of gvy's migration rules a ratchet that only shrinks (#2474). Each rule must stay active, and each
// migration exclude must name one module's src tree whose linted sources still hold what the rule reports, outside
// comments and strings: @Volatile for VolatileProperty, java.util.concurrent.atomic in main code for JdkAtomics. Linted
// sources are the kotlin and java folders of src/main and src/test, what the lint task's detekt run reads by default.
// A pull request that migrates a module therefore has to drop its line, and a line for an unlinted path such as
// tests/e2e (#2480) or for a clean module cannot be added. JdkAtomics also has fixed permanent excludes.

import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../..', import.meta.url));
const lintedRoots = ['src/main/kotlin', 'src/main/java', 'src/test/kotlin', 'src/test/java'];
const lintedMainRoots = ['src/main/kotlin', 'src/main/java'];
// Tests, the facade and modules published on their own keep the JDK atomics for good (see tools/lint/detekt.yml).
const jdkAtomicsPermanent = new Set([
  '**/src/test/**', '**/src/jvmTest/**', '**/src/commonTest/**', '**/src/jmh/**',
  '**/common/concurrent/src/**', '**/bsp/bsp-core/src/**', '**/bsp/maven-bsp/src/**', '**/parser/core/src/**',
]);
const kotlinVolatile = new Set(['kotlin.jvm.Volatile', 'kotlin.concurrent.Volatile']);
const literalOrComment =
  /^(?:\/\/[^\n]*|\/\*[\s\S]*?(?:\*\/|$)|"""[\s\S]*?(?:"""|$)|"(?:\\.|[^"\\\n])*"?|'(?:\\.|[^'\\\n])*'?)/;

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

/** Kotlin source with comments and string and character literals blanked out, line breaks kept. */
function codeOnly(source) {
  let code = '';
  let index = 0;
  while (index < source.length) {
    const token = source.slice(index).match(literalOrComment);
    if (token) {
      code += token[0].replace(/[^\n]/g, ' ');
      index += token[0].length;
    } else {
      code += source[index];
      index += 1;
    }
  }
  return code;
}

/** Whether [source] applies Kotlin's @Volatile, by the spellings the VolatileProperty rule recognises. */
function appliesVolatile(source) {
  const code = codeOnly(source);
  const names = new Set([...kotlinVolatile, 'Volatile']);
  for (const [, imported, alias] of code.matchAll(/^\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?/gm)) {
    const simple = alias ?? imported.split('.').pop();
    if (kotlinVolatile.has(imported)) names.add(simple);
    else if (simple === 'Volatile') names.delete('Volatile');
  }
  return [...code.matchAll(/@(?:\w+:)?\[?\s*([\w.]+)/g)].some(([, name]) => names.has(name));
}

/** Whether [source] uses the java.util.concurrent.atomic package (an import or a qualified name), outside literals. */
function usesJdkAtomics(source) {
  return /\bjava\.util\.concurrent\.atomic\b/.test(codeOnly(source));
}

function kotlinFiles(dir) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter(entry => entry.isFile() && entry.name.endsWith('.kt'))
    .map(entry => join(entry.parentPath, entry.name));
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

test('only an applied annotation counts, not comments, strings or another namesake', () => {
  assert.equal(appliesVolatile('class A {\n    @Volatile var flag = false\n}'), true);
  assert.equal(appliesVolatile('class A { @field:Volatile var flag = false }'), true);
  assert.equal(appliesVolatile('class A { @[Volatile] var flag = false }'), true);
  assert.equal(appliesVolatile('class A { @kotlin.concurrent.Volatile var flag = false }'), true);
  assert.equal(appliesVolatile('import kotlin.jvm.Volatile as JV\n\nclass A { @JV var flag = false }'), true);
  assert.equal(appliesVolatile('// Replaced the @Volatile flag with a OnceFlag.\nclass A'), false);
  assert.equal(appliesVolatile('/* @Volatile */ class A'), false);
  assert.equal(appliesVolatile('val text = "@Volatile var flag = false"'), false);
  assert.equal(appliesVolatile('val text = """\n    @Volatile var flag\n"""'), false);
  assert.equal(appliesVolatile('import com.example.Volatile\n\nclass A { @Volatile var flag = false }'), false);
  assert.equal(appliesVolatile('class Volatile\nval made = Volatile()'), false);
});

test('VolatileProperty is active and each exclude names a module whose linted sources still apply @Volatile', () => {
  const config = gvyRuleConfig(readFileSync(join(root, 'tools/lint/detekt.yml'), 'utf8'), 'VolatileProperty');
  assert.ok(config, 'tools/lint/detekt.yml must configure gvy > VolatileProperty');
  assert.equal(config.active, true, 'VolatileProperty must stay active');
  for (const exclude of config.excludes) {
    const module = exclude.match(/^\*\*\/(.+)\/src\/\*\*$/)?.[1];
    assert.ok(module, `${exclude} must have the form '**/<module>/src/**', a tree the lint task reads (#2480)`);
    const sites = lintedRoots
      .flatMap(linted => kotlinFiles(join(root, module, linted)))
      .filter(file => appliesVolatile(readFileSync(file, 'utf8')));
    assert.ok(sites.length > 0, `${module} applies no @Volatile in its linted sources: remove its exclude (#2474)`);
  }
});

test('a JDK atomics use counts only in code, as a package name and not a longer one', () => {
  assert.equal(usesJdkAtomics('import java.util.concurrent.atomic.AtomicLong'), true);
  assert.equal(usesJdkAtomics('import java.util.concurrent.atomic.*'), true);
  assert.equal(usesJdkAtomics('val counter = java.util.concurrent.atomic.AtomicLong()'), true);
  assert.equal(usesJdkAtomics('// java.util.concurrent.atomic.AtomicLong'), false);
  assert.equal(usesJdkAtomics('val text = "java.util.concurrent.atomic"'), false);
  assert.equal(usesJdkAtomics('import java.util.concurrent.atomicity.Unrelated'), false);
  assert.equal(usesJdkAtomics('import java.util.concurrent.ConcurrentHashMap'), false);
});

test('JdkAtomics is active and each migration exclude names a module whose main code uses the JDK atomics', () => {
  const config = gvyRuleConfig(readFileSync(join(root, 'tools/lint/detekt.yml'), 'utf8'), 'JdkAtomics');
  assert.ok(config, 'tools/lint/detekt.yml must configure gvy > JdkAtomics');
  assert.equal(config.active, true, 'JdkAtomics must stay active');
  for (const permanent of jdkAtomicsPermanent) {
    const module = permanent.match(/^\*\*\/(.+)\/src\/\*\*$/)?.[1];
    if (module) assert.ok(existsSync(join(root, module, 'src')), `${permanent} names no module`);
  }
  for (const exclude of config.excludes.filter(entry => !jdkAtomicsPermanent.has(entry))) {
    const module = exclude.match(/^\*\*\/(.+)\/src\/\*\*$/)?.[1];
    assert.ok(module, `${exclude} must be a permanent exclude or have the form '**/<module>/src/**'`);
    const sites = lintedMainRoots
      .flatMap(linted => kotlinFiles(join(root, module, linted)))
      .filter(file => usesJdkAtomics(readFileSync(file, 'utf8')));
    assert.ok(sites.length > 0, `${module} no longer uses the JDK atomics in main code: remove its exclude`);
  }
});
