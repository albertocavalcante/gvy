// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { ClientProvider, requireClient } from "../../server/clientProvider";

export interface ReplResult {
  result: string;
  output: string;
}

export class GroovyReplClient {
  constructor(private readonly getClient: ClientProvider) {}

  private get client() {
    return requireClient(this.getClient);
  }

  async evaluate(expression: string): Promise<ReplResult> {
    return this.client.sendRequest("groovy/execute", { expression });
  }
}
