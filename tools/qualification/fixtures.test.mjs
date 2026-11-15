// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict";
import test from "node:test";
import { startFixtures } from "./fixtures.mjs";

test("loopback fixture authenticates, records denied-hop requests and never records secrets", async () => {
  const fixtures = await startFixtures();
  try {
    assert.equal((await fetch(`${fixtures.origins.allowed}/auth`)).status, 401);
    assert.equal(
      (
        await fetch(`${fixtures.origins.allowed}/auth`, {
          headers: { authorization: fixtures.authorization },
        })
      ).status,
      200,
    );
    const redirected = await fetch(
      `${fixtures.origins.allowed}/redirect/denied`,
      { redirect: "manual" },
    );
    assert.equal(redirected.status, 302);
    assert.equal(
      redirected.headers.get("location"),
      `${fixtures.origins.denied}/auth`,
    );
    assert.equal(
      fixtures.requests.some((request) => request.origin === "denied"),
      false,
    );
    await fetch(redirected.headers.get("location"));
    assert.equal(
      fixtures.requests.filter((request) => request.origin === "denied").length,
      1,
    );
    assert.equal(
      JSON.stringify(fixtures.requests).includes(fixtures.authorization),
      false,
    );
    assert.equal(
      (await fetch(`${fixtures.origins.allowed}/artifact/../secret`)).status,
      404,
    );
  } finally {
    await fixtures.close();
  }
});
