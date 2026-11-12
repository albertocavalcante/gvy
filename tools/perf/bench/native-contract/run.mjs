import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { readRegularBytes, sha256, sourcePaths, verifyFiles } from './source-files.mjs';

const root = path.dirname(fileURLToPath(import.meta.url));
const fail = code => { throw new Error(code); };
function command(executable, args, input, timeout = 300000) {
  const result = spawnSync(executable, args, {
    cwd: root, encoding: 'utf8', timeout, maxBuffer: 4 * 1024 * 1024,
    env: { ...process.env, CARGO_BUILD_JOBS: '1' }, input,
  });
  if (result.error || result.status !== 0) fail('NATIVE_CONTRACT_COMMAND_FAILED');
  return result.stdout;
}

try {
  const [engine, ...extra] = process.argv.slice(2);
  const verifyOnly = extra.length === 1 && extra[0] === '--verify-only';
  if (!['primary', 'secondary'].includes(engine) || (extra.length && !verifyOnly)) fail('NATIVE_CONTRACT_ARGUMENTS');
  const pins = JSON.parse(fs.readFileSync(path.join(root, 'source-lock.json'), 'utf8'));
  if (pins.schemaVersion !== 1) fail('SOURCE_PIN_INVALID');
  const pin = pins.engines[engine];
  const manifest = path.join(root, engine, 'Cargo.toml');
  const lockBytes = fs.readFileSync(path.join(root, engine, 'Cargo.lock'));
  if (sha256(lockBytes) !== pin.cargoLockSha256) fail('CARGO_LOCK_MISMATCH');
  const metadata = JSON.parse(command('cargo', ['metadata', '--locked', '--format-version', '1', '--manifest-path', manifest], undefined, 90000));
  const grammar = metadata.packages.find(candidate => candidate.name === pin.grammarPackage);
  if (!grammar || grammar.source !== `git+${pin.grammarRepository}?rev=${pin.grammarRevision}#${pin.grammarRevision}`) fail('GRAMMAR_IDENTITY_MISMATCH');
  const grammarRoot = path.dirname(grammar.manifest_path);
  if (JSON.stringify(sourcePaths(grammarRoot)) !== JSON.stringify(Object.keys(pin.files).sort())) fail('SOURCE_INVENTORY_MISMATCH');
  const files = verifyFiles(grammarRoot, pin.files);
  const runtime = ['tree-sitter', 'tree-sitter-highlight'].map(name => {
    const candidate = metadata.packages.find(item => item.name === name);
    if (!candidate || candidate.version !== pin.runtimeVersion || !candidate.source.startsWith('registry+')) fail('RUNTIME_IDENTITY_MISMATCH');
    const vcs = JSON.parse(fs.readFileSync(path.join(path.dirname(candidate.manifest_path), '.cargo_vcs_info.json'), 'utf8'));
    if (vcs.git.sha1 !== pin.runtimeRevision) fail('RUNTIME_REVISION_MISMATCH');
    return { package: name, version: candidate.version, revision: vcs.git.sha1 };
  });
  const queries = {};
  const queryDigests = {};
  for (const kind of ['highlights', 'locals', 'injections']) {
    const relative = `${pin.queryDirectory}/${kind}.scm`;
    queries[kind] = fs.readFileSync(path.join(grammarRoot, relative), 'utf8');
    queryDigests[kind] = { rawSha256: pin.files[relative], effectiveSha256: sha256(Buffer.from(queries[kind])), policy: 'upstream-unchanged' };
    if (queryDigests[kind].effectiveSha256 !== queryDigests[kind].rawSha256) fail('QUERY_ENCODING_MISMATCH');
  }
  const input = JSON.stringify(queries);
  if (Buffer.byteLength(input) > 64 * 1024) fail('QUERY_INPUT_LIMIT');
  if (verifyOnly) {
    console.log(JSON.stringify({ schemaVersion: 1, scope: 'source-pin-verification-only', engine, files, runtime, queryDigests }));
    process.exit(0);
  }
  command('cargo', ['build', '--release', '--locked', '--manifest-path', manifest]);
  const packageName = metadata.packages.find(candidate => candidate.id === metadata.resolve.root).name;
  const binary = path.join(metadata.target_directory, 'release', `${packageName}${process.platform === 'win32' ? '.exe' : ''}`);
  const binarySha256 = sha256(readRegularBytes(binary, 64 * 1024 * 1024));
  const native = JSON.parse(command(binary, [], input, 10000));
  if (native.schemaVersion !== 1 || native.scope !== 'native-feasibility-only' || native.engine !== engine || native.runtimeVersion !== pin.runtimeVersion || native.controlCount !== 6) fail('NATIVE_RESULT_IDENTITY_MISMATCH');
  console.log(JSON.stringify({
    schemaVersion: 1, scope: 'native-feasibility-only', engine,
    grammar: { repository: pin.grammarRepository, revision: pin.grammarRevision, files }, runtime,
    compiler: command('rustc', ['--version'], undefined, 10000).trim(),
    cargo: command('cargo', ['--version'], undefined, 10000).trim(),
    os: process.platform, arch: process.arch,
    cargoLockSha256: pin.cargoLockSha256, manifestSha256: sha256(fs.readFileSync(manifest)),
    binarySha256, queryDigests,
    fixtureDigests: Object.fromEntries(['valid', 'malformed'].map(name => [name, sha256(fs.readFileSync(path.join(root, 'fixtures', `${name}.groovy`)))])),
    native,
  }));
} catch (error) {
  const code = error instanceof Error && /^[A-Z_]+$/.test(error.message) ? error.message : 'NATIVE_CONTRACT_INVALID';
  console.error(code);
  process.exitCode = 1;
}
