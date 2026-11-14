// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as vscode from "vscode";

function needsServer(document: vscode.TextDocument): boolean {
  return (
    (document.uri.scheme === "file" || document.uri.scheme === "untitled") &&
    (document.languageId === "groovy" || document.languageId === "jenkinsfile")
  );
}

/** Keep declarative GDSL editing independent of Java, retaining project/command startup. */
export async function startServerForActivation(
  context: vscode.ExtensionContext,
  start: () => Promise<void>,
): Promise<void> {
  const documents = vscode.workspace.textDocuments;
  if (
    !documents.some((document) => document.languageId === "gdsl") ||
    documents.some(needsServer)
  ) {
    await start();
    return;
  }

  let disposed = false;
  let pending: Promise<void> | undefined;
  const startOnce = (): Promise<void> => {
    if (disposed) return Promise.resolve();
    if (!pending) {
      listener.dispose();
      pending = start();
    }
    return pending;
  };
  const listener = vscode.workspace.onDidOpenTextDocument((document) => {
    if (needsServer(document)) return startOnce();
  });
  context.subscriptions.push({
    dispose: () => {
      disposed = true;
      listener.dispose();
    },
  });

  // Existing Groovy/Gradle/Jenkins sources still need project services, even with GDSL in front.
  try {
    const projectFiles = await vscode.workspace.findFiles(
      "{**/*.{groovy,gvy,gy,gsh,gradle,jenkinsfile,Jenkinsfile},**/Jenkinsfile,**/Jenkinsfile[._-]*}",
      undefined,
      1,
    );
    if (projectFiles.length > 0) await startOnce();
  } catch (error) {
    // A failed search must not disable commands or static editing. Document opens still start the server.
    console.error("Could not check Groovy project files:", error);
  }
}
