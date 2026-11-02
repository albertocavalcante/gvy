import { strict as assert } from "assert";
import { resolveServerVersion } from "../../../../src/features/update/serverVersion";

describe("resolveServerVersion", () => {
  const readTag = () => "v0.4.8";

  it("prefers the version the running server reports", () => {
    assert.strictEqual(
      resolveServerVersion({
        reported: "0.5.1",
        serverPath: undefined,
        readInstalledTag: readTag,
      }),
      "0.5.1",
    );
  });

  it("falls back to the bundled server's installed tag before it starts", () => {
    assert.strictEqual(
      resolveServerVersion({
        reported: undefined,
        serverPath: undefined,
        readInstalledTag: readTag,
      }),
      "v0.4.8",
    );
  });

  it("does not attribute the bundled tag to a custom server JAR", () => {
    assert.strictEqual(
      resolveServerVersion({
        reported: undefined,
        serverPath: "/opt/gls.jar",
        readInstalledTag: readTag,
      }),
      "unknown",
    );
  });

  it("is unknown when the installed tag cannot be read", () => {
    assert.strictEqual(
      resolveServerVersion({
        reported: undefined,
        serverPath: "  ",
        readInstalledTag: () => {
          throw new Error("ENOENT");
        },
      }),
      "unknown",
    );
  });
});
