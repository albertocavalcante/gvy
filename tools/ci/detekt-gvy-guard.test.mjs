// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

// Keeps gvy's own detekt rules on wherever they apply (#2420, #2474). Reading the tracked files, it fails when:
//
// - `build:` in tools/lint/detekt.yml holds anything but `maxIssues: 0` and `excludeCorrectable: false`;
// - the gvy rule set is inactive or has a set-wide key, or a gvy rule block holds a line other than `active`,
//   `excludes`, a single-quoted entry or a comment;
// - VolatileProperty excludes anything; JdkAtomics excludes anything but tests, :common:concurrent and the modules
//   published on their own, which must be exactly the modules that apply a publishing plugin; StdlibAtomics excludes
//   anything but :common:concurrent; RegexInFunctionBody excludes anything but tests, benchmarks and its #2420
//   migration modules, a list that only shrinks; or one of the four is inactive;
// - tools/detekt-rules' own config/config.yml, which detekt adds to its defaults, holds anything but the gvy rule set
//   with `active: true` for it and each rule;
// - a tracked detekt baseline holds a finding of one of the four rules;
// - a .kt or .kts file under src/, outside resources, suppresses VolatileProperty, JdkAtomics, StdlibAtomics, the gvy
//   rule set or all rules outside sanctionedSuppressions, which lists each sanctioned site with its reason;
// - a *.gradle.kts other than the root build, outside resources, names detekt in code (or a detekt task in a string)
//   for anything but a detekt dependency or the multiplatform `detekt.source` override;
// - a built-in rule excludes a multiplatform test set that detekt's default does not, or main code sits in a folder
//   named like a test source set.
//
// It does not see the root build.gradle.kts, which configures detekt for every module; Gradle code that reaches detekt
// without naming it or unhooks `lint` from it; Groovy and init scripts, gradle.properties and flags such as
// `-x detekt`; RegexInFunctionBody suppressions, which need only a comment; or a suppression by a rule ID alias
// (concurrencySuppression writes the IDs out). Kotlin outside src/main and src/test, such as tests/e2e, is not linted
// unless its module sets detekt.source (#2480). Every git call runs without GIT_DIR, GIT_WORK_TREE, GIT_INDEX_FILE
// and GIT_COMMON_DIR.

import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { devNull, tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  detektOverrides, git, gitEnv, gitFiles, gvyRuleConfig, gvyRuleSetKeys, missingRule, moduleDetektSource, publishes,
  inResources, ruleExcludes, sourceSet, suppressions, topLevelBlock, topLevelKeys, unmatched,
} from './detekt-gvy-guard.mjs';

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
// set's ID, the rule set's ID itself, or all rules. Case is ignored, which covers detekt's `ALL` and `All`. The rule
// IDs are written out: none of these rules has an alias today, and a rule that gains one (an `aliases` override in its
// class) or a new concurrency rule must be added here, or its suppression goes unseen.
const concurrencySuppression = /^(?:all|gvy|(?:gvy[.:])?(?:VolatileProperty|JdkAtomics|StdlibAtomics))$/i;
// Every sanctioned suppression of that kind in tracked Kotlin, one entry per site, as { path, id, reason }. The
// accepted reasons are a field that an Atomic*FieldUpdater updates and a measured hot path
// (tools/detekt-rules/README.md), and each entry says which. There is none.
const sanctionedSuppressions = [];

// The rules that detekt 1.23.8's default config excludes from every test source set, `**/test/**`, `**/commonTest/**`,
// `**/jvmTest/**` and the Android, JS and iOS ones alike. Only these may exclude a multiplatform test set here.
const defaultTestLenient = [
  'KDocReferencesNonPublicProperty', 'UndocumentedPublicClass', 'UndocumentedPublicFunction',
  'UndocumentedPublicProperty', 'StringLiteralDuplication', 'TooManyFunctions', 'InstanceOfCheckForException',
  'ThrowingExceptionsWithoutMessageOrCause', 'TooGenericExceptionCaught', 'FunctionNaming', 'ForEachOnRange',
  'SpreadOperator', 'LateinitUsage', 'UnsafeCallOnNullableType', 'MagicNumber',
];

