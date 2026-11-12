// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import * as sinon from "sinon";
import {
  TOOL_DEFINITIONS,
  ILSPToolService,
} from "../../../../src/features/ai/types";

const tool = (name: string) => TOOL_DEFINITIONS.find((t) => t.name === name)!;

describe("AI tool definitions", () => {
  const service = {
    findWorkspaceSymbol: sinon.stub().resolves([]),
    findReferences: sinon.stub().resolves([]),
    getDefinition: sinon.stub().resolves(null),
  } as unknown as ILSPToolService;

  it("reports the missing parameter when called without arguments", async () => {
    await assert.rejects(
      tool("groovy_find_symbol").handler(service, undefined),
      /Parameter 'query' is required/,
    );
    await assert.rejects(
      tool("groovy_get_definition").handler(service, undefined),
      /'uri', 'line', and 'character' are required/,
    );
  });

  it("rejects positions that are not non-negative integers", async () => {
    for (const bad of [-1, 1.5, Number.NaN]) {
      await assert.rejects(
        tool("groovy_get_references").handler(service, {
          uri: "file:///a.groovy",
          line: bad,
          character: 0,
        }),
        /non-negative integers/,
      );
    }
  });
});
