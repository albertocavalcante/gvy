import type { LanguageClient } from "vscode-languageclient/node";

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
