import { appendFileSync, existsSync, readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const fingerprint = text => createHash('sha256').update(text).digest('hex');

// A small lexical inventory, not a Starlark evaluator. Strings/comments cannot introduce calls.
function tokens(text) {
  const result = [];
  const pattern = /\s+|#[^\n]*|"""[\s\S]*?"""|'''[\s\S]*?'''|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[A-Za-z_][A-Za-z_0-9]*|[^\s]/gy;
  let match;
  while ((match = pattern.exec(text))) {
    const value = match[0];
    if (/^\s|^#/.test(value)) continue;
    result.push(value);
  }
  const qualified = [];
  for (let index = 0; index < result.length; index++) {
    if (result[index] === '.' && /^[A-Za-z_]/.test(qualified.at(-1) ?? '') && /^[A-Za-z_]/.test(result[index + 1] ?? '')) {
      qualified[qualified.length - 1] += '.' + result[++index];
    } else qualified.push(result[index]);
  }
  return qualified;
}

function calls(all) {
  const result = [];
  for (let index = 0; index < all.length - 1; index++) {
    if (!/^[A-Za-z_]/.test(all[index]) || all[index + 1] !== '(') continue;
    let depth = 1;
    let end = index + 2;
    for (; end < all.length && depth; end++) {
      if ('([{'.includes(all[end])) depth++;
      else if (')]}'.includes(all[end])) depth--;
    }
    if (depth) throw new Error(`Unclosed Starlark call: ${all[index]}`);
    const body = all.slice(index + 2, end - 1);
    const attributes = new Map();
    let start = 0;
    depth = 0;
    for (let offset = 0; offset <= body.length; offset++) {
      const token = body[offset];
      if (offset === body.length || (token === ',' && depth === 0)) {
        const argument = body.slice(start, offset);
        if (argument[1] === '=') attributes.set(argument[0], argument.slice(2));
        start = offset + 1;
      } else if ('([{'.includes(token)) depth++;
      else if (')]}'.includes(token)) depth--;
    }
    result.push({ name: all[index], body, attributes });
  }
  return result;
}

function literal(values, description) {
  if (values?.length !== 1 || !/^['"]/.test(values[0])) throw new Error(`Inventory needs a literal ${description}`);
  return values[0].slice(1, -1);
}

export function inventory(files, manualSources = []) {
  const found = new Map();
  function add(id, content) {
    if (found.has(id)) throw new Error(`Duplicate inventory source: ${id}`);
    found.set(id, { id, sha256: fingerprint(content) });
  }
  for (const [path, text] of files) {
    if (path.endsWith('.patch')) add(`${path}:patch`, text);
    if (!/(?:\.bzl|\.bazel|(?:^|\/)WORKSPACE(?:\.bazel)?)$/.test(path)) continue;
    const all = tokens(text);
    const parsed = calls(all);
    const downloadNames = new Set(['http_file', 'http_archive', 'git_repository', 'new_git_repository']);
    for (const call of parsed.filter(value => value.name === 'load')) {
      for (const [alias, value] of call.attributes) {
        if (value.length === 1 && downloadNames.has(value[0].slice(1, -1))) downloadNames.add(alias);
      }
    }
    if (parsed.some(call => downloadNames.has(call.name) ||
      (path.endsWith('.bzl') && /\.(?:download|download_and_extract)$/.test(call.name)))) {
      // Include versions, URL templates, checksums and loop data defined outside the call.
      add(`${path}:direct-download`, text);
    }
    for (const call of parsed) {
      if (['archive_override', 'git_override', 'local_path_override', 'single_version_override', 'multiple_version_override'].includes(call.name)) {
        const moduleName = literal(call.attributes.get('module_name'), 'module_name');
        const declaration = parsed.find(value => value.name === 'bazel_dep' && value.attributes.get('name')?.[0]?.slice(1, -1) === moduleName);
        add(`${path}:${call.name}:${moduleName}`, JSON.stringify([call.body, declaration?.attributes.get('version')]));
      } else if (/\.override$/.test(call.name) && call.attributes.has('coordinates')) {
        add(`${path}:maven.override:${literal(call.attributes.get('coordinates'), 'coordinates')}`, JSON.stringify(call.body));
      } else if (/\.install$/.test(call.name) && call.attributes.has('artifacts')) {
        const name = call.attributes.has('name') ? literal(call.attributes.get('name'), 'Maven repository name') : 'maven';
        const prefix = `${path}:maven.install:${name}`;
        const lock = call.attributes.get('lock_file');
        const strict = call.attributes.get('fail_if_repin_required');
        // Unknown/triple/raw/escaped strings remain debt; do not partially decode delimiters.
        const plainString = lock?.length === 1 && /^(?:"[^"\\\r\n]*"|'[^'\\\r\n]*')$/.test(lock[0]);
        const literalPath = plainString && lock[0].slice(1, -1).trim();
        if (!literalPath || strict?.join('') !== 'True') {
          add(`${prefix}:unlocked`, JSON.stringify([lock ?? [], strict ?? []]));
        }
        const checksum = call.attributes.get('fail_on_missing_checksum');
        // rules_jvm_external 6.10 defaults to True; unknown explicit expressions may disable it.
        if (checksum && !(checksum.length === 1 && checksum[0] === 'True')) {
          add(`${prefix}:missing-checksums`, JSON.stringify(checksum));
        }
        if (call.attributes.get('fetch_sources')?.join('') !== 'True') add(`${prefix}:sources-disabled`, 'fetch_sources disabled');
        const options = call.attributes.get('additional_coursier_options');
        if (options?.some(value => /^['"]--enable-modules['"]$/.test(value))) add(`${prefix}:gmm`, JSON.stringify(call.body));
      }
    }
  }
  if (!Array.isArray(manualSources)) throw new Error('manualSources must be an array');
  for (const source of manualSources) {
    if (typeof source?.id !== 'string' || !source.id.trim() || typeof source.path !== 'string') throw new Error('Invalid manual source');
    if (typeof source.anchor !== 'string' || !source.anchor.trim()) throw new Error('Invalid manual anchor');
    const text = files.get(source.path);
    if (text === undefined) throw new Error(`Missing manual source: ${source.path}`);
    if (!text.includes(source.anchor)) throw new Error(`Missing manual anchor: ${source.id}`);
    add(`manual:${source.id}`, text);
  }
  return [...found.values()].sort((left, right) => left.id.localeCompare(right.id, 'en'));
}

function validDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

export function evaluate(discovered, records, now = new Date()) {
  const errors = [];
  const known = new Map();
  const ids = new Set();
  if (!Array.isArray(records)) return { ok: false, errors: ['records must be an array'] };
  const today = now.toISOString().slice(0, 10);
  for (const entry of records) {
    const label = entry?.id ?? '<unnamed>';
    for (const field of ['id', 'issue', 'responsibleScope', 'reason', 'upstreamStatus', 'upstreamTrigger',
      'lastVerified', 'reviewDue', 'verification', 'removalCriteria', 'nextAction']) {
      if (typeof entry?.[field] !== 'string' || !entry[field].trim()) errors.push(`${label} requires ${field}`);
    }
    if (ids.has(label)) errors.push(`Duplicate record: ${label}`);
    ids.add(label);
    if (!/^https:\/\/github\.com\/albertocavalcante\/gvy\/issues\/[1-9][0-9]*$/.test(entry?.issue)) errors.push(`${label} requires a gvy issue URL`);
    if (!Array.isArray(entry?.evidence) || !entry.evidence.length || entry.evidence.some(url => {
      try { return new URL(url).protocol !== 'https:'; } catch { return true; }
    })) errors.push(`${label} requires HTTPS evidence URLs`);
    if (!validDate(entry?.lastVerified) || !validDate(entry?.reviewDue)) errors.push(`${label} has an invalid verification/review date`);
    else {
      if (entry.lastVerified > today) errors.push(`${label} verification is in the future`);
      if (entry.reviewDue < today) errors.push(`${label} review overdue: ${entry.reviewDue} (${entry.issue})`);
      const days = (Date.parse(entry.reviewDue) - Date.parse(entry.lastVerified)) / 86400000;
      if (days < 0 || days > 90) errors.push(`${label} review must be within 90 days after verification`);
    }
    if (!Array.isArray(entry?.sources) || !entry.sources.length) errors.push(`${label} requires exact sources`);
    else for (const source of entry.sources) {
      if (typeof source?.id !== 'string' || !source.id.trim() || !/^[a-f0-9]{64}$/.test(source.sha256)) {
        errors.push(`${label} has an invalid source fingerprint`);
        continue;
      }
      if (known.has(source.id)) errors.push(`Duplicate source: ${source.id}`);
      known.set(source.id, source.sha256);
    }
  }
  const current = new Set();
  for (const source of discovered) {
    current.add(source.id);
    if (!known.has(source.id)) errors.push(`Untracked source: ${source.id}`);
    else if (known.get(source.id) !== source.sha256) errors.push(`Changed source needs verification: ${source.id}`);
  }
  for (const id of known.keys()) if (!current.has(id)) errors.push(`Stale source: ${id}; remove or correct its record`);
  return { ok: errors.length === 0, errors };
}

function main(args) {
  let root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
  let list = false;
  for (let index = 0; index < args.length; index++) {
    if (args[index] === '--root' && args[index + 1]) root = resolve(args[++index]);
    else if (args[index] === '--inventory') list = true;
    else throw new Error('Usage: dependency-maintenance.mjs [--root DIR] [--inventory]');
  }
  const paths = execFileSync('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z'], { cwd: root, encoding: 'utf8' }).split('\0').filter(Boolean);
  const ledger = JSON.parse(readFileSync(resolve(root, 'tools/ci/dependency-maintenance.json'), 'utf8'));
  const manualPaths = new Set(Array.isArray(ledger.manualSources) ? ledger.manualSources.map(source => source.path) : []);
  const files = new Map();
  for (const path of new Set(paths)) {
    if (existsSync(resolve(root, path)) && (/\.(?:bzl|bazel|patch)$|(?:^|\/)WORKSPACE$/.test(path) || manualPaths.has(path))) {
      files.set(path, readFileSync(resolve(root, path), 'utf8'));
    }
  }
  const discovered = inventory(files, ledger.manualSources);
  if (list) {
    console.log(JSON.stringify(discovered, null, 2));
    return;
  }
  const result = evaluate(discovered, ledger.records);
  const escape = value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
  const summary = [
    '## Temporary dependency maintenance', '',
    `**${result.ok ? 'PASS' : 'FAIL'}**: ${discovered.length} exact sources; ${ledger.records?.length ?? 0} owned records.`, '',
    ...result.errors.map(error => `- ${escape(error)}`), '',
    ...(Array.isArray(ledger.records) ? ledger.records : []).map(entry =>
      `- **${escape(entry.id)}** ([tracker](${escape(entry.issue)})); ${escape(entry.responsibleScope)}; verified ${escape(entry.lastVerified)}, review due ${escape(entry.reviewDue)}. Status: ${escape(entry.upstreamStatus)} Trigger: ${escape(entry.upstreamTrigger)} Evidence: ${escape(entry.verification)} Next: ${escape(entry.nextAction)} Removal: ${escape(entry.removalCriteria)}`), '',
    'See docs/dependency-maintenance.md. Inventory is static; a passing check is not candidate integration evidence.', '',
  ].join('\n');
  console.log(summary);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
  if (!result.ok) process.exitCode = 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(process.argv.slice(2)); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
