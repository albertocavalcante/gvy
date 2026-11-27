// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

// Keeps gvy's own detekt rules on wherever they apply (#2420, #2474). Reading the tracked files, it fails when:
//
// - the gvy rule set in tools/lint/detekt.yml is inactive or has a set-wide key, or a gvy rule block holds a line
//   other than `active`, `excludes`, a single-quoted entry or a comment;
// - VolatileProperty excludes anything; JdkAtomics excludes anything but tests, :common:concurrent and the modules
//   published on their own, which must be exactly the modules that apply maven-publish; StdlibAtomics excludes
//   anything but :common:concurrent; RegexInFunctionBody excludes anything but tests, benchmarks and its #2420
//   migration modules, a list that only shrinks; or one of the four is inactive;
// - tools/detekt-rules' own config/config.yml, which detekt adds to its defaults, sets anything but `active: true`;
// - a tracked detekt baseline holds a finding of one of the four rules;
// - Kotlin under a src/ folder, the rule module's tests aside, suppresses VolatileProperty, JdkAtomics, StdlibAtomics,
//   the gvy rule set or all rules outside sanctionedSuppressions, which lists each sanctioned site with its reason;
// - a *.gradle.kts other than the root build names detekt for anything but a detekt dependency or the multiplatform
//   `detekt.source` override;
// - a rule that excludes test sources misses a test source set, or main code sits in a folder named like one.
//
// It does not see the root build.gradle.kts, which configures detekt for every module; Gradle code that reaches detekt
// without naming it or unhooks `lint` from it; Groovy and init scripts, gradle.properties and flags such as
// `-x detekt`; or RegexInFunctionBody suppressions, which need only a comment. Kotlin outside src/main and src/test,
// such as tests/e2e, is not linted unless its module sets detekt.source (#2480).

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../..', import.meta.url));
// Tests, the facade and modules published on their own keep the JDK atomics for good (see tools/lint/detekt.yml).
const jdkAtomicsPermanent = [
  '**/src/test/**', '**/src/jvmTest/**', '**/src/commonTest/**', '**/src/jmh/**',
  '**/common/concurrent/src/**', '**/bsp/bsp-core/src/**', '**/bsp/maven-bsp/src/**', '**/parser/core/src/**',
];
// Only the facade opts into the experimental stdlib atomics; tests elsewhere do not need them either.
const stdlibAtomicsPermanent = ['**/common/concurrent/src/**'];
// RegexInFunctionBody exempts test and benchmark sources for good. The modules after them are #2420's migration list,
// which only shrinks: the pull request that hoists a module's regexes drops its line here and in tools/lint/detekt.yml.
const regexPermanent = ['**/src/test/**', '**/src/commonTest/**', '**/src/jvmTest/**', '**/src/jmh/**'];
const regexMigration = [
  '**/build-tool/src/**', '**/diagnostics/codenarc/src/**', '**/jenkins/src/**', '**/parser/core/src/**',
  '**/semantics/core/src/**',
];
// The rules whose findings no tracked detekt baseline may hold.
const unbaselined = ['VolatileProperty', 'JdkAtomics', 'StdlibAtomics', 'RegexInFunctionBody'];
// The suppression IDs that silence a gvy concurrency rule as detekt reads them: the rule ID, alone or after the rule
// set's ID, the rule set's ID itself, or all rules. Case is ignored, which covers detekt's `ALL` and `All`.
const concurrencySuppression = /^(?:all|gvy|(?:gvy[.:])?(?:VolatileProperty|JdkAtomics|StdlibAtomics))$/i;
// Every sanctioned suppression of that kind in tracked Kotlin, one entry per site, as { path, id, reason }. The
// accepted reasons are a field that an Atomic*FieldUpdater updates and a measured hot path
// (tools/detekt-rules/README.md), and each entry says which. There is none.
const sanctionedSuppressions = [];
// The only detekt lines a module build may hold: a detekt artifact as a dependency, such as
// `detektPlugins(libs.detekt.formatting)`, and the rule module's include in settings. A module may also hold a
// `detekt {}` block whose every line points detekt at the whole of its src, as the multiplatform modules do.
const moduleDetektLines = [/^"?\w+"?\(libs\.detekt\.[\w.]+\)$/, /^include\("tools:detekt-rules"\)$/];
const moduleDetektSource = 'source.setFrom(fileTree("src") { include("**/*.kt") })';