// Whether an exclude pattern reaches a test source set other than `test`: one of its folders, the file name aside,
// mentions "test" in any case (`**/commonTest/**`, `**/*Test/**`, `**/{common,jvm}Test/**`, `**/jvmTest/**/*.kt`) and
// is not exactly `test`.
const reachesTestSet = pattern =>
  pattern.split('/').filter((folder, index, folders) => index < folders.length - 1 || !folder.includes('.'))
    .some(folder => /test/i.test(folder) && folder !== 'test');

// The environment for git calls on a scratch repository: no global or system config, so the user's settings (hooks,
// signing, templates) cannot reach it. The real checkout keeps them, since CI may need safe.directory from there.
const hermetic = { ...process.env, GIT_CONFIG_GLOBAL: devNull, GIT_CONFIG_NOSYSTEM: '1' };

let trackedCache;
/** The tracked files of this checkout, listed once. */
const tracked = () => (trackedCache ??= gitFiles(root));
const read = path => readFileSync(join(root, path), 'utf8');

/** Every tracked detekt baseline: any project's `detekt-baseline.xml`, and variants like `detekt-baseline-main.xml`. */
const trackedBaselines = (files = tracked()) =>
  files.filter(path => /(?:^|\/)detekt-baseline(?:-[^/]+)?\.xml$/.test(path));

const detektConfig = () => read('tools/lint/detekt.yml');

/** The active `gvy > <rule>` block of [config], tools/lint/detekt.yml by default. */
function configured(rule, config = detektConfig()) {
  const block = gvyRuleConfig(config, rule);
  assert.ok(block, missingRule(config, rule));
  assert.equal(block.active, true, `${rule} must stay active, with \`    active: true\` in its block`);
  return block;
}

