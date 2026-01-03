
import * as assert from 'assert';
import * as vscode from 'vscode';

suite('AST Explorer Test Suite', () => {
    test('Should register AST Explorer command', async () => {
        const commands = await vscode.commands.getCommands(true);
        assert.ok(commands.includes('groovy.ast.show'), 'Should register groovy.ast.show command');
    });

    test('Should execute AST Explorer command', async () => {
        try {
            // Create a dummy groovy file to ensure context is valid
            const doc = await vscode.workspace.openTextDocument({
                language: 'groovy',
                content: 'class Foo {}'
            });
            await vscode.window.showTextDocument(doc);

            // Execute command
            await vscode.commands.executeCommand('groovy.ast.show');

            // If execution completes without error, we consider it a success for this level of integration test
            assert.ok(true);
        } catch (e) {
            assert.fail(`Command execution failed: ${e}`);
        }
    });
});
