import type { Readable } from "stream";

/** Maximum UTF-8 payload bytes retained per diagnostic line. */
export const MAX_DIAGNOSTIC_LINE_BYTES = 64 * 1024;

export interface BoundedLine {
  text: string;
  truncated: boolean;
}

/** Frame bytes before decoding so chunk boundaries cannot corrupt UTF-8. */
export function createBoundedLines(
  onLine: (line: BoundedLine) => void,
  maxBytes = MAX_DIAGNOSTIC_LINE_BYTES,
): { write(chunk: Buffer): void; end(): void } {
  if (!Number.isSafeInteger(maxBytes) || maxBytes <= 0) {
    throw new RangeError("Line byte limit must be a positive safe integer");
  }
  const buffer = Buffer.allocUnsafe(maxBytes);
  let length = 0;
  let overflow = false;
  let afterCR = false;
  let ended = false;

  const emit = () => {
    // In overflow, omit an incomplete code point at the retained boundary.
    const decoder = new TextDecoder("utf-8", { ignoreBOM: true });
    const decoded = decoder.decode(buffer.subarray(0, length), {
      stream: overflow,
    });
    // Replacement characters from malformed input can expand beyond the byte cap.
    const encoded = Buffer.from(decoded, "utf8");
    const clipped = encoded.length > maxBytes;
    const text = clipped
      ? new TextDecoder("utf-8", { ignoreBOM: true }).decode(
          encoded.subarray(0, maxBytes),
          {
            stream: true,
          },
        )
      : decoded;
    const truncated = overflow || clipped;
    onLine({ text, truncated });
    length = 0;
    overflow = false;
  };

  return {
    write(chunk) {
      if (ended) return;
      for (const byte of chunk) {
        if (afterCR) {
          afterCR = false;
          if (byte === 10) continue;
        }
        if (byte === 10 || byte === 13) {
          emit();
          afterCR = byte === 13;
        } else if (length < maxBytes) {
          buffer[length++] = byte;
        } else {
          overflow = true;
        }
      }
    },
    end() {
      if (ended) return;
      ended = true;
      if (length > 0 || overflow) emit();
    },
  };
}

/** Drain the stream after overflow and finish exactly once on end or close. */
export function pipeBoundedLines(
  input: Readable,
  onLine: (line: BoundedLine) => void,
  onError: (error: Error) => void,
  maxBytes = MAX_DIAGNOSTIC_LINE_BYTES,
): void {
  const lines = createBoundedLines(onLine, maxBytes);
  let reportedError = false;
  const fail = (error: Error) => {
    lines.end();
    if (!reportedError) {
      reportedError = true;
      onError(error);
    }
  };
  // Decoded strings have already lost the original byte boundaries. Keep
  // draining them, but report the unsupported stream instead of copying them.
  input.on("data", (chunk: Buffer | string) => {
    if (typeof chunk === "string") {
      fail(new Error("Expected a raw byte stream"));
    } else {
      lines.write(chunk);
    }
  });
  input.once("end", lines.end);
  input.once("close", lines.end);
  input.on("error", fail);
}
