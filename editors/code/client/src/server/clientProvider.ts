import type { LanguageClient } from "vscode-languageclient/node";
import { ErrorCodes } from "vscode-languageserver-protocol";

/**
 * Returns the language client that is current right now. Restarts replace
 * the LanguageClient instance, so features must not hold on to one.
 */
export type ClientProvider = () => LanguageClient | undefined;

export function requireClient(provider: ClientProvider): LanguageClient {
  const client = provider();
  if (!client) {
    throw new Error("Groovy Language Server is not running");
  }
  return client;
}

/**
 * True when the server rejected a request because it has no handler for its
 * method, i.e. this server version does not implement the feature.
 */
export function isMethodNotFound(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    (error as { code?: unknown }).code === ErrorCodes.MethodNotFound
  );
}
