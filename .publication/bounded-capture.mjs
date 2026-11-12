import fs from 'node:fs';
import path from 'node:path';
const [root, output] = process.argv.slice(2);
const LIMIT=128*1024*1024, STREAM=16*1024*1024;
function total(dir) {
  let size=0;
  for (const entry of fs.readdirSync(dir,{withFileTypes:true})) {
    const p=path.join(dir,entry.name); const st=fs.lstatSync(p);
    if (st.isSymbolicLink() || (!st.isDirectory() && !st.isFile())) throw Error('invalid evidence entry');
    size+=st.isDirectory()?total(p):st.size;
    if (!Number.isSafeInteger(size) || size>LIMIT) throw Error('evidence limit');
  }
  return size;
}
let fd, used=0, failed=false;
try {
  if (!root || !output || path.dirname(path.resolve(output))!==path.resolve(root)) throw Error('invalid destination');
  used=total(root); fd=fs.openSync(output,'wx',0o600);
} catch { failed=true; }
let stream=0;
for await (const chunk of process.stdin) {
  stream+=chunk.length;
  if (failed || stream>STREAM || used+chunk.length>LIMIT) { failed=true; continue; }
  try { fs.writeFileSync(fd,chunk); used+=chunk.length; } catch { failed=true; }
}
if (fd!==undefined) { try { fs.closeSync(fd); } catch { failed=true; } }
if (failed) { console.error('bounded capture failed'); process.exitCode=74; }
