import * as assert from 'assert';
import * as vscode from 'vscode';
import { getClient } from '../../server/client';

suite('Extension Test Suite', () => {
    console.log('Start all tests.');
    // ... (rest of the file is unchanged, will just replace the top imports and the specific test case)


    test('Extension should be present', () => {
        const extension = vscode.extensions.getExtension('albertocavalcante.gvy');
        assert.ok(extension, 'Extension should be found');
    });

    test('Extension should activate', async () => {
        const extension = vscode.extensions.getExtension('albertocavalcante.gvy');
        assert.ok(extension, 'Extension should be present');
        await extension.activate();
        assert.ok(extension.isActive, 'Extension should be active');
    });

    test('Should register all Groovy commands', async () => {
        const commands = await vscode.commands.getCommands(true);

        // Core commands
        assert.ok(commands.includes('groovy.restartServer'), 'Should register restart server command');
        assert.ok(commands.includes('groovy.checkForUpdates'), 'Should register check for updates command');

        // Status bar commands
        assert.ok(commands.includes('groovy.showStatusMenu'), 'Should register show status menu command');
        assert.ok(commands.includes('groovy.openLogs'), 'Should register open logs command');
        assert.ok(commands.includes('groovy.stopServer'), 'Should register stop server command');
        assert.ok(commands.includes('groovy.reportIssue'), 'Should register report issue command');

        // Gradle commands
        assert.ok(commands.includes('groovy.gradle.build'), 'Should register gradle build command');
        assert.ok(commands.includes('groovy.gradle.test'), 'Should register gradle test command');
        assert.ok(commands.includes('groovy.gradle.clean'), 'Should register gradle clean command');

        // Test commands (Not implemented yet)
        // assert.ok(commands.includes('groovy.test.runAll'), 'Should register run all tests command');
        // assert.ok(commands.includes('groovy.test.runCurrentFile'), 'Should register run tests in file command');
    });

    test('Should recognize Groovy language', async () => {
        const languages = await vscode.languages.getLanguages();
        assert.ok(languages.includes('groovy'), 'Should recognize groovy language');
    });

    test('Configuration should have correct structure', () => {
        const config = vscode.workspace.getConfiguration('groovy');
        assert.ok(config.has('java.home'), 'Should have java.home configuration');
        assert.ok(config.has('trace.server'), 'Should have trace.server configuration');
        assert.ok(config.has('compilation.mode'), 'Should have compilation.mode configuration');
    });

    test('Status bar configuration should have correct structure', () => {
        const config = vscode.workspace.getConfiguration('groovy');

        // Status bar visibility setting
        assert.ok(config.has('statusBar.show'), 'Should have statusBar.show configuration');
        const showValue = config.get<string>('statusBar.show');
        assert.ok(
            ['always', 'onGroovyFile', 'never'].includes(showValue || 'onGroovyFile'),
            'statusBar.show should have valid enum value'
        );

        // Status bar click action setting
        assert.ok(config.has('statusBar.clickAction'), 'Should have statusBar.clickAction configuration');
        const clickValue = config.get<string>('statusBar.clickAction');
        assert.ok(
            ['menu', 'logs', 'restart'].includes(clickValue || 'menu'),
            'statusBar.clickAction should have valid enum value'
        );
    });

    test('Should use correct output channel', async () => {
        const client = getClient();
        assert.ok(client, 'Client should be successfully initialized');
        if (!client) {
            throw new Error('Client is undefined');
        }

        // Verify client output channel matches what we expect
        // Note: We can't easily access the extension's created channel instance here
        // without exporting it, but we can verify the client has one.
        assert.ok(client.outputChannel, 'Client should have an output channel');
        assert.strictEqual(client.outputChannel.name, 'Groovy Language Server', 'Output channel name should match');
    });
});