/** The test source sets of tracked Kotlin, such as `test`, `commonTest` and `jvmTest`. */
function testSourceSets() {
  const sets = tracked().filter(path => path.endsWith('.kt')).map(sourceSet);
  return [...new Set(sets.filter(set => /^(?:t|.+T)est$/.test(set)))].sort();
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
  const published = tracked()
    .filter(path => path.endsWith('.gradle.kts') && !inResources(path) && publishes(read(path)))
    .map(path => path.replace(/\/?[^/]+\.gradle\.kts$/, '') || '.')
    .sort();
  const exempt = jdkAtomicsPermanent
    .map(permanent => permanent.match(/^\*\*\/(.+)\/src\/\*\*$/)?.[1])
    .filter(module => module && module !== 'common/concurrent')
    .sort();
  const message = 'JdkAtomics exempts a module because it is published on its own and so cannot depend on the ' +
    'unpublished :common:concurrent. Exempt exactly the modules that apply a publishing plugin: add a module that ' +
    'starts publishing to jdkAtomicsPermanent here and the JdkAtomics excludes in tools/lint/detekt.yml, or do not ' +
    'publish it; drop the exemption of a module that stops, and move its atomics to :common:concurrent (#2474)';
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
    'tools/ci/detekt-gvy-guard.test.mjs in the same pull request, so its line cannot come back (#2420)';
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
  assert.deepEqual(topLevelKeys(config), ['style', 'gvy', 'naming']);
  assert.deepEqual(topLevelBlock('build:\n  maxIssues: 0\n  # A comment.\n  weights:\n    gvy: 0\nconfig:\n', 'build'),
    ['  maxIssues: 0', '  weights:', '    gvy: 0']);
  assert.equal(topLevelBlock('build: { maxIssues: 1000 }\n', 'build'), null);
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

test('detekt fails on any finding: build holds only maxIssues 0 and excludeCorrectable false', () => {
  const message = 'tools/lint/detekt.yml keeps `build:` to exactly `  maxIssues: 0` and ' +
    '`  excludeCorrectable: false`. A higher maxIssues, or `weights` that weigh a rule set such as gvy at 0, would ' +
    'let its findings pass (#2474)';
  const block = topLevelBlock(detektConfig(), 'build');
  assert.deepEqual([...(block ?? [])].sort(), ['  excludeCorrectable: false', '  maxIssues: 0'], message);
});

test('the gvy plugin default config holds only the gvy rule set and activates it and its rules', () => {
  // detekt appends every plugin's config/config.yml to its default config, which tools/lint/detekt.yml builds upon. A
  // key there that detekt.yml leaves unset, such as VolatileProperty's excludes or any rule's includes, would apply.
  const path = 'tools/detekt-rules/src/main/resources/config/config.yml';
  const config = read(path);
  const message = `${path} may hold only the gvy rule set, with \`active: true\` for it and each rule. Configure ` +
    'detekt in tools/lint/detekt.yml, where this guard reads it (#2474)';
  assert.deepEqual(topLevelKeys(config), ['gvy'], message);
  const keys = gvyRuleSetKeys(config);
  assert.deepEqual(keys.filter(key => key !== 'active: true' && !/^[A-Z]\w*:$/.test(key)), [], message);
  assert.ok(keys.includes('active: true'), message);
  for (const rule of keys.filter(key => key !== 'active: true').map(key => key.slice(0, -1))) {
    assert.deepEqual(gvyRuleConfig(config, rule), { active: true, excludes: [] }, `${message}: gvy > ${rule}`);
  }
});

test('no tracked detekt baseline holds a finding of a gvy rule', () => {
  const finding = new RegExp(`<ID>(?:${unbaselined.join('|')}):[^<]*</ID>`, 'g');
  for (const baseline of trackedBaselines()) {
    const ids = read(baseline).match(finding) ?? [];
    const message = `${baseline} must not hold a finding of ${unbaselined.join(', ')}. Fix the code, or suppress a ` +
      'sanctioned site with a reason (#2420, #2474)';
    assert.deepEqual(ids, [], message);
  }
});

test('the baseline listing ignores git variables that point at another index or repository', () => {
  const expected = trackedBaselines();
  const scratch = mkdtempSync(join(tmpdir(), 'gvy-detekt-guard-'));
  try {
    git(['-c', 'init.defaultBranch=main', 'init', '-q', scratch], { env: hermetic });
    // Either one makes `git ls-files` list nothing, which would let the baseline check pass without reading a file.
    const elsewhere = { GIT_INDEX_FILE: join(scratch, 'missing-index'), GIT_DIR: join(scratch, '.git') };
    for (const [name, value] of Object.entries(elsewhere)) {
      const files = gitFiles(root, { ...process.env, [name]: value });
      assert.deepEqual(trackedBaselines(files), expected, `${name} must not change the baselines read`);
    }
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('git runs without the variables that point it elsewhere, so an exported GIT_DIR cannot be rewritten', () => {
  const kept = { HOME: '/home/a', GIT_CONFIG_GLOBAL: '/a/.gitconfig' };
  const elsewhere = {
    GIT_DIR: 'a', GIT_WORK_TREE: 'b', GIT_INDEX_FILE: 'c', GIT_COMMON_DIR: 'd', GIT_OBJECT_DIRECTORY: 'e',
    GIT_ALTERNATE_OBJECT_DIRECTORIES: 'f',
  };
  assert.deepEqual(gitEnv({ ...kept, ...elsewhere }), kept);
  // A hook run in a linked worktree exports GIT_DIR=<repository>/.git/worktrees/<name>. A scratch `git init` that
  // inherited it reinitialised that repository and set its shared core.bare to true.
  const scratch = mkdtempSync(join(tmpdir(), 'gvy-detekt-guard-'));
  const repository = join(scratch, 'repository');
  const isolated = ['-c', 'init.defaultBranch=main', '-c', `core.hooksPath=${join(scratch, 'no-hooks')}`];
  const identity = ['-c', 'user.name=guard', '-c', 'user.email=guard@example.invalid', '-c', 'commit.gpgsign=false'];
  try {
    git([...isolated, 'init', '-q', repository], { env: hermetic });
    git([...isolated, ...identity, 'commit', '-q', '--allow-empty', '-m', 'empty'], { cwd: repository, env: hermetic });
    git([...isolated, 'worktree', 'add', '-q', join(scratch, 'linked')], { cwd: repository, env: hermetic });
    const exported = { ...hermetic, GIT_DIR: join(repository, '.git', 'worktrees', 'linked') };
    git([...isolated, 'init', '-q', join(scratch, 'fresh')], { env: exported });
    assert.equal(git(['config', 'core.bare'], { cwd: repository, env: hermetic }).trim(), 'false');
    git(['status', '--porcelain'], { cwd: repository, env: hermetic });
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
  // are read as well: the suppressions they feed the rules sit inside string literals, which do not count.
  const scanned = path => /\.kts?$/.test(path) && sourceSet(path) && !inResources(path);
  const sites = tracked()
    .filter(scanned)
    .flatMap(path => suppressions(read(path)).map(site => ({ path, ...site })))
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
    'description = "gvy\'s own detekt rules"',
    'dependencies {',
    '    detektPlugins(libs.detekt.formatting) // The formatting rules.',
    '    "detektPlugins"(libs.detekt.formatting)',
    '    add("detektPlugins", libs.detekt.formatting)',
    '    compileOnly(libs.detekt.api)',
    '    testRuntimeOnly(libs.detekt.test)',
    '    testFixturesImplementation(libs.detekt.test)',
    '    compileOnlyApi(libs.detekt.api)',
    '    "jvmTestImplementation"(libs.detekt.test)',
    '    implementation(libs.detekt.rules.libraries)',
    '    testImplementation(libs.detekt.test) { exclude(group = "org.jetbrains.kotlin") }',
    '}',
    'dependencies { testImplementation(libs.detekt.test) }',
    'val tool = libs.detekt.api',
    'include(":tools:detekt-rules")',
    'kover { reports { filters { excludes { packages("com.github.albertocavalcante.gvy.detekt") } } } }',
    '// detekt { ignoreFailures = true } in a comment, and /* tasks.named("detekt") */ in a block comment.',
    'detekt {',
    '    source.setFrom(fileTree("src") { include("**/*.kt") })',
    '}',
  ].join('\n');
  assert.deepEqual(detektOverrides(accepted), []);
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
    ['tasks.getByName("detektMain").enabled = false', 'tasks.getByName("detektMain").enabled = false'],
    ['tasks.getByPath(":fmt:detekt").enabled = false', 'tasks.getByPath(":fmt:detekt").enabled = false'],
    ['gradle.startParameter.excludedTaskNames.add(":fmt:detekt")',
      'gradle.startParameter.excludedTaskNames.add(":fmt:detekt")'],
    ['excludedTaskNames.add("${project.path}:detekt")', 'excludedTaskNames.add("${project.path}:detekt")'],
    ['tasks.withType(Class.forName("io.gitlab.arturbosch.detekt.Detekt") as Class<Task>) { enabled = false }',
      'tasks.withType(Class.forName("io.gitlab.arturbosch.detekt.Detekt") as Class<Task>) { enabled = false }'],
    ['plugins { id("io.gitlab.arturbosch.detekt") }', 'plugins { id("io.gitlab.arturbosch.detekt") }'],
    ['detektPlugins(project(":other-rules"))', 'detektPlugins(project(":other-rules"))'],
    ['detektPlugins("com.example:rules:1.0")', 'detektPlugins("com.example:rules:1.0")'],
    ['detekt(libs.detekt.api)', 'detekt(libs.detekt.api)'],
    ['"detekt"(libs.detekt.cli)', '"detekt"(libs.detekt.cli)'],
    ['add("detekt", libs.detekt.cli)', 'add("detekt", libs.detekt.cli)'],
  ];
  for (const [script, text] of overrides) assert.deepEqual(detektOverrides(script).map(found => found.text), [text]);
});

test('the publishing reader takes a publishing plugin by ID, class or alias, not in comments or prose', () => {
  const publishing = [
    'plugins {\n    `maven-publish`\n}',
    'plugins { `ivy-publish` }',
    'plugins { id("maven-publish") }',
    'plugins { id("com.gradle.plugin-publish") version "1.3.0" }',
    'plugins { id("com.vanniktech.maven.publish") }',
    'apply(plugin = "maven-publish")',
    'apply<MavenPublishPlugin>()',
    'plugins { alias(libs.plugins.mavenPublish) }',
    'plugins { alias(libs.plugins.gradle.plugin.publish) }',
  ];
  for (const script of publishing) assert.equal(publishes(script), true, script);
  const notPublishing = [
    'plugins { `java-library` }',
    '// plugins { `maven-publish` }',
    'description = "Not published with maven-publish"',
    'val publication = "maven-publish-notes"',
    'tasks.register("listMavenPublications")',
  ];
  for (const script of notPublishing) assert.equal(publishes(script), false, script);
});

test('no module build configures detekt beyond its dependencies and the multiplatform source override', () => {
  const found = tracked()
    .filter(path => path.endsWith('.gradle.kts') && path !== 'build.gradle.kts' && !inResources(path))
    .flatMap(path => detektOverrides(read(path)).map(({ line, text }) => `${path}:${line} ${text}`));
  const message = "The root build.gradle.kts configures detekt for every module: tools/lint/detekt.yml, the module's " +
    'own detekt-baseline.xml and failure on any finding. A module build may only add libs.detekt.* artifacts to a ' +
    `configuration other than \`detekt\`, and hold a \`detekt {}\` block of \`${moduleDetektSource}\`. Set ` +
    'anything else (a config, baseline, ignoreFailures, a source filter, a Detekt task setting or another ' +
    'detektPlugins artifact) in the root build, where review sees it, or extend this guard (#2474)';
  assert.deepEqual(found, [], message);
});

test('the loose excludes reader takes the styles detekt reads and rejects the rest', () => {
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
    '    excludes: ["**/test/**", \'**/commonTest/**\'] # A comment.',
    '  ForbiddenMethodCall:',
    "    excludes: '**/jvmTest/**,**/test/**; **/jsTest/**'",
    'gvy:',
    '  JdkAtomics:',
    '    excludes:',
    "    - '**/src/test/**'",
  ].join('\n');
  assert.deepEqual(Object.fromEntries(ruleExcludes(config)), {
    'style > MagicNumber': ['**/test/**', '**/jvmTest/**'],
    'style > ForbiddenComment': ['**/test/**', '**/commonTest/**'],
    'style > ForbiddenMethodCall': ['**/jvmTest/**', '**/test/**', '**/jsTest/**'],
    'gvy > JdkAtomics': ['**/src/test/**'],
  });
  const otherStyles = [
    "    excludes: [\n      '**/jvmTest/**',\n    ]",
    '    excludes:\n    - **/jvmTest/**',
    '    excludes: **/jvmTest/**',
    "    excludes: &tests ['**/jvmTest/**']",
    "    excludes:\n    - '**/test/**'\n      '**/jvmTest/**'",
  ];
  for (const block of otherStyles) {
    assert.throws(() => ruleExcludes(`style:\n  ForbiddenMethodCall:\n${block}\n`), /Unexpected line/, block);
  }
});

test('only rules that detekt makes lenient on tests by default exclude a multiplatform test set', () => {
  const reaching = ['**/commonTest/**', '**/*Test/**', '**/jvmTest/**/*.kt', '**/CommonTest/**', '**/jvmTest/**,x'];
  for (const pattern of reaching) {
    assert.equal(reachesTestSet(pattern), true, pattern);
  }
  for (const pattern of ['**/test/**', '**/test/**/*Test.kt', '**/dsl/**', '**/src/main/**']) {
    assert.equal(reachesTestSet(pattern), false, pattern);
  }
  // detekt.yml replaces a rule's default excludes, so it names `**/commonTest/**` and `**/jvmTest/**` where detekt's
  // default has them. Elsewhere such an exclude would widen a rule's reach past its default; dropping one is always
  // fine. The gvy rules' excludes are pinned by the tests above.
  const widened = [...ruleExcludes(detektConfig())]
    .filter(([rule]) => !rule.startsWith('gvy > ') && !defaultTestLenient.includes(rule.split(' > ')[1]))
    .flatMap(([rule, excludes]) => excludes.filter(reachesTestSet).map(exclude => `${rule}: ${exclude}`));
  const message = 'Only a rule whose detekt default excludes every test source set (defaultTestLenient in ' +
    'tools/ci/detekt-gvy-guard.test.mjs) may exclude a multiplatform test set (#2474)';
  assert.deepEqual(widened, [], message);
});

test('no main source path holds a folder that a test-source exclude matches', () => {
  // detekt matches excludes against absolute paths, so `**/test/**` would also skip main code in a `test` package.
  const sets = testSourceSets();
  const layout = 'No tracked Kotlin lies under a src/test folder, so sourceSet() in tools/ci/detekt-gvy-guard.mjs ' +
    'no longer reads the source layout and this check would pass on nothing: fix sourceSet()';
  assert.ok(sets.includes('test'), layout);
  const hidden = tracked().filter(path => {
    const set = path.endsWith('.kt') && sourceSet(path);
    return set && !sets.includes(set) && path.split('/').some(folder => sets.includes(folder));
  });
  assert.deepEqual(hidden, [], `main code in a folder named ${sets.join(', ')} would escape every test-lenient rule`);
});
