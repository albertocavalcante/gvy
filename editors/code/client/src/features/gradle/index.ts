import * as vscode from "vscode";
import { getClient, restartClient } from "../../server/client";
import { GradleService } from "./GradleService";
import {
  GRADLE_TASK_TYPE,
  GradleTaskProvider,
  createGradleTask,
} from "./GradleTaskProvider";

export function registerGradleFeatures(context: vscode.ExtensionContext) {
  const gradleService = new GradleService(getClient, (message) =>
    getClient()?.outputChannel.info(message),
  );
  const taskProvider = new GradleTaskProvider(gradleService);

  const taskProviderDisposable = vscode.tasks.registerTaskProvider(
    GRADLE_TASK_TYPE,
    taskProvider,
  );

  // Register commands and providers
  context.subscriptions.push(
    taskProviderDisposable,
    vscode.commands.registerCommand("groovy.gradle.build", () =>
      runGradleTask("build"),
    ),
    vscode.commands.registerCommand("groovy.gradle.test", () =>
      runGradleTask("test"),
    ),
    vscode.commands.registerCommand("groovy.gradle.clean", () =>
      runGradleTask("clean"),
    ),
    // The server has no reload request; it imports the build (dependencies,
    // source sets) when it starts, and tests are rediscovered after a start.
    vscode.commands.registerCommand("groovy.gradle.refresh", () =>
      restartClient(),
    ),
    vscode.commands.registerCommand("groovy.gradle.selectTask", () => {
      vscode.commands.executeCommand(
        "workbench.action.tasks.runTask",
        GRADLE_TASK_TYPE,
      );
    }),
  );
}

/**
 * Runs a root-project task of the first workspace folder. Built directly
 * rather than looked up among provided tasks: the server may not list any,
 * and Build/Test/Clean exist in every Gradle project.
 */
async function runGradleTask(taskName: string) {
  const folder = vscode.workspace.workspaceFolders?.[0];
  if (!folder) {
    vscode.window.showErrorMessage(
      `Open a Gradle project folder to run '${taskName}'.`,
    );
    return;
  }
  await vscode.tasks.executeTask(
    createGradleTask({ type: GRADLE_TASK_TYPE, task: taskName }, folder),
  );
}
