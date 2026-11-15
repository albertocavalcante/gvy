// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package org.gvy.jetbrains;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal stdio peer for process-lifecycle tests, not a GVY protocol implementation. */
public final class FixtureLanguageServer {
    private static final Pattern METHOD = Pattern.compile("\\\"method\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern ID = Pattern.compile("\\\"id\\\"\\s*:\\s*(\\\"[^\\\"]*\\\"|-?\\d+)");

    private FixtureLanguageServer() {}

    public static void main(String[] args) throws Exception {
        Path log = Path.of(System.getProperty("gvy.fixture.log"));
        long pid = ProcessHandle.current().pid();
        append(log, "START " + pid);
        try (InputStream input = new BufferedInputStream(System.in)) {
            while (true) {
                String message = readMessage(input);
                if (message == null) break;
                Matcher method = METHOD.matcher(message);
                if (!method.find()) continue;
                String name = method.group(1);
                append(log, name + " " + pid);
                Matcher id = ID.matcher(message);
                if (id.find()) {
                    String result = name.equals("initialize")
                            ? "{\"capabilities\":{\"textDocumentSync\":1}}" : "null";
                    String response = "{\"jsonrpc\":\"2.0\",\"id\":" + id.group(1) + ",\"result\":" + result + "}";
                    byte[] body = response.getBytes(StandardCharsets.UTF_8);
                    System.out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    System.out.write(body);
                    System.out.flush();
                }
                if (name.equals("exit")) break;
            }
        } finally {
            append(log, "EXIT " + pid);
        }
    }

    private static void append(Path log, String event) throws IOException {
        Files.writeString(log, event + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String readMessage(InputStream input) throws IOException {
        int length = -1;
        while (true) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int value;
            while ((value = input.read()) != -1 && value != '\n') {
                if (value != '\r') line.write(value);
            }
            if (value == -1) return null;
            String header = line.toString(StandardCharsets.US_ASCII);
            if (header.isEmpty()) break;
            if (header.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                length = Integer.parseInt(header.substring(15).trim());
            }
        }
        if (length < 0 || length > 1_000_000) throw new IOException("Invalid Content-Length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IOException("Truncated JSON-RPC message");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
