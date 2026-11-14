// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { fileURLToPath } from 'node:url';

/** Fixed trusted read targets, never paths obtained from a receipt or dependency metadata. */
export function probeSourceFiles(engine) {
  if (!['primary', 'secondary'].includes(engine)) throw new Error('ENGINE_INVALID');
  const local = ['contract.rs', 'run.mjs', 'command.mjs', 'diagnostics.mjs', 'source-files.mjs', 'attempt.mjs', 'feasibility-policy.mjs', 'feasibility.mjs', 'primary-result.mjs', 'probe-sources.mjs', 'source-lock.json', `${engine}/src/main.rs`];
  return {
    ...Object.fromEntries(local.map(name => [name, fileURLToPath(new URL(name, import.meta.url))])),
    'tools/ci/check-rust-diagnostics.mjs': fileURLToPath(new URL('../../../ci/check-rust-diagnostics.mjs', import.meta.url)),
    'tools/ci/read-evidence.mjs': fileURLToPath(new URL('../../../ci/read-evidence.mjs', import.meta.url)),
    'tools/ci/windows-evidence.mjs': fileURLToPath(new URL('../../../ci/windows-evidence.mjs', import.meta.url)),
    // Platform-limited source identity; this does not bind an environment-selected executable.
    'tools/ci/windows-evidence-reader.c': fileURLToPath(new URL('../../../ci/windows-evidence-reader.c', import.meta.url)),
  };
}
