// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { digest } from '../vscode-provisioning-policy.mjs';
import { probeVscodeVersion, runSdkProvisioning } from '../vscode-provisioning-worker.mjs';
import { vscodeTestConfigurations, vscodeTestVersion } from '../../../editors/code/tools/vscode-test-config.mjs';

// Static executable fixture: paths and context arrive as IPC data, never source.
process.once('message', async input => {
  let seq = 0;
  const send = message => process.send({ token: input.token, attempt: input.number, contextSha256: digest(JSON.stringify(input.context)), seq: ++seq, ...message });
  process.on('message', () => {});
  const commit = 'c'.repeat(40);
  const sdk = {
    download: async ({ reporter }) => {
      reporter.report({ stage: 'resolvedVersion', version: vscodeTestVersion });
      reporter.report({ stage: 'resolvingCDNLocation' });
      reporter.report({ stage: 'downloading', bytesSoFar: 1, totalBytes: 1 });
      reporter.report({ stage: 'newInstallComplete', commitHash: commit });
      return input.cachePath;
    },
    resolveCliArgsFromVSCodeExecutablePath: () => [`${input.cachePath}.cmd`, input.cachePath],
  };
  let final;
  await runSdkProvisioning(async () => ({
    context: input.context, budgetMs: input.budgetMs,
    sdkVersion: '3.1.0', cliVersion: '0.0.15', proxyAgentVersion: '7.0.6',
    manifest: { engines: { vscode: `^${vscodeTestVersion}` }, devDependencies: { '@types/vscode': `^${vscodeTestVersion}` } },
    configurations: vscodeTestConfigurations({}), platform: 'linux-x64', cachePath: input.cachePath, sdk,
    fetchRelease: async () => ({ version: vscodeTestVersion, platform: 'linux-x64', quality: 'stable', commit, archiveSha256: 'e'.repeat(64) }),
  }), message => {
    if (message.type === 'raw-error') process.stderr.write(message.bytes);
    else if (message.type === 'result') final = message;
    else send(message);
  }, (sdk, executable, platform, budget) => probeVscodeVersion(sdk, executable, platform, Math.min(budget, 1500)));
  await new Promise(resolve => process.stderr.write('', resolve));
  send(final);
});
