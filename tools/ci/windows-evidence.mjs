// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { spawnSync } from 'node:child_process';
import { isUtf8 } from 'node:buffer';
import { lstatSync } from 'node:fs';
import { isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';

/** The helper inspects the exact opened Windows handle without following reparse points. */
export function readWindowsEvidenceBytes(path, maxBytes) {
  if (
    !Number.isSafeInteger(maxBytes) ||
    maxBytes < 1 ||
    maxBytes > 512 * 1024 * 1024
  )
    throw new Error('Invalid evidence byte limit');
  const unavailable = () =>
    new Error('A compiled no-follow evidence reader is required on Windows');
  const executable = process.env.GVY_CI_EVIDENCE_READER;
  try {
    if (
      !executable ||
      !isAbsolute(executable) ||
      /[\0\r\n]/.test(executable) ||
      !lstatSync(executable).isFile()
    )
      throw unavailable();
  } catch {
    throw unavailable();
  }
  let input;
  try {
    const name =
      path instanceof URL
        ? fileURLToPath(path)
        : Buffer.isBuffer(path) && isUtf8(path)
          ? path.toString('utf8')
          : path;
    if (typeof name !== 'string' || !name.isWellFormed()) throw new Error();
    input = Buffer.from(name, 'utf8');
    if (input.length < 1 || input.length > 256 * 1024) throw new Error();
  } catch {
    throw new Error('Invalid Windows evidence path');
  }
  const result = spawnSync(executable, [String(maxBytes)], {
    input,
    shell: false,
    timeout: 10000,
    maxBuffer: Math.max(4096, maxBytes + 1),
    windowsHide: true,
  });
  if (result.error || result.signal || result.status !== 0) {
    const messages = {
      2: 'Invalid evidence input',
      3: 'Evidence must be a regular file without links',
      4: 'Evidence exceeds byte limit',
      6: 'ENOENT: evidence does not exist',
    };
    throw new Error(
      messages[result.status] ?? 'Cannot safely read Windows evidence',
    );
  }
  if (result.stdout.length > maxBytes)
    throw new Error('Evidence exceeds byte limit');
  return result.stdout;
}
