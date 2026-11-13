// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readinessJobIds } from './check-readiness.mjs';

const dependencyOutput = "      run_dependency_check: ${{ steps.filter.outputs.run_main_ci == 'true' && (github.ref == 'refs/heads/main' || (github.event_name == 'workflow_dispatch' && inputs.dependency_updates == true)) }}";

// This reader supports ci.yml's reviewed plain job keys and immediate job fields.
// The contract separately checks the terminal validate.needs one-line value and
// dependency-check's exact applicability wiring. Other dependencies remain under
// Actions lint/GitHub validation; this does not interpret YAML or conditions.
export function readCiJobs(source) {
  if (typeof source !== 'string' || source.includes('\0') || source.includes('\r') || /^ *\t/m.test(source)) {
    throw new Error('Unsupported CI source encoding or indentation');
  }
  const lines = source.split('\n');
  if (lines.filter(line => line.startsWith('jobs:')).length !== 1 || !lines.includes('jobs:')) {
    throw new Error('Exactly one plain jobs block is required');
  }
  const jobs = new Map();
  let current;
  for (const line of lines.slice(lines.indexOf('jobs:') + 1)) {
    if (!line.trim() || line.trimStart().startsWith('#')) continue;
    if (/^ {0,3}\S/.test(line)) {
      const match = line.match(/^  ([a-z][a-z0-9-]*):$/);
      if (!match || jobs.has(match[1])) throw new Error('Unsupported or duplicate CI job declaration');
      current = { fields: new Map(), lines: [] };
      jobs.set(match[1], current);
    } else {
      if (!current) throw new Error('CI job content precedes a plain job declaration');
      current.lines.push(line);
      if (/^    \S/.test(line)) {
        const match = line.match(/^    ([a-z][a-z0-9_-]*):(?: (.*))?$/);
        if (!match || current.fields.has(match[1])) throw new Error('Unsupported or duplicate immediate CI job field');
        current.fields.set(match[1], match[2] ?? '');
      }
    }
  }
  if (!jobs.size) throw new Error('CI job graph is empty');
  return jobs;
}

function plainNeeds(value) {
  if (typeof value !== 'string') throw new Error('Missing CI needs declaration');
  const values = /^[a-z][a-z0-9-]*$/.test(value) ? [value]
    : /^\[[a-z][a-z0-9-]*(?:, [a-z][a-z0-9-]*)*\]$/.test(value) ? value.slice(1, -1).split(', ') : null;
  if (!values || new Set(values).size !== values.length) throw new Error('Unsupported or duplicate plain CI needs value');
  return values;
}

export function evaluateCiWorkflowContract(source) {
  const errors = [];
  let jobIds = [];
  let terminalNeeds = [];
  try {
    const jobs = readCiJobs(source);
    jobIds = [...jobs.keys()];
    const expected = [...readinessJobIds, 'validate'];
    if (jobIds.length !== expected.length || expected.some(name => !jobs.has(name))) {
      errors.push('CI source jobs differ from the declared readiness policy');
    }
    const observer = jobs.get('validate');
    if (observer?.fields.get('name') !== 'Validation Check' || observer.fields.get('if') !== 'always()') {
      errors.push('The terminal Validation Check must run with if: always()');
    }
    terminalNeeds = plainNeeds(observer?.fields.get('needs'));
    const nonObserver = jobIds.filter(name => name !== 'validate');
    if (terminalNeeds.length !== nonObserver.length || nonObserver.some(name => !terminalNeeds.includes(name))) {
      errors.push('Validation Check must need every non-observer CI source job exactly once');
    }
    const paths = jobs.get('check-paths');
    if (paths?.lines.filter(line => line.startsWith('      run_dependency_check:')).length !== 1 ||
        !paths.lines.includes(dependencyOutput)) {
      errors.push('Dependency applicability must use the reviewed main/path/manual policy');
    }
    if (!paths?.lines.includes('        run: node tools/ci/ci-workflow-contract.mjs')) {
      errors.push('check-paths must enforce the source job graph');
    }
    const dependency = jobs.get('dependency-check');
    if (dependency?.fields.get('needs') !== 'check-paths' ||
        dependency.fields.get('if') !== "needs.check-paths.outputs.run_dependency_check == 'true'") {
      errors.push('Dependency updates must follow verified applicability');
    }
  } catch (error) { errors.push(error.message); }
  return { schemaVersion: 1, ok: errors.length === 0, jobIds, terminalNeeds, errors };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const result = evaluateCiWorkflowContract(readFileSync(new URL('../../.github/workflows/ci.yml', import.meta.url), 'utf8'));
  console.log(JSON.stringify(result, null, 2));
  process.exitCode = result.ok ? 0 : 1;
}
