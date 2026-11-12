import fs from 'node:fs';
import { constants } from 'node:fs';
const [source, target] = process.argv.slice(2);
const max=4*1024*1024;
const fd=fs.openSync(source,constants.O_RDONLY|constants.O_NOFOLLOW|constants.O_NONBLOCK);
try {
  const stat=fs.fstatSync(fd);
  if(!stat.isFile() || stat.size>max) throw Error('invalid report');
  const buf=Buffer.alloc(max+1);let size=0;
  while(size<buf.length) { const n=fs.readSync(fd,buf,size,buf.length-size,null); if(!n) break; size+=n; }
  if(size>max || size!==stat.size) throw Error('invalid report');
  fs.writeFileSync(target,buf.subarray(0,size),{flag:'wx',mode:0o600});
} finally { fs.closeSync(fd); }
