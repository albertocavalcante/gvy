import * as vscode from 'vscode';
import { GradleService } from './GradleService';

export class GradleTaskProvider implements vscode.TaskProvider {
    constructor(private readonly gradleService: GradleService) { }

    async provideTasks(_token: vscode.CancellationToken): Promise<vscode.Task[]> {
        const workspaceFolders = vscode.workspace.workspaceFolders;
        if (!workspaceFolders || workspaceFolders.length === 0) {
            return [];
        }

        // Handle all workspace folders for multi-root workspace support
        const allTasks: vscode.Task[] = [];

        for (const workspaceFolder of workspaceFolders) {
            try {
                const gradleTasks = await this.gradleService.getTasks(workspaceFolder.uri.toString());

                const tasks = gradleTasks.map(taskInfo => {
                    const task = new vscode.Task(
                        { type: 'gradle', task: taskInfo.name, project: taskInfo.project },
                        workspaceFolder,
                        // Include project name for disambiguation in multi-root workspaces
                        workspaceFolders.length > 1 ? `${taskInfo.project}:${taskInfo.name}` : taskInfo.name,
                        'gradle',
                        new vscode.ShellExecution(`gradle ${taskInfo.name}`, { cwd: workspaceFolder.uri.fsPath })
                    );

                    // Map task group from server response if available
                    if (taskInfo.group) {
                        switch (taskInfo.group.toLowerCase()) {
                            case 'build':
                                task.group = vscode.TaskGroup.Build;
                                break;
                            case 'test':
                                task.group = vscode.TaskGroup.Test;
                                break;
                            default:
                                task.group = vscode.TaskGroup.Build;
                        }
                    } else {
                        task.group = vscode.TaskGroup.Build;
                    }

                    return task;
                });

                allTasks.push(...tasks);
            } catch (error) {
                console.error(`Failed to provide Gradle tasks for ${workspaceFolder.name}:`, error);
                // Continue with other workspace folders even if one fails
            }
        }

        return allTasks;
    }

    // This method is called when a task is executed that this provider is supposed to handle.
    // We can return the same task definition.
    resolveTask(task: vscode.Task, _token: vscode.CancellationToken): vscode.ProviderResult<vscode.Task> {
        // We need to ensure the task has a definition and that the definition is for 'gradle'
        if (task.definition.type === 'gradle' && task.definition.task) {
            return new vscode.Task(
                task.definition,
                task.scope || (vscode.workspace.workspaceFolders && vscode.workspace.workspaceFolders.length > 0 ? vscode.workspace.workspaceFolders[0] : vscode.TaskScope.Global),
                task.definition.task,
                'gradle',
                new vscode.ShellExecution(`gradle ${task.definition.task}`)
            );
        }
        return undefined;
    }
}
