// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as vscode from "vscode";
import { randomUUID } from "crypto";
import { toProtocolUri } from "../server/uri";
import { LanguageClient } from "vscode-languageclient/node";
import {
  AnalysisStore,
  DocumentAnalysis,
  analysisLimitations,
} from "./documentAnalysis";

export class AnalysisUi implements vscode.Disposable {
  private readonly store = new AnalysisStore();
  private readonly status = vscode.window.createStatusBarItem(
    vscode.StatusBarAlignment.Right,
    50,
  );
  private readonly disposables: vscode.Disposable[];
  private subscription: vscode.Disposable | undefined;

  constructor() {
    this.status.text = "$(warning) Groovy: Analysis limited";
    this.status.command = "groovy.showAnalysisDetails";
    for (const doc of vscode.workspace.textDocuments)
      this.store.open(toProtocolUri(doc.uri.toString()), doc.version);
    this.disposables = [
      this.status,
      vscode.workspace.onDidOpenTextDocument((doc) => {
        this.store.open(toProtocolUri(doc.uri.toString()), doc.version);
        this.update();
      }),
      vscode.workspace.onDidChangeTextDocument((event) => {
        this.store.change(
          toProtocolUri(event.document.uri.toString()),
          event.document.version,
        );
        this.update();
      }),
      vscode.workspace.onDidCloseTextDocument((doc) => {
        this.store.close(toProtocolUri(doc.uri.toString()));
        this.update();
      }),
      vscode.window.onDidChangeActiveTextEditor(() => this.update()),
      vscode.commands.registerCommand(
        "groovy.showAnalysisDetails",
        (uri?: string) => this.showDetails(uri),
      ),
      vscode.commands.registerCommand(
        "groovy.openAnalysisConfiguration",
        (uri: string) => vscode.window.showTextDocument(vscode.Uri.parse(uri)),
      ),
    ];
  }

  beginSession(document: vscode.TextDocument): string {
    const sessionId = randomUUID();
    this.store.open(toProtocolUri(document.uri.toString()), document.version);
    this.store.session(toProtocolUri(document.uri.toString()), sessionId);
    this.update();
    return sessionId;
  }

  setClient(client: LanguageClient | undefined): void {
    this.subscription?.dispose();
    // A new server has its own run sequence; discard all prior snapshots.
    for (const doc of vscode.workspace.textDocuments)
      this.store.close(toProtocolUri(doc.uri.toString()));
    this.storeReset();
    this.subscription = client?.onNotification(
      "groovy/documentAnalysis",
      (result: DocumentAnalysis) => {
        if (this.store.accept(result)) this.update();
      },
    );
    this.update();
  }
  private storeReset(): void {
    this.store.reset();
    for (const doc of vscode.workspace.textDocuments)
      this.store.open(toProtocolUri(doc.uri.toString()), doc.version);
  }
  private current(): DocumentAnalysis | undefined {
    const doc = vscode.window.activeTextEditor?.document;
    return doc ? this.store.get(toProtocolUri(doc.uri.toString())) : undefined;
  }
  private update(): void {
    const result = this.current();
    const details = result ? analysisLimitations(result) : [];
    if (!details.length) {
      this.status.hide();
      return;
    }
    this.status.tooltip = details.join("; ");
    this.status.show();
  }
  private async showDetails(uri?: string): Promise<void> {
    const result = uri ? this.store.get(toProtocolUri(uri)) : this.current();
    if (!result) return;
    const items: Array<
      vscode.QuickPickItem & { action?: () => Thenable<unknown> }
    > = [];
    if (!result.compiler.complete) {
      items.push({
        label: "Type analysis is limited",
        description: result.compiler.reason ?? undefined,
        detail: result.compiler.phase
          ? `Compiler phase: ${result.compiler.phase}`
          : undefined,
      });
      items.push({
        label: "Refresh Dependencies",
        action: () =>
          vscode.commands.executeCommand("groovy.retryDependencyResolution"),
      });
    } else {
      items.push({
        label: "Type analysis completed",
        detail: result.compiler.phase
          ? `Compiler phase: ${result.compiler.phase}`
          : undefined,
      });
    }
    if (result.compiler.sourceMembershipKnown === false) {
      items.push({
        label: "Source membership is not yet known",
        detail:
          result.compiler.sourceModelMessage ??
          "Project source membership is inferred until dependencies are refreshed.",
      });
      if (result.compiler.complete)
        items.push({
          label: "Refresh Dependencies",
          action: () =>
            vscode.commands.executeCommand("groovy.retryDependencyResolution"),
        });
    }
    if (
      result.compiler.sourceMembershipKnown !== false &&
      result.compiler.sourceVisibilityKnown === false
    ) {
      items.push({
        label: "Project source lookup is inferred",
        detail:
          result.compiler.sourceModelMessage ??
          "Project dependency source ownership is not fully known. Excluded project files remain filtered where the build model provides proof.",
      });
      if (result.compiler.complete) {
        items.push({
          label: "Refresh Dependencies",
          action: () =>
            vscode.commands.executeCommand("groovy.retryDependencyResolution"),
        });
      }
    }
    if (result.compiler.sourceMembershipKnown != null) {
      items.push({
        label: "Source model details",
        detail:
          "After changing files in an external source tree, use Refresh Dependencies if analysis does not update. New external roots and filenames containing wildcard characters may require this refresh.",
      });
    }
    for (const failure of result.providerFailures) {
      items.push({
        label: `${failure.provider}: linting failed`,
        description: failure.message,
      });
      if (failure.configurationUri) {
        const uri = vscode.Uri.parse(failure.configurationUri);
        items.push({
          label: "Open configuration",
          description: uri.fsPath,
          action: () => vscode.window.showTextDocument(uri),
        });
      }
    }
    const selected = await vscode.window.showQuickPick(items, {
      title: "Groovy analysis details",
    });
    await selected?.action?.();
  }
  dispose(): void {
    this.subscription?.dispose();
    this.disposables.forEach((item) => item.dispose());
  }
}
