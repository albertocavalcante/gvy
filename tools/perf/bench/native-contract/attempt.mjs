// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { classifyNativeFailure } from './diagnostics.mjs';
import { verifyFixtureBytes } from './source-files.mjs';

/** Only the closed observed rejection can become an identity-checked attempt. */
export function verifyNativeRejection(error, engine, runtime, fixtures) {
  const fail = () => { throw new Error('NATIVE_REJECTION_INVALID'); };
  if (error?.message !== 'NATIVE_PROBE_IDENTIFIER_BOUNDARY_FAILED_SECONDARY_VARIABLE' || error.nativeExitCode !== 1 || engine !== 'secondary' || typeof error.nativeOutput !== 'string' || error.nativeOutput.length > 16384) fail();
  let value;
  try { value = JSON.parse(error.nativeOutput); } catch { fail(); }
  if (!value || Object.keys(value).sort().join(',') !== 'code,engine,fixtureBytes,runtimeVersion,schemaVersion,scope' || value.schemaVersion !== 1 || value.scope !== 'native-probe-failure' || value.engine !== engine || value.runtimeVersion !== runtime || classifyNativeFailure(`NATIVE_CONTRACT_FAILED:${value.code}\n`) !== error.message) fail();
  verifyFixtureBytes(value.fixtureBytes, fixtures);
  return { failureCode: error.message, fixtureBytesVerified: true };
}
