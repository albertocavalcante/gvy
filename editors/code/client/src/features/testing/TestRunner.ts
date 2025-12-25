import * as vscode from 'vscode';

export class GroovyTestRunner {
    public constructTestFilter(tests: vscode.TestItem[]): string {
        const filters = tests.map(test => `--tests "${this.getTestNameFromId(test.id)}"`);
        return filters.join(' ');
    }

    public getTestNameFromId(id: string): string {
        const suiteMarker = '?suite=';
        const testMarker = '?test=';

        const marker = id.includes(testMarker) ? testMarker : suiteMarker;
        return id.substring(id.indexOf(marker) + marker.length);
    }

    public async run(workspaceFolder: vscode.WorkspaceFolder, tests: vscode.TestItem[]): Promise<void> {
        const filter = this.constructTestFilter(tests);
        const commandLine = `gradle test ${filter}`;

        const task = new vscode.Task(
            { type: 'groovy', test: 'run' },
            workspaceFolder,
            'Run Groovy Tests',
            'Groovy',
            new vscode.ShellExecution(commandLine)
        );

        await vscode.tasks.executeTask(task);
    }
}
