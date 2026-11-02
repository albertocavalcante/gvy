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

/**
 * Task type of this provider. Not "gradle": that type belongs to the Gradle
 * for Java extension, and sharing it mixed both providers' tasks.
 */
export const GRADLE_TASK_TYPE = "groovy-gradle";

export interface GradleTaskDefinition extends vscode.TaskDefinition {
  task: string;
  /** Gradle path of a subproject, e.g. ":app"; omit for the root. */
  project?: string;
}

/**
 * The task path Gradle should run: `task` qualified with the subproject
 * path, or as given for the root project or an already qualified `:a:b`
 * task. Only `:`-prefixed values are paths; anything else (for example the
 * root project's name) means the root project.
 */
export function gradleTaskPath(task: string, project?: string): string {
  if (!project?.startsWith(":") || task.startsWith(":")) {
    return task;
  }
  const subproject = project.replace(/^:+|:+$/gu, "");
  return subproject ? `:${subproject}:${task}` : task;
}

function gradleExecution(
  folder: vscode.WorkspaceFolder | undefined,
  definition: GradleTaskDefinition,
): vscode.ShellExecution {
  const cwd = folder?.uri.fsPath;
  const executable = cwd
    ? gradleExecutable(cwd, process.platform, fs.existsSync)
    : "gradle";
  // Argument form lets VS Code quote per shell instead of splicing a string.
  return new vscode.ShellExecution(
    executable,
    [gradleTaskPath(definition.task, definition.project)],
    { cwd },
  );
}

/** Builds the runnable task for `definition` in `folder`. */
export function createGradleTask(
  definition: GradleTaskDefinition,
  folder: vscode.WorkspaceFolder | undefined,
): vscode.Task {
  return new vscode.Task(
    definition,
    folder ?? vscode.TaskScope.Global,
    gradleTaskPath(definition.task, definition.project),
    "groovy",
    gradleExecution(folder, definition),
  );
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
          const definition: GradleTaskDefinition = {
            type: GRADLE_TASK_TYPE,
            task: taskInfo.name,
            project: taskInfo.project,
          };
          const task = new vscode.Task(
            definition,
            workspaceFolder,
            // Include project name for disambiguation in multi-root workspaces
            workspaceFolders.length > 1
              ? `${taskInfo.project}:${taskInfo.name}`
              : taskInfo.name,
            "groovy",
            gradleExecution(workspaceFolder, definition),
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

  /** Completes a task declared in tasks.json (or built by a command). */
  resolveTask(
    task: vscode.Task,
    _token: vscode.CancellationToken,
  ): vscode.ProviderResult<vscode.Task> {
    const definition = task.definition as GradleTaskDefinition;
    if (definition.type === GRADLE_TASK_TYPE && definition.task) {
      const folder =
        typeof task.scope === "object"
          ? task.scope
          : vscode.workspace.workspaceFolders?.[0];
      return createGradleTask(definition, folder);
    }
    return undefined;
  }
}
