// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

// Readers for tools/ci/detekt-gvy-guard.test.mjs: a strict reader for the gvy blocks of a detekt config, a loose one
// for every rule's excludes, the git listing, and a Kotlin lexer that the suppression, Gradle and publishing scans use.

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

// The only detekt lines a module build may hold: a detekt artifact as a dependency, such as
// `detektPlugins(libs.detekt.formatting)`, and the rule module's include in settings. A module may also hold a
// `detekt {}` block whose every line points detekt at the whole of its src, as the multiplatform modules do.
export const moduleDetektLines = [/^"?\w+"?\(libs\.detekt\.[\w.]+\)$/, /^include\("tools:detekt-rules"\)$/];
export const moduleDetektSource = 'source.setFrom(fileTree("src") { include("**/*.kt") })';

/** [config]'s lines without CRLF line ends or trailing whitespace, which YAML ignores. */
export const yamlLines = config => config.replaceAll('\r\n', '\n').split('\n').map(line => line.trimEnd());

/**
 * The `gvy > <rule>` block as `{ active, excludes }`, or null when it is missing. Any line in the block other than
 * `active: true|false`, `excludes:`, a comment or a single-quoted list entry is an error, so a change of YAML style
 * cannot silently empty the list. Trailing whitespace is ignored, as YAML does.
 */
export function gvyRuleConfig(config, rule) {
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
          'gvyRuleConfig in tools/ci/detekt-gvy-guard.mjs to read it.',
      );
    }
  }
  return { active, excludes };
}

/** Why [config] has no `gvy > <rule>` block the reader takes: the lines that look like its key, and the form read. */
export function missingRule(config, rule) {
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
export function gvyRuleSetKeys(config) {
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

/** The source set of [path], such as `main`, `test` or `commonTest`, when it lies under a `src/<set>/` folder. */
export const sourceSet = path => path.match(/(?:^|\/)src\/([^/]+)\//)?.[1];

// The variables that point git at another repository, index or work tree. A hook or `git rebase -x` exports GIT_DIR,
// which in a linked worktree is `<repository>/.git/worktrees/<name>`, and a `git init` that inherits it rewrites the
// shared config of that repository.
const repositoryVariables = new Set(['GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_COMMON_DIR']);

/** [env] without the variables that point git at another repository, index or work tree. */
export const gitEnv = (env = process.env) =>
  Object.fromEntries(Object.entries(env).filter(([name]) => !repositoryVariables.has(name.toUpperCase())));

/** The output of git [args], run in [cwd] with [env] scrubbed by [gitEnv]. Every git call of the guard goes here. */
export const git = (args, { cwd, env = process.env } = {}) =>
  execFileSync('git', args, { cwd, encoding: 'utf8', env: gitEnv(env), maxBuffer: 1 << 26 });

/**
 * Every tracked file of the checkout at [root], listed with [env] scrubbed. The listing must hold the guard's own
 * input, so a listing of another index, or of nothing, cannot pass a check vacuously. Callers filter it in code, not
 * by pathspec: GIT_GLOB_PATHSPECS or GIT_LITERAL_PATHSPECS would make a pathspec match nothing.
 */
export function gitFiles(root, env = process.env) {
  const files = git(['ls-files', '-z'], { cwd: root, env }).split('\0').filter(Boolean);
  assert.ok(files.includes('tools/lint/detekt.yml'), `git ls-files in ${root} lists another index: no detekt.yml`);
  return files;
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
export function kotlinTokens(source) {
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
export function blanked(source, tokens) {
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
export function suppressions(source) {
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
export function unmatched(items, others, key) {
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
export function ruleExcludes(config) {
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
export function detektOverrides(source) {
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
export const publishes = source =>
  scriptLines(source).some(line => /maven-publish|MavenPublish|maven\.publish/.test(line));
