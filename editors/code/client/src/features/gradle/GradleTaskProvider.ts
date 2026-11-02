import * as vscode from "vscode";
import * as fs from "fs";
import * as path from "path";
import { GradleService } from "./GradleService";

/**
 * The project's wrapper (pinned Gradle version, works without a global
 * install) when present, otherwise `gradle` from PATH.
 */
export function gradleExecutable(
  folderPath: string,
  platform: NodeJS.Platform = process.platform,
  exists: (p: string) => boolean = fs.existsSync,
): string {
  const p = platform === "win32" ? path.win32 : path.posix;
  const wrapper = p.join(
    folderPath,
    platform === "win32" ? "gradlew.bat" : "gradlew",
  );
  return exists(wrapper) ? wrapper : "gradle";
}

function gradleExecution(
  folder: vscode.WorkspaceFolder | undefined,
  taskName: string,
): vscode.ShellExecution {
  const cwd = folder?.uri.fsPath;
  const executable = cwd
    ? gradleExecutable(cwd, process.platform, fs.existsSync)
    : "gradle";
  // Argument form lets VS Code quote per shell instead of splicing a string.
  return new vscode.ShellExecution(executable, [taskName], { cwd });
}

export class GradleTaskProvider implements vscode.TaskProvider {
  constructor(private readonly gradleService: GradleService) {}

  async provideTasks(_token: vscode.CancellationToken): Promise<vscode.Task[]> {
    const workspaceFolders = vscode.workspace.workspaceFolders;
    if (!workspaceFolders || workspaceFolders.length === 0) {
      return [];
    }

    // Handle all workspace folders for multi-root workspace support
    const allTasks: vscode.Task[] = [];

    for (const workspaceFolder of workspaceFolders) {
      try {
        const gradleTasks = await this.gradleService.getTasks(
          workspaceFolder.uri.toString(),
        );

        const tasks = gradleTasks.map((taskInfo) => {
          const task = new vscode.Task(
            { type: "gradle", task: taskInfo.name, project: taskInfo.project },
            workspaceFolder,
            // Include project name for disambiguation in multi-root workspaces
            workspaceFolders.length > 1
              ? `${taskInfo.project}:${taskInfo.name}`
              : taskInfo.name,
            "gradle",
            gradleExecution(workspaceFolder, taskInfo.name),
          );

          // Map task group from server response if available
          if (taskInfo.group) {
            switch (taskInfo.group.toLowerCase()) {
              case "build":
                task.group = vscode.TaskGroup.Build;
                break;
              case "test":
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
        console.error(
          `Failed to provide Gradle tasks for ${workspaceFolder.name}:`,
          error,
        );
        // Continue with other workspace folders even if one fails
      }
    }

    return allTasks;
  }

  // This method is called when a task is executed that this provider is supposed to handle.
  // We can return the same task definition.
  resolveTask(
    task: vscode.Task,
    _token: vscode.CancellationToken,
  ): vscode.ProviderResult<vscode.Task> {
    // We need to ensure the task has a definition and that the definition is for 'gradle'
    if (task.definition.type === "gradle" && task.definition.task) {
      const folder =
        typeof task.scope === "object"
          ? task.scope
          : vscode.workspace.workspaceFolders?.[0];
      return new vscode.Task(
        task.definition,
        folder ?? vscode.TaskScope.Global,
        task.definition.task,
        "gradle",
        gradleExecution(folder, task.definition.task),
      );
    }
    return undefined;
  }
}