/** [config]'s lines without CRLF line ends or trailing whitespace, which YAML ignores. */
const yamlLines = config => config.replaceAll('\r\n', '\n').split('\n').map(line => line.trimEnd());

/**
 * The `gvy > <rule>` block as `{ active, excludes }`, or null when it is missing. Any line in the block other than
 * `active: true|false`, `excludes:`, a comment or a single-quoted list entry is an error, so a change of YAML style
 * cannot silently empty the list. Trailing whitespace is ignored, as YAML does.
 */
function gvyRuleConfig(config, rule) {
  const lines = yamlLines(config);
  const start = lines.indexOf(`  ${rule}:`);
  if (start < 0) return null;
  if (lines.slice(0, start).reverse().find(line => /^[^\s#]/.test(line)) !== 'gvy:') return null;
  let active;
  const excludes = [];
  for (const [offset, line] of lines.slice(start + 1).entries()) {
    // A comment at any indentation is still inside the block for YAML, so it must not end it.
    if (/^\s*(?:#.*)?$/.test(line)) continue;
    if (!line.startsWith('    ')) break;
    const setting = line.match(/^ {4}active: (true|false)$/);
    const entry = line.match(/^ {4}- '([^']+)'$/);
    if (setting) active = setting[1] === 'true';
    else if (entry) excludes.push(entry[1]);
    else if (line !== '    excludes:') {
      throw new Error(
        `Unexpected line ${start + offset + 2} in gvy > ${rule}: \`${line}\`. The guard reads only ` +
          "`    active: true` or `false`, `    excludes:`, `    - '<pattern>'` entries and comments in a gvy rule " +
          'block, so that a YAML style it does not know cannot hide an exclude. To set another key, teach ' +
          'gvyRuleConfig in tools/ci/detekt-gvy-excludes.test.mjs to read it.',
      );
    }
  }
  return { active, excludes };
}

/** Why [config] has no `gvy > <rule>` block the reader takes: the lines that look like its key, and the form read. */
function missingRule(config, rule) {
  let ruleSet = 'no rule set';
  const lookalikes = yamlLines(config).flatMap((line, index) => {
    if (/^[^\s#]/.test(line)) ruleSet = `\`${line}\``;
    const lookalike = new RegExp(`^\\s*["']?${rule}["']?\\s*:`).test(line);
    return lookalike ? [`line ${index + 1}, under ${ruleSet}: \`${line}\``] : [];
  });
  const found = lookalikes.length > 0 ? `Found ${lookalikes.join('; ')}.` : `No line names ${rule} as a key.`;
  return `tools/lint/detekt.yml must configure gvy > ${rule}, read only from a line that is exactly \`  ${rule}:\` ` +
    `(two spaces, the rule ID and a colon) in the \`gvy:\` rule set. ${found}`;
}

/**
 * The `gvy:` rule set's own keys (the lines indented two spaces, comments skipped), or null when it is missing. detekt
 * applies a set-level `active`, `excludes` or `includes` to every rule in the set.
 */
function gvyRuleSetKeys(config) {
  const lines = yamlLines(config);
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

/**
 * Every tracked file of this checkout. git runs without the variables that point it at another repository, index or
 * work tree, and the listing must hold this guard's own input, so a listing of nothing cannot pass a check vacuously.
 * Callers filter it in code, not by pathspec: GIT_GLOB_PATHSPECS or GIT_LITERAL_PATHSPECS would make a pathspec match
 * nothing.
 */
function gitFiles() {
  const env = { ...process.env };
  for (const name of ['GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE']) delete env[name];
  const listing = execFileSync('git', ['ls-files', '-z'], { cwd: root, encoding: 'utf8', env, maxBuffer: 1 << 26 });
  const files = listing.split('\0').filter(Boolean);
  assert.ok(files.includes('tools/lint/detekt.yml'), `git ls-files in ${root} lists another index: no detekt.yml`);
  return files;
}

/** Every tracked detekt baseline: any project's `detekt-baseline.xml`, and variants like `detekt-baseline-main.xml`. */
const trackedBaselines = () => gitFiles().filter(path => /(?:^|\/)detekt-baseline(?:-[^/]+)?\.xml$/.test(path));

const detektConfig = () => readFileSync(join(root, 'tools/lint/detekt.yml'), 'utf8');

/** The active `gvy > <rule>` block of [config], tools/lint/detekt.yml by default. */
function configured(rule, config = detektConfig()) {
  const block = gvyRuleConfig(config, rule);
  assert.ok(block, missingRule(config, rule));
  assert.equal(block.active, true, `${rule} must stay active, with \`    active: true\` in its block`);
  return block;
}

/** Where the line holding offset [at] of [source] ends. */
function lineEnd(source, at) {
  const end = source.indexOf('\n', at);
  return end < 0 ? source.length : end;
}

/**
 * The comment, string or character literal, or backticked name that starts at offset [at] of Kotlin [source], as
 * `{ start, end, kind }`, or null. Block comments nest, a raw string ends at its last closing quote, a `${}` template
 * may hold literals of its own, and an unterminated quote ends with its line.
 */
function tokenAt(source, at) {
  const token = (kind, end) => ({ start: at, end, kind });
  if (source.startsWith('//', at)) return token('comment', lineEnd(source, at));
  if (source.startsWith('/*', at)) {
    let depth = 0;
    for (let index = at; index < source.length;) {
      if (source.startsWith('/*', index)) depth += 1;
      else if (source.startsWith('*/', index)) depth -= 1;
      else {
        index += 1;
        continue;
      }
      index += 2;
      if (depth === 0) return token('comment', index);
    }
    return token('comment', source.length);
  }
  if (source.startsWith('"""', at)) {
    for (let index = at + 3; index < source.length;) {
      if (source.startsWith('${', index)) index = templateEnd(source, index + 2);
      else if (source.startsWith('"""', index)) {
        let end = index + 3;
        while (source[end] === '"') end += 1;
        return token('string', end);
      } else index += 1;
    }
    return token('string', source.length);
  }
  const quote = source[at];
  if (quote === '`') {
    const close = source.indexOf('`', at + 1);
    return token('name', close < 0 ? lineEnd(source, at) : Math.min(close + 1, lineEnd(source, at)));
  }
  if (quote !== '"' && quote !== "'") return null;
  const kind = quote === '"' ? 'string' : 'char';
  let index = at + 1;
  while (index < source.length && source[index] !== '\n') {
    if (source[index] === '\\') index += 2;
    else if (kind === 'string' && source.startsWith('${', index)) index = templateEnd(source, index + 2);
    else if (source[index] === quote) return token(kind, index + 1);
    else index += 1;
  }
  return token(kind, Math.min(index, source.length));
}

/** Where the `${` template whose body starts at offset [at] of [source] ends: just past its closing brace. */
function templateEnd(source, at) {
  let depth = 1;
  for (let index = at; index < source.length;) {
    const token = tokenAt(source, index);
    if (token) {
      index = token.end;
      continue;
    }
    if (source[index] === '{') depth += 1;
    else if (source[index] === '}' && (depth -= 1) === 0) return index + 1;
    index += 1;
  }
  return source.length;
}

/** The comments and literals of Kotlin [source], in order (backticked names are skipped, not returned). */
function kotlinTokens(source) {
  const tokens = [];
  for (let at = 0; at < source.length;) {
    const token = tokenAt(source, at);
    if (!token) at += 1;
    else {
      if (token.kind !== 'name') tokens.push(token);
      at = Math.max(token.end, at + 1);
    }
  }
  return tokens;
}

/** [source] with each of [tokens] blanked to spaces, line breaks kept, so offsets and line numbers still hold. */
function blanked(source, tokens) {
  let text = '';
  let at = 0;
  for (const { start, end } of tokens) {
    text += source.slice(at, start) + source.slice(start, end).replace(/[^\n]/g, ' ');
    at = end;
  }
  return text + source.slice(at);
}

/**
 * The IDs that each `Suppress(...)` or `SuppressWarnings(...)` in the code of Kotlin [source] names, as `{ line, id }`,
 * read as detekt reads them: a `detekt.` or `detekt:` prefix and every quote are dropped. Comments and literals do not
 * count, so a KDoc or a test fixture that mentions a suppression is not one.
 */
function suppressions(source) {
  if (!source.includes('Suppress')) return [];
  const tokens = kotlinTokens(source);
  const code = blanked(source, tokens);
  return [...code.matchAll(/\bSuppress(?:Warnings)?\s*\(/g)].flatMap(match => {
    const open = match.index + match[0].length;
    let close = open;
    for (let depth = 1; close < code.length; close += 1) {
      if (code[close] === '(') depth += 1;
      else if (code[close] === ')' && (depth -= 1) === 0) break;
    }
    const line = code.slice(0, match.index).split('\n').length;
    const id = ({ start, end }) => source.slice(start, end).replace(/detekt[.:]/gi, '').replaceAll('"', '');
    return tokens
      .filter(({ start, kind }) => kind === 'string' && start >= open && start < close)
      .map(literal => ({ line, id: id(literal) }));
  });
}

/** The items of [items] that [others] does not account for, one for one, compared by [key]. */
function unmatched(items, others, key) {
  const counts = new Map();
  for (const other of others) counts.set(key(other), (counts.get(key(other)) ?? 0) + 1);
  return items.filter(item => {
    const count = counts.get(key(item)) ?? 0;
    counts.set(key(item), count - 1);
    return count <= 0;
  });
}

/**
 * Every rule's `excludes` in detekt [config], as a map from `<rule set> > <rule>` to its patterns. It reads block and
 * inline lists loosely, for the test-source consistency check; the gvy rules are pinned by the strict reader above.
 */
function ruleExcludes(config) {
  const excludes = new Map();
  let ruleSet;
  let rule;
  let list;
  for (const line of yamlLines(config)) {
    if (/^\s*(?:#.*)?$/.test(line)) continue;
    const key = line.match(/^( *)([\w-]+):(.*)$/);
    const entry = line.match(/^\s*- ['"]([^'"]+)['"]/);
    if (key) {
      const [, indent, name, value] = key;
      if (indent.length === 0) [ruleSet, rule] = [name, undefined];
      else if (indent.length === 2) rule = `${ruleSet} > ${name}`;
      list = indent.length > 2 && name === 'excludes' && rule ? [] : undefined;
      if (!list) continue;
      excludes.set(rule, list);
      for (const [, single, double] of value.matchAll(/'([^']*)'|"([^"]*)"/g)) list.push(single ?? double);
    } else if (entry && list) list.push(entry[1]);
  }
  return excludes;
}

/** The test source sets of tracked Kotlin, such as `test`, `commonTest` and `jvmTest`. */
function testSourceSets() {
  const sets = gitFiles().map(path => path.match(/(?:^|\/)src\/([^/]+)\/.*\.kt$/)?.[1]);
  return [...new Set(sets.filter(set => /^(?:t|.+T)est$/.test(set)))].sort();
}

/** The text of Gradle Kotlin script [source] with its comments blanked out, as lines. */
function scriptLines(source) {
  const comments = kotlinTokens(source).filter(({ kind }) => kind === 'comment');
  return blanked(source, comments).split('\n');
}

/**
 * The lines of Gradle Kotlin script [source] that configure detekt beyond what a module build may, as `{ line, text }`.
 * A line counts when its code names detekt in any case, so `detekt {}`, `tasks.withType<Detekt>()` and
 * `tasks.named("detekt")` all show, unless it is one of [moduleDetektLines]. In a `detekt {` block, every line but the
 * source override counts. Comments never count.
 */
function detektOverrides(source) {
  const text = scriptLines(source);
  const code = blanked(source, kotlinTokens(source)).split('\n');
  const found = [];
  for (let index = 0; index < text.length; index += 1) {
    const line = text[index].trim();
    if (line === 'detekt {') {
      let end = index;
      for (let depth = 0; end < code.length; end += 1) {
        depth += (code[end].match(/\{/g)?.length ?? 0) - (code[end].match(/\}/g)?.length ?? 0);
        if (depth <= 0) break;
      }
      for (let inner = index + 1; inner < end; inner += 1) {
        const body = text[inner].trim();
        if (body && body !== moduleDetektSource) found.push({ line: inner + 1, text: body });
      }
      if (text[end]?.trim() !== '}') found.push({ line: end + 1, text: text[end]?.trim() ?? '' });
      index = end;
    } else if (/detekt/i.test(line) && !moduleDetektLines.some(form => form.test(line))) {
      found.push({ line: index + 1, text: line });
    }
  }
  return found;
}

/** Whether Gradle Kotlin script [source] applies a plugin that publishes the module, outside comments. */
const publishes = source => scriptLines(source).some(line => /maven-publish|MavenPublish|maven\.publish/.test(line));

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

test('the configuration reader ignores trailing spaces and names the forms it accepts', () => {
  const trailing = "gvy: \n  JdkAtomics: \n    active: true \n    excludes: \n    - '**/first/src/**' \n";
  assert.deepEqual(gvyRuleConfig(trailing, 'JdkAtomics'), { active: true, excludes: ['**/first/src/**'] });
  const severity = 'gvy:\n  JdkAtomics:\n    active: true\n    severity: error\n';
  assert.throws(() => gvyRuleConfig(severity, 'JdkAtomics'), error => {
    assert.match(error.message, /Unexpected line 4 in gvy > JdkAtomics: `    severity: error`/);
    assert.match(error.message, /reads only `    active: true` or `false`, `    excludes:`, `    - '<pattern>'`/);
    return true;
  });
});

test('a missing rule block says what was found and the form the guard reads', () => {
  const spaced = 'style:\n  JdkAtomics:\n    active: true\ngvy:\n  JdkAtomics :\n    active: true\n';
  assert.throws(() => configured('JdkAtomics', spaced), error => {
    assert.match(error.message, /read only from a line that is exactly `  JdkAtomics:`/);
    assert.match(error.message, /line 2, under `style:`: `  JdkAtomics:`; line 5, under `gvy:`: `  JdkAtomics :`/);
    return true;
  });
  assert.throws(() => configured('JdkAtomics', 'gvy:\n  active: true\n'), /No line names JdkAtomics as a key/);
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

test('the modules JdkAtomics exempts as published on their own are the ones that apply maven-publish', () => {
  const published = gitFiles()
    .filter(path => path.endsWith('.gradle.kts') && publishes(readFileSync(join(root, path), 'utf8')))
    .map(path => path.replace(/\/?[^/]+\.gradle\.kts$/, '') || '.')
    .sort();
  const exempt = jdkAtomicsPermanent
    .map(permanent => permanent.match(/^\*\*\/(.+)\/src\/\*\*$/)?.[1])
    .filter(module => module && module !== 'common/concurrent')
    .sort();
  const message = 'JdkAtomics exempts these modules because each is published on its own and so cannot depend on ' +
    'the unpublished :common:concurrent. When a module starts or stops applying maven-publish, change ' +
    'jdkAtomicsPermanent here and the JdkAtomics excludes in tools/lint/detekt.yml with it (#2474)';
  assert.deepEqual(published, exempt, message);
});

test('StdlibAtomics is active and excludes only :common:concurrent', () => {
  const { excludes } = configured('StdlibAtomics');
  const message = 'StdlibAtomics excludes exactly :common:concurrent, the one module that opts into the stdlib ' +
    'atomics. Suppress a sanctioned use at its site, with a reason; do not exclude tests or other modules (#2474)';
  assert.deepEqual(excludes, stdlibAtomicsPermanent, message);
  for (const permanent of stdlibAtomicsPermanent) {
    const module = permanent.match(/^\*\*\/(.+)\/src\/\*\*$/)?.[1];
    assert.ok(module && existsSync(join(root, module, 'src')), `${permanent} names no module`);
  }
});

test('RegexInFunctionBody is active, and its migration excludes only shrink', () => {
  const { excludes } = configured('RegexInFunctionBody');
  const added = excludes.filter(exclude => !regexPermanent.includes(exclude) && !regexMigration.includes(exclude));
  const never = 'Never add a RegexInFunctionBody exclude: hoist the regex into a file-scope, object or companion ' +
    'object property, or suppress a site that cannot be hoisted with a reason (#2420)';
  assert.deepEqual(added, [], never);
  const hoisted = regexMigration.filter(exclude => !excludes.includes(exclude));
  const drop = 'A module left the RegexInFunctionBody excludes: drop it from regexMigration in ' +
    'tools/ci/detekt-gvy-excludes.test.mjs in the same pull request, so its line cannot come back (#2420)';
  assert.deepEqual(hoisted, [], drop);
  for (const exclude of regexMigration) {
    const module = exclude.match(/^\*\*\/(.+)\/src\/\*\*$/)?.[1];
    assert.ok(module && existsSync(join(root, module, 'src')), `${exclude} names no module`);
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
  assert.ok(keys.includes('active: true'), 'the gvy rule set must stay active, as exactly `  active: true`');
  const unexpected = keys.filter(key => key !== 'active: true' && !/^[A-Z]\w*:$/.test(key));
  const message = 'The gvy rule set holds only `  active: true` and its rule blocks (`  <RuleId>:`). A rule-set key ' +
    'such as `excludes` or `includes` applies to every gvy rule; configure each rule instead (#2474)';
  assert.deepEqual(unexpected, [], message);
});

test('the gvy plugin default config only activates the rule set and its rules', () => {
  // detekt appends every plugin's config/config.yml to its default config, which tools/lint/detekt.yml builds upon. A
  // key there that detekt.yml leaves unset, such as VolatileProperty's excludes or any rule's includes, would apply.
  const path = 'tools/detekt-rules/src/main/resources/config/config.yml';
  const config = readFileSync(join(root, path), 'utf8');
  const keys = gvyRuleSetKeys(config);
  assert.ok(keys, `${path} must configure the gvy rule set`);
  const message = `${path} may hold only \`active: true\` for the gvy rule set and each rule. Configure a rule in ` +
    'tools/lint/detekt.yml, where this guard reads it (#2474)';
  assert.deepEqual(keys.filter(key => key !== 'active: true' && !/^[A-Z]\w*:$/.test(key)), [], message);
  assert.ok(keys.includes('active: true'), message);
  for (const rule of keys.filter(key => key !== 'active: true').map(key => key.slice(0, -1))) {
    assert.deepEqual(gvyRuleConfig(config, rule), { active: true, excludes: [] }, `${message}: gvy > ${rule}`);
  }
});

test('no tracked detekt baseline holds a finding of a gvy rule', () => {
  const finding = new RegExp(`<ID>(?:${unbaselined.join('|')}):[^<]*</ID>`, 'g');
  for (const baseline of trackedBaselines()) {
    const ids = readFileSync(join(root, baseline), 'utf8').match(finding) ?? [];
    const message = `${baseline} must not hold a finding of ${unbaselined.join(', ')}. Fix the code, or suppress a ` +
      'sanctioned site with a reason (#2420, #2474)';
    assert.deepEqual(ids, [], message);
  }
});

test('the baseline listing ignores git variables that point at another index or repository', () => {
  const expected = trackedBaselines();
  const scratch = mkdtempSync(join(tmpdir(), 'gvy-detekt-guard-'));
  try {
    execFileSync('git', ['-c', 'init.defaultBranch=main', 'init', '-q', scratch]);
    // Either one makes `git ls-files` list nothing, which would let the baseline check pass without reading a file.
    const elsewhere = { GIT_INDEX_FILE: join(scratch, 'missing-index'), GIT_DIR: join(scratch, '.git') };
    for (const [name, value] of Object.entries(elsewhere)) {
      const saved = process.env[name];
      process.env[name] = value;
      try {
        assert.deepEqual(trackedBaselines(), expected, `${name} must not change which baselines are checked`);
      } finally {
        if (saved === undefined) delete process.env[name];
        else process.env[name] = saved;
      }
    }
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('the suppression reader takes the IDs of annotations in code, as detekt reads them', () => {
  const source = [
    '@file:Suppress("JdkAtomics")',
    '// @Suppress("all") in a line comment',
    '/* @Suppress("all") /* nested */ still a comment: @Suppress("all") */',
    '/** KDoc: annotate the field `@Suppress("VolatileProperty")`. */',
    'val text = "@Suppress(\\"all\\")"',
    'val raw = """',
    '    @Suppress("all")',
    '"""',
    'val template = "${listOf("@Suppress(\\"all\\")")}"',
    "val quote = '\"'",
    "fun `it's named`() = Unit",
    '@Suppress(',
    '    "UNCHECKED_CAST",',
    '    "detekt:gvy:StdlibAtomics",',
    ')',
    'class A(@field:Suppress("""gvy""") val a: Int)',
    '@SuppressWarnings("ALL") class B',
  ].join('\n');
  assert.deepEqual(suppressions(source), [
    { line: 1, id: 'JdkAtomics' },
    { line: 12, id: 'UNCHECKED_CAST' },
    { line: 12, id: 'gvy:StdlibAtomics' },
    { line: 16, id: 'gvy' },
    { line: 17, id: 'ALL' },
  ]);
  // An unterminated literal ends with its line, so it cannot hide the next one.
  assert.deepEqual(suppressions('val broken = "open\n@Suppress("all") class C'), [{ line: 2, id: 'all' }]);
  for (const id of ['all', 'All', 'gvy', 'JdkAtomics', 'gvy:VolatileProperty', 'gvy.StdlibAtomics']) {
    assert.match(id, concurrencySuppression);
  }
  for (const id of ['UNCHECKED_CAST', 'MagicNumber', 'RegexInFunctionBody', 'gvy:RegexInFunctionBody', 'allow']) {
    assert.doesNotMatch(id, concurrencySuppression);
  }
});

test('tracked Kotlin suppresses the gvy concurrency rules, the gvy rule set or all rules only where sanctioned', () => {
  for (const { path, id, reason } of sanctionedSuppressions) {
    assert.ok(reason?.trim(), `the sanctioned @Suppress("${id}") in ${path} must give its reason`);
  }
  // Every source set, tests included: VolatileProperty and StdlibAtomics lint tests too. The rule module's own tests
  // are left out, since they feed such annotations to the rules.
  const scanned = path => /(?:^|\/)src\/.*\.kt$/.test(path) && !path.startsWith('tools/detekt-rules/src/test/');
  const sites = gitFiles()
    .filter(scanned)
    .flatMap(path => suppressions(readFileSync(join(root, path), 'utf8')).map(site => ({ path, ...site })))
    .filter(({ id }) => concurrencySuppression.test(id));
  const key = ({ path, id }) => `${path} ${id}`;
  const where = ({ path, line, id }) => `${path}:${line} ${id}`;
  const unsanctioned = unmatched(sites, sanctionedSuppressions, key).map(where);
  const message = 'Do not suppress VolatileProperty, JdkAtomics, StdlibAtomics, the gvy rule set or all rules: use ' +
    'the :common:concurrent types. A sanctioned site (a field updater, or a measured hot path) suppresses only its ' +
    'rule, with a reason, and gets an entry with that reason in sanctionedSuppressions (#2474)';
  assert.deepEqual(unsanctioned, [], message);
  const stale = unmatched(sanctionedSuppressions, sites, key).map(({ path, id }) => `${path} ${id}`);
  assert.deepEqual(stale, [], 'these sanctionedSuppressions entries match no suppression any more: remove them');
});

test('the Gradle reader takes detekt dependencies and the source override, and reports any other detekt line', () => {
  const accepted = [
    'dependencies {',
    '    detektPlugins(libs.detekt.formatting) // The formatting rules.',
    '    "detektPlugins"(libs.detekt.formatting)',
    '    compileOnly(libs.detekt.api)',
    '}',
    '// detekt { ignoreFailures = true } in a comment, and /* tasks.named("detekt") */ in a block comment.',
    'detekt {',
    '    source.setFrom(fileTree("src") { include("**/*.kt") })',
    '}',
    'val note = "detekt"',
  ].join('\n');
  // The last line is reported: a literal is not a comment, and a name in it can configure a task.
  assert.deepEqual(detektOverrides(accepted), [{ line: 10, text: 'val note = "detekt"' }]);
  const overrides = [
    ['detekt {\n    config.from(file("other.yml"))\n}', 'config.from(file("other.yml"))'],
    ['detekt {\n    config.setFrom(files())\n}', 'config.setFrom(files())'],
    ['detekt {\n    baseline = file("other.xml")\n}', 'baseline = file("other.xml")'],
    ['detekt {\n    ignoreFailures = true\n}', 'ignoreFailures = true'],
    ['detekt {\n    source.setFrom(files())\n}', 'source.setFrom(files())'],
    ['detekt {\n    source.setFrom(fileTree("src") { include("**/*.kt") })\n    buildUponDefaultConfig = false\n}',
      'buildUponDefaultConfig = false'],
    ['detekt { source.setFrom(fileTree("src") { include("**/*.kt") }) }',
      'detekt { source.setFrom(fileTree("src") { include("**/*.kt") }) }'],
    ['tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {\n    exclude("**/gls/**")\n}',
      'tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {'],
    ['tasks.named("detekt") { enabled = false }', 'tasks.named("detekt") { enabled = false }'],
    ['detektPlugins(project(":other-rules"))', 'detektPlugins(project(":other-rules"))'],
  ];
  for (const [script, text] of overrides) assert.deepEqual(detektOverrides(script).map(found => found.text), [text]);
});

test('no module build configures detekt beyond its dependencies and the multiplatform source override', () => {
  const found = gitFiles()
    .filter(path => path.endsWith('.gradle.kts') && path !== 'build.gradle.kts')
    .flatMap(path => {
      const overrides = detektOverrides(readFileSync(join(root, path), 'utf8'));
      return overrides.map(({ line, text }) => `${path}:${line} ${text}`);
    });
  const message = "The root build.gradle.kts configures detekt for every module: tools/lint/detekt.yml, the module's " +
    'own detekt-baseline.xml and failure on any finding. A module build may only add detekt artifacts as ' +
    `dependencies and, in a \`detekt {}\` block of its own, \`${moduleDetektSource}\`. Set anything else (a config, ` +
    'baseline, ignoreFailures, a source filter or a Detekt task setting) in the root build, where review sees it, or ' +
    'extend this guard (#2474)';
  assert.deepEqual(found, [], message);
});

test('the loose excludes reader takes block and inline lists of every rule', () => {
  const config = [
    'style:',
    '  MagicNumber:',
    '    active: true',
    '    excludes:',
    "    - '**/test/**'",
    "      - '**/jvmTest/**'  # A comment.",
    '    ignoreNumbers:',
    "    - '-1'",
    '  ForbiddenComment:',
    '    excludes: ["**/test/**", \'**/commonTest/**\']',
    'gvy:',
    '  JdkAtomics:',
    '    excludes:',
    "    - '**/src/test/**'",
  ].join('\n');
  assert.deepEqual(Object.fromEntries(ruleExcludes(config)), {
    'style > MagicNumber': ['**/test/**', '**/jvmTest/**'],
    'style > ForbiddenComment': ['**/test/**', '**/commonTest/**'],
    'gvy > JdkAtomics': ['**/src/test/**'],
  });
});

test('a rule lenient on test sources is lenient on every test source set', () => {
  // detekt's own defaults list the multiplatform test sets next to `**/test/**`; an override that names only `test`
  // holds commonTest and jvmTest code to the main-code rules.
  const sets = testSourceSets();
  assert.ok(sets.includes('test') && sets.includes('commonTest'), `test source sets read as ${sets.join(', ')}`);
  const missing = [...ruleExcludes(detektConfig())].flatMap(([rule, excludes]) =>
    [['**/test/**', set => `**/${set}/**`], ['**/src/test/**', set => `**/src/${set}/**`]]
      .filter(([lenient]) => excludes.includes(lenient))
      .flatMap(([, pattern]) => sets.map(pattern).filter(wanted => !excludes.includes(wanted)))
      .map(wanted => `${rule}: ${wanted}`));
  assert.deepEqual(missing, [], 'A rule that excludes test sources must exclude every test source set gvy has');
});

test('no main source path holds a folder that a test-source exclude matches', () => {
  // detekt matches excludes against absolute paths, so `**/test/**` would also skip main code in a `test` package.
  const sets = testSourceSets();
  const hidden = gitFiles().filter(path => {
    const set = path.match(/(?:^|\/)src\/([^/]+)\/.*\.kt$/)?.[1];
    return set && !sets.includes(set) && path.split('/').some(folder => sets.includes(folder));
  });
  assert.deepEqual(hidden, [], `main code in a folder named ${sets.join(', ')} would escape every test-lenient rule`);
});
