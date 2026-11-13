// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import * as fs from 'node:fs';
import { dirname, join } from 'node:path';
import { createHash } from 'node:crypto';
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
export function readBounded(file, maximum = 1024 * 1024, io = fs) {
  const before = io.lstatSync(file);
  if (!before.isFile() || before.nlink !== 1 || before.uid !== process.getuid() || before.size > maximum || io.realpathSync(file) !== file) throw Error('Noncanonical, linked or oversized input: ' + file);
  const fd = io.openSync(file, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
  try {
    const first = io.fstatSync(fd), out = Buffer.alloc(maximum + 1); let size = 0;
    if (!first.isFile() || first.dev !== before.dev || first.ino !== before.ino || first.uid !== before.uid || first.nlink !== 1 || first.size > maximum) throw Error('Input changed before open');
    while (size < out.length) { const count = io.readSync(fd, out, size, out.length - size, null); if (!count) break; size += count; }
    const after = io.fstatSync(fd), last = io.lstatSync(file);
    if (size !== first.size || size > maximum || [after, last].some(st => st.dev !== first.dev || st.ino !== first.ino || st.size !== first.size || st.mtimeMs !== first.mtimeMs || st.ctimeMs !== first.ctimeMs || st.nlink !== 1)) throw Error('Input changed during bounded read');
    return out.subarray(0, size);
  } finally { io.closeSync(fd); }
}
export function exclusive(file, value, io = fs) {
  const bytes = Buffer.isBuffer(value) ? value : Buffer.from(JSON.stringify(value, null, 2) + '\n');
  const fd = io.openSync(file, 'wx', 0o600);
  try { io.writeFileSync(fd, bytes); io.fsyncSync(fd); } finally { io.closeSync(fd); }
}
export function syncDirectory(directory, io = fs) {
  const fd = io.openSync(directory, 'r');
  try { io.fsyncSync(fd); } finally { io.closeSync(fd); }
}
export function intent(directory, value, io = fs) {
  exclusive(join(directory, 'intent.json'), value, io);
  syncDirectory(directory, io); syncDirectory(dirname(directory), io);
}
export function retainOutcome(record, stdout, stderr, save) {
  const files = {}, failures = [];
  for (const [name, value] of [['command.json', record], ['stdout', stdout], ['stderr', stderr]]) {
    try { save(name, value); files[name] = true; }
    catch (error) { files[name] = false; failures.push({ file: name, error: String(error.message ?? error) }); }
  }
  try { save('persistence.json', { knownCommand: record, files, failures }); }
  catch (error) { failures.push({ file: 'persistence.json', error: String(error.message ?? error) }); }
  if (failures.length) {
    try { save('failure.json', { knownCommand: record, failures, successSuperseded: true }); }
    catch (error) { failures.push({ file: 'failure.json', error: String(error.message ?? error) }); }
  }
  return { ok: failures.length === 0 && record.status === 0 && !record.signal && !record.error && record.lifecycle?.rawComplete === true && record.lifecycle.cleanupComplete === true && !record.lifecycle.ambiguous && !record.lifecycle.budgetOverrun,
    ...record, persistence: { files, failures } };
}
export function budget(root, reserve = 0, io = fs) {
  let bytes = 0, entries = 0;
  function walk(directory, depth) {
    if (depth > 32) throw Error('Evidence depth exceeded');
    for (const entry of io.readdirSync(directory, { withFileTypes: true })) {
      if (++entries > 8192) throw Error('Evidence entry limit');
      const file = join(directory, entry.name), st = io.lstatSync(file);
      if (st.isDirectory()) walk(file, depth + 1);
      else if (st.isFile() && st.nlink === 1) bytes += st.size;
      else throw Error('Linked or nonregular evidence');
      if (bytes + reserve > 128 * 1024 * 1024) throw Error('Evidence budget exceeded');
    }
  }
  if (!Number.isSafeInteger(reserve) || reserve < 0) throw Error('Invalid reserve');
  walk(root, 0); return bytes;
}
