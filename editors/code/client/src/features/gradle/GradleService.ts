// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import {
  ClientProvider,
  isMethodNotFound,
  requireClient,
} from "../../server/clientProvider";
import { RequestType } from "vscode-languageserver-protocol";

export interface GradleTask {
  name: string;
  group: string;
  project: string;
}

const GradleTasksRequest = new RequestType<
  { workspaceUri: string },
  GradleTask[] | null,
  void
>("groovy/gradleTasks");

function isGradleTask(value: unknown): value is GradleTask {
  return (
    typeof value === "object" &&
    value !== null &&
    typeof (value as { name?: unknown }).name === "string"
  );
}

export class GradleService {
  /** Clients that answered MethodNotFound; a restart yields a new client. */
  private readonly unsupportedClients = new WeakSet<object>();

  constructor(
    private readonly getClient: ClientProvider,
    private readonly log: (message: string) => void = () => undefined,
  ) {}

  private get client() {
    return requireClient(this.getClient);
  }

  /**
   * False once the current server has said it has no `groovy/gradleTasks`
   * handler. Unknown (not yet asked) counts as supported.
   */
  isTaskDiscoverySupported(): boolean {
    const client = this.getClient();
    return !client || !this.unsupportedClients.has(client);
  }

  async getTasks(workspaceUri: string): Promise<GradleTask[]> {
    const client = this.client;
    // Ask an unsupporting server once, not on every task refresh.
    if (this.unsupportedClients.has(client)) {
      return [];
    }
    let reply: unknown;
    try {
      reply = await client.sendRequest(GradleTasksRequest, { workspaceUri });
    } catch (error) {
      // This server version does not implement groovy/gradleTasks (the
      // packaged server has no handler). Remember it for this client, list
      // no tasks and keep Build/Test/Clean and tasks.json entries working.
      if (isMethodNotFound(error)) {
        this.unsupportedClients.add(client);
        this.log(
          "The language server does not support groovy/gradleTasks; Gradle task discovery is off until it restarts.",
        );
        return [];
      }
      throw error;
    }
    if (reply === null || reply === undefined) {
      return [];
    }
    if (!Array.isArray(reply) || !reply.every(isGradleTask)) {
      throw new Error("Malformed groovy/gradleTasks reply from the server");
    }
    return reply;
  }
}
