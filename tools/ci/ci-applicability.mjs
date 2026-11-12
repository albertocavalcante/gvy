import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const narrative = /^(?:(?:README|AGENTS|CONTRIBUTING|REVIEW|CHANGELOG)\.md|LICENSE(?:\.md|\.txt)?|(?:kb|\.agent)\/.*\.md|docs\/(?!website\/).*\.md)$/;
const bazelOnly = /^(?:\.bazel(?:rc|version|ignore)|bazel\/.*\.bazelrc|(?:.*\/)?BUILD(?:\.bazel)?|.*\.bzl|MODULE\.bazel(?:\.lock)?|third_party\/patches\/.*)$/;
const executableTree = /(?:^|\/)(?:src|resources|fixtures|snapshots|workspaces|testdata)(?:\/|$)/;
const knownNarrative = new Set(['tests/e2e/README.md', 'editors/zed/README.md',
  'editors/zed/LICENSE', 'editors/zed/LICENSE.md', 'editors/zed/LICENSE.txt']);
const sharedZedInputs = new Set(['Makefile', '.github/workflows/ci.yml']);

/** Unknown inputs are conservatively tested. Fixtures are never excluded by suffix. */
export function classifyPaths(paths) {
  const classified = [...new Set(paths)].sort().map((path) => {
    if (typeof path !== 'string' || !path || path.startsWith('/') || path.includes('\0') || path.split('/').includes('..')) {
      throw new Error(`Invalid repository path: ${JSON.stringify(path)}`);
    }
    const reason = knownNarrative.has(path) ? 'narrative documentation'
      : path.startsWith('editors/zed/') ? 'Zed extension input'
      : executableTree.test(path) ? 'build or executable input'
      : narrative.test(path) ? 'narrative documentation' : bazelOnly.test(path) ? 'Bazel configuration' : 'build or executable input';
    return { path, reason };
  });
  return {
    schemaVersion: 1,
    runMain: !classified.length || classified.some(({ reason }) => reason === 'build or executable input'),
    runBazel: !classified.length || classified.some(({ reason }) => reason === 'build or executable input' || reason === 'Bazel configuration'),
    runZed: !classified.length || classified.some(({ path, reason }) => reason === 'Zed extension input' || sharedZedInputs.has(path)),
    paths: classified,
  };
}

const git = (...args) => execFileSync('git', args, { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 }).trim();
const sha = (value) => typeof value === 'string' && /^[a-f0-9]{40}$/.test(value);

export function changedPaths(eventName, event, testedSha) {
  let base;
  let head = testedSha;
  let range = '..';
  if (eventName === 'pull_request') {
    base = event.pull_request?.base?.sha;
    head = event.pull_request?.head?.sha;
    range = '...';
  } else if (eventName === 'push') {
    base = event.before;
    if (base === '0'.repeat(40)) return [];
  } else if (eventName === 'workflow_dispatch') return [];
  else throw new Error(`Unsupported applicability event: ${eventName}`);
  if (!sha(base) || !sha(head)) throw new Error('Missing exact base/head revision.');
  // No rename detection: both the removed and added paths must influence applicability.
  return execFileSync('git', ['diff', '--name-only', '-z', '--no-renames', `${base}${range}${head}`],
    { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 }).split('\0').filter(Boolean);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const testedSha = git('rev-parse', 'HEAD');
  if (testedSha !== process.env.GITHUB_SHA) throw new Error('Checkout does not match the workflow revision.');
  const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  const report = classifyPaths(changedPaths(process.env.GITHUB_EVENT_NAME, event, testedSha));
  report.context = {
    headSha: event.pull_request?.head?.sha ?? testedSha,
    testedSha,
    runId: process.env.GITHUB_RUN_ID,
    runAttempt: process.env.GITHUB_RUN_ATTEMPT,
  };
  const output = process.argv[2];
  if (!output || !process.env.GITHUB_OUTPUT) throw new Error('Usage: node ci-applicability.mjs REPORT.json (GitHub Actions context required).');
  mkdirSync(dirname(output), { recursive: true });
  writeFileSync(output, `${JSON.stringify(report, null, 2)}\n`);
  appendFileSync(process.env.GITHUB_OUTPUT, `run_main_ci=${report.runMain}\nrun_bazel_ci=${report.runBazel}\nrun_zed_ci=${report.runZed}\n`);
  console.log(`Gradle applicable: ${report.runMain}; Bazel applicable: ${report.runBazel}; Zed applicable: ${report.runZed}; ${report.paths.length} changed paths.`);
}
