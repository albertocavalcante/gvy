// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import type { CancellationToken, Disposable, workspace } from "vscode";
import type { LanguageClient } from "vscode-languageclient/node";
import type { GroovyStatusParams } from "../ui/statusUtils";

type StatusListener = (status: GroovyStatusParams) => void;
interface StatusSubscription {
  listeners: Set<StatusListener>;
  wire: Disposable;
}
const subscriptions = new WeakMap<LanguageClient, StatusSubscription>();

/** The language client supports one wire handler per method. Share it across this client's consumers. */
export function onGroovyStatus(
  client: LanguageClient,
  listener: StatusListener,
): Disposable {
  let subscription = subscriptions.get(client);
  if (!subscription) {
    const listeners = new Set<StatusListener>();
    const wire = client.onNotification(
      "groovy/status",
      (status: GroovyStatusParams) => {
        for (const observe of listeners) observe(status);
      },
    );
    subscription = { listeners, wire };
    subscriptions.set(client, subscription);
  }
  const { listeners, wire } = subscription;
  const observe: StatusListener = (status) => listener(status);
  listeners.add(observe);
  return {
    dispose() {
      if (listeners.delete(observe) && listeners.size === 0) {
        subscriptions.delete(client);
        wire.dispose();
      }
    },
  };
}

type DocumentEvents = Pick<
  typeof workspace,
  "onDidChangeTextDocument" | "onDidCloseTextDocument" | "onDidOpenTextDocument"
>;

export class SymbolStartup {
  private busy = false;
  private readonly waiters = new Set<() => void>();
  private connection = {};

  captureConnection(): () => boolean {
    const connection = this.connection;
    return () => connection === this.connection;
  }

  observe(status: GroovyStatusParams): void {
    if (typeof status?.quiescent !== "boolean") return;
    this.busy = !status.quiescent;
    if (!this.busy) this.wake();
  }

  retireConnection(): void {
    this.connection = {};
    this.busy = false;
    this.wake();
  }

  private wake(): void {
    for (const wake of this.waiters) wake();
  }

  /** Unknown status permits the normal immediate retry. Only an observed busy period has a Ready edge. */
  async retry<T>(
    documents: DocumentEvents,
    token: CancellationToken,
    isCurrent: () => boolean,
    stale: unknown,
    send: () => Promise<T>,
  ): Promise<T> {
    if (!this.busy) return send();
    const listeners: Disposable[] = [];
    let resume: (() => void) | undefined;
    const check = () => {
      if (!this.busy || !isCurrent()) resume?.();
    };
    this.waiters.add(check);
    try {
      listeners.push(documents.onDidChangeTextDocument(check));
      listeners.push(documents.onDidCloseTextDocument(check));
      listeners.push(documents.onDidOpenTextDocument(check));
      listeners.push(token.onCancellationRequested(check));
      while (this.busy && isCurrent()) {
        await new Promise<void>((resolve) => {
          resume = resolve;
        });
      }
      if (!isCurrent()) throw stale;
      return send();
    } finally {
      this.waiters.delete(check);
      for (const listener of listeners) listener.dispose();
    }
  }
}
