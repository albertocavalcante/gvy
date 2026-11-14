// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import { readFileSync } from "fs";
import { resolve } from "path";
import { parsePolicy, normalizeOrigin } from "../../../../shared/networkPolicy";
import { readUserNetworkPolicy } from "../../../src/configuration/networkPolicy";

describe("network policy authority", () => {
  it("ignores workspace and folder grants", () => {
    const policy = readUserNetworkPolicy(
      {
        inspect: () => ({
          globalValue: undefined,
          workspaceValue: "connected",
          workspaceFolderValue: ["https://repo.example"],
        }),
      },
      1,
    );
    assert.strictEqual(policy.mode, "cache-only");
    assert.deepStrictEqual(policy.allowedOrigins, []);
  });

  it("retains malformed user policy as a denial", () => {
    const policy = readUserNetworkPolicy(
      {
        inspect: (key) => ({
          globalValue: key === "network.mode" ? "connected" : ["*"],
        }),
      },
      1,
    );
    assert.strictEqual(policy.valid, false);
    assert.strictEqual(
      policy.authorize("https://repo.example")?.code,
      "INVALID_CONFIGURATION",
    );
  });

  it("does not import permissions from an installation receipt", () => {
    const policy = parsePolicy(
      {
        mode: "connected",
        receipt: { allowedOrigins: ["https://repo.example"] },
      },
      1,
    );
    assert.strictEqual(
      policy.authorize("https://repo.example")?.code,
      "INVALID_CONFIGURATION",
    );
  });

  it("uses the shared conformance fixtures through the typed API", () => {
    const fixtures: {
      requests: { policy: unknown; url: string; code: string | null }[];
      origins: { input: string; normalized: string | null }[];
    } = JSON.parse(
      readFileSync(
        resolve(
          __dirname,
          "../../../../../../build-tool/src/test/resources/network-policy/conformance.json",
        ),
        "utf8",
      ),
    );
    for (const fixture of fixtures.requests) {
      assert.strictEqual(
        parsePolicy(fixture.policy, 0).authorize(fixture.url)?.code ?? null,
        fixture.code,
      );
    }
    for (const fixture of fixtures.origins) {
      assert.strictEqual(normalizeOrigin(fixture.input), fixture.normalized);
    }
  });
});
