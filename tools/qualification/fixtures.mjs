// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0
import { createServer } from "node:http";
import { randomBytes } from "node:crypto";
import { once } from "node:events";

/** Persist only the exact locally validated control outcomes, never HTTP response objects. */
export function fixtureControlReceipt(
  unauthenticated,
  authenticated,
  redirect,
  requests,
) {
  if (
    unauthenticated !== 401 ||
    authenticated !== 200 ||
    redirect !== 302 ||
    requests.some((item) => item.origin === "denied")
  )
    throw new Error("Loopback auth/redirect fixture control failed");
  return {
    unauthenticated: 401,
    authenticated: 200,
    redirect: 302,
    deniedRequests: 0,
  };
}

/** Three origins make allowed/denied redirect destinations independently observable. */
export async function startFixtures() {
  const authorization = `Basic ${Buffer.from(`fixture:${randomBytes(24).toString("hex")}`).toString("base64")}`;
  const requests = [];
  const origins = {};
  const servers = [];
  try {
    for (const name of ["allowed", "second", "denied"]) {
      const server = createServer(
        { maxHeaderSize: 8192, requestTimeout: 5000, headersTimeout: 5000 },
        (request, response) => {
          if (requests.length >= 10000) {
            response.writeHead(429).end();
            return;
          }
          const path = new URL(request.url, "http://fixture").pathname;
          requests.push({
            origin: name,
            method: request.method,
            path: path.slice(0, 256),
            authenticated: request.headers.authorization === authorization,
            credentialPresent: Boolean(request.headers.authorization),
          });
          if (request.method !== "GET") {
            response.writeHead(405).end();
            return;
          }
          if (path === "/redirect/allowed" || path === "/redirect/denied") {
            const target = path.endsWith("/denied") ? "denied" : "second";
            response
              .writeHead(302, { location: `${origins[target]}/auth` })
              .end();
          } else if (path === "/auth") {
            if (request.headers.authorization !== authorization)
              response
                .writeHead(401, {
                  "www-authenticate": 'Basic realm="qualification"',
                })
                .end();
            else
              response
                .writeHead(200, { "content-type": "application/json" })
                .end('{"fixture":"authenticated"}');
          } else if (path === "/health")
            response.writeHead(200).end("loopback");
          else response.writeHead(404).end();
        },
      );
      servers.push(server);
      server.listen(0, "127.0.0.1");
      await once(server, "listening");
      origins[name] = `http://127.0.0.1:${server.address().port}`;
    }
  } catch (error) {
    await Promise.all(
      servers.map(
        (server) =>
          new Promise((resolve) => {
            server.closeAllConnections();
            server.close(resolve);
          }),
      ),
    );
    throw error;
  }
  return {
    authorization,
    origins,
    requests,
    close: () =>
      Promise.all(
        servers.map(
          (server) =>
            new Promise((resolve) => {
              server.closeAllConnections();
              server.close(resolve);
            }),
        ),
      ),
  };
}
