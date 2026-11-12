// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

// Verify the distributable pair before a nightly can be published. No downloads
// or editor installation happen here; the build supplies both archives.
// TODO(#1736): Reuse this paired-artifact contract for explicit nightly updates.
//   See: https://github.com/albertocavalcante/gvy/issues/1736
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { readZipEntry } from '../../editors/code/tools/zip.js';
import { readJarProvenance } from '../../editors/code/tools/jar-provenance.js';

const [directory, commit, version, runUrl] = process.argv.slice(2);
if (!directory || !/^[a-f0-9]{40}$/.test(commit ?? '') ||
    !/^\d+\.\d+\.\d+-nightly\.\d+(\.\d+)*$/.test(version ?? '') ||
    !/^https:\/\/github\.com\/albertocavalcante\/gvy\/actions\/runs\/\d+$/.test(runUrl ?? '')) {
  throw new Error('Usage: nightly-artifacts.mjs <directory> <commit> <nightly-version> <run-url>');
}

const jarName = 'gls-nightly.jar';
const vsixName = 'gvy-nightly.vsix';
const jar = join(directory, jarName);
const vsix = join(directory, vsixName);
const provenance = readJarProvenance(jar);
if (provenance?.gitSha !== commit) throw new Error('Server commit does not match the build commit');
if (provenance.dirty !== false) throw new Error('Server must report a verified clean tree');
const jarBytes = readFileSync(jar);
if (!readZipEntry(vsix, 'extension/server/gls.jar').equals(jarBytes)) {
  throw new Error('VSIX bundled server differs from the verified standalone server');
}
const extension = JSON.parse(readZipEntry(vsix, 'extension/package.json').toString('utf8'));
if (extension.version !== version) throw new Error('Extension version does not match the nightly version');
const vscodeApi = extension.engines?.vscode;
if (typeof vscodeApi !== 'string' || !/^\^\d+\.\d+\.\d+$/.test(vscodeApi)) {
  throw new Error('Expected an explicit VS Code API requirement');
}

const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const sha256 = { [jarName]: digest(jarBytes), [vsixName]: digest(readFileSync(vsix)) };
const manifest = { channel: 'nightly', version, commit, runUrl, vscodeApi, server: provenance, sha256 };
writeFileSync(join(directory, 'nightly.json'), `${JSON.stringify(manifest, null, 2)}\n`);
sha256['nightly.json'] = digest(readFileSync(join(directory, 'nightly.json')));
writeFileSync(join(directory, 'checksums.txt'),
  Object.entries(sha256).map(([name, hash]) => `${hash}  ${name}\n`).join(''));
writeFileSync(join(directory, 'release-notes.md'), `Built and tested from main commit \`${commit}\`.

- Download **gvy-nightly.vsix** and run **Extensions: Install from VSIX…** in your editor.
- Requires Java 17+ and VS Code API \`${vscodeApi}\` (including compatible Cursor/VSCodium builds).
- The VSIX bundles the exact server published as **gls-nightly.jar**.
- **checksums.txt** provides SHA-256 digests for both downloads and **nightly.json**.
- Nightlies are manual installs. Install a newer VSIX to update. To downgrade, uninstall gvy in the editor, then install the older VSIX.
- [Build and test evidence](${runUrl}); **nightly.json** records provenance and both hashes.

This is a prerelease. The stable Homebrew formula and latest stable release remain on the stable channel.
`);
console.log(`Verified nightly ${version} at ${commit}`);
