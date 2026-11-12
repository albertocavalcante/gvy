// Keep tagged JAR and VSIX releases paired. Published v0.5.4 remains affected by
// TODO(#1751): replace the mismatched stable release and coordinate distribution pins.
//   See: https://github.com/albertocavalcante/gvy/issues/1751
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { basename, dirname, join } from 'node:path';
import { readZipEntry } from '../../editors/code/tools/zip.js';
import { readJarProvenance } from '../../editors/code/tools/jar-provenance.js';

const [jar, vsix, version, commit, ...extra] = process.argv.slice(2);
if (!jar || !vsix || !/^\d+\.\d+\.\d+(-[a-zA-Z0-9.-]+)?$/.test(version ?? '') ||
    !/^[a-f0-9]{40}$/.test(commit ?? '') || extra.length) {
  throw new Error('Usage: release-artifacts.mjs <jar> <vsix> <release-version> <source-commit>');
}

const server = readJarProvenance(jar);
if (server?.gitSha !== commit || server.dirty !== false) {
  throw new Error('Release server must come from the expected clean source commit');
}
if (server.version !== version) throw new Error('Release server version does not match the requested version');
const jarBytes = readFileSync(jar);
if (!readZipEntry(vsix, 'extension/server/gls.jar').equals(jarBytes)) {
  throw new Error('Release VSIX bundles a different server from the standalone JAR');
}
const extension = JSON.parse(readZipEntry(vsix, 'extension/package.json').toString('utf8'));
if (extension.version !== version) throw new Error('Release extension version does not match the requested version');

const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const sha256 = { [basename(jar)]: digest(jarBytes), [basename(vsix)]: digest(readFileSync(vsix)) };
writeFileSync(join(dirname(jar), 'release.json'), `${JSON.stringify({ version, commit, server, sha256 }, null, 2)}\n`);
console.log(`Verified release pair ${version} at ${commit}`);
