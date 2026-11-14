// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

const test = require("node:test");
const assert = require("node:assert/strict");
const { selectRun, validateArtifacts } = require("../ci-install.js");

const sha = "a".repeat(40);
test("only exact push-main workflow run is selected", () => {
  const run = {
    id: 7,
    run_attempt: 1,
    head_sha: sha,
    head_branch: "main",
    event: "push",
    path: ".github/workflows/extension.yml",
    status: "completed",
    conclusion: "success",
  };
  assert.equal(
    selectRun(
      [
        { ...run, head_sha: "b".repeat(40) },
        { ...run, event: "pull_request" },
        run,
      ],
      sha,
    ).id,
    7,
  );
  assert.equal(selectRun([{ ...run, head_sha: "b".repeat(40) }], sha), null);
});
test("artifact metadata rejects expired and missing digests", () => {
  const a = (name) => ({
    name,
    id: 1,
    expired: false,
    digest: "sha256:" + "a".repeat(64),
  });
  assert.throws(() =>
    validateArtifacts([
      a("vsix"),
      { ...a("current-editor-server"), expired: true },
    ]),
  );
  assert.throws(() =>
    validateArtifacts([
      a("vsix"),
      { ...a("current-editor-server"), digest: null },
    ]),
  );
  assert.equal(
    validateArtifacts([a("vsix"), a("current-editor-server")]).length,
    2,
  );
});

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const { zipEntry, compatible, installedMatches } = require("../ci-install.js");

