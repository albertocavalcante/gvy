// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { createConnection, createServer } from "node:net";
import { createSocket } from "node:dgram";
import { once } from "node:events";
import { writeJson } from "./io.mjs";

const [resultPath] = process.argv.slice(2);
const tcp = await new Promise((resolve) => {
  const socket = createConnection({ host: "198.51.100.1", port: 9 });
  socket.setTimeout(1500, () => {
    socket.destroy();
    resolve("TIMEOUT");
  });
  socket.once("connect", () => {
    socket.destroy();
    resolve("UNEXPECTED_SUCCESS");
  });
  socket.once("error", (error) => resolve(error.code));
});
const udp = await new Promise((resolve) => {
  const socket = createSocket("udp4");
  const timer = setTimeout(() => {
    socket.close();
    resolve("TIMEOUT");
  }, 1500);
  socket.send(Buffer.from("negative-control"), 9, "198.51.100.1", (error) => {
    clearTimeout(timer);
    socket.close();
    resolve(error?.code ?? "UNEXPECTED_SUCCESS");
  });
});
const server = createServer((socket) => socket.end());
server.listen(0, "::1");
await once(server, "listening");
const socket = createConnection({ host: "::1", port: server.address().port });
await once(socket, "connect");
socket.destroy();
await new Promise((resolve) => server.close(resolve));
await writeJson(resultPath, { tcp, udp, ipv6Loopback: true });
