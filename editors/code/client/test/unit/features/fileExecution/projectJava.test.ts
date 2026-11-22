// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import * as assert from "assert";
import { resolveProjectJavaHome } from "../../../../src/features/fileExecution/projectJava";

describe("project Java selection", () => {
  it("prefers the explicit project JDK, then valid inherited JAVA_HOME", async () => {
    const usable = async (home: string) => home !== "/invalid";
    const probe = async () => {
      throw new Error("must not probe");
    };
    assert.strictEqual(
      await resolveProjectJavaHome("/project", "/environment", usable, probe),
      "/project",
    );
    assert.strictEqual(
      await resolveProjectJavaHome(undefined, "/environment", usable, probe),
      "/environment",
    );
    await assert.rejects(
      () => resolveProjectJavaHome("/invalid", "/environment", usable, probe),
      /project Java/i,
    );
  });
  it("probes PATH when inherited JAVA_HOME is missing or invalid", async () => {
    assert.strictEqual(
      await resolveProjectJavaHome(
        undefined,
        "/invalid",
        async (home) => home === "/path-jdk",
        async () => "    java.home = /path-jdk\n",
      ),
      "/path-jdk",
    );
    await assert.rejects(
      () =>
        resolveProjectJavaHome(
          undefined,
          undefined,
          async () => true,
          async () => "malformed",
        ),
      /java.home/i,
    );
  });
});
