// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import path from "node:path";
const root = path.resolve("dist");
const mime = {
  ".html": "text/html",
  ".js": "text/javascript",
  ".css": "text/css",
  ".json": "application/json",
  ".svg": "image/svg+xml",
  ".wasm": "application/wasm",
  ".png": "image/png",
  ".woff2": "font/woff2",
};
createServer(async (req, res) => {
  try {
    const pathname = new URL(req.url, "http://localhost").pathname;
    if (!pathname.startsWith("/gvy/")) throw new Error("outside base");
    const relative = decodeURIComponent(pathname.slice(5));
    const file = path.resolve(
      root,
      relative.endsWith("/") || !relative ? `${relative}index.html` : relative,
    );
    if (!file.startsWith(root + path.sep)) throw new Error("outside root");
    const bytes = await readFile(file);
    res.writeHead(200, {
      "content-type": mime[path.extname(file)] || "application/octet-stream",
    });
    res.end(bytes);
  } catch {
    res.writeHead(404, { "content-type": "text/html" });
    res.end(await readFile(path.join(root, "404.html")));
  }
}).listen(43219, "127.0.0.1");
