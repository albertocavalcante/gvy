
import * as assert from 'assert';
import * as vscode from 'vscode';

suite('Spock Test Scaffolding Integration', () => {

    suiteSetup(async function () {
        this.timeout(60000); // Increase timeout for Windows CI
        const extension = vscode.extensions.getExtension('albertocavalcante.gvy');
        if (!extension?.isActive) {
            await extension?.activate();
        }
    });

    test('Command groovy.test.generate should be registered', async () => {
        const commands = await vscode.commands.getCommands(true);
        assert.ok(commands.includes('groovy.test.generate'), 'groovy.test.generate command should be registered');
    });

    // Note: Full end-to-end test requiring simulated LSP response and file creation
    // is complex to set up reliably in this suite without a running language server.
    // Logic is covered by unit tests.
    // We can attempt a basic mock integration if needed, but command registration proves enablement.
});
