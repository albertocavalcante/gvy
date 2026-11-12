// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateFeasibility } from './feasibility-policy.mjs';
import { readRegularBytes } from './source-files.mjs';

try {
  const [primaryPath, secondaryPath, exit, binaryDigest, ...extra] = process.argv.slice(2);
  if (!primaryPath || !secondaryPath || exit !== '1' || !/^[a-f0-9]{64}$/.test(binaryDigest ?? '') || extra.length) throw new Error();
  const read = file => JSON.parse(readRegularBytes(file, 128 * 1024).toString('utf8'));
  const policy = read(path.join(path.dirname(fileURLToPath(import.meta.url)), 'feasibility-policy.json'));
  console.log(JSON.stringify(validateFeasibility(read(primaryPath), read(secondaryPath), { secondaryExitCode: Number(exit), secondaryBinarySha256: binaryDigest }, policy)));
} catch {
  console.error('FEASIBILITY_POLICY_MISMATCH');
  process.exitCode = 1;
}
