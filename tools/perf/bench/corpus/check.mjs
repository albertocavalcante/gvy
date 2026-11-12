// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { fileURLToPath } from 'node:url';
import { validateCorpus } from './validate.mjs';

if (process.argv.length > 3) {
  console.error('CORPUS_INVALID_ARGUMENTS');
  process.exitCode = 2;
} else {
  try {
    const report = await validateCorpus(process.argv[2] ?? fileURLToPath(new URL('.', import.meta.url)));
    console.log(JSON.stringify(report));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
