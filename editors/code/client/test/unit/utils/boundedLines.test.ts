// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

import { strict as assert } from "assert";
import { PassThrough } from "stream";
import {
  createBoundedLines,
  pipeBoundedLines,
  type BoundedLine,
} from "../../../src/utils/boundedLines";

describe("boundedLines", () => {
  it("decodes split UTF-8 and all line endings without duplicate final lines", () => {
    const lines: BoundedLine[] = [];
    const parser = createBoundedLines((line) => lines.push(line), 32);
    const input = Buffer.from("café 🧪\r\n終\rnext\nlast");
    for (const byte of input) parser.write(Buffer.from([byte]));
    parser.end();
    parser.end();
    assert.deepEqual(
      lines,
      ["café 🧪", "終", "next", "last"].map((text) => ({
        text,
        truncated: false,
      })),
    );
  });

  it("bounds bytes, marks overflow, and recovers at the next record", () => {
    const lines: BoundedLine[] = [];
    const parser = createBoundedLines((line) => lines.push(line), 5);
    parser.write(Buffer.from("ab🧪tail\nOK\n"));
    parser.end();
    assert.deepEqual(lines, [
      { text: "ab", truncated: true },
      { text: "OK", truncated: false },
    ]);
  });

  it("handles malformed bytes within the output byte budget", () => {
    const lines: BoundedLine[] = [];
    const parser = createBoundedLines((line) => lines.push(line), 5);
    parser.write(Buffer.from([0xff, 0xff, 0xff, 10]));
    parser.end();
    assert.deepEqual(lines, [{ text: "�", truncated: true }]);
    assert.ok(Buffer.byteLength(lines[0].text) <= 5);
  });

  it("flushes a partial line once on error and close", async () => {
    const input = new PassThrough();
    const lines: BoundedLine[] = [];
    const errors: string[] = [];
    pipeBoundedLines(
      input,
      (line) => lines.push(line),
      (error) => errors.push(error.message),
      32,
    );
    input.write("partial");
    input.destroy(new Error("broken"));
    await new Promise((resolve) => input.on("close", resolve));
    assert.deepEqual(lines, [{ text: "partial", truncated: false }]);
    assert.deepEqual(errors, ["broken"]);
  });

  it("drains a huge unterminated record and emits one bounded prefix", () => {
    const lines: BoundedLine[] = [];
    const parser = createBoundedLines((line) => lines.push(line), 64 * 1024);
    for (let i = 0; i < 1000; i++) parser.write(Buffer.alloc(1024, 120));
    parser.end();
    parser.end();
    parser.write(Buffer.from("ignored\n"));
    assert.equal(lines.length, 1);
    assert.equal(Buffer.byteLength(lines[0].text), 64 * 1024);
    assert.equal(lines[0].truncated, true);
  });

  it("does not truncate a record exactly at its byte limit", () => {
    const lines: BoundedLine[] = [];
    const parser = createBoundedLines((line) => lines.push(line), 4);
    parser.write(Buffer.from("🧪\r"));
    parser.write(Buffer.from("\n"));
    parser.end();
    assert.deepEqual(lines, [{ text: "🧪", truncated: false }]);
  });

  it("preserves a UTF-8 byte order mark in the record", () => {
    const lines: BoundedLine[] = [];
    const parser = createBoundedLines((line) => lines.push(line), 16);
    parser.write(Buffer.from("\uFEFFhello\n"));
    parser.end();
    assert.deepEqual(lines, [{ text: "\uFEFFhello", truncated: false }]);
  });

  it("rejects pre-decoded streams without buffering their contents", async () => {
    const input = new PassThrough();
    input.setEncoding("utf8");
    const lines: BoundedLine[] = [];
    const errors: string[] = [];
    pipeBoundedLines(
      input,
      (line) => lines.push(line),
      (error) => errors.push(error.message),
      16,
    );
    input.end("already decoded");
    await new Promise((resolve) => input.on("close", resolve));
    assert.deepEqual(lines, []);
    assert.match(errors[0], /raw byte/);
  });
});
