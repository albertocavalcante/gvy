// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

// Run by VS Code's --extensionTestsPath, against an isolated installed VSIX.
const assert = require("node:assert/strict");
const { execFileSync, spawnSync } = require("node:child_process");
const { createHash } = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { zipEntry } = require("./ci-install.js");

const fixture =
  "class Greeter {\n    String greet(String name) { \"Hello, ${name}\" }\n}\nprintln new Greeter().greet('Groovy')\n";

/** @param {string} file */
function sha256(file) {
  return createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

/** Reject a generic word hover or an empty provider response. @param {string} text */
function verifyHover(text) {
  assert.match(text, /\bString\s+greet\s*\(\s*String\s+name\s*\)/);
  assert.match(text, /String/);
  assert.match(text, /Greeter/);
}

/** Check installed metadata and client against the checksum-verified VSIX.
 * VS Code adds local installation metadata; all packaged fields must match.
 * @param {string} extensionPath @param {object} packagedManifest
 */
function verifyInstalledRelease(extensionPath, packagedManifest) {
  const manifestPath = path.join(extensionPath, "package.json");
  const installed = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  delete installed.__metadata;
  assert.deepEqual(
    installed,
    packagedManifest,
    "Installed manifest differs from release",
  );
  const clientSha256 = sha256(path.join(extensionPath, packagedManifest.main));
  assert.equal(
    clientSha256,
    "f562ba83c56b9303eaddf1ac80682a55825494b586b213129525c652205e0a56",
    "Installed client differs from release",
  );
  return { manifestSha256: sha256(manifestPath), clientSha256 };
}

/** Bound activation and each provider call as well as the overall polling loop.
 * @template T @param {PromiseLike<T>} operation @returns {Promise<T>}
 */
async function bounded(operation) {
  let timer;
  try {
    return await Promise.race([
      operation,
      new Promise((_, reject) => {
        timer = setTimeout(
          () => reject(new Error("Editor operation timed out")),
          15000,
        );
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

async function run() {
  const vscode = require("vscode");
  const receiptPath = process.env.GVY_VERIFY_RECEIPT;
  assert.ok(receiptPath, "GVY_VERIFY_RECEIPT is required");
  const receipt = {
    schemaVersion: 1,
    success: false,
    scope: "stable VSIX first-project hover on Java 17",
  };
  try {
    const javaHome = process.env.GVY_VERIFY_JAVA_HOME;
    const vsix = process.env.GVY_VERIFY_VSIX;
    assert.ok(javaHome && vsix, "Explicit Java home and VSIX are required");
    assert.equal(
      sha256(vsix),
      "128a9f76b8595611196890b4305702b8005335f64b75f1f83e24f6172196a93b",
    );
    assert.equal(
      vscode.version,
      "1.146.0",
      "Reverify and update the editor pin deliberately",
    );
    const extension = vscode.extensions.getExtension("albertocavalcante.gvy");
    assert.ok(extension, "Stable extension must be installed");
    assert.equal(extension.packageJSON.version, "0.5.4");
    const packagedManifest = JSON.parse(
      zipEntry(
        fs.readFileSync(vsix),
        (name) => name === "extension/package.json",
        128 * 1024,
      ).toString("utf8"),
    );
    const installedHashes = verifyInstalledRelease(
      extension.extensionPath,
      packagedManifest,
    );
    const jar = path.join(extension.extensionPath, "server", "gls.jar");
    const serverSha256 = sha256(jar);
    assert.equal(
      serverSha256,
      "552558bc588968c3127aa25114a86bb9137fa740572b39e7af44ff732a2802f2",
    );
    const config = vscode.workspace.getConfiguration("groovy");
    assert.equal(config.get("languageServer.javaHome"), javaHome);
    assert.equal(config.get("server.path", ""), "");
    assert.deepEqual(config.get("server.vmargs"), ["-Xmx512m"]);
    const java = path.join(javaHome, "bin", "java");
    const env = { ...process.env, JDK_JAVA_OPTIONS: "" };
    const versionOptions = {
      encoding: "utf8",
      timeout: 10000,
      env,
      stdio: ["ignore", "pipe", "pipe"],
    };
    // java -version writes to stderr; capture it without interpreting a shell command.
    const javaVersion = spawnSync(
      java,
      ["-Xmx64m", "-version"],
      versionOptions,
    );
    assert.equal(javaVersion.status, 0, javaVersion.stderr);
    assert.match(javaVersion.stderr, /version "17\./);
    const serverVersion = execFileSync(
      java,
      ["-Xmx128m", "-jar", jar, "version"],
      versionOptions,
    ).trim();
    assert.equal(serverVersion, "gls version 0.4.8-SNAPSHOT");
    const folder = vscode.workspace.workspaceFolders?.[0];
    assert.ok(folder, "Open the disposable first-project folder");
    const uri = vscode.Uri.joinPath(folder.uri, "Hello.groovy");
    const document = await bounded(vscode.workspace.openTextDocument(uri));
    assert.equal(
      document.getText(),
      fixture,
      "Use the canonical quickstart example",
    );
    await bounded(vscode.window.showTextDocument(document));
    await bounded(extension.activate());
    const position = document.positionAt(fixture.indexOf("greet"));
    let hoverText = "";
    const deadline = Date.now() + 45000;
    while (Date.now() < deadline) {
      const hovers = await bounded(
        vscode.commands.executeCommand(
          "vscode.executeHoverProvider",
          uri,
          position,
        ),
      );
      hoverText = (hovers ?? [])
        .flatMap((hover) =>
          hover.contents.map((content) =>
            typeof content === "string" ? content : content.value,
          ),
        )
        .join("\n");
      try {
        verifyHover(hoverText);
        break;
      } catch {
        await new Promise((resolve) => setTimeout(resolve, 250));
      }
    }
    verifyHover(hoverText);
    // macOS/Linux only: bind successful hover to the actual configured Java/JAR process.
    assert.ok(
      ["darwin", "linux"].includes(process.platform),
      "Process verification supports macOS/Linux",
    );
    const processes = execFileSync("ps", ["-axo", "pid=,ppid=,command="], {
      encoding: "utf8",
      timeout: 10000,
    });
    const serverProcesses = processes
      .split("\n")
      .filter((line) => line.includes(jar) && line.includes("-jar"));
    assert.equal(
      serverProcesses.length,
      1,
      "Exactly one isolated installed server must be running",
    );
    assert.ok(
      serverProcesses[0].includes(java),
      "Running server must use selected Java 17",
    );
    assert.ok(
      serverProcesses[0].includes("-Xmx512m"),
      "Running server heap must be bounded",
    );
    Object.assign(receipt, {
      success: true,
      probeSha256: sha256(require.resolve("./verify-release-editor.js")),
      editor: {
        version: vscode.version,
        platform: process.platform,
        arch: process.arch,
      },
      vsixSha256: sha256(vsix),
      extensionVersion: extension.packageJSON.version,
      ...installedHashes,
      serverSha256,
      serverVersion,
      javaVersion: javaVersion.stderr.trim(),
      javaHome,
      installedExtensionPath: extension.extensionPath,
      serverProcesses,
      hoverText,
      fixtureSha256: createHash("sha256").update(fixture).digest("hex"),
      dependencyContext:
        "No build files or external dependencies; no Gradle/Jenkins/Spock execution",
      limits:
        "API hover, not visual UI or accessibility proof; server source revision is not established by the VSIX tag",
    });
  } catch (error) {
    receipt.error = error instanceof Error ? error.message : String(error);
    throw error;
  } finally {
    receipt.completedAt = new Date().toISOString();
    fs.writeFileSync(receiptPath, JSON.stringify(receipt, null, 2) + "\n", {
      mode: 0o600,
    });
  }
}

module.exports = { run, fixture, verifyHover, verifyInstalledRelease };
