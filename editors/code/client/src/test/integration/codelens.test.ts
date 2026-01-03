
import * as assert from 'assert';
import * as vscode from 'vscode';

suite('CodeLens Command Test Suite', () => {
    test('Should register groovy.test.run command', async () => {
        const commands = await vscode.commands.getCommands(true);
        assert.ok(commands.includes('groovy.test.run'), 'groovy.test.run command should be registered');
    });

    test('Should register groovy.test.debug command', async () => {
        const commands = await vscode.commands.getCommands(true);
        assert.ok(commands.includes('groovy.test.debug'), 'groovy.test.debug command should be registered');
    });
});
