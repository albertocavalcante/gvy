// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { realpathSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { readEvidenceText } from './read-evidence.mjs';

const MAX_BYTES = 16 * 1024 * 1024;

// Only SGR colors and CRLF are normalized; all other controls are invalid evidence.
export function classifyRustDiagnostics(text) {
  if (typeof text !== 'string' || !text || !text.endsWith('\n')) {
    return { ok: false, valid: false, diagnostics: [] };
  }
  const normalized = text.replace(/\x1b\[[0-9;]*m/g, '').replace(/\r\n/g, '\n');
  if (!normalized.trim() || /[\x00-\x08\x0b-\x1f\x7f-\x9f]/u.test(normalized)) {
    return { ok: false, valid: false, diagnostics: [] };
  }
  const diagnostics = [...new Set(normalized.split('\n')
    .filter(line => /^\s*(?:warn|warning|error)(?:\[[^\]\n]+\])?:/i.test(line))
    .map(line => line.trim()))];
  return { ok: diagnostics.length === 0, valid: true, diagnostics };
}

function main(args) {
  if (args.length !== 1) {
    console.error('Rust diagnostics: expected one complete log.');
    return 1;
  }
  let result;
  try {
    result = classifyRustDiagnostics(readEvidenceText(args[0], MAX_BYTES));
  } catch {
    console.error('Rust diagnostics: unable to read valid evidence.');
    return 1;
  }
  if (!result.valid) {
    console.error('Rust diagnostics: invalid or incomplete evidence.');
    return 1;
  }
  if (!result.ok) {
    for (const diagnostic of result.diagnostics) console.error(diagnostic);
    console.error('Rust diagnostics: warnings or errors detected.');
    return 1;
  }
  console.log('Rust diagnostics: no warnings or errors detected.');
  return 0;
}

if (process.argv[1]) {
  try {
    if (realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))) {
      process.exitCode = main(process.argv.slice(2));
    }
  } catch {
    console.error('Rust diagnostics: unable to identify entry point.');
    process.exitCode = 1;
  }
}
