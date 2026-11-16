// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

/**
 * Pure helpers for editing `settings.gradle(.kts)` text. They only look at
 * top-level blocks so that `plugins {` inside `pluginManagement { }`, comments
 * or strings is never mistaken for the settings-level plugins block.
 */

const FOOJAY_ID = "org\\.gradle\\.toolchains\\.foojay-resolver-convention";
const GROOVY_ID = new RegExp(`\\bid\\s+['"]${FOOJAY_ID}['"]`);
const KOTLIN_ID = new RegExp(`\\bid\\s*\\(\\s*["']${FOOJAY_ID}["']\\s*\\)`);

interface TopLevelBlock {
  name: string;
  /** Offset just after the opening brace. */
  bodyStart: number;
  /** Offset of the closing brace (or text length when unterminated). */
  bodyEnd: number;
}

/** Where to put the Foojay plugin. */
export type FoojayInsertion =
  | { kind: "configured" }
  | { kind: "intoPlugins"; offset: number }
  | { kind: "newBlock"; offset: number };

/**
 * Blanks comments (and optionally string contents) with spaces, keeping every
 * offset and newline unchanged.
 */
function mask(text: string, blankStrings: boolean): string {
  const out = text.split("");
  let i = 0;
  const blank = (from: number, to: number) => {
    for (let k = from; k < to; k++) {
      if (out[k] !== "\n" && out[k] !== "\r") out[k] = " ";
    }
  };
  while (i < text.length) {
    const c = text[i];
    const next = text[i + 1];
    if (c === "/" && next === "/") {
      let end = text.indexOf("\n", i);
      if (end < 0) end = text.length;
      blank(i, end);
      i = end;
    } else if (c === "/" && next === "*") {
      let end = text.indexOf("*/", i + 2);
      end = end < 0 ? text.length : end + 2;
      blank(i, end);
      i = end;
    } else if (c === '"' || c === "'") {
      const triple = text.startsWith(c.repeat(3), i);
      const quote = triple ? c.repeat(3) : c;
      let j = i + quote.length;
      while (j < text.length && !text.startsWith(quote, j)) {
        if (text[j] === "\\") j++;
        else if (!triple && text[j] === "\n") break;
        j++;
      }
      const end = Math.min(text.length, j + quote.length);
      if (blankStrings) blank(i + quote.length, Math.max(i + quote.length, j));
      i = end;
    } else {
      i++;
    }
  }
  return out.join("");
}

function topLevelBlocks(structural: string): TopLevelBlock[] {
  const blocks: TopLevelBlock[] = [];
  let depth = 0;
  let i = 0;
  while (i < structural.length) {
    const c = structural[i];
    if (c === "{") {
      if (depth === 0) {
        const head = /([A-Za-z_][\w]*)\s*$/.exec(structural.slice(0, i));
        if (head) {
          blocks.push({ name: head[1], bodyStart: i + 1, bodyEnd: -1 });
        }
      }
      depth++;
    } else if (c === "}") {
      depth = Math.max(0, depth - 1);
      if (depth === 0) {
        const last = blocks[blocks.length - 1];
        if (last && last.bodyEnd < 0) last.bodyEnd = i;
      }
    }
    i++;
  }
  for (const block of blocks) {
    if (block.bodyEnd < 0) block.bodyEnd = structural.length;
  }
  return blocks;
}

/** Decides how to add the Foojay resolver to `text`. */
export function planFoojayInsertion(text: string): FoojayInsertion {
  const commentFree = mask(text, false);
  const blocks = topLevelBlocks(mask(text, true));

  const plugins = blocks.filter((b) => b.name === "plugins");
  for (const block of plugins) {
    const body = commentFree.slice(block.bodyStart, block.bodyEnd);
    if (GROOVY_ID.test(body) || KOTLIN_ID.test(body)) {
      return { kind: "configured" };
    }
  }
  if (plugins.length > 0) {
    return { kind: "intoPlugins", offset: plugins[0].bodyStart };
  }

  // Gradle requires pluginManagement and buildscript to precede plugins.
  let offset = 0;
  for (const block of blocks) {
    if (block.name === "pluginManagement" || block.name === "buildscript") {
      const close = Math.min(block.bodyEnd + 1, text.length);
      offset = Math.max(offset, close);
    }
  }
  if (offset > 0) {
    // Continue on the line after the closing brace, also past a trailing comment.
    const nl = text.indexOf("\n", offset);
    const rest = commentFree.slice(offset, nl < 0 ? text.length : nl);
    if (rest.trim() === "") {
      offset = nl < 0 ? text.length : nl + 1;
    }
  }
  return { kind: "newBlock", offset };
}

/** Escapes a value for use inside a single-quoted Groovy string. */
export function escapeGroovySingleQuoted(value: string): string {
  return value
    .replace(/\\/g, "\\\\")
    .replace(/'/g, "\\'")
    .replace(/\r/g, "\\r")
    .replace(/\n/g, "\\n");
}