function storedZip(name, content) {
  const filename = Buffer.from(name),
    data = Buffer.from(content);
  const local = Buffer.alloc(30);
  local.writeUInt32LE(0x04034b50);
  local.writeUInt32LE(data.length, 18);
  local.writeUInt32LE(data.length, 22);
  local.writeUInt16LE(filename.length, 26);
  const central = Buffer.alloc(46);
  central.writeUInt32LE(0x02014b50);
  central.writeUInt32LE(data.length, 20);
  central.writeUInt32LE(data.length, 24);
  central.writeUInt16LE(filename.length, 28);
  const offset = local.length + filename.length + data.length;
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(1, 8);
  end.writeUInt16LE(1, 10);
  end.writeUInt32LE(central.length + filename.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([local, filename, data, central, filename, end]);
}
test("ZIP reads only the matching entry and rejects oversize declarations", () => {
  const zip = storedZip("gls.jar", "bytes");
  assert.equal(zipEntry(zip, (n) => n === "gls.jar", 10).toString(), "bytes");
  assert.throws(() => zipEntry(zip, (n) => n === "../gls.jar", 10));
  assert.throws(() => zipEntry(zip, (n) => n === "gls.jar", 4));
});
test("reads a non-ZIP64 JAR directory with over 20,000 entries", () => {
  const count = 31577;
  const locals = [],
    central = [];
  let offset = 0;
  for (let i = 0; i < count; i++) {
    const name = Buffer.from(
      i === count - 1 ? "version.properties" : `entry/${i}`,
    );
    const data = Buffer.from(i === count - 1 ? "gitDirty=false" : "");
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(name.length, 26);
    const record = Buffer.alloc(46);
    record.writeUInt32LE(0x02014b50);
    record.writeUInt32LE(data.length, 20);
    record.writeUInt32LE(data.length, 24);
    record.writeUInt16LE(name.length, 28);
    record.writeUInt32LE(offset, 42);
    locals.push(local, name, data);
    central.push(record, name);
    offset += local.length + name.length + data.length;
  }
  const directory = Buffer.concat(central),
    end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(count, 8);
  end.writeUInt16LE(count, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  const archive = Buffer.concat([...locals, directory, end]);
  assert.equal(
    zipEntry(archive, (n) => n === "version.properties", 1024).toString(),
    "gitDirty=false",
  );
  end.writeUInt16LE(0xffff, 8);
  end.writeUInt16LE(0xffff, 10);
  assert.throws(
    () =>
      zipEntry(
        Buffer.concat([...locals, directory, end]),
        (n) => n === "version.properties",
        1024,
      ),
    /Unsupported ZIP/,
  );
});
test("editor engine version requires compatible major and minimum", () => {
  assert.equal(compatible("^1.144.0", "1.146.2"), true);
  assert.equal(compatible("^1.144.0", "1.143.9"), false);
  assert.equal(compatible("^1.144.0", "2.0.0"), false);
  assert.equal(compatible("*", "1.146.0"), false);
});
test("installed extension check compares actual files", () => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), "gvy-ci-test-"));
  try {
    const crypto = require("node:crypto");
    const hash = (b) => crypto.createHash("sha256").update(b).digest("hex");
    const root = path.join(home, "extension");
    fs.mkdirSync(path.join(root, "server"), { recursive: true });
    fs.mkdirSync(path.join(root, "client/out"), { recursive: true });
    fs.writeFileSync(path.join(root, "server/gls.jar"), "jar");
    fs.writeFileSync(path.join(root, "client/out/extension.js"), "client");
    const registry = path.join(home, ".vscode/extensions");
    fs.mkdirSync(registry, { recursive: true });
    fs.writeFileSync(
      path.join(registry, "extensions.json"),
      JSON.stringify([
        {
          identifier: { id: "albertocavalcante.gvy" },
          location: { scheme: "file", path: root },
        },
      ]),
    );
    assert.equal(installedMatches(home, hash("jar"), hash("client")), true);
    fs.writeFileSync(path.join(root, "client/out/extension.js"), "changed");
    assert.equal(installedMatches(home, hash("jar"), hash("client")), false);
  } finally {
    fs.rmSync(home, { recursive: true, force: true });
  }
});
test("pending receipt pins commit; resume does not resolve ref again", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "gvy-ci-test-"));
  try {
    const bin = path.join(root, "bin");
    fs.mkdirSync(bin);
    const log = path.join(root, "calls");
    fs.writeFileSync(
      path.join(bin, "gh"),
      `#!${process.execPath}\nconst fs=require('node:fs'); fs.appendFileSync(${JSON.stringify(log)},JSON.stringify(process.argv.slice(2))+'\\n'); if(process.argv[3].includes('/commits/')) console.log(JSON.stringify({sha:'${sha}'})); else console.log(JSON.stringify({workflow_runs:[]}));\n`,
      { mode: 0o700 },
    );
    fs.writeFileSync(
      path.join(bin, "code"),
      `#!${process.execPath}\nconsole.log('1.146.0');\n`,
      { mode: 0o700 },
    );
    fs.writeFileSync(
      path.join(bin, "which"),
      `#!${process.execPath}\nprocess.exit(1);\n`,
      { mode: 0o700 },
    );
    const javaHome = path.join(root, "jdk");
    fs.mkdirSync(path.join(javaHome, "bin"), { recursive: true });
    fs.writeFileSync(
      path.join(javaHome, "bin/java"),
      `#!${process.execPath}\nconsole.error('openjdk version "21.0.1"');\n`,
      { mode: 0o700 },
    );
    const cli = path.join(__dirname, "../install-extension.js");
    const env = {
      ...process.env,
      HOME: root,
      JAVA_HOME: javaHome,
      PATH: bin + path.delimiter + process.env.PATH,
    };
    const first = spawnSync(
      process.execPath,
      [cli, "--from-ci", "--state-root", root, "--json"],
      { encoding: "utf8", env },
    );
    assert.equal(first.status, 1);
    const result = JSON.parse(first.stdout);
    assert.equal(result.stage, "select-run");
    const saved = JSON.parse(fs.readFileSync(result.receipt));
    assert.equal(saved.sha, sha);
    assert.equal(saved.success, false);
    const second = spawnSync(
      process.execPath,
      [
        cli,
        "--from-ci",
        "--state-root",
        root,
        "--resume",
        result.receipt,
        "--json",
      ],
      { encoding: "utf8", env },
    );
    assert.equal(second.status, 1);
    assert.equal(
      fs
        .readFileSync(log, "utf8")
        .split("\n")
        .filter((s) => s.includes("/commits/")).length,
      1,
    );
    assert.equal(fs.existsSync(path.join(root, "ci-v1/.lock")), false);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("verified artifacts are smoked, installed, and then recognized by hashes", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "gvy-ci-test-"));
  try {
    const crypto = require("node:crypto");
    const digest = (b) => crypto.createHash("sha256").update(b).digest("hex");
    const zip = (entries) => {
      const locals = [],
        centrals = [];
      let offset = 0;
      for (const [name, value] of entries) {
        const file = Buffer.from(name),
          data = Buffer.from(value);
        const local = Buffer.alloc(30);
        local.writeUInt32LE(0x04034b50);
        local.writeUInt32LE(data.length, 18);
        local.writeUInt32LE(data.length, 22);
        local.writeUInt16LE(file.length, 26);
        const central = Buffer.alloc(46);
        central.writeUInt32LE(0x02014b50);
        central.writeUInt32LE(data.length, 20);
        central.writeUInt32LE(data.length, 24);
        central.writeUInt16LE(file.length, 28);
        central.writeUInt32LE(offset, 42);
        locals.push(local, file, data);
        centrals.push(central, file);
        offset += local.length + file.length + data.length;
      }
      const central = Buffer.concat(centrals),
        end = Buffer.alloc(22);
      end.writeUInt32LE(0x06054b50);
      end.writeUInt16LE(entries.length, 8);
      end.writeUInt16LE(entries.length, 10);
      end.writeUInt32LE(central.length, 12);
      end.writeUInt32LE(offset, 16);
      return Buffer.concat([...locals, central, end]);
    };
    const jar = zip([
        ["version.properties", `gitSha=${sha}\ngitDirty=false\n`],
      ]),
      client = Buffer.from("client");
    const vsix = zip([
      ["extension/server/gls.jar", jar],
      ["extension/client/out/extension.js", client],
      [
        "extension/package.json",
        JSON.stringify({
          publisher: "albertocavalcante",
          name: "gvy",
          engines: { vscode: "^1.144.0" },
        }),
      ],
    ]);
    const archives = [zip([["gls.jar", jar]]), zip([["gvy-0.5.4.vsix", vsix]])];
    archives.forEach((b, i) =>
      fs.writeFileSync(path.join(root, `${i}.zip`), b),
    );
    const wrongJar = zip([
      ["version.properties", `gitSha=${"b".repeat(40)}\ngitDirty=false\n`],
    ]);
    const wrongVsix = zip([
      ["extension/server/gls.jar", wrongJar],
      ["extension/client/out/extension.js", client],
      [
        "extension/package.json",
        JSON.stringify({
          publisher: "albertocavalcante",
          name: "gvy",
          engines: { vscode: "^1.144.0" },
        }),
      ],
    ]);
    const wrongArchives = [
      zip([["gls.jar", wrongJar]]),
      zip([["gvy-0.5.4.vsix", wrongVsix]]),
    ];
    wrongArchives.forEach((b, i) =>
      fs.writeFileSync(path.join(root, `wrong${i}.zip`), b),
    );
    const run = {
      id: 7,
      run_attempt: 1,
      head_sha: sha,
      head_branch: "main",
      event: "push",
      path: ".github/workflows/extension.yml",
      status: "completed",
      conclusion: "success",
    };
    const artifacts = ["current-editor-server", "vsix"].map((name, i) => ({
      name,
      id: i + 1,
      expired: false,
      digest: `sha256:${digest(archives[i])}`,
    }));
    const wrongArtifacts = artifacts.map((a, i) => ({
      ...a,
      digest: `sha256:${digest(wrongArchives[i])}`,
    }));
    const bin = path.join(root, "bin"),
      javaHome = path.join(root, "jdk");
    fs.mkdirSync(bin);
    fs.mkdirSync(path.join(javaHome, "bin"), { recursive: true });
    const executable = (file, body) =>
      fs.writeFileSync(file, `#!${process.execPath}\n${body}`, { mode: 0o700 });
    executable(
      path.join(bin, "gh"),
      `const fs=require('node:fs'); const p=process.argv[3]; if(p.endsWith('/zip'))fs.appendFileSync(${JSON.stringify(path.join(root, "downloads"))},'download\\n'); if(p.includes('/commits/')) console.log(JSON.stringify({sha:'${sha}'})); else if(p.includes('/workflows/')) console.log(JSON.stringify({workflow_runs:${JSON.stringify([run])}})); else if(p.includes('/runs/')) console.log(JSON.stringify({artifacts:process.env.BAD_SHA?${JSON.stringify(wrongArtifacts)}:${JSON.stringify(artifacts)}})); else process.stdout.write(fs.readFileSync(${JSON.stringify(root)}+'/'+(process.env.BAD_SHA?'wrong':'')+(p.includes('/1/zip')?'0':'1')+'.zip'));`,
    );
    executable(
      path.join(bin, "which"),
      `const fs=require('node:fs'); const link=${JSON.stringify(path.join(root, "commands/gvy"))}; if(fs.existsSync(link))console.log(link);else process.exit(1);`,
    );
    const calls = path.join(root, "editor-calls");
    executable(
      path.join(bin, "code"),
      `if(process.argv.includes('--version')) console.log('1.146.0'); else { require('node:fs').appendFileSync(${JSON.stringify(calls)},'install\\n'); if(process.env.FAIL_INSTALL) process.exit(1); }`,
    );
    executable(
      path.join(javaHome, "bin/java"),
      `if(process.argv.includes('-version')) {console.error('openjdk version "21.0.1"');process.exit(0)} let b=Buffer.alloc(0); function send(m){const j=JSON.stringify(m);process.stdout.write('Content-Length: '+Buffer.byteLength(j)+'\\r\\n\\r\\n'+j)} process.stdin.on('data',c=>{b=Buffer.concat([b,c]);while(true){const p=b.indexOf('\\r\\n\\r\\n');if(p<0)return;const n=Number(/Content-Length: (\\d+)/i.exec(b.subarray(0,p).toString())[1]);if(b.length<p+4+n)return;const m=JSON.parse(b.subarray(p+4,p+4+n));b=b.subarray(p+4+n);if(m.id===1)send({jsonrpc:'2.0',id:1,result:{capabilities:{}}});if(m.id===3)send({jsonrpc:'2.0',id:3,result:[{name:'GvySmoke'}]});if(m.id===2)send({jsonrpc:'2.0',id:2,result:null});if(m.method==='exit')process.exit(0)}});`,
    );
    const extension = path.join(root, "installed");
    fs.mkdirSync(path.join(extension, "server"), { recursive: true });
    fs.mkdirSync(path.join(extension, "client/out"), { recursive: true });
    fs.writeFileSync(path.join(extension, "server/gls.jar"), jar);
    fs.writeFileSync(path.join(extension, "client/out/extension.js"), client);
    const registry = path.join(root, ".vscode/extensions");
    fs.mkdirSync(registry, { recursive: true });
    fs.writeFileSync(
      path.join(registry, "extensions.json"),
      JSON.stringify([
        {
          identifier: { id: "albertocavalcante.gvy" },
          location: { scheme: "file", path: extension },
        },
      ]),
    );
    const cli = path.join(__dirname, "../install-extension.js"),
      env = {
        ...process.env,
        HOME: root,
        PATH: bin + path.delimiter + process.env.PATH,
      };
    const args = [
      cli,
      "--from-ci",
      "--state-root",
      path.join(root, "state"),
      "--bin-dir",
      path.join(root, "commands"),
      "--java-home",
      javaHome,
      "--json",
    ];
    const wrong = spawnSync(process.execPath, args, {
      encoding: "utf8",
      env: { ...env, BAD_SHA: "1" },
      timeout: 30000,
    });
    assert.equal(wrong.status, 1);
    assert.match(JSON.parse(wrong.stdout).error, /provenance/);
    assert.equal(fs.existsSync(calls), false);
    fs.writeFileSync(path.join(root, "0.zip"), "bad archive");
    const bad = spawnSync(process.execPath, args, {
      encoding: "utf8",
      env,
      timeout: 30000,
    });
    assert.equal(bad.status, 1);
    assert.match(JSON.parse(bad.stdout).error, /digest mismatch/);
    assert.equal(fs.existsSync(calls), false);
    fs.writeFileSync(path.join(root, "0.zip"), archives[0]);
    const partial = spawnSync(process.execPath, args, {
      encoding: "utf8",
      env: { ...env, FAIL_INSTALL: "1" },
      timeout: 30000,
    });
    assert.equal(partial.status, 1);
    const partialResult = JSON.parse(partial.stdout);
    assert.equal(partialResult.stage, "editor-install");
    assert.equal(
      JSON.parse(fs.readFileSync(partialResult.receipt)).editorMayHaveChanged,
      true,
    );
    assert.equal(fs.existsSync(path.join(root, "state/ci-v1/.lock")), false);
    const first = spawnSync(process.execPath, args, {
      encoding: "utf8",
      env,
      timeout: 30000,
    });
    assert.equal(first.status, 0, first.stderr);
    assert.equal(JSON.parse(first.stdout).success, true);
    const second = spawnSync(process.execPath, args, {
      encoding: "utf8",
      env,
      timeout: 30000,
    });
    assert.equal(second.status, 0, second.stderr);
    assert.equal(JSON.parse(second.stdout).noop, true);
    assert.equal(fs.readFileSync(calls, "utf8"), "install\ninstall\n");
    // Two prior failures and the first success each downloaded two archives;
    // the verified no-op must not download another byte.
    const downloads = fs
      .readFileSync(path.join(root, "downloads"), "utf8")
      .trim()
      .split("\n").length;
    assert.equal(downloads, 5);
    fs.writeFileSync(
      path.join(root, "state/ci-v1", sha, "gls.jar"),
      "tampered",
    );
    const corrupted = spawnSync(process.execPath, args, {
      encoding: "utf8",
      env,
      timeout: 30000,
    });
    assert.equal(corrupted.status, 1);
    assert.match(JSON.parse(corrupted.stdout).error, /hash mismatch/);
    assert.equal(
      fs.readFileSync(path.join(root, "downloads"), "utf8").trim().split("\n")
        .length,
      downloads,
    );
    assert.equal(fs.readFileSync(calls, "utf8"), "install\ninstall\n");
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("download timeout and stream errors settle and terminate the child", async () => {
  const { download } = require("../ci-install.js");
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "gvy-ci-test-"));
  const oldPath = process.env.PATH;
  try {
    fs.writeFileSync(
      path.join(root, "gh"),
      `#!${process.execPath}\nsetInterval(() => {}, 1000);\n`,
      { mode: 0o700 },
    );
    process.env.PATH = root + path.delimiter + oldPath;
    await assert.rejects(
      download("unused", path.join(root, "archive"), 250),
      /timed out/,
    );
    await assert.rejects(
      download("unused", path.join(root, "missing", "archive"), 1000),
      /ENOENT/,
    );
  } finally {
    process.env.PATH = oldPath;
    fs.rmSync(root, { recursive: true, force: true });
  }
});
