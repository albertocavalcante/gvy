// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

export function quoteJavaArgument(value: string): string {
  return (
    '"' +
    value
      .replace(/\\/g, "\\\\")
      .replace(/"/g, '\\"')
      .replace(/\n/g, "\\n")
      .replace(/\r/g, "\\r")
      .replace(/\t/g, "\\t")
      .replace(/\f/g, "\\f") +
    '"'
  );
}

/** Shorten only the classpath; program arguments remain direct, literal argv. */
export async function shortenJavaArguments(
  args: string[],
  file: string,
  platform: NodeJS.Platform,
  javaMajor: () => Promise<number>,
  write: (file: string, content: string) => Promise<void>,
): Promise<string[]> {
  const threshold = platform === "win32" ? 16000 : 60000;
  if (args.reduce((sum, arg) => sum + arg.length + 1, 0) < threshold)
    return args;
  const cp = args.lastIndexOf("-cp");
  if (cp < 0 || !args[cp + 1])
    throw new Error(
      "Java command is too long and has no classpath to shorten.",
    );
  if (args.includes("--disable-@files"))
    throw new Error("Remove --disable-@files to run this long Java command.");
  if ((await javaMajor()) < 9)
    throw new Error("A long classpath requires Java 9 or newer.");
  const value = args[cp + 1];
  if (
    platform === "win32" &&
    [...value].some((character) => character.charCodeAt(0) > 127)
  )
    throw new Error(
      "This long Windows classpath contains characters whose argument-file encoding is not yet qualified.",
    );
  const result = [...args];
  result[cp + 1] = `@${file}`;
  if (result.reduce((sum, arg) => sum + arg.length + 1, 0) >= threshold)
    throw new Error(
      "Java arguments are too long even after shortening the classpath.",
    );
  await write(file, `${quoteJavaArgument(value)}\n`);
  return result;
}

/** Java's Windows launcher can corrupt Unicode argv before the JVM starts. */
export async function qualifyWindowsArguments(
  args: string[],
  platform: NodeJS.Platform,
  probe: () => Promise<string>,
): Promise<void> {
  if (
    platform !== "win32" ||
    !args.some((arg) =>
      [...arg].some((character) => character.charCodeAt(0) > 127),
    )
  )
    return;
  const settings = await probe();
  const encodings = [
    ...settings.matchAll(
      /^\s*(?:native\.encoding|sun\.jnu\.encoding)\s*=\s*(\S+)/gm,
    ),
  ].map((match) => match[1].toLowerCase().replace(/[-_]/g, ""));
  if (!encodings.length || encodings.some((encoding) => encoding !== "utf8")) {
    throw new Error(
      "The selected Java launcher cannot safely pass Unicode arguments with the current Windows encoding. Use a UTF-8-compatible Java/Windows environment or ASCII paths and arguments.",
    );
  }
}
