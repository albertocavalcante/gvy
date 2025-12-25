import * as vscode from 'vscode';
import { getClient } from '../../server/client';
import { GradleService } from './GradleService';
import { GradleTaskProvider } from './GradleTaskProvider';

export function registerGradleFeatures(context: vscode.ExtensionContext) {
    const client = getClient();
    if (!client) {
        console.error('Gradle features cannot be registered: Language Client not ready.');
        return;
    }

    const gradleService = new GradleService(client);
    const taskProvider = new GradleTaskProvider(gradleService);

    const taskProviderDisposable = vscode.tasks.registerTaskProvider('gradle', taskProvider);

    // Register commands and providers
    context.subscriptions.push(
        taskProviderDisposable,
        vscode.commands.registerCommand('groovy.gradle.build', () => runGradleTask('build')),
        vscode.commands.registerCommand('groovy.gradle.test', () => runGradleTask('test')),
        vscode.commands.registerCommand('groovy.gradle.clean', () => runGradleTask('clean')),
        vscode.commands.registerCommand('groovy.gradle.refresh', async () => {
            // This would ideally trigger a re-scan of tasks in the LSP.
            // For now, we can just show a message.
            vscode.window.showInformationMessage('Gradle project refresh requested. (Not implemented)');
        }),
        vscode.commands.registerCommand('groovy.gradle.selectTask', () => {
            vscode.commands.executeCommand('workbench.action.tasks.runTask', 'gradle');
        })
    );
}

async function runGradleTask(taskName: string) {
    const tasks = await vscode.tasks.fetchTasks({ type: 'gradle' });
    const taskToRun = tasks.find(task => task.name === taskName);

    if (taskToRun) {
        vscode.tasks.executeTask(taskToRun);
    } else {
        vscode.window.showErrorMessage(`Gradle task '${taskName}' not found.`);
    }
}
