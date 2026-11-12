// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { statSync } from 'node:fs';
import { win32 } from 'node:path';

export function gradleLaunch(
  args,
  {
    platform = process.platform,
    bashPath = process.env.GVY_CI_GRADLE_BASH,
    stat = statSync,
  } = {},
) {
  if (platform !== 'win32') return { executable: './gradlew', args };
  // The workflow supplies its running Git Bash, rather than resolving PATH/WSL.
  const invalid = () =>
    new Error(
      'Windows Gradle requires an absolute existing Git Bash executable',
    );
  if (
    typeof bashPath !== 'string' ||
    !/^[a-z]:[\\/]/i.test(bashPath) ||
    /[\0\r\n]/.test(bashPath) ||
    win32.basename(bashPath).toLowerCase() !== 'bash.exe'
  )
    throw invalid();
  try {
    if (!stat(bashPath).isFile()) throw invalid();
  } catch {
    throw invalid();
  }
  return { executable: bashPath, args: ['./gradlew', ...args] };
}
