import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { runCommand } from './command.mjs';
import { verifyNativeRejection } from './attempt.mjs';
import { readRegularBytes, sha256, sourcePaths, verifyFiles, verifyFixtureBytes } from './source-files.mjs';

const root = path.dirname(fileURLToPath(import.meta.url));
const fail = code => { throw new Error(code); };
const controller = new AbortController();
const cancel = () => controller.abort();
process.once('SIGINT', cancel); process.once('SIGTERM', cancel);
const command = (executable, args, input, timeoutMs = 300000, nativeProbe = false) => runCommand(executable, args, {
  cwd: root, timeoutMs, environment: { ...process.env, CARGO_BUILD_JOBS: '1' }, input, signal: controller.signal, nativeProbe,
});

try {
  const [engine, ...extra] = process.argv.slice(2);
  const verifyOnly = extra.length === 1 && extra[0] === '--verify-only';
  const attempt = engine === 'secondary' && extra.length === 1 && extra[0] === '--attempt';
  if (!['primary', 'secondary'].includes(engine) || (extra.length && !verifyOnly && !attempt)) fail('NATIVE_CONTRACT_ARGUMENTS');
  const pins = JSON.parse(readRegularBytes(path.join(root, 'source-lock.json'), 64 * 1024).toString('utf8'));
  if (pins.schemaVersion !== 1) fail('SOURCE_PIN_INVALID');
  const pin = pins.engines[engine];
  const manifest = path.join(root, engine, 'Cargo.toml');
  const lockBytes = readRegularBytes(path.join(root, engine, 'Cargo.lock'), 128 * 1024);
  if (sha256(lockBytes) !== pin.cargoLockSha256) fail('CARGO_LOCK_MISMATCH');
  const metadata = JSON.parse(await command('cargo', ['metadata', '--locked', '--format-version', '1', '--manifest-path', manifest], undefined, 90000));
  const grammar = metadata.packages.find(candidate => candidate.name === pin.grammarPackage);
  if (!grammar || grammar.source !== `git+${pin.grammarRepository}?rev=${pin.grammarRevision}#${pin.grammarRevision}`) fail('GRAMMAR_IDENTITY_MISMATCH');
  const grammarRoot = path.dirname(grammar.manifest_path);
  if (JSON.stringify(sourcePaths(grammarRoot)) !== JSON.stringify(Object.keys(pin.files).sort())) fail('SOURCE_INVENTORY_MISMATCH');
  const files = verifyFiles(grammarRoot, pin.files);
  const runtime = ['tree-sitter', 'tree-sitter-highlight'].map(name => {
    const candidate = metadata.packages.find(item => item.name === name);
    if (!candidate || candidate.version !== pin.runtimeVersion || !candidate.source.startsWith('registry+')) fail('RUNTIME_IDENTITY_MISMATCH');
    const vcs = JSON.parse(readRegularBytes(path.join(path.dirname(candidate.manifest_path), '.cargo_vcs_info.json'), 4096).toString('utf8'));
    if (vcs.git.sha1 !== pin.runtimeRevision) fail('RUNTIME_REVISION_MISMATCH');
    return { package: name, version: candidate.version, revision: vcs.git.sha1 };
  });
  const queries = {};
  const queryDigests = {};
  for (const kind of ['highlights', 'locals', 'injections']) {
    const relative = `${pin.queryDirectory}/${kind}.scm`;
    queries[kind] = readRegularBytes(path.join(grammarRoot, relative), 64 * 1024).toString('utf8');
    queryDigests[kind] = { rawSha256: pin.files[relative], effectiveSha256: sha256(Buffer.from(queries[kind])), policy: 'upstream-unchanged' };
    if (queryDigests[kind].effectiveSha256 !== queryDigests[kind].rawSha256) fail('QUERY_ENCODING_MISMATCH');
  }
  const input = JSON.stringify(queries);
  if (Buffer.byteLength(input) > 64 * 1024) fail('QUERY_INPUT_LIMIT');
  if (verifyOnly) {
    console.log(JSON.stringify({ schemaVersion: 1, scope: 'source-pin-verification-only', engine, files, runtime, queryDigests }));
  } else {
    const fixtureBytes = Object.fromEntries(['valid', 'malformed'].map(name => [name, readRegularBytes(path.join(root, 'fixtures', `${name}.groovy`), 1024)]));
    try { await command('cargo', ['build', '--release', '--locked', '--manifest-path', manifest]); }
    catch (error) {
      if (error instanceof Error && error.message === 'NATIVE_COMMAND_FAILED') fail('NATIVE_RELEASE_BUILD_FAILED');
      throw error;
    }
    const packageName = metadata.packages.find(candidate => candidate.id === metadata.resolve.root).name;
    const binary = path.join(metadata.target_directory, 'release', `${packageName}${process.platform === 'win32' ? '.exe' : ''}`);
    const binarySha256 = sha256(readRegularBytes(binary, 64 * 1024 * 1024));
    const identity = {
      schemaVersion: 1, scope: 'native-feasibility-only', engine,
      grammar: { repository: pin.grammarRepository, revision: pin.grammarRevision, files }, runtime,
      compiler: (await command('rustc', ['--version'], undefined, 10000)).trim(),
      cargo: (await command('cargo', ['--version'], undefined, 10000)).trim(),
      os: process.platform, arch: process.arch,
      cargoLockSha256: pin.cargoLockSha256, manifestSha256: sha256(readRegularBytes(manifest, 64 * 1024)),
      binarySha256, queryDigests,
      fixtureDigests: Object.fromEntries(Object.entries(fixtureBytes).map(([name, bytes]) => [name, sha256(bytes)])),
      probeSourceDigests: Object.fromEntries(['contract.rs', `${engine}/src/main.rs`, 'run.mjs', 'command.mjs', 'diagnostics.mjs', 'source-files.mjs', 'attempt.mjs', 'feasibility-policy.mjs', 'feasibility.mjs', 'source-lock.json'].map(name => [name, sha256(readRegularBytes(path.join(root, name), 64 * 1024))])),
    };
    let output;
    try { output = await command(binary, [], input, 10000, true); }
    catch (error) {
      if (!attempt || error?.message !== 'NATIVE_PROBE_IDENTIFIER_BOUNDARY_FAILED_SECONDARY_VARIABLE') throw error;
      const rejection = verifyNativeRejection(error, engine, pin.runtimeVersion, fixtureBytes);
      console.log(JSON.stringify({
        ...identity, scope: 'native-feasibility-attempt', outcome: 'rejected-candidate', ...rejection,
        completedControls: ['unknown-predicate-rejected', 'valid-parse'], failedControl: 'exact-variable-identifier-boundary',
        notRunAfterRejection: ['malformed-error-location', 'complete-highlight-event-stream', 'regex-positive-negative', 'embedded-injection-explicitly-unsupported'],
      }));
      throw new Error(rejection.failureCode);
    }
    const native = JSON.parse(output);
    if (native.schemaVersion !== 1 || native.scope !== 'native-feasibility-only' || native.engine !== engine || native.runtimeVersion !== pin.runtimeVersion || native.controlCount !== 7) fail('NATIVE_RESULT_IDENTITY_MISMATCH');
    verifyFixtureBytes(native.fixtureBytes, fixtureBytes);
    delete native.fixtureBytes;
    console.log(JSON.stringify({ ...identity, native }));
  }
} catch (error) {
  const code = error instanceof Error && /^[A-Z_]+$/.test(error.message) ? error.message : 'NATIVE_CONTRACT_INVALID';
  console.error(code);
  process.exitCode = 1;
} finally {
  process.removeListener('SIGINT', cancel); process.removeListener('SIGTERM', cancel);
}
