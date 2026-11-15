// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { runCommand } from './command.mjs';
import { readRegularBytes, sha256 } from './source-files.mjs';

const root = path.dirname(fileURLToPath(import.meta.url));
const bench = path.resolve(root, '..');
const sources = {
  manifest: path.join(root, 'primary/Cargo.toml'),
  lock: path.join(root, 'primary/Cargo.lock'),
  sourceLock: path.join(root, 'source-lock.json'),
  binarySource: path.join(root, 'primary/src/bin/native-parse.rs'),
  inputs: path.join(bench, 'parse/inputs.json'),
  recipe: path.join(root, 'build-parse.mjs'),
};
const fixtureDefinitions = [
  { id: 'sample', path: 'sample.groovy', byteLength: 42, sha256: '350b181b863a855df6081b25f16fdb02caae407022ca9e2f7494f153c751da1e' },
  { id: 'cli-builder-exception', path: '../corpus/fixtures/upstream/groovy-cli-exception/CliBuilderException.groovy', byteLength: 1034, sha256: '3fda51167bdd4f018e23622c3ee62b633c5ae58e0fa18674fc6d925f6037e28c' },
];

export function verifyBuildInputs() {
  const contents = Object.fromEntries(Object.entries(sources).map(([key, file]) => [key, readRegularBytes(file, 128 * 1024)]));
  const pins = JSON.parse(contents.sourceLock).engines.primary;
  const manifest = contents.manifest.toString('utf8');
  if (pins.grammarPackage !== 'dekobon-tree-sitter-groovy' || pins.grammarRepository !== 'https://github.com/dekobon/tree-sitter-groovy' || pins.runtimeVersion !== '0.27.2' || pins.grammarRevision !== 'be0e3922cfcea075f37bdc1abd5463b953c5cd68' || sha256(contents.lock) !== pins.cargoLockSha256 || !manifest.includes('tree-sitter = "=0.27.2"') || !manifest.includes(`rev = "${pins.grammarRevision}"`)) throw new Error('NATIVE_BUILD_PIN_MISMATCH');
  const inputContract = JSON.parse(contents.inputs);
  if (inputContract.schemaVersion !== 1 || JSON.stringify(inputContract.inputs) !== JSON.stringify(fixtureDefinitions)) throw new Error('NATIVE_BUILD_INPUT_CONTRACT_MISMATCH');
  for (const fixture of fixtureDefinitions) {
    const bytes = readRegularBytes(path.resolve(bench, 'parse', fixture.path), fixture.byteLength);
    if (bytes.length !== fixture.byteLength || sha256(bytes) !== fixture.sha256) throw new Error('NATIVE_BUILD_FIXTURE_MISMATCH');
  }
  return Object.fromEntries(Object.entries(contents).map(([key, bytes]) => [key, sha256(bytes)]));
}

async function main() {
  const [target] = process.argv.slice(2);
  if (process.argv.length !== 3 || !target || !path.isAbsolute(target)) throw new Error('NATIVE_BUILD_ARGUMENTS');
  for (const [name, value] of Object.entries(process.env)) {
    if (value && (['RUSTFLAGS', 'RUSTC', 'RUSTC_WRAPPER', 'RUSTC_WORKSPACE_WRAPPER', 'RUSTUP_TOOLCHAIN', 'RUSTC_BOOTSTRAP', 'RUSTDOCFLAGS', 'CARGO_ENCODED_RUSTFLAGS', 'TARGET_CC', 'HOST_CC', 'CRATE_CC_NO_DEFAULTS', 'CC', 'CFLAGS', 'CXX', 'CXXFLAGS', 'AR'].includes(name) || /^(?:CC|CFLAGS|CXX|CXXFLAGS|AR)_/.test(name) || /^CARGO_(?:PROFILE_|TARGET_.*_(?:RUSTFLAGS|LINKER|RUNNER)$|BUILD_(?!JOBS$|TARGET_DIR$))/.test(name))) throw new Error('NATIVE_BUILD_ENVIRONMENT_OVERRIDE');
  }
  const configRoots = new Set([path.resolve(process.env.CARGO_HOME || path.join(process.env.HOME || '', '.cargo'))]);
  for (let dir = root; ; dir = path.dirname(dir)) {
    configRoots.add(path.join(dir, '.cargo'));
    if (path.dirname(dir) === dir) break;
  }
  for (const directory of configRoots) {
    if (['config', 'config.toml'].some(name => fs.existsSync(path.join(directory, name)))) throw new Error('NATIVE_BUILD_CARGO_CONFIG_UNACCOUNTED');
  }
  const before = verifyBuildInputs();
  // The target must not pre-exist: cached artifacts cannot stand in for this build.
  fs.mkdirSync(target);
  const environment = { ...process.env, CARGO_BUILD_JOBS: '1', CARGO_TARGET_DIR: target };
  await runCommand(process.execPath, [path.join(root, 'run.mjs'), 'primary', '--verify-only'], { cwd: root, environment, timeoutMs: 300000 });
  const rust = (executable, args) => runCommand(executable, args, { cwd: root, environment, rustDiagnostics: true, timeoutMs: 300000 });
  const rustc = (await rust('rustc', ['--version'])).trim();
  const sysroot = (await rust('rustc', ['--print', 'sysroot'])).trim();
  const rustcPath = fs.realpathSync(path.join(sysroot, 'bin/rustc'));
  const ccPath = fs.realpathSync((await runCommand('which', ['cc'], { cwd: root, environment, timeoutMs: 10000 })).trim());
  const executableHash = file => sha256(readRegularBytes(file, 128 * 1024 * 1024));
  const compiler = { path: rustcPath, sha256: executableHash(rustcPath), version: rustc };
  const cCompiler = { path: ccPath, sha256: executableHash(ccPath), version: (await runCommand(ccPath, ['--version'], { cwd: root, environment, timeoutMs: 10000 })).trim() };
  environment.RUSTC = rustcPath;
  environment.CC = ccPath;
  const cargo = (await rust('cargo', ['--version'])).trim();
  await rust('cargo', ['build', '--release', '--locked', '--manifest-path', sources.manifest, '--bin', 'native-parse']);
  if (executableHash(rustcPath) !== compiler.sha256 || executableHash(ccPath) !== cCompiler.sha256) throw new Error('NATIVE_BUILD_COMPILER_CHANGED');
  const after = verifyBuildInputs();
  if (JSON.stringify(before) !== JSON.stringify(after)) throw new Error('NATIVE_BUILD_SOURCE_CHANGED');
  const binary = path.join(target, 'release/native-parse');
  const binarySha256 = sha256(readRegularBytes(binary, 64 * 1024 * 1024));
  console.log(JSON.stringify({ schemaVersion: 1, kind: 'native-parse-build', binary, binarySha256, sources: before, fixtures: fixtureDefinitions.map(({ id, byteLength, sha256: digest }) => ({ id, byteLength, sha256: digest })), runtime: { treeSitterVersion: '0.27.2', grammarRevision: 'be0e3922cfcea075f37bdc1abd5463b953c5cd68' }, rustc, cargo, compiler, cCompiler, buildSettings: { profile: 'release', target: 'host-default', environmentOverrides: 'rejected' }, platform: process.platform, arch: process.arch }));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error(/^[A-Z_]+$/.test(error.message) ? error.message : 'NATIVE_BUILD_FAILED'); process.exitCode = 1; });
}
