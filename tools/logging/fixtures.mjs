/** Deterministic synthetic inputs; these contain no user workspace data. */
import { once } from 'node:events';
import { pathToFileURL } from 'node:url';

export function sourceFixture(kind, size) {
  if (!['groovy', 'spock'].includes(kind) || !['small', 'large'].includes(size)) {
    throw new Error('Unknown source fixture');
  }
  const count = size === 'small' ? 10 : 1000;
  const header = kind === 'spock'
    ? 'import spock.lang.Specification\n\nclass SampleSpec extends Specification {\n'
    : 'class Sample {\n';
  const methods = Array.from({ length: count }, (_, i) => kind === 'spock'
    ? `  def method${i}() { expect: ${i} + 1 == ${i + 1} }\n`
    : `  int method${i}() { ${i} + 1 }\n`).join('');
  return `${header}${methods}}\n`;
}

export function childFixture(mode) {
  switch (mode) {
    case 'burst':
      return Buffer.from(Array.from({ length: 1000 }, (_, i) => `record ${i}\n`).join(''));
    case 'no-newline':
      return Buffer.alloc(128 * 1024, 120);
    case 'unicode':
      return Buffer.from('café 🧪\r\n終わり\n');
    default:
      throw new Error('Unknown child fixture');
  }
}

// Write in small byte slices deliberately, including inside UTF-8 code points.
// Consumers must tolerate arbitrary transport chunking and cannot assume these
// write boundaries survive the pipe.
async function emit(mode) {
  const bytes = childFixture(mode);
  let writes = 0;
  for (let offset = 0; offset < bytes.length; offset += 7) {
    writes += 1;
    if (!process.stdout.write(bytes.subarray(offset, offset + 7))) {
      await once(process.stdout, 'drain');
    }
  }
  process.stderr.write(`${JSON.stringify({ writes, flushCalls: null })}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await emit(process.argv[2]);
}
