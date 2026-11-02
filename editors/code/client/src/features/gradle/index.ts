import * as vscode from "vscode";
import { getClient, restartClient } from "../../server/client";
import { GradleService } from "./GradleService";
import { GradleTaskProvider } from "./GradleTaskProvider";

export function registerGradleFeatures(context: vscode.ExtensionContext) {
  const gradleService = new GradleService(getClient);
  const taskProvider = new GradleTaskProvider(gradleService);

  const taskProviderDisposable = vscode.tasks.registerTaskProvider(
    "gradle",
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
        "gradle",
      );
    }),
  );
}

async function runGradleTask(taskName: string) {
  const tasks = await vscode.tasks.fetchTasks({ type: "gradle" });
  const taskToRun = tasks.find((task) => task.name === taskName);

  if (taskToRun) {
    vscode.tasks.executeTask(taskToRun);
  } else {
    vscode.window.showErrorMessage(`Gradle task '${taskName}' not found.`);
  }
}
